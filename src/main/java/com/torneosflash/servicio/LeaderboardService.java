package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * Lógica del leaderboard: rankings por periodo, historial y premios periódicos con reset (A3).
 * Antes vivía en {@code RutasLeaderboard}. No conoce HTTP.
 */
public class LeaderboardService {
    private static final Logger logger = LoggerFactory.getLogger(LeaderboardService.class);

    /** Reparto del pozo entre el top 10 (suma 1.0). */
    private static final double[] PORCENTAJES = {0.25, 0.18, 0.14, 0.10, 0.08, 0.07, 0.06, 0.05, 0.04, 0.03};

    private final GenericDAO db;
    private final SocketIOServer io;
    private final WalletService wallet;

    public LeaderboardService(GenericDAO db, SocketIOServer io, WalletService wallet) {
        this.db = db;
        this.io = io;
        this.wallet = wallet;
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Ranking (top 50) del periodo pedido; en los periodos con pozo incluye los premios por posición. */
    public JsonObject ranking(String periodo) {
        // La columna sale de esta lista cerrada (nunca del request), por eso es seguro concatenarla en el SQL
        String orderColumn;
        switch (periodo) {
            case "dia": orderColumn = "victorias_dia"; break;
            case "semana": orderColumn = "victorias_semana"; break;
            case "mes": orderColumn = "victorias_mes"; break;
            case "ano": orderColumn = "victorias_ano"; break;
            case "global": orderColumn = "total_victorias"; break;
            case "monto_torneos": orderColumn = "total_monto_torneos"; break;
            case "ganado": orderColumn = "total_ganado"; break;
            default: throw ServicioException.solicitudInvalida("Periodo no válido");
        }

        boolean isMoneyTab = "monto_torneos".equals(periodo) || "ganado".equals(periodo);
        String selectAlias = isMoneyTab ? orderColumn + " as monto" : orderColumn + " as victorias";

        ArrayList<JsonObject> rows = db.query(
                "SELECT id, username, " + selectAlias + ", total_partidas, total_victorias, total_derrotas, " +
                "ganancia_generada, tipo_suscripcion, total_monto_torneos, total_ganado FROM users WHERE " +
                orderColumn + " > 0 ORDER BY " + orderColumn + " DESC LIMIT 50");

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
        return res;
    }

    public ArrayList<JsonObject> historial(int userId) {
        return db.query("SELECT * FROM leaderboard_history WHERE user_id = ? ORDER BY fecha DESC LIMIT 20", userId);
    }

    private static JsonArray calcularPremiosDinamicos(double pozoTotal) {
        JsonArray premios = new JsonArray();
        for (int i = 0; i < PORCENTAJES.length; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("posicion", i + 1);
            p.addProperty("premio", (int) (pozoTotal * PORCENTAJES[i]));
            premios.add(p);
        }
        return premios;
    }

    // ------------------------------------------------------------------
    // Premios periódicos
    // ------------------------------------------------------------------

    /**
     * Premia al top del periodo con el pozo acumulado y resetea contadores y pozo.
     * Lo llama el scheduler de {@code Main} (día, semana, mes, año).
     */
    public void premiarYResetear(String periodo, String columna) {
        try {
            logger.info("🏆 Premiando leaderboard {}...", periodo);

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
                double decimalesSobrantes = 0;

                // 1. Premio base de cada jugador en el top
                for (int i = 0; i < PORCENTAJES.length; i++) {
                    if (i < top.size()) {
                        JsonObject jugador = top.get(i);
                        double premioExacto = pozoTotal * PORCENTAJES[i];
                        int premioMonto = (int) premioExacto;
                        jugador.addProperty("premio_asignado", premioMonto);
                        decimalesSobrantes += (premioExacto - premioMonto);
                    } else {
                        pozoSobrante += pozoTotal * PORCENTAJES[i];
                    }
                }

                // 2. Dividir el sobrante (si hay menos de 10 jugadores)
                double bonoExtraJugadorExacto = 0;
                double mitadParaAdmin = 0;

                if (pozoSobrante > 0) {
                    double mitadParaJugadores = pozoSobrante / 2.0;
                    mitadParaAdmin = pozoSobrante / 2.0;

                    if (top.size() > 0) {
                        bonoExtraJugadorExacto = mitadParaJugadores / top.size();
                    } else {
                        // Nadie en el top, todo para el admin
                        mitadParaAdmin += mitadParaJugadores;
                    }
                }

                // 3. Pagar a los jugadores y notificar
                for (int i = 0; i < top.size(); i++) {
                    JsonObject jugador = top.get(i);
                    int jugadorId = jugador.get("id").getAsNumber().intValue();

                    int bonoInt = (int) bonoExtraJugadorExacto;
                    decimalesSobrantes += (bonoExtraJugadorExacto - bonoInt);

                    int premioFinal = jugador.get("premio_asignado").getAsInt() + bonoInt;

                    if (premioFinal <= 0) continue;

                    // Dar premio // REVIEW-MONEY
                    WalletService.WalletResult premioResult = wallet.premiar(jugadorId, BigDecimal.valueOf(premioFinal),
                            "Premio leaderboard #" + (i + 1) + " " + configKey);

                    // Historial
                    db.update("INSERT INTO leaderboard_history (user_id, username, periodo, victorias, ganancias, posicion, premio, fecha_inicio, fecha_fin) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            jugadorId, jugador.get("username").getAsString(), configKey,
                            (int) jugador.get("victorias").getAsLong(), jugador.get("ganancia_generada").getAsDouble(),
                            i + 1, premioFinal, ahora, ahora);

                    // Notificar por socket
                    for (SocketIOClient s : io.getSockets().values()) {
                        if (s.getUserData() != null && s.getUserData().get("id").getAsNumber().intValue() == jugadorId) {
                            double nuevoSaldo = premioResult.isSuccess() ? premioResult.getNuevoSaldoDouble() :
                                    wallet.obtenerSaldo(jugadorId).doubleValue();
                            s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));

                            JsonObject premioData = new JsonObject();
                            premioData.addProperty("periodo", configKey);
                            premioData.addProperty("posicion", i + 1);
                            premioData.addProperty("premio", premioFinal);
                            premioData.addProperty("mensaje", "🏆 ¡Quedaste #" + (i + 1) + " del ranking " + configKey + "! Ganaste $" + premioFinal);
                            s.emit("premio_leaderboard", premioData);
                        }
                    }
                    logger.info("   🥇 #{} {}: +${}", i + 1, jugador.get("username").getAsString(), premioFinal);
                }

                // 4. Pagar al admin (sobrantes y decimales)
                double gananciaAdminTotal = mitadParaAdmin + decimalesSobrantes;
                if (gananciaAdminTotal > 0) {
                    ArrayList<JsonObject> admins = db.query("SELECT id FROM users WHERE tipo_suscripcion = 'admin'");
                    if (!admins.isEmpty()) {
                        double cuotaAdmin = gananciaAdminTotal / admins.size();
                        // REVIEW-MONEY
                        wallet.premiarAdmins(BigDecimal.valueOf(cuotaAdmin), "Leaderboard sobrantes " + configKey);

                        // Notificar a los admins por socket
                        for (SocketIOClient s : io.getSockets().values()) {
                            if (s.getUserData() != null && "admin".equals(s.getUserData().get("tipo_suscripcion").getAsString())) {
                                int aid = s.getUserData().get("id").getAsNumber().intValue();
                                double nuevoSaldo = wallet.obtenerSaldo(aid).doubleValue();
                                s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));
                            }
                        }
                    }
                }

                // 5. Restar el pozo de la billetera de comisiones (leaderboard) para que actualice en el panel
                db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'cierre_leaderboard', ?, 'leaderboard')",
                        -pozoTotal, "Cierre leaderboard " + configKey);
            }

            // Resetear columna
            db.update("UPDATE users SET " + columna + " = 0");
            logger.info("   ✅ Columna {} reseteada", columna);

            // Reiniciar el pozo para ese periodo
            db.update("UPDATE leaderboard_pools SET " + periodo + " = 0 WHERE id = 1");
            logger.info("   ✅ Pozo de {} reiniciado a 0", periodo);

            JsonObject resetData = new JsonObject();
            resetData.addProperty("periodo", configKey);
            io.emit("leaderboard_reset", resetData);
        } catch (Exception e) {
            logger.error("Error premiando leaderboard " + periodo + ": " + e.getMessage());
        }
    }
}
