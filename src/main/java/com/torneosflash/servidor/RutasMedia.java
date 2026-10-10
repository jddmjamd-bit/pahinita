package com.torneosflash.servidor;

import com.torneosflash.servicio.MediaService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;

/**
 * Rutas para subir y servir archivos multimedia (videos).
 *
 * A3: aquí solo se leen el archivo y los headers del request y se escriben los headers de la respuesta;
 * validación, guardado en BD y resolución del header Range viven en {@link MediaService}.
 *
 * POST /api/upload     → sube un video y devuelve su URL
 * GET  /api/media/{id} → sirve el archivo desde la BD
 */
public class RutasMedia {

    public static void register(Javalin app, MediaService media) {

        // POST /api/upload - Subir video
        app.post("/api/upload", ctx -> {
            UploadedFile archivo = ctx.uploadedFile("file");
            if (archivo == null) {
                throw ServicioException.solicitudInvalida("No se recibió ningún archivo");
            }
            ctx.json(media.subirVideo(archivo.contentType(), archivo.filename(), archivo.content()));
        });

        // GET /api/media/{id} - Servir archivo desde BD (con soporte Range para seek en videos)
        app.get("/api/media/{id}", ctx -> {
            MediaService.Descarga descarga = media.descargar(Peticion.pathEntero(ctx, "id"), ctx.header("Range"));
            if (descarga == null) {
                ctx.status(404).result("Archivo no encontrado");
                return;
            }

            ctx.contentType(descarga.contentType());
            ctx.header("X-Content-Type-Options", "nosniff"); // S5: el navegador no debe "adivinar" otro tipo (p.ej. HTML)
            ctx.header("Accept-Ranges", "bytes");
            ctx.header("Cache-Control", "public, max-age=86400"); // Cache 24h

            if (descarga.status() == 416) {
                ctx.status(416).header("Content-Range", descarga.contentRange());
                return;
            }
            if (descarga.status() == 206) {
                ctx.status(206);
                ctx.header("Content-Range", descarga.contentRange());
            }
            ctx.header("Content-Length", String.valueOf(descarga.datos().length));
            ctx.result(descarga.datos());
        });
    }
}
