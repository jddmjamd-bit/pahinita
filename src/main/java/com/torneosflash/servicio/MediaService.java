package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Subida y descarga de archivos multimedia (A3). Los videos se guardan en PostgreSQL (tabla {@code media_files})
 * para que sean persistentes en Render (sin depender del filesystem efímero). No conoce HTTP.
 */
public class MediaService {
    private static final Logger logger = LoggerFactory.getLogger(MediaService.class);

    /** Tamaño máximo de un video subido (50 MB). */
    private static final int LIMITE_BYTES = 50 * 1024 * 1024;

    /** Tamaño máximo de cada bloque leído de PostgreSQL durante una descarga. */
    private static final int BLOQUE_DESCARGA_BYTES = 1024 * 1024;

    /**
     * Metadatos de descarga. Mantiene una conexión abierta y transmite el contenido en bloques,
     * sin cargar el video completo en la memoria de la aplicación.
     */
    public static final class Descarga implements AutoCloseable {
        private final int status;
        private final String contentType;
        private final Connection conexion;
        private final int mediaId;
        private final long inicio;
        private final long fin;
        private final long totalBytes;

        private Descarga(int status, String contentType, Connection conexion, int mediaId,
                         long inicio, long fin, long totalBytes) {
            this.status = status;
            this.contentType = contentType;
            this.conexion = conexion;
            this.mediaId = mediaId;
            this.inicio = inicio;
            this.fin = fin;
            this.totalBytes = totalBytes;
        }

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public long contentLength() {
            return status == 416 || fin < inicio ? 0 : fin - inicio + 1;
        }

        public String contentRange() {
            if (status == 416) return "bytes */" + totalBytes;
            if (status == 206) return "bytes " + inicio + "-" + fin + "/" + totalBytes;
            return null;
        }

        public void escribirEn(OutputStream salida) throws IOException {
            if (status == 416 || contentLength() == 0) return;
            if (conexion == null) {
                throw new IOException("La descarga no tiene una conexión de base de datos");
            }

            long posicion = inicio;
            while (posicion <= fin) {
                int longitud = (int) Math.min(BLOQUE_DESCARGA_BYTES, fin - posicion + 1);

                try (PreparedStatement ps = conexion.prepareStatement(
                        "SELECT substring(data FROM ? FOR ?) FROM media_files WHERE id = ?")) {
                    ps.setInt(1, Math.toIntExact(posicion + 1));
                    ps.setInt(2, longitud);
                    ps.setInt(3, mediaId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IOException("El archivo multimedia dejó de estar disponible");
                        }
                        byte[] bloque = rs.getBytes(1);
                        if (bloque == null || bloque.length != longitud) {
                            throw new IOException("Se recibió un bloque multimedia incompleto");
                        }
                        salida.write(bloque);
                    }
                } catch (java.sql.SQLException e) {
                    throw new IOException("Error leyendo el bloque multimedia", e);
                }

                posicion += longitud;
            }
        }

