package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;

import java.sql.*;
import java.util.UUID;

/**
 * Rutas para subir y servir archivos multimedia (videos).
 * Los videos se guardan directamente en PostgreSQL (tabla media_files)
 * para que sean persistentes en Render (sin depender del filesystem efímero).
 *
 * POST /api/upload  → Sube un video, lo guarda en la BD, devuelve URL
 * GET  /api/media/{id} → Sirve el archivo desde la BD
 */
public class RutasMedia {

    public static void register(Javalin app, GenericDAO db) {

        // =============================================
        // POST /api/upload - Subir video
        // =============================================
        app.post("/api/upload", ctx -> {
            try {
                UploadedFile uploadedFile = ctx.uploadedFile("file");
                if (uploadedFile == null) {
                    ctx.status(400).json(errorJson("No se recibió ningún archivo"));
                    return;
                }

                String contentType = uploadedFile.contentType();
                if (contentType == null || !contentType.startsWith("video/")) {
                    ctx.status(400).json(errorJson("Solo se permiten archivos de video"));
                    return;
                }

                // Leer bytes del archivo
                byte[] fileBytes = uploadedFile.content().readAllBytes();

                // Limitar tamaño (50MB máximo)
                if (fileBytes.length > 50 * 1024 * 1024) {
                    ctx.status(413).json(errorJson("El video excede el límite de 50MB"));
                    return;
                }

                // Generar nombre único
                String extension = "";
                String originalName = uploadedFile.filename();
                int dotIdx = originalName.lastIndexOf('.');
                if (dotIdx > 0) extension = originalName.substring(dotIdx);
                String filename = UUID.randomUUID().toString() + extension;

                // Guardar en PostgreSQL (tabla media_files)
                int mediaId = db.insertReturningId(
                    "INSERT INTO media_files (filename, content_type, data) VALUES (?, ?, ?) RETURNING id",
                    filename, contentType, fileBytes
                );

                if (mediaId < 0) {
                    ctx.status(500).json(errorJson("Error al guardar el archivo en la base de datos"));
                    return;
                }

                // Devolver la URL pública
                String url = "/api/media/" + mediaId;
                JsonObject response = new JsonObject();
                response.addProperty("url", url);
                response.addProperty("tipo", "video");
                response.addProperty("id", mediaId);
                ctx.json(response);

                System.out.println("📹 Video subido: " + filename + " (" + fileBytes.length / 1024 + "KB) → ID: " + mediaId);

            } catch (Exception e) {
                System.err.println("❌ Error en /api/upload: " + e.getMessage());
                e.printStackTrace();
                ctx.status(500).json(errorJson("Error interno al subir archivo"));
            }
        });

        // =============================================
        // GET /api/media/{id} - Servir archivo desde BD
        // =============================================
        app.get("/api/media/{id}", ctx -> {
            try {
                int mediaId = Integer.parseInt(ctx.pathParam("id"));

                // Buscar en la BD
                try (Connection conn = db.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                         "SELECT filename, content_type, data FROM media_files WHERE id = ?")) {
                    ps.setInt(1, mediaId);
                    ResultSet rs = ps.executeQuery();

                    if (!rs.next()) {
                        ctx.status(404).result("Archivo no encontrado");
                        return;
                    }

                    String contentType = rs.getString("content_type");
                    byte[] data = rs.getBytes("data");

                    // Headers para streaming de video
                    ctx.contentType(contentType);
                    ctx.header("Accept-Ranges", "bytes");
                    ctx.header("Cache-Control", "public, max-age=86400"); // Cache 24h

                    // Soporte Range requests para seek en videos
                    String rangeHeader = ctx.header("Range");
                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        String[] rangeParts = rangeHeader.substring(6).split("-");
                        int start = Integer.parseInt(rangeParts[0]);
                        int end = rangeParts.length > 1 && !rangeParts[1].isEmpty()
                                ? Integer.parseInt(rangeParts[1])
                                : data.length - 1;

                        if (start >= data.length) {
                            ctx.status(416).header("Content-Range", "bytes */" + data.length);
                            return;
                        }
                        if (end >= data.length) end = data.length - 1;

                        int length = end - start + 1;
                        byte[] rangeData = new byte[length];
                        System.arraycopy(data, start, rangeData, 0, length);

                        ctx.status(206);
                        ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
                        ctx.header("Content-Length", String.valueOf(length));
                        ctx.result(rangeData);
                    } else {
                        ctx.header("Content-Length", String.valueOf(data.length));
                        ctx.result(data);
                    }
                }
            } catch (NumberFormatException e) {
                ctx.status(400).result("ID inválido");
            } catch (Exception e) {
                System.err.println("❌ Error en /api/media: " + e.getMessage());
                ctx.status(500).result("Error interno");
            }
        });
    }

    private static JsonObject errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj;
    }
}
