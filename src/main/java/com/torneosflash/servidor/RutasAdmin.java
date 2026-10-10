package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.servicio.AdminService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de administración: transacciones, disputas, stats, secret-admin, fix-status.
 *
 * A3: solo parsean el request y llaman a {@link AdminService}.
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

        // GET /secret-admin/:username (hacer admin)
        // ⚠️ Sigue abierta tal como estaba: la elimina la tarea S3 (no es parte de A3).
        app.get("/secret-admin/{username}", ctx -> {
            String username = ctx.pathParam("username");
            admin.hacerAdmin(username);
            ctx.result("✅ " + username + " ahora es admin");
        });

        // GET /admin-fix-status/:targetUser/:adminUser (resetear estado)
        app.get("/admin-fix-status/{targetUser}/{adminUser}", ctx -> {
            String targetUser = ctx.pathParam("targetUser");
            if (admin.resetearEstado(targetUser, ctx.pathParam("adminUser"))) {
                ctx.result("✅ Estado de " + targetUser + " reseteado a normal");
            } else {
                ctx.result("⛔ No tienes permisos de admin");
            }
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
