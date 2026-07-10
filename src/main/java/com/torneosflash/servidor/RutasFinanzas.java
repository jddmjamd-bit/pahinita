package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOServer;
import com.torneosflash.socketio.SocketIOClient;
import io.javalin.Javalin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de finanzas: depósitos, retiros, Wompi webhooks.
 */
public class RutasFinanzas {

    public static void register(Javalin app, GenericDAO db, AppConfig config, SocketIOServer io) {

        // POST /api/deposit (depósito manual por admin)
        app.post("/api/deposit", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = body.get("userId").getAsInt();
            double monto = body.get("monto").getAsDouble();

            db.update("UPDATE users SET saldo = saldo + ? WHERE id = ?", monto, userId);
            double nuevoSaldo = 0;
            JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
            if (u != null) nuevoSaldo = u.get("saldo").getAsDouble();

            // Notificar via socket
            notificarUsuario(io, userId, "✅ Recarga acreditada.", nuevoSaldo);
            ctx.json(successJson("Depósito realizado", 0));
        });

        // POST /api/transaction/create (crear solicitud de recarga)
        app.post("/api/transaction/create", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = body.get("userId").getAsInt();
            String username = body.get("username").getAsString();
            String metodo = body.get("metodo").getAsString();
            double monto = body.get("monto").getAsDouble();
            String referencia = body.has("referencia") ? body.get("referencia").getAsString() : "";

            db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia) " +
                    "VALUES (?, ?, 'deposito', ?, ?, ?)", userId, username, metodo, monto, referencia);
            ctx.json(successJson("Solicitud creada", 0));
        });

        // POST /api/transaction/withdraw (solicitar retiro)
        app.post("/api/transaction/withdraw", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = body.get("userId").getAsInt();
            String username = body.get("username").getAsString();
            double monto = body.get("monto").getAsDouble();
            String metodo = body.has("metodo") ? body.get("metodo").getAsString() : "nequi_retiro";
            String referencia = body.has("referencia") ? body.get("referencia").getAsString() : "";

            // Verificar saldo
            JsonObject user = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
            if (user == null || user.get("saldo").getAsDouble() < monto) {
                ctx.json(errorJson("Saldo insuficiente"));
                return;
            }

            // Descontar saldo inmediatamente
            db.update("UPDATE users SET saldo = saldo - ? WHERE id = ?", monto, userId);

            // Crear transacción
            db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia) " +
                    "VALUES (?, ?, 'retiro', ?, ?, ?)", userId, username, metodo, monto, referencia);

            // Notificar saldo actualizado
            JsonObject updated = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
            double nuevoSaldo = updated != null ? updated.get("saldo").getAsDouble() : 0;
            notificarUsuario(io, userId, "⏳ Retiro en proceso...", nuevoSaldo);

            ctx.json(successJson("Retiro solicitado", 0));
        });

        // POST /api/wompi/init (iniciar pago Wompi)
        app.post("/api/wompi/init", ctx -> {
            JsonObject body = parseBody(ctx);
            if (!body.has("monto") || body.get("monto").isJsonNull()) {
                ctx.status(400).json(errorJson("El monto es requerido"));
                return;
            }
            double monto = body.get("monto").getAsDouble();

            // Calcular el total con comisiones
            double baseCara = monto + 840;
            double totalCobrado = Math.ceil(baseCara / 0.964);
            long montoCentavos = (long) (totalCobrado * 100);

            // Generar referencia
            String reference = "TF-" + System.currentTimeMillis();

            // Firma de integridad
            String integritySecret = config.getWompiIntegritySecret();
            String toSign = reference + montoCentavos + "COP" + integritySecret;
            String signature = sha256(toSign);

            JsonObject res = new JsonObject();
            res.addProperty("publicKey", config.getWompiPublicKey());
            res.addProperty("reference", reference);
            res.addProperty("amountInCents", montoCentavos);
            res.addProperty("signature", signature);
            res.addProperty("montoReal", monto);
            ctx.json(res);
        });

        // POST /api/wompi/webhook (webhook de Wompi)
        app.post("/api/wompi/webhook", ctx -> {
            try {
                JsonObject body = parseBody(ctx);
                JsonObject data = body.getAsJsonObject("data");
                JsonObject transaction = data.getAsJsonObject("transaction");

                String status = transaction.get("status").getAsString();
                String reference = transaction.get("reference").getAsString();

                if ("APPROVED".equals(status)) {
                    // Buscar transacción pendiente con esta referencia
                    JsonObject trans = db.queryOne(
                            "SELECT * FROM transactions WHERE referencia = ? AND estado = 'pendiente'", reference);

                    if (trans != null) {
                        int userId = trans.get("usuario_id").getAsNumber().intValue();
                        double monto = trans.get("monto").getAsDouble();

                        // Acreditar saldo
                        db.update("UPDATE users SET saldo = saldo + ? WHERE id = ?", monto, userId);
                        db.update("UPDATE transactions SET estado = 'completado' WHERE referencia = ?", reference);

                        JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
                        double nuevoSaldo = u != null ? u.get("saldo").getAsDouble() : 0;
                        notificarUsuario(io, userId, "✅ Pago Wompi aprobado. Saldo acreditado.", nuevoSaldo);
                    }
                }
                ctx.result("OK");
            } catch (Exception e) {
                System.err.println("Error webhook Wompi: " + e.getMessage());
                ctx.status(500).result("Error");
            }
        });
    }

    // --- Helpers ---
    static void notificarUsuario(SocketIOServer io, int userId, String mensaje, double saldo) {
        System.out.println("Intentando notificar al usuario ID: " + userId + " - Mensaje: " + mensaje);
        System.out.println("-> Total sockets conectados actualmente: " + io.getSockets().size());
        boolean found = false;
        for (SocketIOClient client : io.getSockets().values()) {
            System.out.println("-> Revisando socket SID: " + client.getSid() + " | Tiene UserData? " + (client.getUserData() != null));
            if (client.getUserData() != null &&
                client.getUserData().has("id") &&
                client.getUserData().get("id").getAsInt() == userId) {
                
                System.out.println("-> Socket encontrado para usuario " + userId + "! Emitiendo eventos...");
                found = true;
                JsonObject notifData = new JsonObject();
                notifData.addProperty("mensaje", mensaje);
                notifData.addProperty("saldo", saldo);
                client.emit("notificacion", notifData);
                client.emit("actualizar_saldo", new JsonPrimitive(saldo));
            }
        }
        if (!found) {
            System.out.println("-> ADVERTENCIA: No se encontró ningún socket conectado para el usuario ID " + userId);
        }
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
