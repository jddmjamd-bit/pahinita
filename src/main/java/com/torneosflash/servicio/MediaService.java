package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
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

    /**
     * Respuesta de una descarga. {@code status} es 200 (archivo completo), 206 (rango) o 416 (rango fuera del archivo;
     * en ese caso {@code datos} es null). {@code contentRange} viene lleno en 206 y 416.
     */
    public record Descarga(int status, String contentType, byte[] datos, String contentRange) {}

    private final GenericDAO db;

    public MediaService(GenericDAO db) {
        this.db = db;
    }

    /** Valida y guarda un video. Devuelve {@code {url, tipo, id}}. */
    public JsonObject subirVideo(String contentType, String nombreOriginal, InputStream contenido) {
        if (contentType == null || !contentType.startsWith("video/")) {
            throw ServicioException.solicitudInvalida("Solo se permiten archivos de video");
        }

        byte[] bytes;
        try {
            bytes = contenido.readAllBytes();
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
     * Lee un archivo y resuelve el header Range (para poder hacer seek en los videos).
     *
     * @return la descarga, o null si el archivo no existe
     */
    public Descarga descargar(int mediaId, String rangeHeader) {
        String contentType;
        byte[] data;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT filename, content_type, data FROM media_files WHERE id = ?")) {
            ps.setInt(1, mediaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                contentType = rs.getString("content_type");
                data = rs.getBytes("data");
            }
        } catch (Exception e) {
            throw ServicioException.interno("Error interno", e);
        }

        int[] rango = parsearRango(rangeHeader, data.length);
        if (rango == null) {
            return new Descarga(200, contentType, data, null);
        }

        int start = rango[0];
        int end = rango[1];
        if (start >= data.length) {
            return new Descarga(416, contentType, null, "bytes */" + data.length);
        }
        if (end >= data.length) end = data.length - 1;

        int length = end - start + 1;
        byte[] rangeData = new byte[length];
        System.arraycopy(data, start, rangeData, 0, length);
        return new Descarga(206, contentType, rangeData, "bytes " + start + "-" + end + "/" + data.length);
    }

    /**
     * Interpreta {@code bytes=inicio-fin}. Devuelve null si no hay Range o no se entiende (se sirve el archivo
     * completo, que es lo que permite el estándar con un Range inválido).
     */
    private static int[] parsearRango(String rangeHeader, int total) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) return null;
        try {
            String[] partes = rangeHeader.substring(6).split("-");
            int start = Integer.parseInt(partes[0]);
            int end = partes.length > 1 && !partes[1].isEmpty() ? Integer.parseInt(partes[1]) : total - 1;
            if (start < 0 || end < start) return null;
            return new int[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
