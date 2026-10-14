package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.servicio.AdminService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de administración: transacciones, disputas, stats, fix-status.
 *
 * A3: solo parsean el request y llaman a {@link AdminService}.
 *
 * S3: el acceso lo controla {@link AdminMiddleware} (sesión JWT + rol admin leído de la BD) para todo
 * {@code /api/admin/*}, {@code /api/deposit} y {@code /admin-fix-status/*}; las rutas de aquí ya no verifican nada.
 * La ruta {@code /secret-admin/{username}}, que convertía en admin a cualquiera sin autenticación, fue eliminada:
 * para hacer admin a alguien se edita {@code users.tipo_suscripcion} desde el panel {@code /admin-db}.
 */
public class RutasAdmin {

    public static void register(Javalin app, AdminService admin) {

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

        // GET /admin-fix-status/:targetUser/:adminUser (resetear estado)
        // S3: AdminMiddleware exige sesión de admin. El segmento {adminUser} se conserva solo para no romper la URL
        // que ya usan los clientes: se IGNORA (antes bastaba con escribir el nombre de cualquier admin).
        app.get("/admin-fix-status/{targetUser}/{adminUser}", ctx -> {
            String targetUser = ctx.pathParam("targetUser");
            admin.resetearEstado(targetUser);
            ctx.result("✅ Estado de " + targetUser + " reseteado a normal");
        });

        // GET /check-ip (mostrar IP del cliente y del servidor)
        app.get("/check-ip", ctx -> {
            String clientIp = ctx.header("x-forwarded-for");
            if (clientIp == null || clientIp.isEmpty()) clientIp = ctx.ip();
            if ("0:0:0:0:0:0:0:1".equals(clientIp)) clientIp = "127.0.0.1 (Localhost)";

            ctx.result("IP del Cliente: " + clientIp + "\nIP Pública del Servidor: " + admin.ipPublicaServidor());
        });
    }
}
