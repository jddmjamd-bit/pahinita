package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOServer;
import com.torneosflash.socketio.SocketIOClient;
import io.javalin.Javalin;
import java.util.*;

/**
 * Rutas de leaderboard/rankings y lógica de premios periódicos dinámicos.
 */
public class RutasLeaderboard {

    private static final double[] PORCENTAJES = {0.25, 0.18, 0.14, 0.10, 0.08, 0.07, 0.06, 0.05, 0.04, 0.03};

    private static JsonArray calcularPremiosDinamicos(double pozoTotal) {
        JsonArray premios = new JsonArray();
        for (int i = 0; i < PORCENTAJES.length; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("posicion", i + 1);
            p.addProperty("premio", (int)(pozoTotal * PORCENTAJES[i]));
            premios.add(p);
        }
        return premios;
    }

    public static void register(Javalin app, GenericDAO db, SocketIOServer io) {

        // GET /api/leaderboard/:periodo
        app.get("/api/leaderboard/{periodo}", ctx -> {
            String periodo = ctx.pathParam("periodo");
            String orderColumn;
            switch (periodo) {
                case "dia": orderColumn = "victorias_dia"; break;
                case "semana": orderColumn = "victorias_semana"; break;
                case "mes": orderColumn = "victorias_mes"; break;
                case "ano": orderColumn = "victorias_ano"; break;
                case "global": orderColumn = "total_victorias"; break;
                case "apostado": orderColumn = "total_apostado"; break;
                case "ganado": orderColumn = "total_ganado"; break;
                default: ctx.status(400).json(new JsonObject()); return;
            }

            boolean isMoneyTab = "apostado".equals(periodo) || "ganado".equals(periodo);
            String selectAlias = isMoneyTab ? orderColumn + " as monto" : orderColumn + " as victorias";

            ArrayList<JsonObject> rows = db.query(
                    "SELECT id, username, " + selectAlias + ", total_partidas, total_victorias, total_derrotas, " +
                    "ganancia_generada, tipo_suscripcion, total_apostado, total_ganado FROM users WHERE " +
                    orderColumn + " > 0 ORDER BY " + orderColumn + " DESC LIMIT 50");

            // Agregar posición
            JsonArray ranking = new JsonArray();
            for (int i = 0; i < rows.size(); i++) {
                JsonObject row = rows.get(i);
                row.addProperty("posicion", i + 1);
                ranking.add(row);
            }

            JsonObject res = new JsonObject();
            res.addProperty("periodo", periodo);
            res.add("ranking", ranking);

            if ("dia".equals(periodo) || "semana".equals(periodo) || "mes".equals(periodo) || "ano".equals(periodo)) {
                JsonObject poolData = db.queryOne("SELECT * FROM leaderboard_pools WHERE id = 1");
                double pozoTotal = poolData != null ? poolData.get(periodo).getAsDouble() : 0;
                res.add("premios", calcularPremiosDinamicos(pozoTotal));
            }
            ctx.json(res);
        });

        // GET /api/leaderboard/history/:userId
        app.get("/api/leaderboard/history/{userId}", ctx -> {
            int userId = Integer.parseInt(ctx.pathParam("userId"));
            ctx.json(db.query("SELECT * FROM leaderboard_history WHERE user_id = ? ORDER BY fecha DESC LIMIT 20", userId));
        });
    }