        @Override
        public void close() {
            if (conexion != null) {
                try {
                    conexion.close();
                } catch (Exception e) {
                    logger.warn("No se pudo cerrar la conexión de descarga multimedia", e);
                }
            }
        }
    }

    private final GenericDAO db;

    public MediaService(GenericDAO db) {
        this.db = db;
    }

    /** Valida y guarda un video. Devuelve {@code {url, tipo, id}}. */
    public JsonObject subirVideo(String contentType, String nombreOriginal, InputStream contenido) {
        if (contentType == null || !contentType.startsWith("video/")) {
            throw ServicioException.solicitudInvalida("Solo se permiten archivos de video");
        }

        // A1: leer como máximo LIMITE_BYTES + 1 para cortar de inmediato si supera 50 MB sin agotar la RAM
        if (contenido == null) {
            throw ServicioException.solicitudInvalida("No se recibió el contenido del archivo");
        }

        byte[] bytes;
        try (InputStream entrada = contenido) {
            bytes = entrada.readNBytes(LIMITE_BYTES + 1);
        } catch (IOException e) {
            throw ServicioException.interno("Error interno al subir archivo", e);
        }

        if (bytes.length > LIMITE_BYTES) {
            throw new ServicioException(413, "El video excede el límite de 50MB");
        }

        // Nombre único conservando la extensión original
        String extension = "";
        if (nombreOriginal != null) {
            int dotIdx = nombreOriginal.lastIndexOf('.');
            if (dotIdx > 0) extension = nombreOriginal.substring(dotIdx);
        }
        String filename = UUID.randomUUID() + extension;

        int mediaId = db.insertReturningId(
                "INSERT INTO media_files (filename, content_type, data) VALUES (?, ?, ?) RETURNING id",
                filename, contentType, bytes);
        if (mediaId < 0) {
            throw ServicioException.interno("Error al guardar el archivo en la base de datos", null);
        }

        logger.info("📹 Video subido: {} ({}KB) → ID: {}", filename, bytes.length / 1024, mediaId);

        JsonObject response = new JsonObject();
        response.addProperty("url", "/api/media/" + mediaId);
        response.addProperty("tipo", "video");
        response.addProperty("id", mediaId);
        return response;
    }

    /**
     * Lee los metadatos y resuelve el header Range sin traer el video completo a memoria.
     *
     * @return la descarga, o null si el archivo no existe
     */
    public Descarga descargar(int mediaId, String rangeHeader) {
        Connection conexion = null;
        boolean transferirConexion = false;

        try {
            conexion = db.getConnection();
            String contentType;
            long totalBytes;

            try (PreparedStatement ps = conexion.prepareStatement(
                    "SELECT content_type, octet_length(data) AS total_bytes FROM media_files WHERE id = ?")) {
                ps.setInt(1, mediaId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;

                    contentType = rs.getString("content_type");
                    totalBytes = rs.getLong("total_bytes");
                    if (rs.wasNull()) {
                        throw ServicioException.interno("El archivo multimedia no contiene datos válidos", null);
                    }
                }
            }

            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            if (totalBytes < 0 || totalBytes > LIMITE_BYTES) {
                throw new ServicioException(413, "El archivo almacenado excede el límite permitido");
            }

            Rango rango = parsearRango(rangeHeader, totalBytes);
            Descarga descarga;
            if (rango == null) {
                descarga = new Descarga(200, contentType, conexion, mediaId, 0, totalBytes - 1, totalBytes);
            } else if (!rango.satisfacible()) {
                descarga = new Descarga(416, contentType, conexion, mediaId, 0, -1, totalBytes);
            } else {
                descarga = new Descarga(206, contentType, conexion, mediaId,
                        rango.inicio(), rango.fin(), totalBytes);
            }

            transferirConexion = true;
            return descarga;
        } catch (ServicioException e) {
            throw e;
        } catch (Exception e) {
            throw ServicioException.interno("Error interno al descargar el archivo", e);
        } finally {
            if (!transferirConexion && conexion != null) {
                try {
                    conexion.close();
                } catch (Exception e) {
                    logger.warn("No se pudo cerrar la conexión de descarga multimedia", e);
                }
            }
        }
    }

    private record Rango(long inicio, long fin, boolean satisfacible) {}

    /**
     * Interpreta un rango simple de bytes. Devuelve null si el header no se puede interpretar,
     * para servir el recurso completo; los rangos fuera del archivo se responden con 416.
     */
    private static Rango parsearRango(String rangeHeader, long total) {
        if (rangeHeader == null || !rangeHeader.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }

        String valor = rangeHeader.substring(6).trim();
        if (valor.contains(",")) return null;

        String[] partes = valor.split("-", -1);
        if (partes.length != 2) return null;

        String inicioTexto = partes[0].trim();
        String finTexto = partes[1].trim();
        if (inicioTexto.isEmpty() && finTexto.isEmpty()) return null;
        if (total == 0) return new Rango(0, -1, false);

        try {
            if (inicioTexto.isEmpty()) {
                long sufijo = Long.parseLong(finTexto);
                if (sufijo <= 0) return null;
                long longitud = Math.min(sufijo, total);
                return new Rango(total - longitud, total - 1, true);
            }

            long inicio = Long.parseLong(inicioTexto);
            if (inicio < 0) return null;
            if (inicio >= total) return new Rango(inicio, total - 1, false);

            long fin = finTexto.isEmpty() ? total - 1 : Long.parseLong(finTexto);
            if (fin < inicio) return new Rango(inicio, fin, false);

            return new Rango(inicio, Math.min(fin, total - 1), true);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
