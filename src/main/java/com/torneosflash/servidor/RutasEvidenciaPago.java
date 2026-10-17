package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.servicio.EvidenciaPagoService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.parseBody;

/**
 * Rutas de evidencias de pago (roadmap 7).
 *
 * A3: solo parsean el request y llaman a {@link EvidenciaPagoService}. El usuario sale SIEMPRE de la sesión JWT
 * ({@link Peticion#userIdDeSesion}); el servicio responde 401 sin sesión y 403 si una ruta de admin la llama alguien
 * que no es admin. Las rutas de admin cuelgan de {@code /api/admin/*}, así que además las cubre {@link AdminMiddleware}.
 *
 * <pre>
 * Usuario con sesión
 *   GET  /api/pagos/evidencias                  últimas evidencias, ya con forma de mensaje de chat (canal «pagos»)
 * Admin
 *   GET  /api/admin/pagos/retiros               retiros aprobados que aún no tienen evidencia
 *   POST /api/admin/pagos/evidencias            {transaccionId, imagen (data URI base64), nota?} publica la evidencia
 * </pre>
 */
public class RutasEvidenciaPago {

    public static void register(Javalin app, EvidenciaPagoService evidencias) {

        // ---------------- Usuario ----------------

        app.get("/api/pagos/evidencias", ctx -> ctx.json(evidencias.historial(Peticion.userIdDeSesion(ctx))));

        // ---------------- Admin ----------------

        app.get("/api/admin/pagos/retiros", ctx -> ctx.json(evidencias.retirosSinEvidencia(Peticion.userIdDeSesion(ctx))));

        app.post("/api/admin/pagos/evidencias", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(evidencias.publicar(Peticion.userIdDeSesion(ctx),
                    Peticion.entero(body, "transaccionId"),
                    Peticion.texto(body, "imagen"),
                    Peticion.textoOpcional(body, "nota", "")));
        });
    }
}
