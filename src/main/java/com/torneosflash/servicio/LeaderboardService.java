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
import java.math.RoundingMode;
import java.util.ArrayList;

/**
 * Lógica del leaderboard: rankings por periodo, historial y premios periódicos con reset (A3).
 * Antes vivía en {@code RutasLeaderboard}. No conoce HTTP.
 *
 * S4: Todo nombre de columna/periodo que se interpola en SQL proviene exclusivamente de listas
 * blancas cerradas dentro de esta clase.
 * D1: Todos los cálculos de pozos, premios y sobrantes usan {@link BigDecimal} (nunca {@code double}).
 */
public class LeaderboardService {
    private static final Logger logger = LoggerFactory.getLogger(LeaderboardService.class);

    /** Reparto del pozo entre el top 10 (suma 1.00). // REVIEW-MONEY */
    private static final BigDecimal[] PORCENTAJES = {
            new BigDecimal("0.25"), new BigDecimal("0.18"), new BigDecimal("0.14"),
            new BigDecimal("0.10"), new BigDecimal("0.08"), new BigDecimal("0.07"),
            new BigDecimal("0.06"), new BigDecimal("0.05"), new BigDecimal("0.04"),
            new BigDecimal("0.03")
    };
    private static final BigDecimal DOS = BigDecimal.valueOf(2);

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
            BigDecimal pozoTotal = poolData != null && poolData.has(periodo) && !poolData.get(periodo).isJsonNull()
                    ? poolData.get(periodo).getAsBigDecimal() : BigDecimal.ZERO;
            res.add("premios", calcularPremiosDinamicos(pozoTotal));
        }
        return res;
    }

    public ArrayList<JsonObject> historial(int userId) {
        return db.query("SELECT * FROM leaderboard_history WHERE user_id = ? ORDER BY fecha DESC LIMIT 20", userId);
    }

    private static JsonArray calcularPremiosDinamicos(BigDecimal pozoTotal) {
        JsonArray premios = new JsonArray();
        for (int i = 0; i < PORCENTAJES.length; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("posicion", i + 1);
            p.addProperty("premio", pozoTotal.multiply(PORCENTAJES[i]).setScale(0, RoundingMode.FLOOR));
            premios.add(p);
        }
        return premios;
    }

    // ------------------------------------------------------------------
    // Premios periódicos
    // ------------------------------------------------------------------

    /**
     * S4: Lista blanca cerrada para los periodos premiables y su columna de victorias en {@code users}.
     */
    private static String columnaVictoriasSegura(String periodo, String columnaSolicitada) {
        String esperada;
        switch (periodo == null ? "" : periodo) {
            case "dia": esperada = "victorias_dia"; break;
            case "semana": esperada = "victorias_semana"; break;
            case "mes": esperada = "victorias_mes"; break;
            case "ano": esperada = "victorias_ano"; break;
            default: throw new IllegalArgumentException("Periodo de leaderboard no válido: " + periodo);
        }
        if (columnaSolicitada != null && !esperada.equals(columnaSolicitada)) {
            throw new IllegalArgumentException("Columna de leaderboard no coincide con el periodo: " + columnaSolicitada);
        }
        return esperada;
    }

    private static String columnaPozoSegura(String periodo) {
        switch (periodo == null ? "" : periodo) {
            case "dia": return "dia";
            case "semana": return "semana";
            case "mes": return "mes";
            case "ano": return "ano";
            default: throw new IllegalArgumentException("Periodo de pozo no válido: " + periodo);
        }
    }

    /**
     * Premia al top del periodo con el pozo acumulado y resetea contadores y pozo.
     * Lo llama el scheduler de {@code Main} (día, semana, mes, año).
     */
    public void premiarYResetear(String periodo, String columna) {
        try {
            String columnaSegura = columnaVictoriasSegura(periodo, columna);
            String periodoSeguro = columnaPozoSegura(periodo);

            logger.info("🏆 Premiando leaderboard {}...", periodoSeguro);

            JsonObject poolData = db.queryOne("SELECT * FROM leaderboard_pools WHERE id = 1");
            if (poolData == null) {
                db.update("UPDATE users SET " + columnaSegura + " = 0");
                return;
            }

            BigDecimal pozoTotal = poolData.has(periodoSeguro) && !poolData.get(periodoSeguro).isJsonNull()
                    ? poolData.get(periodoSeguro).getAsBigDecimal() : BigDecimal.ZERO; // REVIEW-MONEY

            ArrayList<JsonObject> top = db.query(
                    "SELECT id, username, " + columnaSegura + " as victorias, ganancia_generada FROM users WHERE " +
                    columnaSegura + " > 0 ORDER BY " + columnaSegura + " DESC, ganancia_generada DESC LIMIT 10");

            String configKey = "dia".equals(periodoSeguro) ? "diario" : "semana".equals(periodoSeguro) ? "semanal" :
                    "mes".equals(periodoSeguro) ? "mensual" : "anual";

            String ahora = new java.util.Date().toString();

            if (pozoTotal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pozoSobrante = BigDecimal.ZERO;
                BigDecimal decimalesSobrantes = BigDecimal.ZERO;

                // 1. Premio base de cada jugador en el top
                for (int i = 0; i < PORCENTAJES.length; i++) {
                    BigDecimal premioExacto = pozoTotal.multiply(PORCENTAJES[i]);
                    if (i < top.size()) {
                        JsonObject jugador = top.get(i);
                        BigDecimal premioMonto = premioExacto.setScale(0, RoundingMode.FLOOR);
                        jugador.addProperty("premio_asignado", premioMonto);
                        decimalesSobrantes = decimalesSobrantes.add(premioExacto.subtract(premioMonto));
                    } else {
                        pozoSobrante = pozoSobrante.add(premioExacto);
                    }
                }

                // 2. Dividir el sobrante (si hay menos de 10 jugadores)
                BigDecimal bonoExtraJugadorExacto = BigDecimal.ZERO;
                BigDecimal mitadParaAdmin = BigDecimal.ZERO;

                if (pozoSobrante.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal mitadParaJugadores = pozoSobrante.divide(DOS, 6, RoundingMode.HALF_UP);
                    mitadParaAdmin = pozoSobrante.subtract(mitadParaJugadores);

                    if (!top.isEmpty()) {
                        bonoExtraJugadorExacto = mitadParaJugadores.divide(
                                BigDecimal.valueOf(top.size()), 6, RoundingMode.HALF_UP);
                    } else {
                        // Nadie en el top, todo para el admin
                        mitadParaAdmin = pozoSobrante;
                    }
                }

                // 3. Pagar a los jugadores y notificar
                BigDecimal bonoEntero = bonoExtraJugadorExacto.setScale(0, RoundingMode.FLOOR);
                BigDecimal bonoDecimal = bonoExtraJugadorExacto.subtract(bonoEntero);

                for (int i = 0; i < top.size(); i++) {
                    JsonObject jugador = top.get(i);
                    int jugadorId = jugador.get("id").getAsNumber().intValue();

                    decimalesSobrantes = decimalesSobrantes.add(bonoDecimal);
                    BigDecimal premioFinal = jugador.get("premio_asignado").getAsBigDecimal().add(bonoEntero);

                    if (premioFinal.compareTo(BigDecimal.ZERO) <= 0) continue;

                    // Dar premio // REVIEW-MONEY
                    WalletService.WalletResult premioResult = wallet.premiar(jugadorId, premioFinal,
                            "Premio leaderboard #" + (i + 1) + " " + configKey);

                    BigDecimal gananciaGenerada = jugador.has("ganancia_generada") && !jugador.get("ganancia_generada").isJsonNull()
                            ? jugador.get("ganancia_generada").getAsBigDecimal() : BigDecimal.ZERO;

                    // Historial
                    db.update("INSERT INTO leaderboard_history (user_id, username, periodo, victorias, ganancias, posicion, premio, fecha_inicio, fecha_fin) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            jugadorId, jugador.get("username").getAsString(), configKey,
                            (int) jugador.get("victorias").getAsLong(), gananciaGenerada,
                            i + 1, premioFinal, ahora, ahora);

                    // Notificar por socket
                    for (SocketIOClient s : io.getSockets().values()) {
                        if (s.getUserData() != null && s.getUserData().get("id").getAsNumber().intValue() == jugadorId) {
                            BigDecimal nuevoSaldo = premioResult.isSuccess() ? premioResult.getNuevoSaldo() :
                                    wallet.obtenerSaldo(jugadorId);
                            s.getUserData().addProperty("saldo", nuevoSaldo);
                            s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));

                            JsonObject premioData = new JsonObject();
                            premioData.addProperty("periodo", configKey);
                            premioData.addProperty("posicion", i + 1);
                            premioData.addProperty("premio", premioFinal);
                            premioData.addProperty("mensaje", "🏆 ¡Quedaste #" + (i + 1) + " del ranking " + configKey + "! Ganaste $" + premioFinal.toPlainString());
                            s.emit("premio_leaderboard", premioData);
                        }
                    }
                    logger.info("   🏆 #{} {}: +${}", i + 1, jugador.get("username").getAsString(), premioFinal.toPlainString());
                }

                // 4. Pagar al admin (sobrantes y decimales)
                BigDecimal gananciaAdminTotal = mitadParaAdmin.add(decimalesSobrantes).setScale(2, RoundingMode.HALF_UP);
                if (gananciaAdminTotal.compareTo(BigDecimal.ZERO) > 0) {
                    ArrayList<JsonObject> admins = db.query("SELECT id FROM users WHERE tipo_suscripcion = 'admin'");
                    if (!admins.isEmpty()) {
                        BigDecimal cuotaAdmin = gananciaAdminTotal.divide(
                                BigDecimal.valueOf(admins.size()), 2, RoundingMode.DOWN);
                        if (cuotaAdmin.compareTo(BigDecimal.ZERO) > 0) {
                            // REVIEW-MONEY
                            wallet.premiarAdmins(cuotaAdmin, "Leaderboard sobrantes " + configKey);

                            // Notificar a los admins por socket
                            for (SocketIOClient s : io.getSockets().values()) {
                                if (s.getUserData() != null && "admin".equals(s.getUserData().get("tipo_suscripcion").getAsString())) {
                                    int aid = s.getUserData().get("id").getAsNumber().intValue();
                                    BigDecimal nuevoSaldo = wallet.obtenerSaldo(aid);
                                    s.getUserData().addProperty("saldo", nuevoSaldo);
                                    s.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));
                                }
                            }
                        }
                    }
                }

                // 5. Restar el pozo de la billetera de comisiones (leaderboard) para que actualice en el panel
                db.update("INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, 'cierre_leaderboard', ?, 'leaderboard')",
                        pozoTotal.negate(), "Cierre leaderboard " + configKey);
            }

            // Resetear columna
            db.update("UPDATE users SET " + columnaSegura + " = 0");
            logger.info("   ✅ Columna {} reseteada", columnaSegura);

            // Reiniciar el pozo para ese periodo
            db.update("UPDATE leaderboard_pools SET " + periodoSeguro + " = 0 WHERE id = 1");
            logger.info("   ✅ Pozo de {} reiniciado a 0", periodoSeguro);

            JsonObject resetData = new JsonObject();
            resetData.addProperty("periodo", configKey);
            io.emit("leaderboard_reset", resetData);
        } catch (Exception e) {
            logger.error("Error premiando leaderboard " + periodo + ": " + e.getMessage());
        }
    }
}
