package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.servicio.SoporteService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.parseBody;

/**
 * Rutas de soporte, "Hablar con el admin" (roadmap 24.5).
 *
 * A3: solo parsean el request y llaman a {@link SoporteService}. El usuario sale SIEMPRE de la sesión JWT
 * ({@link Peticion#userIdDeSesion}), nunca del body; el servicio responde 401 si no hay sesión y 403 si una ruta de
 * admin la llama alguien que no es admin. Las rutas de admin cuelgan de {@code /api/admin/*}, así que además las
 * cubre {@link AdminMiddleware} (S3).
 *
 * <pre>
 * Usuario
 *   POST /api/soporte/tickets   {categoria, asunto, mensaje} abre un ticket (429 si supera sus topes)
 *   GET  /api/soporte/tickets   mis tickets, con la respuesta del admin si ya la hay
 * Admin
 *   GET  /api/admin/soporte/tickets?estado=abierto|resuelto|todos        bandeja (default: abierto)
 *   POST /api/admin/soporte/tickets/{id}/responder   {respuesta}         responde y deja el ticket resuelto
 * </pre>
 */
public class RutasSoporte {

    public static void register(Javalin app, SoporteService soporte) {

        // ---------------- Usuario ----------------

        app.post("/api/soporte/tickets", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(soporte.crear(Peticion.userIdDeSesion(ctx),
                    Peticion.texto(body, "categoria"),
                    Peticion.texto(body, "asunto"),
                    Peticion.texto(body, "mensaje")));
        });

        app.get("/api/soporte/tickets", ctx ->
                ctx.json(soporte.misTickets(Peticion.userIdDeSesion(ctx))));

        // ---------------- Admin ----------------

        app.get("/api/admin/soporte/tickets", ctx -> {
            String estado = ctx.queryParam("estado");
            ctx.json(soporte.listarAdmin(Peticion.userIdDeSesion(ctx), estado == null ? "abierto" : estado));
        });

        app.post("/api/admin/soporte/tickets/{id}/responder", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(soporte.responder(Peticion.userIdDeSesion(ctx),
                    Peticion.pathEntero(ctx, "id"), Peticion.texto(body, "respuesta")));
        });
    }
}
