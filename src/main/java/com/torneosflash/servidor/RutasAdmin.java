package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.AdminService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de administración: transacciones, disputas, stats, fix-status.
 *
 * A3: solo parsean el request y llaman a {@link AdminService}.
 *
 * S3: el acceso lo controla {@link AdminMiddleware} (sesión JWT + rol admin leído de la BD) para todo
 * {@code /api/admin/*}, {@code /api/deposit}, {@code /api/upload} y {@code /check-ip}.
 * La ruta {@code /secret-admin/{username}} y la ruta GET {@code /admin-fix-status/...} (vulnerable a CSRF por ser GET)
 * fueron eliminadas: para resetear el estado de un jugador se usa {@code POST /api/admin/fix-status}.
 */
public class RutasAdmin {

    public static void register(Javalin app, AdminService admin, AppConfig config) {

        // GET /api/admin/transactions (solo pendientes)
        app.get("/api/admin/transactions", ctx -> ctx.json(admin.transaccionesPendientes()));

        // GET /api/admin/disputes
        app.get("/api/admin/disputes", ctx -> ctx.json(admin.disputas()));

        // GET /api/admin/stats
        app.get("/api/admin/stats", ctx -> ctx.json(admin.estadisticas()));

        // POST /api/admin/transaction/process (approve/reject)
        app.post("/api/admin/transaction/process", ctx -> {
            JsonObject body = parseBody(ctx);
            String mensaje = admin.procesarTransaccion(
                    Peticion.entero(body, "transId"),
                    Peticion.texto(body, "action"));
            ctx.json(successJson(mensaje, 0));
        });

        // POST /api/admin/resolve-dispute
        app.post("/api/admin/resolve-dispute", ctx -> {
            JsonObject body = parseBody(ctx);
            admin.resolverDisputa(
                    Peticion.entero(body, "matchId"),
                    Peticion.texto(body, "ganadorNombre"),
                    Peticion.textoOpcional(body, "culpableNombre", "nadie"));
            ctx.json(successJson("Disputa resuelta", 0));
        });

        // POST /api/admin/fix-status (ruta segura contra CSRF para resetear el estado de un jugador)
        app.post("/api/admin/fix-status", ctx -> {
            JsonObject body = parseBody(ctx);
            String targetUser = Peticion.texto(body, "targetUser");
            admin.resetearEstado(targetUser);
            ctx.json(successJson("Estado de " + targetUser + " reseteado a normal", 0));
        });

        // GET /check-ip (diagnóstico de IP efectiva y cadena XFF; protegida por AdminMiddleware)
        app.get("/check-ip", ctx -> {
            int saltosProxy = config.getRateLimitProxyHops();
            String ipEfectiva = RateLimitMiddleware.ipCliente(ctx, saltosProxy);
            String xffNoConfiable = ctx.header("X-Forwarded-For");
            if (xffNoConfiable == null || xffNoConfiable.isBlank()) xffNoConfiable = "(ausente)";
            if (xffNoConfiable.length() > 512) xffNoConfiable = xffNoConfiable.substring(0, 512) + "…";

            ctx.result("IP usada por el rate limit: " + ipEfectiva
                    + "\nRATE_LIMIT_PROXY_HOPS: " + saltosProxy
                    + "\nX-Forwarded-For recibido (dato no confiable): " + xffNoConfiable
                    + "\nIP Pública del Servidor: " + admin.ipPublicaServidor());
        });
    }
}
