package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOServer;
import com.torneosflash.socketio.SocketIOClient;
import io.javalin.Javalin;
import java.util.*;

/**
 * Rutas de leaderboard/rankings y lógica de premios periódicos.
 */
public class RutasLeaderboard {

    // Configuración de premios
    private static final JsonObject LEADERBOARD_PRIZES = new JsonObject();
    static {
        JsonArray diario = new JsonArray();
        diario.add(crearPremio(1, 1000)); diario.add(crearPremio(2, 500)); diario.add(crearPremio(3, 250));
        JsonArray semanal = new JsonArray();
        semanal.add(crearPremio(1, 5000)); semanal.add(crearPremio(2, 3000)); semanal.add(crearPremio(3, 1500));
        semanal.add(crearPremio(4, 1000)); semanal.add(crearPremio(5, 500));
        JsonArray mensual = new JsonArray();
        mensual.add(crearPremio(1, 20000)); mensual.add(crearPremio(2, 12000)); mensual.add(crearPremio(3, 7000));
        mensual.add(crearPremio(4, 4000)); mensual.add(crearPremio(5, 2000));
        JsonArray anual = new JsonArray();
        anual.add(crearPremio(1, 100000)); anual.add(crearPremio(2, 50000)); anual.add(crearPremio(3, 25000));
        LEADERBOARD_PRIZES.add("diario", diario);
        LEADERBOARD_PRIZES.add("semanal", semanal);
        LEADERBOARD_PRIZES.add("mensual", mensual);
        LEADERBOARD_PRIZES.add("anual", anual);
    }

    private static JsonObject crearPremio(int posicion, int premio) {
        JsonObject p = new JsonObject();
        p.addProperty("posicion", posicion);
        p.addProperty("premio", premio);
        return p;
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

            String premioKey = "dia".equals(periodo) ? "diario" : "semana".equals(periodo) ? "semanal" :
                    "mes".equals(periodo) ? "mensual" : "ano".equals(periodo) ? "anual" : null;

            JsonObject res = new JsonObject();
            res.addProperty("periodo", periodo);
            res.add("ranking", ranking);
            if (premioKey != null) res.add("premios", LEADERBOARD_PRIZES.get(premioKey));
            ctx.json(res);
        });

        // GET /api/leaderboard/prizes/config
        app.get("/api/leaderboard/prizes/config", ctx -> {
            ctx.json(LEADERBOARD_PRIZES);
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
            String configKey = "dia".equals(periodo) ? "diario" : "semana".equals(periodo) ? "semanal" :
                    "mes".equals(periodo) ? "mensual" : "anual";

            JsonArray premios = LEADERBOARD_PRIZES.getAsJsonArray(configKey);
            if (premios == null) return;

            ArrayList<JsonObject> top = db.query(
                    "SELECT id, username, " + columna + " as victorias, ganancia_generada FROM users WHERE " +
                    columna + " > 0 ORDER BY " + columna + " DESC, ganancia_generada DESC LIMIT " + premios.size());

            String ahora = new java.util.Date().toString();

            for (int i = 0; i < top.size() && i < premios.size(); i++) {
                JsonObject jugador = top.get(i);
                int premioMonto = premios.get(i).getAsJsonObject().get("premio").getAsInt();
                int jugadorId = jugador.get("id").getAsNumber().intValue();

                // Dar premio
                db.update("UPDATE users SET saldo = saldo + ? WHERE id = ?", (double) premioMonto, jugadorId);

                // Historial
                db.update("INSERT INTO leaderboard_history (user_id, username, periodo, victorias, ganancias, posicion, premio, fecha_inicio, fecha_fin) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        jugadorId, jugador.get("username").getAsString(), configKey,
                        (int) jugador.get("victorias").getAsLong(), jugador.get("ganancia_generada").getAsDouble(),
                        i + 1, premioMonto, ahora, ahora);

                // Notificar por socket
                for (SocketIOClient s : io.getSockets().values()) {
                    if (s.getUserData() != null && s.getUserData().get("id").getAsNumber().intValue() == jugadorId) {
                        JsonObject u = db.queryOne("SELECT saldo FROM users WHERE id = ?", jugadorId);
                        double nuevoSaldo = u != null ? u.get("saldo").getAsDouble() : 0;
                        s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));

                        JsonObject premioData = new JsonObject();
                        premioData.addProperty("periodo", configKey);
                        premioData.addProperty("posicion", i + 1);
                        premioData.addProperty("premio", premioMonto);
                        premioData.addProperty("mensaje", "🏆 ¡Quedaste #" + (i + 1) + " del ranking " + configKey + "! +$" + premioMonto);
                        s.emit("premio_leaderboard", premioData);
                    }
                }
                System.out.println("   🥇 #" + (i + 1) + " " + jugador.get("username").getAsString() + ": +$" + premioMonto);
            }

            // Resetear columna
            db.update("UPDATE users SET " + columna + " = 0");
            System.out.println("   ✅ Columna " + columna + " reseteada");

            JsonObject resetData = new JsonObject();
            resetData.addProperty("periodo", configKey);
            io.emit("leaderboard_reset", resetData);
        } catch (Exception e) {
            System.err.println("Error premiando leaderboard " + periodo + ": " + e.getMessage());
        }
    }
}
