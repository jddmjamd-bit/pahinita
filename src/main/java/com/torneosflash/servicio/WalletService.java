package com.torneosflash.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.dao.ConexionDB;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;

/**
 * WalletService — Servicio centralizado para todo movimiento de dinero.
 *
 * Reglas:
 *  - Todo UPDATE de saldo pasa por aquí. Nunca hacer UPDATE users SET saldo directo.
 *  - Usa BigDecimal internamente. Nunca double para cálculos de dinero.
 *  - Las deducciones verifican saldo con WHERE saldo >= ? dentro de la misma transacción.
 *  - Cada operación es atómica (conn.setAutoCommit(false) + commit/rollback).
 *
 * // REVIEW-MONEY — Archivo completo trata con dinero real.
 */
public class WalletService {
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private final ConexionDB db;

    public WalletService(ConexionDB db) {
        this.db = db;
    }

    // ═══════════════════════════════════════════
    // RESULTADO DE OPERACIÓN
    // ═══════════════════════════════════════════

    /**
     * Resultado inmutable de una operación de wallet.
     * Contiene si fue exitosa, el nuevo saldo, y un mensaje de error en caso de fallo.
     */
    public static class WalletResult {
        private final boolean success;
        private final BigDecimal nuevoSaldo;
        private final String error;

        private WalletResult(boolean success, BigDecimal nuevoSaldo, String error) {
            this.success = success;
            this.nuevoSaldo = nuevoSaldo;
            this.error = error;
        }

        public static WalletResult ok(BigDecimal nuevoSaldo) {
            return new WalletResult(true, nuevoSaldo, null);
        }

        public static WalletResult fail(String error) {
            return new WalletResult(false, null, error);
        }

        public boolean isSuccess() { return success; }
        public BigDecimal getNuevoSaldo() { return nuevoSaldo; }
        /** Retorna el nuevo saldo como double para compatibilidad con el frontend JSON. */
        public double getNuevoSaldoDouble() { return nuevoSaldo != null ? nuevoSaldo.doubleValue() : 0; }
        public String getError() { return error; }
    }

    // ═══════════════════════════════════════════
    // DEPOSITAR
    // ═══════════════════════════════════════════

