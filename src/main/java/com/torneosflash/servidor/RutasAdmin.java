package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.servicio.NotificacionPushServicio;
import com.torneosflash.socketio.SocketIOServer;
import com.torneosflash.socketio.SocketIOClient;
import io.javalin.Javalin;
import java.net.URI;

import static com.torneosflash.servidor.RutasAuth.*;
import static com.torneosflash.servidor.RutasFinanzas.notificarUsuario;

/**
 * Rutas de administración: transacciones, disputas, stats, secret-admin, fix-status.
 */
public class RutasAdmin {

    public static void register(Javalin app, UsuarioDAO usuarioDAO, GenericDAO db,
                                 SocketIOServer io, NotificacionPushServicio pushService) {

        // GET /api/admin/transactions (solo pendientes)
        app.get("/api/admin/transactions", ctx -> {
            ctx.json(db.query("SELECT * FROM transactions WHERE estado = 'pendiente' ORDER BY id DESC"));
        });

        // GET /api/admin/disputes
        app.get("/api/admin/disputes", ctx -> {
            ctx.json(db.query("SELECT * FROM matches WHERE estado = 'disputa' ORDER BY id DESC"));
        });

        // GET /api/admin/stats
        app.get("/api/admin/stats", ctx -> {
            JsonObject stats = new JsonObject();

            // Dinero en circulación = suma de saldos de todos los usuarios
            JsonObject saldoSum = db.queryOne("SELECT COALESCE(SUM(saldo),0) as total FROM users");
            stats.addProperty("totalUsuarios", saldoSum != null ? saldoSum.get("total").getAsDouble() : 0);

            // Ganancias del admin = suma de admin_wallet (histórico total)
            JsonObject walletSum = db.queryOne("SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet");
            stats.addProperty("totalGanancias", walletSum != null ? walletSum.get("total").getAsDouble() : 0);

            // Desglose por categorías (Actual e Histórico - por ahora es lo mismo)
            String[] categorias = {"sorteos", "misiones", "logros", "leaderboard", "devolucion", "ganancia", "referidos"};
            JsonObject desglose = new JsonObject();
            for (String cat : categorias) {
                JsonObject catSum = db.queryOne("SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet WHERE categoria = ?", cat);
                desglose.addProperty(cat, catSum != null ? catSum.get("total").getAsDouble() : 0);
            }
            stats.add("desglose", desglose);

            // Lista de todos los usuarios con sus detalles
            java.util.ArrayList<JsonObject> usuarios = db.query(
                "SELECT id, username, email, saldo, tipo_suscripcion, ganancia_generada, " +
                "gen_sorteos, gen_misiones, gen_logros, gen_leaderboard, gen_devolucion, gen_referidos, " +
                "total_partidas, total_victorias, victorias_normales, victorias_disputa, " +
                "total_derrotas, derrotas_normales, derrotas_disputa, " +
                "faltas, salidas_chat, salidas_x, salidas_canal, salidas_desconexion " +
                "FROM users ORDER BY saldo DESC");
            JsonArray listaUsuarios = new JsonArray();
            for (JsonObject u : usuarios) {
                listaUsuarios.add(u);
            }
            stats.add("listaUsuarios", listaUsuarios);

            stats.addProperty("usuariosOnline", io.getSockets().size());
            ctx.json(stats);
        });

        // POST /api/admin/transaction/process (approve/reject)
        app.post("/api/admin/transaction/process", ctx -> {
            JsonObject body = parseBody(ctx);
            int transId = body.get("transId").getAsInt();
            String action = body.get("action").getAsString();

            JsonObject trans = db.queryOne("SELECT * FROM transactions WHERE id = ?", transId);
            if (trans == null) { ctx.json(errorJson("No existe")); return; }

            // Evitar procesar transacciones que ya fueron aprobadas/rechazadas
            String estadoActual = trans.get("estado").getAsString();
            if (!"pendiente".equals(estadoActual)) {
                ctx.json(errorJson("Esta transacción ya fue procesada (" + estadoActual + ")"));
                return;
            }

            String tipo = trans.get("tipo").getAsString();
            int userId = trans.get("usuario_id").getAsNumber().intValue();
            double monto = trans.get("monto").getAsDouble();

            if ("reject".equals(action)) {
                if ("retiro".equals(tipo)) {
                    // Devolver dinero
                    db.update("UPDATE users SET saldo = saldo + ? WHERE id = ?", monto, userId);
                    JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
                    double saldo = u != null ? u.get("saldo").getAsDouble() : 0;
                    notificarUsuario(io, db, userId, "❌ Retiro rechazado. Saldo devuelto.", saldo);
                } else {
                    JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
                    double saldo = u != null ? u.get("saldo").getAsDouble() : 0;
                    notificarUsuario(io, db, userId, "❌ Recarga rechazada.", saldo);
                }
                db.update("UPDATE transactions SET estado = 'rechazado' WHERE id = ?", transId);
                ctx.json(successJson("Rechazada", 0));
            } else {
                // Aprobar
                if ("deposito".equals(tipo)) {
                    db.update("UPDATE users SET saldo = saldo + ? WHERE id = ?", monto, userId);
                    JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
                    double saldo = u != null ? u.get("saldo").getAsDouble() : 0;
                    notificarUsuario(io, db, userId, "✅ Recarga aprobada.", saldo);
                } else {
                    JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", userId);
                    double saldo = u != null ? u.get("saldo").getAsDouble() : 0;
                    notificarUsuario(io, db, userId, "✅ Tu retiro ha sido enviado.", saldo);
                }
                db.update("UPDATE transactions SET estado = 'completado' WHERE id = ?", transId);
                ctx.json(successJson("Aprobada", 0));
            }
        });

        // POST /api/admin/resolve-dispute
        app.post("/api/admin/resolve-dispute", ctx -> {
            JsonObject body = parseBody(ctx);
            int matchId = body.get("matchId").getAsInt();
            String ganadorNombre = body.get("ganadorNombre").getAsString();
            String culpableNombre = body.has("culpableNombre") ? body.get("culpableNombre").getAsString() : "nadie";

            JsonObject match = db.queryOne("SELECT * FROM matches WHERE id = ?", matchId);
            if (match == null) { ctx.json(errorJson("No existe")); return; }

            JsonObject winner = db.queryOne("SELECT id, saldo FROM users WHERE username = ?", ganadorNombre);
            if (winner == null) { ctx.json(errorJson("Ganador no encontrado")); return; }

            int winnerId = winner.get("id").getAsNumber().intValue();
            double apuesta = match.get("apuesta").getAsDouble();
            double pozo = apuesta * 2;
            double porcentajeComision = 0.25 - ((apuesta - 1000.0) / 19000.0) * 0.15;
            if (porcentajeComision > 0.25) porcentajeComision = 0.25;
            if (porcentajeComision < 0.10) porcentajeComision = 0.10;
            double comision = pozo * porcentajeComision;
            double premio = pozo - comision;
            double comSorteos = comision * 0.20;
            double comMisiones = comision * 0.10;
            double comLogros = comision * 0.05;
            double comLeaderboard = comision * 0.15;
            double comDevolucion = comision * 0.15;
            double comReferidos = comision * 0.10;
            double comGanancia = comision - (comSorteos + comMisiones + comLogros + comLeaderboard + comDevolucion + comReferidos);
            double utilidad = comGanancia / 2.0;

            String j1 = match.get("jugador1").getAsString();
            String j2 = match.get("jugador2").getAsString();

            // Pagar al ganador
            db.update("UPDATE users SET saldo = saldo + ?, total_ganado = total_ganado + ? WHERE id = ?",
                    premio, premio, winnerId);

            // Stats
            db.update("UPDATE users SET ganancia_generada = ganancia_generada + ?, gen_sorteos = gen_sorteos + ?, gen_misiones = gen_misiones + ?, gen_logros = gen_logros + ?, gen_leaderboard = gen_leaderboard + ?, gen_devolucion = gen_devolucion + ?, gen_referidos = gen_referidos + ? WHERE username IN (?, ?)",
                    utilidad, comSorteos / 2.0, comMisiones / 2.0, comLogros / 2.0, comLeaderboard / 2.0, comDevolucion / 2.0, comReferidos / 2.0, j1, j2);
            
            String detalle = "Match #" + matchId;
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'sorteos')", comSorteos, detalle);
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'misiones')", comMisiones, detalle);
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'logros')", comLogros, detalle);
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'leaderboard')", comLeaderboard, detalle);
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'devolucion')", comDevolucion, detalle);
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'referidos')", comReferidos, detalle);
            db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'comision_disputa', ?, 'ganancia')", comGanancia, detalle);

            // Victorias/derrotas
            db.update("UPDATE users SET total_victorias = total_victorias + 1, victorias_disputa = victorias_disputa + 1, " +
                    "total_partidas = total_partidas + 1, victorias_dia = victorias_dia + 1, " +
                    "victorias_semana = victorias_semana + 1, victorias_mes = victorias_mes + 1, " +
                    "victorias_ano = victorias_ano + 1 WHERE id = ?", winnerId);
            String perdedor = ganadorNombre.equals(j1) ? j2 : j1;
            db.update("UPDATE users SET total_derrotas = total_derrotas + 1, derrotas_disputa = derrotas_disputa + 1, " +
                    "total_partidas = total_partidas + 1 WHERE username = ?", perdedor);

            if (culpableNombre != null && !"nadie".equals(culpableNombre)) {
                db.update("UPDATE users SET faltas = faltas + 1 WHERE username = ?", culpableNombre);
            }

            // Acumular tickets para ambos jugadores (cada uno recibe su mitad de la comisión de sorteos)
            JsonObject j1Data = db.queryOne("SELECT id FROM users WHERE username = ?", j1);
            JsonObject j2Data = db.queryOne("SELECT id FROM users WHERE username = ?", j2);
            if (j1Data != null) {
                int j1Id = j1Data.get("id").getAsNumber().intValue();
                int ticketsJ1 = RutasSorteos.acumularTickets(db, j1Id, comSorteos / 2.0);
                if (ticketsJ1 > 0) {
                    for (SocketIOClient s : io.getSockets().values()) {
                        if (s.getUserData() != null && s.getUserData().get("id").getAsNumber().intValue() == j1Id) {
                            JsonObject ticketData = new JsonObject();
                            ticketData.addProperty("cantidad", ticketsJ1);
                            s.emit("tickets_ganados", ticketData);
                        }
                    }
                }
            }
            if (j2Data != null) {
                int j2Id = j2Data.get("id").getAsNumber().intValue();
                int ticketsJ2 = RutasSorteos.acumularTickets(db, j2Id, comSorteos / 2.0);
                if (ticketsJ2 > 0) {
                    for (SocketIOClient s : io.getSockets().values()) {
                        if (s.getUserData() != null && s.getUserData().get("id").getAsNumber().intValue() == j2Id) {
                            JsonObject ticketData = new JsonObject();
                            ticketData.addProperty("cantidad", ticketsJ2);
                            s.emit("tickets_ganados", ticketData);
                        }
                    }
                }
            }

            // Cerrar match y liberar jugadores
            db.update("UPDATE matches SET estado = 'finalizada', ganador = ? WHERE id = ?", ganadorNombre, matchId);
            db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE username IN (?, ?)", j1, j2);

            // Notificar sockets
            for (SocketIOClient s : io.getSockets().values()) {
                if (s.getUserData() != null) {
                    int sId = s.getUserData().get("id").getAsNumber().intValue();
                    if (sId == winnerId) {
                        JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", winnerId);
                        double nuevoSaldo = u != null ? u.get("saldo").getAsDouble() : 0;
                        s.getUserData().addProperty("saldo", nuevoSaldo);
                        s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));
                    }
                    String uname = s.getUserData().get("username").getAsString();
                    if (j1.equals(uname) || j2.equals(uname)) {
                        s.emit("flujo_completado");
                        s.getUserData().addProperty("estado", "normal");
                    }
                }
            }
            ctx.json(successJson("Disputa resuelta", 0));
        });

        // GET /secret-admin/:username (hacer admin)
        app.get("/secret-admin/{username}", ctx -> {
            String username = ctx.pathParam("username");
            usuarioDAO.hacerAdmin(username);
            ctx.result("✅ " + username + " ahora es admin");
        });

        // GET /admin-fix-status/:targetUser/:adminUser (resetear estado)
        app.get("/admin-fix-status/{targetUser}/{adminUser}", ctx -> {
            String targetUser = ctx.pathParam("targetUser");
            String adminUser = ctx.pathParam("adminUser");

            // Verificar que el admin es admin
            JsonObject admin = db.queryOne("SELECT tipo_suscripcion FROM users WHERE username = ?", adminUser);
            if (admin == null || !"admin".equals(admin.get("tipo_suscripcion").getAsString())) {
                ctx.result("⛔ No tienes permisos de admin");
                return;
            }

            usuarioDAO.resetearEstado(targetUser);
            ctx.result("✅ Estado de " + targetUser + " reseteado a normal");
        });

        // GET /check-ip (mostrar IP del servidor y cliente)
        app.get("/check-ip", ctx -> {
            String clientIp = ctx.header("x-forwarded-for");
            if (clientIp == null || clientIp.isEmpty()) clientIp = ctx.ip();
            if ("0:0:0:0:0:0:0:1".equals(clientIp)) clientIp = "127.0.0.1 (Localhost)";
            
            String serverIp = "Desconocida";
            try {
                java.net.URL url = new URI("https://api.ipify.org").toURL();
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(url.openStream()));
                serverIp = in.readLine();
                in.close();
            } catch (Exception e) {
                serverIp = "Error obteniendo IP: " + e.getMessage();
            }
            
            ctx.result("IP del Cliente: " + clientIp + "\nIP Pública del Servidor: " + serverIp);
        });
    }
}
