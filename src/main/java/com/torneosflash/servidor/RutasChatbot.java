package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.servicio.ChatbotService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.parseBody;
import static com.torneosflash.servidor.RutasAuth.successJson;

/**
 * Rutas del chatbot IA (roadmap parte 20).
 *
 * A3: solo parsean el request y llaman a {@link ChatbotService}. El usuario sale SIEMPRE de la sesión JWT
 * ({@link Peticion#userIdDeSesion}), nunca del body; el servicio responde 401 si no hay sesión y 403 si una ruta de
 * admin la llama alguien que no es admin.
 *
 * <pre>
 * Usuario
 *   GET    /api/chatbot/estado      estado del chat: disponibilidad, tono e historial
 *   POST   /api/chatbot/mensaje     {texto} -> respuesta de la IA (límite extra por IP: ver RateLimitMiddleware)
 *   PUT    /api/chatbot/tono        {tono: formal | informal | adaptativo | defecto}
 *   DELETE /api/chatbot/historial   borra el historial propio
 * Admin
 *   GET    /api/admin/chatbot/config                       tono por defecto y estado de la IA
 *   PUT    /api/admin/chatbot/config                       {tono}
 *   GET    /api/admin/chatbot/conocimiento?estado=...      pendiente (default) | respondida | descartada
 *   POST   /api/admin/chatbot/conocimiento                 {pregunta, respuesta} agrega conocimiento a mano
 *   POST   /api/admin/chatbot/entrenamiento/siguiente      la IA le hace una pregunta al admin
 *   POST   /api/admin/chatbot/conocimiento/{id}/responder  {respuesta} responde o corrige
 *   POST   /api/admin/chatbot/conocimiento/{id}/descartar  "no aplica"
 *   DELETE /api/admin/chatbot/conocimiento/{id}            borra la entrada
 * </pre>
 */
public class RutasChatbot {

    public static void register(Javalin app, ChatbotService chatbot) {

        // ---------------- Usuario ----------------

        app.get("/api/chatbot/estado", ctx ->
                ctx.json(chatbot.estado(Peticion.userIdDeSesion(ctx))));

        app.post("/api/chatbot/mensaje", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(chatbot.responder(Peticion.userIdDeSesion(ctx), Peticion.texto(body, "texto")));
        });

        app.put("/api/chatbot/tono", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(chatbot.cambiarTono(Peticion.userIdDeSesion(ctx), Peticion.texto(body, "tono")));
        });

        app.delete("/api/chatbot/historial", ctx ->
                ctx.json(chatbot.borrarHistorial(Peticion.userIdDeSesion(ctx))));

        // ---------------- Admin ----------------

        app.get("/api/admin/chatbot/config", ctx ->
                ctx.json(chatbot.configuracion(Peticion.userIdDeSesion(ctx))));

        app.put("/api/admin/chatbot/config", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(chatbot.cambiarTonoDefecto(Peticion.userIdDeSesion(ctx), Peticion.texto(body, "tono")));
        });

        app.get("/api/admin/chatbot/conocimiento", ctx -> {
            String estado = ctx.queryParam("estado");
            ctx.json(chatbot.listarConocimiento(Peticion.userIdDeSesion(ctx), estado == null ? "pendiente" : estado));
        });

        app.post("/api/admin/chatbot/conocimiento", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(chatbot.agregarConocimiento(Peticion.userIdDeSesion(ctx),
                    Peticion.texto(body, "pregunta"), Peticion.texto(body, "respuesta")));
        });

        app.post("/api/admin/chatbot/entrenamiento/siguiente", ctx ->
                ctx.json(chatbot.siguientePregunta(Peticion.userIdDeSesion(ctx))));

        app.post("/api/admin/chatbot/conocimiento/{id}/responder", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(chatbot.responderDuda(Peticion.userIdDeSesion(ctx),
                    Peticion.pathEntero(ctx, "id"), Peticion.texto(body, "respuesta")));
        });

        app.post("/api/admin/chatbot/conocimiento/{id}/descartar", ctx -> {
            chatbot.descartar(Peticion.userIdDeSesion(ctx), Peticion.pathEntero(ctx, "id"));
            ctx.json(successJson("Pregunta descartada", 0));
        });

        app.delete("/api/admin/chatbot/conocimiento/{id}", ctx -> {
            chatbot.eliminar(Peticion.userIdDeSesion(ctx), Peticion.pathEntero(ctx, "id"));
            ctx.json(successJson("Entrada eliminada", 0));
        });
    }
}