    /**
     * Deposita dinero en la cuenta de un usuario.
     * No requiere verificación de saldo (siempre se puede depositar).
     *
     * @param userId ID del usuario
     * @param monto  Monto a depositar (debe ser positivo)
     * @param motivo Descripción del depósito (ej: "Recarga Wompi", "Recarga manual admin")
     * @return WalletResult con éxito/fallo y nuevo saldo
     */
    // REVIEW-MONEY
    public WalletResult depositar(int userId, BigDecimal monto, String motivo) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("El monto debe ser positivo");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // Acreditar saldo
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo + ? WHERE id = ?")) {
                ps.setBigDecimal(1, monto);
                ps.setInt(2, userId);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    return WalletResult.fail("Usuario no encontrado");
                }
            }

            // Leer nuevo saldo
            BigDecimal nuevoSaldo = leerSaldo(conn, userId);

            conn.commit();
            logger.info("💰 DEPÓSITO userId={} monto={} motivo='{}' nuevoSaldo={}",
                    userId, monto, motivo, nuevoSaldo);
            return WalletResult.ok(nuevoSaldo);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en depositar userId={}: {}", userId, e.getMessage());
            return WalletResult.fail("Error interno al depositar");
        } finally {
            cerrarConexion(conn);
        }
    }

    // ═══════════════════════════════════════════
    // RETIRAR
    // ═══════════════════════════════════════════

    /**
     * Retira dinero de la cuenta de un usuario.
     * Verifica que el saldo sea suficiente dentro de la misma transacción con WHERE saldo >= ?.
     *
     * @param userId ID del usuario
     * @param monto  Monto a retirar (debe ser positivo)
     * @param motivo Descripción del retiro
     * @return WalletResult con éxito/fallo y nuevo saldo
     */
    // REVIEW-MONEY
    public WalletResult retirar(int userId, BigDecimal monto, String motivo) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("El monto debe ser positivo");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // Descontar con guarda atómica
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo - ? WHERE id = ? AND saldo >= ?")) {
                ps.setBigDecimal(1, monto);
                ps.setInt(2, userId);
                ps.setBigDecimal(3, monto);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    return WalletResult.fail("Saldo insuficiente");
                }
            }

            // Leer nuevo saldo
            BigDecimal nuevoSaldo = leerSaldo(conn, userId);

            conn.commit();
            logger.info("💸 RETIRO userId={} monto={} motivo='{}' nuevoSaldo={}",
                    userId, monto, motivo, nuevoSaldo);
            return WalletResult.ok(nuevoSaldo);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en retirar userId={}: {}", userId, e.getMessage());
            return WalletResult.fail("Error interno al retirar");
        } finally {
            cerrarConexion(conn);
        }
    }

    // ═══════════════════════════════════════════
    // APOSTAR
    // ═══════════════════════════════════════════

    /**
     * Descuenta la apuesta de un jugador al iniciar una partida.
     * Verifica saldo con WHERE saldo >= ? y también actualiza total_apostado y estado.
     *
     * @param userId  ID del jugador
     * @param apuesta Monto de la apuesta (debe ser positivo)
     * @return WalletResult con éxito/fallo y nuevo saldo
     */
    // REVIEW-MONEY
    public WalletResult apostar(int userId, BigDecimal apuesta) {
        if (apuesta == null || apuesta.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("La apuesta debe ser positiva");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // Descontar apuesta y actualizar estado + total_apostado atómicamente
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo - ?, total_apostado = total_apostado + ?, " +
                    "estado = 'jugando', paso_juego = 0 " +
                    "WHERE id = ? AND saldo >= ?")) {
                ps.setBigDecimal(1, apuesta);
                ps.setBigDecimal(2, apuesta);
                ps.setInt(3, userId);
                ps.setBigDecimal(4, apuesta);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    return WalletResult.fail("Saldo insuficiente para apostar");
                }
            }

            BigDecimal nuevoSaldo = leerSaldo(conn, userId);

            conn.commit();
            logger.info("🎲 APUESTA userId={} apuesta={} nuevoSaldo={}", userId, apuesta, nuevoSaldo);
            return WalletResult.ok(nuevoSaldo);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en apostar userId={}: {}", userId, e.getMessage());
            return WalletResult.fail("Error interno al apostar");
        } finally {
            cerrarConexion(conn);
        }
    }

    // ═══════════════════════════════════════════
    // LIQUIDAR (resultado de partida)
    // ═══════════════════════════════════════════

    /**
     * Resultado de la liquidación de una partida. Incluye desglose de comisiones.
     */
    public static class LiquidacionResult {
        public final boolean success;
        public final BigDecimal premio;
        public final BigDecimal comisionTotal;
        public final BigDecimal comSorteos;
        public final BigDecimal comMisiones;
        public final BigDecimal comLogros;
        public final BigDecimal comLeaderboard;
        public final BigDecimal comDevolucion;
        public final BigDecimal comReferidos;
        public final BigDecimal comGanancia;
        public final BigDecimal utilidadPorJugador;
        public final BigDecimal nuevoSaldoGanador;
        public final String error;

        private LiquidacionResult(boolean success, BigDecimal premio, BigDecimal comisionTotal,
                                  BigDecimal comSorteos, BigDecimal comMisiones, BigDecimal comLogros,
                                  BigDecimal comLeaderboard, BigDecimal comDevolucion, BigDecimal comReferidos,
                                  BigDecimal comGanancia, BigDecimal utilidadPorJugador,
                                  BigDecimal nuevoSaldoGanador, String error) {
            this.success = success;
            this.premio = premio;
            this.comisionTotal = comisionTotal;
            this.comSorteos = comSorteos;
            this.comMisiones = comMisiones;
            this.comLogros = comLogros;
            this.comLeaderboard = comLeaderboard;
            this.comDevolucion = comDevolucion;
            this.comReferidos = comReferidos;
            this.comGanancia = comGanancia;
            this.utilidadPorJugador = utilidadPorJugador;
            this.nuevoSaldoGanador = nuevoSaldoGanador;
            this.error = error;
        }

        public static LiquidacionResult fail(String error) {
            return new LiquidacionResult(false, null, null, null, null, null, null, null, null, null, null, null, error);
        }
    }

    /**
     * Liquida el resultado de una partida: paga al ganador, distribuye comisiones,
     * actualiza estadísticas y admin_wallet. Todo en una sola transacción SQL atómica.
     *
     * @param idGanador   ID del jugador ganador
     * @param idPerdedor  ID del jugador perdedor
     * @param apuesta     Monto original de la apuesta (lo que cada jugador puso)
     * @param matchDbId   ID de la partida en la BD
     * @param razonComision  "comision_match" o "comision_disputa"
     * @return LiquidacionResult con desglose completo
     */
    // REVIEW-MONEY
    public LiquidacionResult liquidar(int idGanador, int idPerdedor, BigDecimal apuesta,
                                       int matchDbId, String razonComision) {
        if (apuesta == null || apuesta.compareTo(BigDecimal.ZERO) <= 0) {
            return LiquidacionResult.fail("Apuesta inválida");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // --- Cálculo de comisiones con BigDecimal ---
            BigDecimal pozo = apuesta.multiply(BigDecimal.valueOf(2));
            // Porcentaje de comisión escalonado: 25% para pozo=2000, baja a 10% para pozo=20000
            BigDecimal porcentajeComision = calcularPorcentajeComision(pozo);
            BigDecimal comisionTeorica = pozo.multiply(porcentajeComision).setScale(0, RoundingMode.FLOOR);
            BigDecimal premio = pozo.subtract(comisionTeorica);
            BigDecimal comisionReal = pozo.subtract(premio);

            // Distribución de comisiones
            BigDecimal comSorteos     = comisionTeorica.multiply(BigDecimal.valueOf(0.20)).setScale(0, RoundingMode.FLOOR);
            BigDecimal comMisiones    = comisionTeorica.multiply(BigDecimal.valueOf(0.10)).setScale(0, RoundingMode.FLOOR);
            BigDecimal comLogros      = comisionTeorica.multiply(BigDecimal.valueOf(0.05)).setScale(0, RoundingMode.FLOOR);
            BigDecimal comLeaderboard = comisionTeorica.multiply(BigDecimal.valueOf(0.15)).setScale(0, RoundingMode.FLOOR);
            BigDecimal comDevolucion  = comisionTeorica.multiply(BigDecimal.valueOf(0.15)).setScale(0, RoundingMode.FLOOR);
            BigDecimal comReferidos   = comisionTeorica.multiply(BigDecimal.valueOf(0.10)).setScale(0, RoundingMode.FLOOR);
            BigDecimal comGanancia    = comisionReal.subtract(comSorteos).subtract(comMisiones)
                    .subtract(comLogros).subtract(comLeaderboard).subtract(comDevolucion).subtract(comReferidos);
            BigDecimal utilidadPorJugador = comGanancia.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

            String detalle = "Match #" + matchDbId;

            // 1. Pagar al ganador
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo + ?, total_ganado = total_ganado + ? WHERE id = ?")) {
                ps.setBigDecimal(1, premio);
                ps.setBigDecimal(2, premio);
                ps.setInt(3, idGanador);
                ps.executeUpdate();
            }

            // 2. Estadísticas de ganancia generada para ambos jugadores
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET ganancia_generada = ganancia_generada + ?, " +
                    "gen_sorteos = gen_sorteos + ?, gen_misiones = gen_misiones + ?, " +
                    "gen_logros = gen_logros + ?, gen_leaderboard = gen_leaderboard + ?, " +
                    "gen_devolucion = gen_devolucion + ?, gen_referidos = gen_referidos + ? " +
                    "WHERE id IN (?, ?)")) {
                ps.setBigDecimal(1, utilidadPorJugador);
                ps.setBigDecimal(2, comSorteos.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                ps.setBigDecimal(3, comMisiones.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                ps.setBigDecimal(4, comLogros.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                ps.setBigDecimal(5, comLeaderboard.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                ps.setBigDecimal(6, comDevolucion.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                ps.setBigDecimal(7, comReferidos.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                ps.setInt(8, idGanador);
                ps.setInt(9, idPerdedor);
                ps.executeUpdate();
            }

            // 3. Estadísticas de victoria/derrota
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET total_victorias = total_victorias + 1, " +
                    "victorias_normales = victorias_normales + 1, total_partidas = total_partidas + 1, " +
                    "victorias_dia = victorias_dia + 1, victorias_semana = victorias_semana + 1, " +
                    "victorias_mes = victorias_mes + 1, victorias_ano = victorias_ano + 1 WHERE id = ?")) {
                ps.setInt(1, idGanador);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET total_derrotas = total_derrotas + 1, " +
                    "derrotas_normales = derrotas_normales + 1, total_partidas = total_partidas + 1 WHERE id = ?")) {
                ps.setInt(1, idPerdedor);
                ps.executeUpdate();
            }

            // 4. Registrar comisiones en admin_wallet
            insertarComisionAdmin(conn, comSorteos, razonComision, detalle, "sorteos");
            insertarComisionAdmin(conn, comMisiones, razonComision, detalle, "misiones");
            insertarComisionAdmin(conn, comLogros, razonComision, detalle, "logros");
            insertarComisionAdmin(conn, comLeaderboard, razonComision, detalle, "leaderboard");
            insertarComisionAdmin(conn, comDevolucion, razonComision, detalle, "devolucion");
            insertarComisionAdmin(conn, comReferidos, razonComision, detalle, "referidos");
            insertarComisionAdmin(conn, comGanancia, razonComision, detalle, "ganancia");

            // 5. Repartir comisión de leaderboard en los 4 pozos
            BigDecimal parteLeaderboard = comLeaderboard.divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE leaderboard_pools SET dia = dia + ?, semana = semana + ?, " +
                    "mes = mes + ?, ano = ano + ? WHERE id = 1")) {
                ps.setBigDecimal(1, parteLeaderboard);
                ps.setBigDecimal(2, parteLeaderboard);
                ps.setBigDecimal(3, parteLeaderboard);
                ps.setBigDecimal(4, parteLeaderboard);
                ps.executeUpdate();
            }

            // 6. Leer saldo final del ganador
            BigDecimal nuevoSaldoGanador = leerSaldo(conn, idGanador);

            conn.commit();
            logger.info("🏆 LIQUIDACIÓN match#{} ganador={} premio={} comisión={} nuevoSaldo={}",
                    matchDbId, idGanador, premio, comisionReal, nuevoSaldoGanador);

            return new LiquidacionResult(true, premio, comisionReal,
                    comSorteos, comMisiones, comLogros, comLeaderboard,
                    comDevolucion, comReferidos, comGanancia, utilidadPorJugador,
                    nuevoSaldoGanador, null);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en liquidar match#{}: {}", matchDbId, e.getMessage());
            return LiquidacionResult.fail("Error interno al liquidar partida");
        } finally {
            cerrarConexion(conn);
        }
    }

    // ═══════════════════════════════════════════
    // PREMIAR (leaderboard, sorteos, admin)
    // ═══════════════════════════════════════════

    /**
     * Otorga un premio a un usuario (leaderboard, sorteo, etc.)
     * Similar a depositar, pero con semántica diferente para logs.
     *
     * @param userId ID del usuario
     * @param monto  Monto del premio (debe ser positivo)
     * @param motivo Descripción (ej: "Premio leaderboard #1 diario")
     * @return WalletResult con éxito/fallo y nuevo saldo
     */
    // REVIEW-MONEY
    public WalletResult premiar(int userId, BigDecimal monto, String motivo) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("El monto del premio debe ser positivo");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo + ? WHERE id = ?")) {
                ps.setBigDecimal(1, monto);
                ps.setInt(2, userId);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    return WalletResult.fail("Usuario no encontrado");
                }
            }

            BigDecimal nuevoSaldo = leerSaldo(conn, userId);

            conn.commit();
            logger.info("🎁 PREMIO userId={} monto={} motivo='{}' nuevoSaldo={}",
                    userId, monto, motivo, nuevoSaldo);
            return WalletResult.ok(nuevoSaldo);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en premiar userId={}: {}", userId, e.getMessage());
            return WalletResult.fail("Error interno al premiar");
        } finally {
            cerrarConexion(conn);
        }
    }

    /**
     * Premiar a todos los admins (leaderboard sobrantes).
     *
     * @param montoPorAdmin Monto a dar a cada admin
     * @param motivo        Descripción
     * @return WalletResult (el saldo retornado no es específico de un solo admin)
     */
    // REVIEW-MONEY
    public WalletResult premiarAdmins(BigDecimal montoPorAdmin, String motivo) {
        if (montoPorAdmin == null || montoPorAdmin.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("El monto debe ser positivo");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo + ? WHERE tipo_suscripcion = 'admin'")) {
                ps.setBigDecimal(1, montoPorAdmin);
                ps.executeUpdate();
            }

            conn.commit();
            logger.info("🎁 PREMIO ADMINS monto={} motivo='{}'", montoPorAdmin, motivo);
            return WalletResult.ok(montoPorAdmin); // No hay un saldo específico para "todos los admins"

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en premiarAdmins: {}", e.getMessage());
            return WalletResult.fail("Error interno al premiar admins");
        } finally {
            cerrarConexion(conn);
        }
    }

    // ═══════════════════════════════════════════
    // CONSULTAR SALDO
    // ═══════════════════════════════════════════

    /**
     * Obtiene el saldo actual de un usuario.
     *
     * @param userId ID del usuario
     * @return Saldo como BigDecimal, o BigDecimal.ZERO si no se encuentra
     */
    public BigDecimal obtenerSaldo(int userId) {
        try (Connection conn = db.getConnection()) {
            return leerSaldo(conn, userId);
        } catch (SQLException e) {
            logger.error("❌ Error obteniendo saldo userId={}: {}", userId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // ═══════════════════════════════════════════
    // CÁLCULO DE COMISIÓN
    // ═══════════════════════════════════════════

    /**
     * Calcula el porcentaje de comisión escalonado según el pozo.
     * 25% para pozo=2000, baja linealmente a 10% para pozo=20000+.
     *
     * @param pozo Monto total del pozo (apuesta * 2)
     * @return Porcentaje como BigDecimal (0.10 a 0.25)
     */
    // REVIEW-MONEY
    public static BigDecimal calcularPorcentajeComision(BigDecimal pozo) {
        // porcentaje = 0.25 - ((pozo - 2000) / 18000) * 0.15
        BigDecimal diff = pozo.subtract(BigDecimal.valueOf(2000));
        BigDecimal factor = diff.divide(BigDecimal.valueOf(18000), 10, RoundingMode.HALF_UP);
        BigDecimal descuento = factor.multiply(BigDecimal.valueOf(0.15));
        BigDecimal porcentaje = BigDecimal.valueOf(0.25).subtract(descuento);

        // Clamp entre 0.10 y 0.25
        if (porcentaje.compareTo(BigDecimal.valueOf(0.25)) > 0) {
            porcentaje = BigDecimal.valueOf(0.25);
        }
        if (porcentaje.compareTo(BigDecimal.valueOf(0.10)) < 0) {
            porcentaje = BigDecimal.valueOf(0.10);
        }
        return porcentaje;
    }

    // ═══════════════════════════════════════════
    // HELPERS PRIVADOS
    // ═══════════════════════════════════════════

    /**
     * Lee el saldo actual de un usuario usando una conexión existente (dentro de transacción).
     */
    private BigDecimal leerSaldo(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT saldo FROM users WHERE id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal saldo = rs.getBigDecimal("saldo");
                    return saldo != null ? saldo : BigDecimal.ZERO;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Inserta un registro de comisión en admin_wallet dentro de la transacción activa.
     */
    private void insertarComisionAdmin(Connection conn, BigDecimal monto, String razon,
                                        String detalle, String categoria) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, ?, ?, ?)")) {
            ps.setBigDecimal(1, monto);
            ps.setString(2, razon);
            ps.setString(3, detalle);
            ps.setString(4, categoria);
            ps.executeUpdate();
        }
    }

    private void rollbackSilencioso(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void cerrarConexion(Connection conn) {
        if (conn != null) {
            try {
                conn.setAutoCommit(true); // Restaurar para el pool
                conn.close();
            } catch (SQLException ignored) {}
        }
    }
}