    /**
     * Premiar y resetear un periodo del leaderboard.
     * Se llama desde el scheduler en Main.java.
     */
    public static void premiarYResetear(GenericDAO db, SocketIOServer io, String periodo, String columna) {
        try {
            System.out.println("🏆 Premiando leaderboard " + periodo + "...");
            
            JsonObject poolData = db.queryOne("SELECT * FROM leaderboard_pools WHERE id = 1");
            if (poolData == null) {
                db.update("UPDATE users SET " + columna + " = 0");
                return;
            }

            double pozoTotal = poolData.get(periodo).getAsDouble();

            ArrayList<JsonObject> top = db.query(
                    "SELECT id, username, " + columna + " as victorias, ganancia_generada FROM users WHERE " +
                    columna + " > 0 ORDER BY " + columna + " DESC, ganancia_generada DESC LIMIT 10");

            String configKey = "dia".equals(periodo) ? "diario" : "semana".equals(periodo) ? "semanal" :
                    "mes".equals(periodo) ? "mensual" : "anual";

            String ahora = new java.util.Date().toString();

            if (pozoTotal > 0) {
                double pozoSobrante = 0;

                // 1. Calcular premio base de cada jugador en el top
                for (int i = 0; i < PORCENTAJES.length; i++) {
                    if (i < top.size()) {
                        JsonObject jugador = top.get(i);
                        int premioMonto = (int) (pozoTotal * PORCENTAJES[i]);
                        jugador.addProperty("premio_asignado", premioMonto);
                    } else {
                        pozoSobrante += pozoTotal * PORCENTAJES[i];
                    }
                }

                // 2. Dividir el sobrante (si hay menos de 10 jugadores)
                double bonoExtraJugador = 0;
                if (pozoSobrante > 0) {
                    double mitadParaJugadores = pozoSobrante / 2.0;
                    double mitadParaAdmin = pozoSobrante / 2.0;

                    if (top.size() > 0) {
                        bonoExtraJugador = mitadParaJugadores / top.size();
                    } else {
                        // Nadie en el top, todo para el admin
                        mitadParaAdmin += mitadParaJugadores;
                    }

                    if (mitadParaAdmin > 0) {
                        db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'ganancia_sobrante_top', ?, 'ganancia')", 
                                  mitadParaAdmin, "Sobrante leaderboard " + configKey);
                    }
                }

                // 3. Pagar a los jugadores y notificar
                for (int i = 0; i < top.size(); i++) {
                    JsonObject jugador = top.get(i);
                    int jugadorId = jugador.get("id").getAsNumber().intValue();
                    int premioFinal = jugador.get("premio_asignado").getAsInt() + (int) bonoExtraJugador;
                    
                    if (premioFinal <= 0) continue;

                    // Dar premio
                    db.update("UPDATE users SET saldo = saldo + ? WHERE id = ?", (double) premioFinal, jugadorId);

                    // Historial
                    db.update("INSERT INTO leaderboard_history (user_id, username, periodo, victorias, ganancias, posicion, premio, fecha_inicio, fecha_fin) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            jugadorId, jugador.get("username").getAsString(), configKey,
                            (int) jugador.get("victorias").getAsLong(), jugador.get("ganancia_generada").getAsDouble(),
                            i + 1, premioFinal, ahora, ahora);

                    // Notificar por socket
                    for (SocketIOClient s : io.getSockets().values()) {
                        if (s.getUserData() != null && s.getUserData().get("id").getAsNumber().intValue() == jugadorId) {
                            JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", jugadorId);
                            double nuevoSaldo = u != null ? u.get("saldo").getAsDouble() : 0;
                            s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));

                            JsonObject premioData = new JsonObject();
                            premioData.addProperty("periodo", configKey);
                            premioData.addProperty("posicion", i + 1);
                            premioData.addProperty("premio", premioFinal);
                            premioData.addProperty("mensaje", "🏆 ¡Quedaste #" + (i + 1) + " del ranking " + configKey + "! Ganaste $" + premioFinal);
                            s.emit("premio_leaderboard", premioData);
                        }
                    }
                    System.out.println("   🥇 #" + (i + 1) + " " + jugador.get("username").getAsString() + ": +$" + premioFinal);
                }
            }

            // Resetear columna
            db.update("UPDATE users SET " + columna + " = 0");
            System.out.println("   ✅ Columna " + columna + " reseteada");

            // Reiniciar el pozo para ese periodo
            db.update("UPDATE leaderboard_pools SET " + periodo + " = 0 WHERE id = 1");
            System.out.println("   ✅ Pozo de " + periodo + " reiniciado a 0");

            JsonObject resetData = new JsonObject();
            resetData.addProperty("periodo", configKey);
            io.emit("leaderboard_reset", resetData);
        } catch (Exception e) {
            System.err.println("Error premiando leaderboard " + periodo + ": " + e.getMessage());
        }
    }
}
