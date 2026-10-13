package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.ChatbotService;
import com.torneosflash.servicio.RateLimiter;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;
import io.javalin.http.Context;

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
 *   POST   /api/chatbot/mensaje     {texto} -> respuesta de la IA (límite extra por IP, ver {@link #limitarPorIp})
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

    /**
     * @param config    para el límite por IP ({@code RATE_LIMIT_CHATBOT_PER_MIN}) y los saltos de proxy
     * @param limitador el mismo {@link RateLimiter} que usa {@link ChatbotService} para el límite por usuario
     *                  (las claves no chocan: {@code chatbot|usuario|<id>} y {@code chatbot|ip|<ip>})
     */
    public static void register(Javalin app, ChatbotService chatbot, AppConfig config, RateLimiter limitador) {

        // ---------------- Usuario ----------------

        app.get("/api/chatbot/estado", ctx ->
                ctx.json(chatbot.estado(Peticion.userIdDeSesion(ctx))));

        app.post("/api/chatbot/mensaje", ctx -> {
            limitarPorIp(ctx, config, limitador);
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

    /**
     * Cada mensaje al bot es una llamada de pago a la IA, así que además del límite general por IP de
     * {@code RateLimitMiddleware} (60/min) este endpoint tiene el suyo, más estricto. Respeta
     * {@code RATE_LIMIT_ENABLED} y usa la misma regla de IP real detrás del proxy (X-Forwarded-For).
     * El límite por usuario (minuto y día) lo aplica {@link ChatbotService#responder}.
     */
    private static void limitarPorIp(Context ctx, AppConfig config, RateLimiter limitador) {
        if (!config.isRateLimitEnabled()) return;
        String ip = RateLimitMiddleware.ipCliente(ctx, config.getRateLimitProxyHops());
        RateLimiter.Resultado r = limitador.intentar("chatbot|ip|" + ip, config.getRateLimitChatbotPorMin());
        if (!r.isPermitido()) {
            ctx.header("Retry-After", String.valueOf(r.getReintentarEnSegundos()));
            throw ServicioException.demasiadasSolicitudes(
                    "Vas muy rápido. Espera " + r.getReintentarEnSegundos() + " segundos e inténtalo de nuevo");
        }
    }
}
