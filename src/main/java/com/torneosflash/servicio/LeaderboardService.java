package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    /** C7: Zona horaria oficial de los cortes diarios/semanales/mensuales/anuales (Colombia, UTC-5). */
    private static final ZoneId ZONA_COLOMBIA = ZoneId.of("America/Bogota");

    private final GenericDAO db;
    private final SocketIOServer io;
    private final WalletService wallet;
    private final NotificacionesService notificaciones;
    private EventBus eventos;

    public LeaderboardService(GenericDAO db, SocketIOServer io, WalletService wallet) {
        this(db, io, wallet, null);
    }

    public LeaderboardService(GenericDAO db, SocketIOServer io, WalletService wallet,
                              NotificacionesService notificaciones) {
        this.db = db;
        this.io = io;
        this.wallet = wallet;
        this.notificaciones = notificaciones;
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
        String selectAlias = isMoneyTab ? "u." + orderColumn + " as monto" : "u." + orderColumn + " as victorias";
        String orden = ordenRanking(periodo, orderColumn);

        // S6 / M5: no exponer tipo_suscripcion (quién es admin) ni ganancia_generada en el ranking público
        ArrayList<JsonObject> rows = db.query(
                "SELECT u.id, u.username, " + selectAlias + ", u.total_partidas, u.total_victorias, " +
                        "u.total_derrotas, u.total_monto_torneos, u.total_ganado, " +
                        "ROW_NUMBER() OVER (ORDER BY " + orden + ") AS posicion, p.posicion_previa " +
                        "FROM users u LEFT JOIN leaderboard_live_positions p ON p.periodo = ? AND p.user_id = u.id " +
                        "WHERE u.tipo_suscripcion IS DISTINCT FROM 'admin' AND u." + orderColumn + " > 0 " +
                        "ORDER BY " + orden + " LIMIT 50", periodo);

        JsonArray ranking = new JsonArray();
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i);
            int posicion = row.has("posicion") ? row.get("posicion").getAsInt() : i + 1;
            row.addProperty("posicion", posicion);
            if (row.has("posicion_previa") && !row.get("posicion_previa").isJsonNull()) {
                int previa = row.get("posicion_previa").getAsInt();
                row.addProperty("posicion_previa", previa);
                row.addProperty("cambio_posicion", previa - posicion);
            } else {
                row.add("posicion_previa", com.google.gson.JsonNull.INSTANCE);
                row.addProperty("cambio_posicion", 0);
            }
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

    /** Puestos de todos los usuarios con puntaje, incluso cuando el jugador está por fuera del top 50. */
    public JsonObject posicionUsuario(String periodo, int userId) {
        String columna = columnaRanking(periodo);
        String orden = ordenRanking(periodo, columna);
        JsonObject fila = db.queryOne(
                "SELECT posicion, total_usuarios FROM (" +
                        "SELECT u.id, ROW_NUMBER() OVER (ORDER BY " + orden + ") AS posicion, " +
                        "COUNT(*) OVER () AS total_usuarios FROM users u " +
                        "WHERE u.tipo_suscripcion IS DISTINCT FROM 'admin' AND u." + columna + " > 0" +
                        ") puestos WHERE id = ?", userId);
        JsonObject resultado = new JsonObject();
        resultado.addProperty("periodo", periodo);
        if (fila == null) {
            JsonObject total = db.queryOne("SELECT COUNT(*) AS total FROM users " +
                    "WHERE tipo_suscripcion IS DISTINCT FROM 'admin' AND " + columna + " > 0");
            resultado.add("posicion", com.google.gson.JsonNull.INSTANCE);
            resultado.addProperty("totalUsuarios", total == null ? 0 : total.get("total").getAsInt());
        } else {
            resultado.add("posicion", fila.get("posicion"));
            resultado.add("totalUsuarios", fila.get("total_usuarios"));
        }
        JsonObject movimiento = db.queryOne(
                "SELECT posicion_previa FROM leaderboard_live_positions WHERE periodo = ? AND user_id = ?",
                periodo, userId);
        if (movimiento != null && !movimiento.get("posicion_previa").isJsonNull()
                && !resultado.get("posicion").isJsonNull()) {
            int anterior = movimiento.get("posicion_previa").getAsInt();
            resultado.addProperty("posicionPrevia", anterior);
            resultado.addProperty("cambioPosicion", anterior - resultado.get("posicion").getAsInt());
        } else {
            resultado.add("posicionPrevia", com.google.gson.JsonNull.INSTANCE);
            resultado.addProperty("cambioPosicion", 0);
        }
        resultado.addProperty("enTop50", !resultado.get("posicion").isJsonNull()
                && resultado.get("posicion").getAsInt() <= 50);
        return resultado;
    }

    /** Últimos ganadores guardados con cada corte, ordenados por fecha y puesto. */
    public ArrayList<JsonObject> ganadoresRecientes() {
        return db.query(
                "SELECT s.corte_id, s.user_id, s.username, s.posicion, s.victorias, s.ganancias, s.premio, " +
                        "c.periodo, c.clave_corte, c.completado_en " +
                        "FROM leaderboard_cut_snapshots s JOIN leaderboard_cuts c ON c.id = s.corte_id " +
                        "WHERE c.estado = 'completado' AND s.posicion <= 10 " +
                        "ORDER BY c.completado_en DESC, s.posicion ASC LIMIT 100");
    }

    /** Conserva la posición actual al reiniciar y guarda los últimos cambios para las flechas. */
    public void registrar(EventBus bus) {
        this.eventos = bus;
        if (bus != null) bus.on(Eventos.MATCH_FINISHED, "cambio-posicion-ranking", this::actualizarMovimientos);
    }

    /** Rellena posiciones faltantes en un reinicio sin borrar los últimos cambios que ya vio el usuario. */
    public void inicializarPosiciones() {
        for (Map.Entry<String, String> periodo : columnasRanking().entrySet()) {
            String orden = ordenRanking(periodo.getKey(), periodo.getValue());
            db.update("INSERT INTO leaderboard_live_positions (periodo, user_id, posicion) " +
                            "SELECT ?, puestos.id, puestos.posicion FROM (" +
                            "SELECT u.id, ROW_NUMBER() OVER (ORDER BY " + orden + ") AS posicion " +
                            "FROM users u WHERE u.tipo_suscripcion IS DISTINCT FROM 'admin' " +
                            "AND u." + periodo.getValue() + " > 0) puestos " +
                            "ON CONFLICT (periodo, user_id) DO NOTHING", periodo.getKey());
        }
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

    private static String columnaUltimoCorteSegura(String periodo) {
        switch (periodo == null ? "" : periodo) {
            case "dia": return "ultimo_corte_dia";
            case "semana": return "ultimo_corte_semana";
            case "mes": return "ultimo_corte_mes";
            case "ano": return "ultimo_corte_ano";
            default: throw new IllegalArgumentException("Periodo de corte no válido: " + periodo);
        }
    }

    /**
     * C7: Verifica cada minuto si cambió el día, semana, mes o año en hora de Colombia (America/Bogota)
     * comparando contra {@code leaderboard_pools.ultimo_corte_*}. Si el servidor estuvo dormido o reinició
     * justo a medianoche, ejecuta el corte en cuanto despierta sin perder premios ni pagar dos veces.
     */
    public void verificarYCerrarPeriodos() {
        try {
            recuperarCortesPendientes();
            LocalDate hoy = LocalDate.now(ZONA_COLOMBIA);
            String claveDia = hoy.toString();
            String claveSemana = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            String claveMes = hoy.withDayOfMonth(1).toString();
            String claveAno = hoy.withDayOfYear(1).toString();

            verificarPeriodo("dia", "victorias_dia", claveDia);
            verificarPeriodo("semana", "victorias_semana", claveSemana);
            verificarPeriodo("mes", "victorias_mes", claveMes);
            verificarPeriodo("ano", "victorias_ano", claveAno);
        } catch (Exception e) {
            logger.error("Error verificando cierres de leaderboard: {}", e.getMessage());
        }
    }

    private void verificarPeriodo(String periodo, String columnaVictorias, String claveActual) {
        String colCorte = columnaUltimoCorteSegura(periodo);
        JsonObject row = db.queryOne("SELECT " + colCorte + " FROM leaderboard_pools WHERE id = 1");
        if (row == null) return;

        String ultimoCorte = row.has(colCorte) && !row.get(colCorte).isJsonNull()
                ? row.get(colCorte).getAsString() : null;
        if (ultimoCorte == null || ultimoCorte.isEmpty()) {
            // Primera ejecución tras la migración: registrar el periodo en curso sin resetear a mitad del día
            db.update("UPDATE leaderboard_pools SET " + colCorte + " = ? WHERE id = 1 AND " + colCorte + " IS NULL", claveActual);
            return;
        }
        if (!claveActual.equals(ultimoCorte)) {
            premiarYResetear(periodo, columnaVictorias, claveActual);
        }
    }

    public void premiarYResetear(String periodo, String columna) {
        String clave = LocalDate.now(ZONA_COLOMBIA).toString();
        premiarYResetear(periodo, columna, clave);
    }

    /**
     * Crea el corte, guarda la lista inmutable de pagos y reinicia pozo/contador en una transacción.
     * Los créditos se procesan después, cada uno de forma idempotente en WalletService.
     */
    public void premiarYResetear(String periodo, String columna, String claveCorte) {
        try {
            String columnaSegura = columnaVictoriasSegura(periodo, columna);
            String periodoSeguro = columnaPozoSegura(periodo);
            long corteId = crearCorteRecuperable(periodoSeguro, columnaSegura, claveCorte);
            if (corteId > 0) procesarCorte(corteId);
        } catch (Exception e) {
            logger.error("Error premiando leaderboard " + periodo + ": " + e.getMessage());
        }
    }

    private static final class Candidato {
        final int userId;
        final String username;
        final int victorias;
        final BigDecimal ganancias;
        BigDecimal premioBase = BigDecimal.ZERO;

        Candidato(int userId, String username, int victorias, BigDecimal ganancias) {
            this.userId = userId;
            this.username = username;
            this.victorias = victorias;
            this.ganancias = ganancias;
        }
    }

    /** Persiste snapshot, destinatarios, pozo y reset antes de intentar cualquier crédito. // REVIEW-MONEY */
    private long crearCorteRecuperable(String periodo, String columnaVictorias, String claveCorte) throws SQLException {
        String columnaPozo = columnaPozoSegura(periodo);
        String colCorte = columnaUltimoCorteSegura(periodo);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // El orden users -> pool coincide con WalletService.liquidar y evita dejar el pozo
                // bloqueado mientras se esperan las filas de usuarios.
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users ORDER BY id FOR UPDATE")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) { /* bloquea el snapshot hasta cerrar el corte */ }
                    }
                }

                BigDecimal pozo;
                String ultimoCorte;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + columnaPozo + " AS pozo, " + colCorte + " AS ultimo_corte " +
                        "FROM leaderboard_pools WHERE id = 1 FOR UPDATE")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return 0;
                        }
                        pozo = rs.getBigDecimal("pozo");
                        if (pozo == null) pozo = BigDecimal.ZERO;
                        ultimoCorte = rs.getString("ultimo_corte");
                    }
                }

                if (claveCorte.equals(ultimoCorte)) {
                    long existente = buscarIdCorte(conn, periodo, claveCorte);
                    conn.commit();
                    return existente;
                }

                long corteId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO leaderboard_cuts (periodo, clave_corte, pozo) VALUES (?, ?, ?) " +
                        "ON CONFLICT (periodo, clave_corte) DO NOTHING RETURNING id")) {
                    ps.setString(1, periodo);
                    ps.setString(2, claveCorte);
                    ps.setBigDecimal(3, pozo);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            long existente = buscarIdCorte(conn, periodo, claveCorte);
                            conn.commit();
                            return existente;
                        }
                        corteId = rs.getLong(1);
                    }
                }

                ArrayList<Candidato> top = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, username, " + columnaVictorias + " AS victorias, ganancia_generada " +
                        "FROM users WHERE tipo_suscripcion IS DISTINCT FROM 'admin' AND " + columnaVictorias + " > 0 " +
                        "ORDER BY " + columnaVictorias + " DESC, ganancia_generada DESC, id ASC LIMIT 10")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            BigDecimal ganancias = rs.getBigDecimal("ganancia_generada");
                            top.add(new Candidato(rs.getInt("id"), rs.getString("username"),
                                    rs.getInt("victorias"), ganancias == null ? BigDecimal.ZERO : ganancias));
                        }
                    }
                }

                insertarPagosJugadores(conn, corteId, pozo, top);
                insertarPagosAdmins(conn, corteId, pozo, top);
                insertarSnapshotCorte(conn, corteId, periodo, columnaVictorias);

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE leaderboard_pools SET " + columnaPozo + " = 0, " + colCorte + " = ? WHERE id = 1")) {
                    ps.setString(1, claveCorte);
                    if (ps.executeUpdate() != 1) throw new SQLException("No se pudo cerrar el pozo del leaderboard");
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET " + columnaVictorias + " = 0")) {
                    ps.executeUpdate();
                }
                conn.commit();
                logger.info("Corte leaderboard persistido: periodo={} corte={} pozo={} pagos pendientes", periodo, claveCorte, pozo);
                return corteId;
            } catch (SQLException | RuntimeException e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                throw e;
            }
        }
    }

    private long buscarIdCorte(Connection conn, String periodo, String claveCorte) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM leaderboard_cuts WHERE periodo = ? AND clave_corte = ?")) {
            ps.setString(1, periodo);
            ps.setString(2, claveCorte);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    // REVIEW-MONEY
    private void insertarPagosJugadores(Connection conn, long corteId, BigDecimal pozo,
                                        ArrayList<Candidato> top) throws SQLException {
        if (pozo.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal pozoSobrante = BigDecimal.ZERO;
        for (int i = 0; i < PORCENTAJES.length; i++) {
            BigDecimal exacto = pozo.multiply(PORCENTAJES[i]);
            if (i < top.size()) {
                BigDecimal base = exacto.setScale(0, RoundingMode.FLOOR);
                top.get(i).premioBase = base;
            } else {
                pozoSobrante = pozoSobrante.add(exacto);
            }
        }

        BigDecimal bonoExacto = BigDecimal.ZERO;
        if (pozoSobrante.compareTo(BigDecimal.ZERO) > 0 && !top.isEmpty()) {
            BigDecimal mitadJugadores = pozoSobrante.divide(DOS, 6, RoundingMode.HALF_UP);
            bonoExacto = mitadJugadores.divide(BigDecimal.valueOf(top.size()), 6, RoundingMode.HALF_UP);
        }
        BigDecimal bonoEntero = bonoExacto.setScale(0, RoundingMode.FLOOR);

        for (int i = 0; i < top.size(); i++) {
            Candidato jugador = top.get(i);
            BigDecimal premio = jugador.premioBase.add(bonoEntero);
            if (premio.compareTo(BigDecimal.ZERO) <= 0) continue;
            insertarPago(conn, corteId, jugador.userId, "jugador", jugador.username,
                    jugador.victorias, jugador.ganancias, i + 1, premio);
        }
    }

    // REVIEW-MONEY
    private void insertarPagosAdmins(Connection conn, long corteId, BigDecimal pozo,
                                     ArrayList<Candidato> top) throws SQLException {
        if (pozo.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal pozoSobrante = BigDecimal.ZERO;
        BigDecimal decimalesSobrantes = BigDecimal.ZERO;
        for (int i = 0; i < PORCENTAJES.length; i++) {
            BigDecimal exacto = pozo.multiply(PORCENTAJES[i]);
            if (i < top.size()) {
                BigDecimal base = exacto.setScale(0, RoundingMode.FLOOR);
                decimalesSobrantes = decimalesSobrantes.add(exacto.subtract(base));
            } else {
                pozoSobrante = pozoSobrante.add(exacto);
            }
        }

        if (!top.isEmpty() && pozoSobrante.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bonoExacto = pozoSobrante.divide(DOS, 6, RoundingMode.HALF_UP)
                    .divide(BigDecimal.valueOf(top.size()), 6, RoundingMode.HALF_UP);
            BigDecimal bonoDecimal = bonoExacto.subtract(bonoExacto.setScale(0, RoundingMode.FLOOR));
            decimalesSobrantes = decimalesSobrantes.add(bonoDecimal.multiply(BigDecimal.valueOf(top.size())));
        }

        BigDecimal mitadAdmin = BigDecimal.ZERO;
        if (pozoSobrante.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal mitadJugadores = pozoSobrante.divide(DOS, 6, RoundingMode.HALF_UP);
            mitadAdmin = top.isEmpty() ? pozoSobrante : pozoSobrante.subtract(mitadJugadores);
        }
        BigDecimal totalAdmin = mitadAdmin.add(decimalesSobrantes).setScale(2, RoundingMode.HALF_UP);
        if (totalAdmin.compareTo(BigDecimal.ZERO) <= 0) return;

        ArrayList<Integer> admins = new ArrayList<>();
        ArrayList<String> usernames = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, username FROM users WHERE tipo_suscripcion = 'admin' ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    admins.add(rs.getInt("id"));
                    usernames.add(rs.getString("username"));
                }
            }
        }
        if (admins.isEmpty()) return;
        BigDecimal cuota = totalAdmin.divide(BigDecimal.valueOf(admins.size()), 2, RoundingMode.DOWN);
        if (cuota.compareTo(BigDecimal.ZERO) <= 0) return;
        for (int i = 0; i < admins.size(); i++) {
            insertarPago(conn, corteId, admins.get(i), "admin", usernames.get(i),
                    null, null, null, cuota);
        }
    }

    // REVIEW-MONEY
    private void insertarPago(Connection conn, long corteId, int userId, String tipo, String username,
                              Integer victorias, BigDecimal ganancias, Integer posicion, BigDecimal monto)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO leaderboard_cut_payments " +
                "(corte_id, user_id, tipo_pago, username, victorias, ganancias, posicion, monto) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, corteId);
            ps.setInt(2, userId);
            ps.setString(3, tipo);
            ps.setString(4, username == null ? "" : username);
            if (victorias == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, victorias);
            if (ganancias == null) ps.setNull(6, java.sql.Types.NUMERIC); else ps.setBigDecimal(6, ganancias);
            if (posicion == null) ps.setNull(7, java.sql.Types.INTEGER); else ps.setInt(7, posicion);
            ps.setBigDecimal(8, monto);
            ps.executeUpdate();
        }
    }

    /** Cada minuto reintenta pagos pendientes y reconstruye historial que haya quedado por escribir. */
    private void recuperarCortesPendientes() {
        try {
            ArrayList<JsonObject> cortes = db.query(
                    "SELECT id FROM leaderboard_cuts WHERE estado = 'pendiente' ORDER BY id");
            for (JsonObject corte : cortes) procesarCorte(corte.get("id").getAsLong());
        } catch (Exception e) {
            logger.error("Error recuperando cortes de leaderboard: {}", e.getMessage());
        }
    }

    private void procesarCorte(long corteId) {
        try {
            ArrayList<JsonObject> pagos = db.query(
                    "SELECT p.id, p.user_id, p.tipo_pago, p.username, p.victorias, p.ganancias, " +
                    "p.posicion, p.monto, c.periodo " +
                    "FROM leaderboard_cut_payments p JOIN leaderboard_cuts c ON c.id = p.corte_id " +
                    "WHERE p.corte_id = ? ORDER BY p.id", corteId);
            boolean completo = true;
            for (JsonObject pago : pagos) {
                long pagoId = pago.get("id").getAsLong();
                int userId = pago.get("user_id").getAsInt();
                String periodo = pago.get("periodo").getAsString();
                String tipo = pago.get("tipo_pago").getAsString();
                String nombrePeriodo = nombrePeriodo(periodo);
                String motivo = "jugador".equals(tipo)
                        ? "Premio leaderboard corte#" + corteId + " #" + pago.get("posicion").getAsInt() + " " + nombrePeriodo
                        : "Leaderboard sobrantes corte#" + corteId + " " + nombrePeriodo;

                WalletService.WalletResult resultado = wallet.premiarLeaderboard(pagoId, motivo);
                if (!resultado.isSuccess()) {
                    completo = false;
                    logger.error("Pago leaderboard pendiente: corte={} pago={} usuario={} error={}",
                            corteId, pagoId, userId, resultado.getError());
                    continue;
                }

                if ("jugador".equals(tipo)) {
                    try {
                        insertarHistorialIdempotente(pagoId);
                    } catch (Exception e) {
                        completo = false;
                        logger.error("No se pudo registrar historial leaderboard pago={}: {}", pagoId, e.getMessage());
                    }
                }
                if (resultado.wasApplied()) notificarPagoLeaderboard(pago, resultado, nombrePeriodo);
            }

            if (!completo) return;
            boolean cerrado = cerrarCorteSiCompleto(corteId);
            if (cerrado) {
                JsonObject periodoCorte = db.queryOne("SELECT periodo FROM leaderboard_cuts WHERE id = ?", corteId);
                String clavePeriodo = periodoCorte == null ? "" : periodoCorte.get("periodo").getAsString();
                if (!clavePeriodo.isEmpty()) {
                    db.update("DELETE FROM leaderboard_live_positions WHERE periodo = ?", clavePeriodo);
                }
                JsonObject resetData = new JsonObject();
                resetData.addProperty("periodo", nombrePeriodoDeCorte(corteId));
                resetData.addProperty("periodoClave", clavePeriodo);
                resetData.addProperty("corteId", corteId);
                io.emit("leaderboard_reset", resetData);
                if (eventos != null) {
                    eventos.emit(Eventos.LEADERBOARD_CUT_COMPLETED,
                            new Eventos.CorteLeaderboard(corteId, clavePeriodo));
                }
                logger.info("Corte leaderboard {} completado", corteId);
            }
        } catch (Exception e) {
            logger.error("Error procesando corte leaderboard {}: {}", corteId, e.getMessage());
        }
    }

    private void insertarHistorialIdempotente(long pagoId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO leaderboard_history " +
                     "(corte_pago_id, user_id, username, periodo, victorias, ganancias, posicion, premio, fecha_inicio, fecha_fin) " +
                     "SELECT p.id, p.user_id, p.username, " +
                     "CASE c.periodo WHEN 'dia' THEN 'diario' WHEN 'semana' THEN 'semanal' " +
                     "WHEN 'mes' THEN 'mensual' ELSE 'anual' END, p.victorias, p.ganancias, p.posicion, p.monto, ?, ? " +
                     "FROM leaderboard_cut_payments p JOIN leaderboard_cuts c ON c.id = p.corte_id " +
                     "WHERE p.id = ? AND p.tipo_pago = 'jugador' AND p.estado = 'pagado' " +
                     "ON CONFLICT DO NOTHING")) {
            String fecha = new java.util.Date().toString();
            ps.setString(1, fecha);
            ps.setString(2, fecha);
            ps.setLong(3, pagoId);
            ps.executeUpdate();
        }
    }

    /** Snapshot del ranking completo al corte; el top 10 se usa como historial público de ganadores. */
    private void insertarSnapshotCorte(Connection conn, long corteId, String periodo,
                                       String columnaVictorias) throws SQLException {
        String orden = "u." + columnaVictorias + " DESC, u.ganancia_generada DESC, u.id ASC";
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO leaderboard_cut_snapshots " +
                        "(corte_id, user_id, username, posicion, victorias, ganancias, premio) " +
                        "SELECT ?, u.id, u.username, ROW_NUMBER() OVER (ORDER BY " + orden + "), " +
                        "u." + columnaVictorias + ", COALESCE(u.ganancia_generada, 0), COALESCE(p.monto, 0) " +
                        "FROM users u LEFT JOIN leaderboard_cut_payments p ON p.corte_id = ? " +
                        "AND p.user_id = u.id AND p.tipo_pago = 'jugador' " +
                        "WHERE u.tipo_suscripcion IS DISTINCT FROM 'admin' AND u." + columnaVictorias + " > 0 " +
                        "ON CONFLICT (corte_id, user_id) DO NOTHING")) {
            ps.setLong(1, corteId);
            ps.setLong(2, corteId);
            ps.executeUpdate();
        }
    }

    private synchronized void actualizarMovimientos(EventBus.Evento evento) {
        Eventos.PartidaFinalizada partida = evento.datos(Eventos.PartidaFinalizada.class);
        if (partida == null) return;
        Map<Integer, JsonArray> detallesPorUsuario = new LinkedHashMap<>();
        Map<Integer, ArrayList<String>> mensajesPorUsuario = new LinkedHashMap<>();

        for (Map.Entry<String, String> periodo : columnasRanking().entrySet()) {
            String clave = periodo.getKey();
            String columna = periodo.getValue();
            Map<Integer, Integer> anteriores = new HashMap<>();
            for (JsonObject fila : db.query(
                    "SELECT user_id, posicion FROM leaderboard_live_positions WHERE periodo = ?", clave)) {
                anteriores.put(fila.get("user_id").getAsInt(), fila.get("posicion").getAsInt());
            }

            String orden = ordenRanking(clave, columna);
            Set<Integer> presentes = new HashSet<>();
            for (JsonObject fila : db.query(
                    "SELECT puestos.id, puestos.posicion FROM (" +
                            "SELECT u.id, ROW_NUMBER() OVER (ORDER BY " + orden + ") AS posicion " +
                            "FROM users u WHERE u.tipo_suscripcion IS DISTINCT FROM 'admin' " +
                            "AND u." + columna + " > 0) puestos")) {
                int userId = fila.get("id").getAsInt();
                int nueva = fila.get("posicion").getAsInt();
                presentes.add(userId);
                Integer anterior = anteriores.get(userId);
                if (anterior == null) {
                    db.update("INSERT INTO leaderboard_live_positions (periodo, user_id, posicion) " +
                                    "VALUES (?, ?, ?) ON CONFLICT (periodo, user_id) DO NOTHING",
                            clave, userId, nueva);
                    continue;
                }
                if (anterior == nueva) continue;
                db.update("UPDATE leaderboard_live_positions SET posicion_previa = ?, posicion = ?, " +
                                "actualizado_en = CURRENT_TIMESTAMP WHERE periodo = ? AND user_id = ?",
                        anterior, nueva, clave, userId);
                JsonObject cambio = new JsonObject();
                cambio.addProperty("periodo", clave);
                cambio.addProperty("desde", anterior);
                cambio.addProperty("hasta", nueva);
                detallesPorUsuario.computeIfAbsent(userId, ignorado -> new JsonArray()).add(cambio);
                mensajesPorUsuario.computeIfAbsent(userId, ignorado -> new ArrayList<>())
                        .add(etiquetaPeriodo(clave) + (nueva < anterior ? " subiste" : " bajaste") +
                                " del #" + anterior + " al #" + nueva);
            }
            for (Integer userId : anteriores.keySet()) {
                if (!presentes.contains(userId)) {
                    db.update("DELETE FROM leaderboard_live_positions WHERE periodo = ? AND user_id = ?",
                            clave, userId);
                }
            }
        }

        if (notificaciones == null) return;
        for (Map.Entry<Integer, ArrayList<String>> cambio : mensajesPorUsuario.entrySet()) {
            JsonObject datos = new JsonObject();
            datos.addProperty("matchId", partida.matchId());
            datos.add("cambios", detallesPorUsuario.get(cambio.getKey()));
            notificaciones.notificar(cambio.getKey(), "ranking", "Cambió tu posición en rankings",
                    String.join(" · ", cambio.getValue()), "/leaderboard",
                    "ranking-movimiento:" + partida.matchId(), datos, false);
        }
    }

    private static Map<String, String> columnasRanking() {
        Map<String, String> columnas = new LinkedHashMap<>();
        columnas.put("dia", "victorias_dia");
        columnas.put("semana", "victorias_semana");
        columnas.put("mes", "victorias_mes");
        columnas.put("ano", "victorias_ano");
        columnas.put("global", "total_victorias");
        columnas.put("monto_torneos", "total_monto_torneos");
        columnas.put("ganado", "total_ganado");
        return columnas;
    }

    private static String columnaRanking(String periodo) {
        String columna = columnasRanking().get(periodo);
        if (columna == null) throw ServicioException.solicitudInvalida("Periodo no válido");
        return columna;
    }

    private static String ordenRanking(String periodo, String columna) {
        String orden = "u." + columna + " DESC";
        if ("dia".equals(periodo) || "semana".equals(periodo) || "mes".equals(periodo) || "ano".equals(periodo)) {
            orden += ", u.ganancia_generada DESC";
        }
        return orden + ", u.id ASC";
    }

    private static String etiquetaPeriodo(String periodo) {
        return switch (periodo) {
            case "dia" -> "Diario:";
            case "semana" -> "Semanal:";
            case "mes" -> "Mensual:";
            case "ano" -> "Anual:";
            case "global" -> "Global:";
            case "monto_torneos" -> "Monto en torneos:";
            case "ganado" -> "Ganado:";
            default -> "Ranking:";
        };
    }

    private boolean cerrarCorteSiCompleto(long corteId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE leaderboard_cuts c SET estado = 'completado', completado_en = CURRENT_TIMESTAMP " +
                     "WHERE c.id = ? AND c.estado = 'pendiente' " +
                     "AND NOT EXISTS (SELECT 1 FROM leaderboard_cut_payments p WHERE p.corte_id = c.id AND p.estado <> 'pagado') " +
                     "AND NOT EXISTS (SELECT 1 FROM leaderboard_cut_payments p WHERE p.corte_id = c.id AND p.tipo_pago = 'jugador' " +
                     "AND NOT EXISTS (SELECT 1 FROM leaderboard_history h WHERE h.corte_pago_id = p.id))")) {
            ps.setLong(1, corteId);
            return ps.executeUpdate() == 1;
        }
    }

    private String nombrePeriodoDeCorte(long corteId) {
        JsonObject row = db.queryOne("SELECT periodo FROM leaderboard_cuts WHERE id = ?", corteId);
        return row == null ? "" : nombrePeriodo(row.get("periodo").getAsString());
    }

    private static String nombrePeriodo(String periodo) {
        switch (periodo) {
            case "dia": return "diario";
            case "semana": return "semanal";
            case "mes": return "mensual";
            case "ano": return "anual";
            default: return periodo;
        }
    }

    private void notificarPagoLeaderboard(JsonObject pago, WalletService.WalletResult resultado, String nombrePeriodo) {
        int userId = pago.get("user_id").getAsInt();
        boolean jugador = "jugador".equals(pago.get("tipo_pago").getAsString());
        for (SocketIOClient socket : io.getSockets().values()) {
            JsonObject userData = socket.getUserData();
            if (userData == null || !userData.has("id") || userData.get("id").isJsonNull()
                    || userData.get("id").getAsInt() != userId) continue;
            userData.addProperty("saldo", resultado.getNuevoSaldo());
            socket.emit("actualizar_saldo", new JsonPrimitive(resultado.getNuevoSaldo()));
            if (jugador) {
                int posicion = pago.get("posicion").getAsInt();
                BigDecimal premio = pago.get("monto").getAsBigDecimal();
                JsonObject data = new JsonObject();
                data.addProperty("periodo", nombrePeriodo);
                data.addProperty("posicion", posicion);
                data.addProperty("premio", premio);
                data.addProperty("mensaje", "🏆 ¡Quedaste #" + posicion + " del ranking " + nombrePeriodo +
                        "! Ganaste $" + premio.toPlainString());
                socket.emit("premio_leaderboard", data);
            }
        }
        logger.info("Premio leaderboard aplicado usuario={} tipo={} monto={}", userId,
                jugador ? "jugador" : "admin", pago.get("monto").getAsBigDecimal().toPlainString());
    }
}
