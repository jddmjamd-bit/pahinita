package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.NotificacionesService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;

/** API autenticada del centro de notificaciones. */
public final class RutasNotificaciones {
    private RutasNotificaciones() {}

    public static void register(Javalin app, NotificacionesService notificaciones, AppConfig config) {
        // Solo expone los valores públicos que Firebase necesita en el navegador; nunca la cuenta de servicio.
        app.get("/api/push/config", ctx -> {
            JsonObject respuesta = new JsonObject();
            respuesta.addProperty("habilitado", config.hasFirebaseWebPush());
            if (config.hasFirebaseWebPush()) {
                try {
                    respuesta.add("firebaseConfig", JsonParser.parseString(config.getFirebaseWebConfig()).getAsJsonObject());
                    respuesta.addProperty("vapidKey", config.getFirebaseVapidKey());
                } catch (RuntimeException e) {
                    respuesta.addProperty("habilitado", false);
                }
            }
            ctx.json(respuesta);
        });

        app.get("/api/notificaciones", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(notificaciones.listar(userId));
        });

        app.post("/api/notificaciones/leer/categoria", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            JsonObject body = RutasAuth.parseBody(ctx);
            String categoria = Peticion.textoOpcional(body, "categoria", "");
            notificaciones.marcarCategoriaLeida(userId, categoria);
            ctx.json(RutasAuth.successJson("Notificaciones actualizadas", 0));
        });

        app.post("/api/notificaciones/leer/{id}", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            long id = id(ctx.pathParam("id"));
            if (id <= 0 || !notificaciones.marcarLeida(userId, id)) {
                throw ServicioException.noEncontrado("Notificación no encontrada");
            }
            ctx.json(RutasAuth.successJson("Notificación leída", 0));
        });

        app.delete("/api/notificaciones/{id}", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            long id = id(ctx.pathParam("id"));
            if (id <= 0 || !notificaciones.descartar(userId, id)) {
                throw ServicioException.noEncontrado("Notificación no encontrada");
            }
            ctx.json(RutasAuth.successJson("Notificación eliminada", 0));
        });
    }

    private static long id(String valor) {
        try { return Long.parseLong(valor); }
        catch (RuntimeException e) { return -1; }
    }
}
