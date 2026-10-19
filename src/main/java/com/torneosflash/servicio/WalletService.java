package com.torneosflash.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.ComisionService.Categoria;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.List;
import java.util.Map;

/**
 * WalletService — Servicio centralizado para todo movimiento de dinero (D2).
 *
 * Reglas:
 *  - Todo UPDATE de saldo pasa por aquí. Nunca hacer UPDATE users SET saldo directo.
 *  - Usa BigDecimal internamente. Nunca double para cálculos de dinero.
 *  - Las deducciones verifican saldo con WHERE saldo >= ? dentro de la misma transacción.
 *  - Cada operación es atómica (conn.setAutoCommit(false) + commit/rollback).
 *  - Cada movimiento se registra en wallet_ledger dentro de la misma transacción (D3).
 *    El ledger es inmutable: solo INSERT, nunca UPDATE ni DELETE.
 *  - Después de cada commit exitoso se emite un evento al bus (A4) para que
 *    listeners como notificaciones de dinero (Prio 1) reaccionen sin acoplar.
 *
 * // REVIEW-MONEY — Archivo completo trata con dinero real.
 */
public class WalletService {
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    // Tipos de movimiento para wallet_ledger (D3). Coinciden con la columna tipo VARCHAR(20).
    private static final String TIPO_DEPOSITO     = "deposito";
    private static final String TIPO_RETIRO       = "retiro";
    private static final String TIPO_REEMBOLSO    = "reembolso";
    private static final String TIPO_TORNEO       = "torneo";
    private static final String TIPO_PREMIO        = "premio";
    private static final String TIPO_COMISION      = "comision";
    private static final String TIPO_LIQUIDACION   = "liquidacion";

    private final ConexionDB db;
    private final ComisionService comisiones;
    /** Bus de eventos (A4). Puede ser null en tests o constructores legacy. */
    private final EventBus eventos;

    /** Constructor principal: con bus de eventos y política de comisiones. */
    public WalletService(ConexionDB db, ComisionService comisiones, EventBus eventos) {
        this.db = db;
        this.comisiones = comisiones;
        this.eventos = eventos;
    }

    /** Compatibilidad: sin bus de eventos (los movimientos no se notifican al bus). */
    public WalletService(ConexionDB db, ComisionService comisiones) {
        this(db, comisiones, null);
    }

    /** Compatibilidad: usa la política de comisiones por defecto, sin bus. */
    public WalletService(ConexionDB db) {
        this(db, new ComisionService(), null);
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
        public BigDecimal getNuevoSaldo() { return nuevoSaldo != null ? nuevoSaldo : BigDecimal.ZERO; }
        public String getError() { return error; }
    }

    /** Resultado atómico del inicio de una partida entre dos jugadores (C1). */
    public static final class InicioPartidaResult {
        public final boolean success;
        public final int matchId;
        public final BigDecimal nuevoSaldoJugador1;
        public final BigDecimal nuevoSaldoJugador2;
        public final String error;

        private InicioPartidaResult(boolean success, int matchId, BigDecimal nuevoSaldoJugador1,
                                    BigDecimal nuevoSaldoJugador2, String error) {
            this.success = success;
            this.matchId = matchId;
            this.nuevoSaldoJugador1 = nuevoSaldoJugador1;
            this.nuevoSaldoJugador2 = nuevoSaldoJugador2;
            this.error = error;
        }

        public static InicioPartidaResult ok(int matchId, BigDecimal saldo1, BigDecimal saldo2) {
            return new InicioPartidaResult(true, matchId, saldo1, saldo2, null);
        }

        public static InicioPartidaResult fail(String error) {
            return new InicioPartidaResult(false, -1, null, null, error);
        }
    }

    /** Resultado atómico de aprobar o rechazar una transacción en el panel admin (C3). */
    public static final class ResultadoTransaccionAdmin {
        public final int userId;
        public final String tipo;
        public final boolean aprobada;
        public final BigDecimal monto;
        public final BigDecimal nuevoSaldo;

        public ResultadoTransaccionAdmin(int userId, String tipo, boolean aprobada, BigDecimal monto, BigDecimal nuevoSaldo) {
            this.userId = userId;
            this.tipo = tipo;
            this.aprobada = aprobada;
            this.monto = monto;
            this.nuevoSaldo = nuevoSaldo;
        }
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

            // Leer y bloquear saldo anterior (D3 / M3: FOR UPDATE evita lecturas sucias bajo concurrencia)
            BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
            if (saldoAnterior == null) {
                conn.rollback();
                return WalletResult.fail("Usuario no encontrado");
            }

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

            BigDecimal nuevoSaldo = saldoAnterior.add(monto);

            // D3: registrar en ledger (inmutable, dentro de la transacción)
            insertarLedger(conn, userId, TIPO_DEPOSITO, monto, saldoAnterior, nuevoSaldo, motivo);

            conn.commit();
            logger.info("💰 DEPÓSITO userId={} monto={} motivo='{}' nuevoSaldo={}",
                    userId, monto, motivo, nuevoSaldo);
            emitirEvento(Eventos.WALLET_DEPOSITED, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.DEPOSITO, userId, monto, nuevoSaldo, motivo));
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

            // Leer y bloquear saldo anterior (D3 / M3)
            BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
            if (saldoAnterior == null || saldoAnterior.compareTo(monto) < 0) {
                conn.rollback();
                return WalletResult.fail("Saldo insuficiente");
            }

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

            BigDecimal nuevoSaldo = saldoAnterior.subtract(monto);

            // D3: registrar en ledger (inmutable, dentro de la transacción)
            insertarLedger(conn, userId, TIPO_RETIRO, monto, saldoAnterior, nuevoSaldo, motivo);

            conn.commit();
            logger.info("💸 RETIRO userId={} monto={} motivo='{}' nuevoSaldo={}",
                    userId, monto, motivo, nuevoSaldo);
            emitirEvento(Eventos.WALLET_WITHDRAWN, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.RETIRO, userId, monto, nuevoSaldo, motivo));
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
    // REALIZAR TORNEO
    // ═══════════════════════════════════════════

    /**
     * Descuenta el monto del torneo de un jugador al iniciar una partida.
     * Verifica saldo con WHERE saldo >= ? y también actualiza total_monto_torneos y estado.
     *
     * @param userId ID del jugador
     * @param monto  Monto del torneo (debe ser positivo)
     * @return WalletResult con éxito/fallo y nuevo saldo
     */
    // REVIEW-MONEY
    public WalletResult realizarTorneo(int userId, BigDecimal monto) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("El monto debe ser positivo");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // Leer y bloquear saldo anterior (D3 / M3)
            BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
            if (saldoAnterior == null || saldoAnterior.compareTo(monto) < 0) {
                conn.rollback();
                return WalletResult.fail("Saldo insuficiente para realizar el torneo");
            }

            // Descontar monto y actualizar estado + total_monto_torneos atómicamente
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo - ?, total_monto_torneos = total_monto_torneos + ?, " +
                    "estado = 'jugando', paso_juego = 0 " +
                    "WHERE id = ? AND saldo >= ?")) {
                ps.setBigDecimal(1, monto);
                ps.setBigDecimal(2, monto);
                ps.setInt(3, userId);
                ps.setBigDecimal(4, monto);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    return WalletResult.fail("Saldo insuficiente para realizar el torneo");
                }
            }

            BigDecimal nuevoSaldo = saldoAnterior.subtract(monto);

            // D3: registrar en ledger (inmutable, dentro de la transacción)
            insertarLedger(conn, userId, TIPO_TORNEO, monto, saldoAnterior, nuevoSaldo, "Torneo");

            conn.commit();
            logger.info("🎲 TORNEO userId={} monto={} nuevoSaldo={}", userId, monto, nuevoSaldo);
            emitirEvento(Eventos.WALLET_BET, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.TORNEO, userId, monto, nuevoSaldo, "Torneo"));
            return WalletResult.ok(nuevoSaldo);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en realizarTorneo userId={}: {}", userId, e.getMessage());
            return WalletResult.fail("Error interno al realizar el torneo");
        } finally {
            cerrarConexion(conn);
        }
    }

    /**
     * C1: Inicia una partida descontando el monto a AMBOS jugadores y creando la fila en {@code matches}
     * dentro de UNA sola transacción SQL atómica. Si el segundo jugador no tiene saldo o falla cualquier
     * paso, el rollback devuelve automáticamente todo al estado inicial (nadie pierde dinero). // REVIEW-MONEY
     */
    public InicioPartidaResult iniciarPartidaTorneo(int player1Id, String player1Username,
                                                    int player2Id, String player2Username,
                                                    String salaId, String modo, BigDecimal monto) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return InicioPartidaResult.fail("El monto debe ser positivo");
        }
        if (player1Id <= 0 || player2Id <= 0 || player1Id == player2Id) {
            return InicioPartidaResult.fail("Jugadores inválidos para iniciar la partida");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // Bloquear siempre en orden ascendente de ID para evitar deadlocks entre transacciones concurrentes
            int primeroId = Math.min(player1Id, player2Id);
            int segundoId = Math.max(player1Id, player2Id);
            BigDecimal saldoPrimero = leerSaldoBloqueando(conn, primeroId);
            BigDecimal saldoSegundo = leerSaldoBloqueando(conn, segundoId);

            BigDecimal saldoAntesP1 = (player1Id == primeroId) ? saldoPrimero : saldoSegundo;
            BigDecimal saldoAntesP2 = (player2Id == primeroId) ? saldoPrimero : saldoSegundo;

            if (saldoAntesP1 == null || saldoAntesP1.compareTo(monto) < 0) {
                conn.rollback();
                return InicioPartidaResult.fail("Saldo insuficiente para " + player1Username);
            }
            if (saldoAntesP2 == null || saldoAntesP2.compareTo(monto) < 0) {
                conn.rollback();
                return InicioPartidaResult.fail("Saldo insuficiente para " + player2Username);
            }

            // 1. Crear el registro del match en BD dentro de la misma transacción
            int matchId = -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO matches (jugador1, jugador2, modo, monto, estado) VALUES (?, ?, ?, ?, 'en_curso') RETURNING id")) {
                ps.setString(1, player1Username);
                ps.setString(2, player2Username);
                ps.setString(3, modo != null ? modo : "N/A");
                ps.setBigDecimal(4, monto);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        matchId = rs.getInt(1);
                    }
                }
            }
            if (matchId <= 0) {
                conn.rollback();
                return InicioPartidaResult.fail("No se pudo registrar la partida");
            }

            // 2. Descontar a ambos jugadores y actualizar su estado y sala_actual
            int[] jugadores = { player1Id, player2Id };
            String[] nombres = { player1Username, player2Username };
            for (int i = 0; i < 2; i++) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET saldo = saldo - ?, total_monto_torneos = total_monto_torneos + ?, " +
                        "estado = 'jugando', sala_actual = ?, paso_juego = 0 " +
                        "WHERE id = ? AND saldo >= ?")) {
                    ps.setBigDecimal(1, monto);
                    ps.setBigDecimal(2, monto);
                    ps.setString(3, salaId);
                    ps.setInt(4, jugadores[i]);
                    ps.setBigDecimal(5, monto);
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        conn.rollback();
                        return InicioPartidaResult.fail("Saldo insuficiente para " + nombres[i]);
                    }
                }
            }

            BigDecimal nuevoSaldoP1 = saldoAntesP1.subtract(monto);
            BigDecimal nuevoSaldoP2 = saldoAntesP2.subtract(monto);
            String refMatch = "Torneo Match #" + matchId;

            // 3. Registrar ambos descuentos en wallet_ledger
            insertarLedger(conn, player1Id, TIPO_TORNEO, monto, saldoAntesP1, nuevoSaldoP1, refMatch);
            insertarLedger(conn, player2Id, TIPO_TORNEO, monto, saldoAntesP2, nuevoSaldoP2, refMatch);

            conn.commit();
            logger.info("🎲 PARTIDA INICIADA match#{} p1={} (nuevoSaldo={}) p2={} (nuevoSaldo={}) monto={}",
                    matchId, player1Id, nuevoSaldoP1, player2Id, nuevoSaldoP2, monto);

            emitirEvento(Eventos.WALLET_BET, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.TORNEO, player1Id, monto, nuevoSaldoP1, refMatch));
            emitirEvento(Eventos.WALLET_BET, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.TORNEO, player2Id, monto, nuevoSaldoP2, refMatch));

            return InicioPartidaResult.ok(matchId, nuevoSaldoP1, nuevoSaldoP2);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en iniciarPartidaTorneo ({} vs {}): {}", player1Id, player2Id, e.getMessage());
            return InicioPartidaResult.fail("Error interno al iniciar la partida");
        } finally {
            cerrarConexion(conn);
        }
    }

    /**
     * C3: Solicita un retiro descontando el saldo e insertando la fila en {@code transactions}
     * dentro de UNA sola transacción SQL atómica. Si el INSERT falla, el saldo no se descuenta. // REVIEW-MONEY
     */
    public WalletResult solicitarRetiroTransaccional(int userId, String username, String metodo,
                                                     String referencia, BigDecimal monto) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return WalletResult.fail("El monto debe ser positivo");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
            if (saldoAnterior == null || saldoAnterior.compareTo(monto) < 0) {
                conn.rollback();
                return WalletResult.fail("Saldo insuficiente");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo - ? WHERE id = ? AND saldo >= ?")) {
                ps.setBigDecimal(1, monto);
                ps.setInt(2, userId);
                ps.setBigDecimal(3, monto);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    return WalletResult.fail("Saldo insuficiente");
                }
            }

            BigDecimal nuevoSaldo = saldoAnterior.subtract(monto);
            String motivo = "Retiro " + (metodo != null ? metodo : "nequi_retiro");
            insertarLedger(conn, userId, TIPO_RETIRO, monto, saldoAnterior, nuevoSaldo, motivo);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado) " +
                    "VALUES (?, ?, 'retiro', ?, ?, ?, 'pendiente')")) {
                ps.setInt(1, userId);
                ps.setString(2, username);
                ps.setString(3, metodo);
                ps.setBigDecimal(4, monto);
                ps.setString(5, referencia);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    return WalletResult.fail("No se pudo registrar la solicitud de retiro");
                }
            }

            conn.commit();
            logger.info("💸 RETIRO SOLICITADO userId={} monto={} metodo='{}' nuevoSaldo={}",
                    userId, monto, metodo, nuevoSaldo);
            emitirEvento(Eventos.WALLET_WITHDRAWN, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.RETIRO, userId, monto, nuevoSaldo, motivo));
            return WalletResult.ok(nuevoSaldo);

        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en solicitarRetiroTransaccional userId={}: {}", userId, e.getMessage());
            return WalletResult.fail("Error interno al solicitar el retiro");
        } finally {
            cerrarConexion(conn);
        }
    }

    /**
     * C3: Aprueba o rechaza una transacción pendiente de forma 100% atómica (FOR UPDATE sobre transactions +
     * movimiento de saldo + wallet_ledger en la misma transacción SQL). Impide dobles pagos por doble clic. // REVIEW-MONEY
     */
    public ResultadoTransaccionAdmin procesarTransaccionAdmin(int transId, String action) {
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw ServicioException.solicitudInvalida("La acción debe ser 'approve' o 'reject'");
        }
        boolean aprobar = "approve".equals(action);

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            int userId;
            String tipo;
            BigDecimal monto;
            String estadoActual;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT usuario_id, tipo, monto, estado FROM transactions WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, transId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        throw ServicioException.respuestaConError("No existe");
                    }
                    userId = rs.getInt("usuario_id");
                    tipo = rs.getString("tipo");
                    monto = rs.getBigDecimal("monto");
                    estadoActual = rs.getString("estado");
                }
            }

            if (!"pendiente".equals(estadoActual)) {
                conn.rollback();
                throw ServicioException.respuestaConError("Esta transacción ya fue procesada (" + estadoActual + ")");
            }

            String nuevoEstado = aprobar ? "completado" : "rechazado";
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE transactions SET estado = ? WHERE id = ? AND estado = 'pendiente'")) {
                ps.setString(1, nuevoEstado);
                ps.setInt(2, transId);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    throw ServicioException.respuestaConError("Esta transacción ya fue procesada");
                }
            }

            BigDecimal nuevoSaldo;
            boolean movioSaldo = false;
            String motivoLedger = null;
            Eventos.TipoMovimiento tipoEvento = Eventos.TipoMovimiento.DEPOSITO;

            if (aprobar && "deposito".equals(tipo)) {
                BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
                if (saldoAnterior == null) {
                    conn.rollback();
                    throw ServicioException.respuestaConError("Usuario no encontrado");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET saldo = saldo + ? WHERE id = ?")) {
                    ps.setBigDecimal(1, monto);
                    ps.setInt(2, userId);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        throw ServicioException.respuestaConError("Usuario no encontrado");
                    }
                }
                nuevoSaldo = saldoAnterior.add(monto);
                motivoLedger = "Depósito aprobado por admin #" + transId;
                insertarLedger(conn, userId, TIPO_DEPOSITO, monto, saldoAnterior, nuevoSaldo, motivoLedger);
                movioSaldo = true;
            } else if (!aprobar && "retiro".equals(tipo)) {
                BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
                if (saldoAnterior == null) {
                    conn.rollback();
                    throw ServicioException.respuestaConError("Usuario no encontrado");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET saldo = saldo + ? WHERE id = ?")) {
                    ps.setBigDecimal(1, monto);
                    ps.setInt(2, userId);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        throw ServicioException.respuestaConError("Usuario no encontrado");
                    }
                }
                nuevoSaldo = saldoAnterior.add(monto);
                motivoLedger = "Retiro rechazado, devolución #" + transId;
                insertarLedger(conn, userId, TIPO_REEMBOLSO, monto, saldoAnterior, nuevoSaldo, motivoLedger);
                tipoEvento = Eventos.TipoMovimiento.REEMBOLSO;
                movioSaldo = true;
            } else {
                nuevoSaldo = leerSaldo(conn, userId);
            }

            conn.commit();
            if (movioSaldo) {
                logger.info("💰 TRANSACCIÓN ADMIN #{} ({}) userId={} monto={} nuevoSaldo={}",
                        transId, nuevoEstado, userId, monto, nuevoSaldo);
                emitirEvento(Eventos.WALLET_DEPOSITED, new Eventos.MovimientoWallet(
                        tipoEvento, userId, monto, nuevoSaldo, motivoLedger));
            }
            return new ResultadoTransaccionAdmin(userId, tipo, aprobar, monto, nuevoSaldo);

        } catch (ServicioException e) {
            rollbackSilencioso(conn);
            throw e;
        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error en procesarTransaccionAdmin id={}: {}", transId, e.getMessage());
            throw ServicioException.respuestaConError("Error interno al procesar la transacción");
        } finally {
            cerrarConexion(conn);
        }
    }

    /**
     * C4: Acredita un pago de Wompi y marca su transacción como {@code completado} en UNA sola transacción SQL.
     * Si la transacción ya fue completada por un webhook previo, retorna {@code null} sin duplicar ni fallar. // REVIEW-MONEY
     */
    public WalletResult acreditarDepositoWompi(String reference, long pagadoCentavos,
                                               java.util.function.Function<BigDecimal, Long> calculadorCentavos) {
        return acreditarDepositoWompi(reference, null, pagadoCentavos, calculadorCentavos);
    }

    public WalletResult acreditarDepositoWompi(String reference, String wompiTransactionId, long pagadoCentavos,
                                               java.util.function.Function<BigDecimal, Long> calculadorCentavos) {
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            int transaccionId;
            int userId;
            BigDecimal monto;
            String estado;
            String externalIdExistente;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, usuario_id, monto, estado, external_id FROM transactions " +
                    "WHERE referencia = ? AND tipo = 'deposito' AND metodo = 'wompi' FOR UPDATE")) {
                ps.setString(1, reference);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return null;
                    }
                    transaccionId = rs.getInt("id");
                    userId = rs.getInt("usuario_id");
                    monto = rs.getBigDecimal("monto");
                    estado = rs.getString("estado");
                    externalIdExistente = rs.getString("external_id");

                    if (rs.next()) {
                        conn.rollback();
                        logger.error("⛔ Hay varias transacciones Wompi con la referencia '{}'", reference);
                        throw ServicioException.solicitudInvalida(
                                "La referencia Wompi no identifica una sola transacción");
                    }
                }
            }

            if (!"pendiente".equals(estado)) {
                conn.rollback();
                if (wompiTransactionId != null && externalIdExistente != null
                        && !wompiTransactionId.equals(externalIdExistente)) {
                    logger.error("⛔ Intento de asociar wompiId={} a ref={} ya completada con external_id={}",
                            wompiTransactionId, reference, externalIdExistente);
                    throw ServicioException.solicitudInvalida("La referencia ya fue acreditada con otra transacción");
                }
                return null; // Idempotente: ya fue acreditada antes
            }

            // S7: Verificar que este transaction.id de Wompi no haya sido usado en ninguna otra referencia
            if (wompiTransactionId != null && !wompiTransactionId.isBlank()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT referencia FROM transactions WHERE external_id = ?")) {
                    ps.setString(1, wompiTransactionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String otraRef = rs.getString("referencia");
                            conn.rollback();
                            logger.error("⛔ Reutilización de transaction.id Wompi '{}' detectada (ya usado en ref '{}', intentado en '{}')",
                                    wompiTransactionId, otraRef, reference);
                            throw ServicioException.solicitudInvalida("El identificador de pago Wompi ya fue utilizado");
                        }
                    }
                }
            }

            long esperadoCentavos = calculadorCentavos.apply(monto);
            if (pagadoCentavos != esperadoCentavos) {
                conn.rollback();
                logger.error("⚠️ Webhook Wompi rechazado ref {}: monto en centavos recibido ({}) != esperado ({})",
                        reference, pagadoCentavos, esperadoCentavos);
                throw ServicioException.solicitudInvalida("El monto pagado no coincide con la transacción registrada");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE transactions SET estado = 'completado', external_id = ? " +
                    "WHERE id = ? AND tipo = 'deposito' AND metodo = 'wompi' AND estado = 'pendiente'")) {
                if (wompiTransactionId != null && !wompiTransactionId.isBlank()) {
                    ps.setString(1, wompiTransactionId);
                } else {
                    ps.setNull(1, Types.VARCHAR);
                }
                ps.setInt(2, transaccionId);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    return null;
                }
            }

            BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
            if (saldoAnterior == null) {
                conn.rollback();
                throw ServicioException.interno("Usuario de transacción Wompi no encontrado", null);
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo + ? WHERE id = ?")) {
                ps.setBigDecimal(1, monto);
                ps.setInt(2, userId);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    throw ServicioException.interno("No se pudo acreditar el saldo Wompi", null);
                }
            }

            BigDecimal nuevoSaldo = saldoAnterior.add(monto);
            String motivo = (wompiTransactionId != null && !wompiTransactionId.isBlank())
                    ? "Pago Wompi ref:" + reference + " id:" + wompiTransactionId
                    : "Pago Wompi ref:" + reference;
            insertarLedger(conn, userId, TIPO_DEPOSITO, monto, saldoAnterior, nuevoSaldo, motivo);

            conn.commit();
            logger.info("💰 WOMPI ACREDITADO userId={} monto={} ref='{}' nuevoSaldo={}",
                    userId, monto, reference, nuevoSaldo);
            emitirEvento(Eventos.WALLET_DEPOSITED, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.DEPOSITO, userId, monto, nuevoSaldo, motivo));
            return WalletResult.ok(nuevoSaldo);

        } catch (ServicioException e) {
            rollbackSilencioso(conn);
            throw e;
        } catch (SQLException e) {
            rollbackSilencioso(conn);
            logger.error("❌ Error SQL en acreditarDepositoWompi ref={}: {}", reference, e.getMessage());
            throw ServicioException.interno("Error de base de datos al procesar pago Wompi", e);
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
        /** Desglose completo de la comisión (D4). Null si la liquidación falló. */
        public final ComisionService.Desglose desglose;
        public final String error;

        private LiquidacionResult(boolean success, BigDecimal premio, BigDecimal comisionTotal,
                                  BigDecimal comSorteos, BigDecimal comMisiones, BigDecimal comLogros,
                                  BigDecimal comLeaderboard, BigDecimal comDevolucion, BigDecimal comReferidos,
                                  BigDecimal comGanancia, BigDecimal utilidadPorJugador,
                                  BigDecimal nuevoSaldoGanador, ComisionService.Desglose desglose, String error) {
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
            this.desglose = desglose;
            this.error = error;
        }

        public static LiquidacionResult fail(String error) {
            return new LiquidacionResult(false, null, null, null, null, null, null, null, null, null, null, null, null, error);
        }
    }

    /**
     * Liquida el resultado de una partida: paga al ganador, distribuye comisiones,
     * actualiza estadísticas y admin_wallet. Todo en una sola transacción SQL atómica.
     *
     * @param idGanador   ID del jugador ganador
     * @param idPerdedor  ID del jugador perdedor
     * @param monto       Monto original del torneo (lo que cada jugador puso)
     * @param matchDbId   ID de la partida en la BD
     * @param razonComision  "comision_match" o "comision_disputa"
     * @return LiquidacionResult con desglose completo
     */
    // REVIEW-MONEY
    public LiquidacionResult liquidar(int idGanador, int idPerdedor, BigDecimal monto,
                                       int matchDbId, String razonComision) {
        return liquidar(idGanador, idPerdedor, monto, matchDbId, razonComision, null, null, null);
    }

    /**
     * C2: Liquida el resultado de una partida de forma idempotente: si se indica {@code estadoEsperadoMatch}
     * ('en_curso' para API, 'disputa' para resolución de admin), cambia {@code matches.estado = 'finalizada'}
     * con {@code WHERE id = ? AND estado = ?} DENTRO de la misma transacción SQL antes de pagar.
     * Si la partida ya había sido liquidada antes, hace rollback inmediato evitando cualquier doble pago. // REVIEW-MONEY
     */
    public LiquidacionResult liquidar(int idGanador, int idPerdedor, BigDecimal monto,
                                       int matchDbId, String razonComision,
                                       String estadoEsperadoMatch, String ganadorNombre, String culpableNombre) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return LiquidacionResult.fail("Monto inválido");
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // 0. Guarda atómica de idempotencia sobre la fila de matches (C2)
            if (matchDbId > 0 && estadoEsperadoMatch != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE matches SET estado = 'finalizada', ganador = COALESCE(?, ganador) " +
                        "WHERE id = ? AND estado = ?")) {
                    ps.setString(1, ganadorNombre);
                    ps.setInt(2, matchDbId);
                    ps.setString(3, estadoEsperadoMatch);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        conn.rollback();
                        return LiquidacionResult.fail("La partida #" + matchDbId + " ya fue procesada o no está en estado '" + estadoEsperadoMatch + "'");
                    }
                }
            }

            // --- Cálculo de comisiones: única fuente de verdad en ComisionService (D4) ---
            ComisionService.Desglose desglose = comisiones.calcular(monto);
            BigDecimal premio = desglose.getPremio();
            BigDecimal comisionReal = desglose.getComisionTotal();

            BigDecimal comSorteos     = desglose.getMonto(Categoria.SORTEOS);
            BigDecimal comMisiones    = desglose.getMonto(Categoria.MISIONES);
            BigDecimal comLogros      = desglose.getMonto(Categoria.LOGROS);
            BigDecimal comLeaderboard = desglose.getMonto(Categoria.LEADERBOARD);
            BigDecimal comDevolucion  = desglose.getMonto(Categoria.DEVOLUCION);
            BigDecimal comReferidos   = desglose.getMonto(Categoria.REFERIDOS);
            BigDecimal comGanancia    = desglose.getMonto(Categoria.GANANCIA);
            BigDecimal utilidadPorJugador = desglose.utilidadPorJugador();

            String detalle = "Match #" + matchDbId;

            // D3 / M3: leer y bloquear saldo del ganador ANTES del pago
            BigDecimal saldoAnteriorGanador = leerSaldoBloqueando(conn, idGanador);
            if (saldoAnteriorGanador == null) {
                conn.rollback();
                return LiquidacionResult.fail("Ganador no encontrado");
            }

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
                ps.setBigDecimal(2, desglose.porJugador(Categoria.SORTEOS));
                ps.setBigDecimal(3, desglose.porJugador(Categoria.MISIONES));
                ps.setBigDecimal(4, desglose.porJugador(Categoria.LOGROS));
                ps.setBigDecimal(5, desglose.porJugador(Categoria.LEADERBOARD));
                ps.setBigDecimal(6, desglose.porJugador(Categoria.DEVOLUCION));
                ps.setBigDecimal(7, desglose.porJugador(Categoria.REFERIDOS));
                ps.setInt(8, idGanador);
                ps.setInt(9, idPerdedor);
                ps.executeUpdate();
            }

            // 3. Estadísticas de victoria/derrota (normales o por disputa dentro de la misma transacción)
            boolean esDisputa = "disputa".equals(estadoEsperadoMatch) || "comision_disputa".equals(razonComision);
            String colVictoria = esDisputa ? "victorias_disputa" : "victorias_normales";
            String colDerrota = esDisputa ? "derrotas_disputa" : "derrotas_normales";

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET total_victorias = total_victorias + 1, " +
                    colVictoria + " = " + colVictoria + " + 1, total_partidas = total_partidas + 1, " +
                    "victorias_dia = victorias_dia + 1, victorias_semana = victorias_semana + 1, " +
                    "victorias_mes = victorias_mes + 1, victorias_ano = victorias_ano + 1 WHERE id = ?")) {
                ps.setInt(1, idGanador);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET total_derrotas = total_derrotas + 1, " +
                    colDerrota + " = " + colDerrota + " + 1, total_partidas = total_partidas + 1 WHERE id = ?")) {
                ps.setInt(1, idPerdedor);
                ps.executeUpdate();
            }

            if (esDisputa && culpableNombre != null && !"nadie".equals(culpableNombre)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET faltas = faltas + 1 WHERE username = ?")) {
                    ps.setString(1, culpableNombre);
                    ps.executeUpdate();
                }
            }

            // 4. Registrar comisiones en admin_wallet (una fila por categoría del desglose)
            for (Map.Entry<Categoria, BigDecimal> entrada : desglose.getMontos().entrySet()) {
                insertarComisionAdmin(conn, entrada.getValue(), razonComision, detalle, entrada.getKey().getClave());
            }

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

            // 6. Calcular saldo final exacto del ganador
            BigDecimal nuevoSaldoGanador = saldoAnteriorGanador.add(premio);

            // D3: registrar en ledger — premio del ganador
            insertarLedger(conn, idGanador, TIPO_LIQUIDACION, premio,
                    saldoAnteriorGanador, nuevoSaldoGanador, detalle);

            // D3: registrar en ledger — cada categoría de comisión (user_id=0 = sistema/admin)
            for (Map.Entry<Categoria, BigDecimal> entrada : desglose.getMontos().entrySet()) {
                BigDecimal montoCategoria = entrada.getValue();
                if (montoCategoria.compareTo(BigDecimal.ZERO) > 0) {
                    insertarLedger(conn, 0, TIPO_COMISION, montoCategoria,
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            detalle + " " + entrada.getKey().getClave());
                }
            }

            conn.commit();
            logger.info("🏆 LIQUIDACIÓN match#{} ganador={} premio={} comisión={} nuevoSaldo={}",
                    matchDbId, idGanador, premio, comisionReal, nuevoSaldoGanador);

            return new LiquidacionResult(true, premio, comisionReal,
                    comSorteos, comMisiones, comLogros, comLeaderboard,
                    comDevolucion, comReferidos, comGanancia, utilidadPorJugador,
                    nuevoSaldoGanador, desglose, null);

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

            // Leer y bloquear saldo anterior (D3 / M3)
            BigDecimal saldoAnterior = leerSaldoBloqueando(conn, userId);
            if (saldoAnterior == null) {
                conn.rollback();
                return WalletResult.fail("Usuario no encontrado");
            }

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

            BigDecimal nuevoSaldo = saldoAnterior.add(monto);

            // D3: registrar en ledger (inmutable, dentro de la transacción)
            insertarLedger(conn, userId, TIPO_PREMIO, monto, saldoAnterior, nuevoSaldo, motivo);

            conn.commit();
            logger.info("🎁 PREMIO userId={} monto={} motivo='{}' nuevoSaldo={}",
                    userId, monto, motivo, nuevoSaldo);
            emitirEvento(Eventos.WALLET_AWARDED, new Eventos.MovimientoWallet(
                    Eventos.TipoMovimiento.PREMIO, userId, monto, nuevoSaldo, motivo));
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

            // D3 / M3: leer y bloquear IDs y saldos de los admins ANTES del pago
            List<Object[]> adminsInfo = new java.util.ArrayList<>(); // {id, saldoAnterior}
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, saldo FROM users WHERE tipo_suscripcion = 'admin' ORDER BY id ASC FOR UPDATE")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int adminId = rs.getInt("id");
                        BigDecimal saldoAntes = rs.getBigDecimal("saldo");
                        if (saldoAntes == null) saldoAntes = BigDecimal.ZERO;
                        adminsInfo.add(new Object[]{adminId, saldoAntes});
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET saldo = saldo + ? WHERE tipo_suscripcion = 'admin'")) {
                ps.setBigDecimal(1, montoPorAdmin);
                ps.executeUpdate();
            }

            // D3: registrar en el ledger una fila por cada admin
            for (Object[] info : adminsInfo) {
                int adminId = (int) info[0];
                BigDecimal saldoAntes = (BigDecimal) info[1];
                BigDecimal saldoDespues = saldoAntes.add(montoPorAdmin);
                insertarLedger(conn, adminId, TIPO_PREMIO, montoPorAdmin, saldoAntes, saldoDespues, motivo);
            }

            conn.commit();
            logger.info("🎁 PREMIO ADMINS monto={} motivo='{}' admins={}", montoPorAdmin, motivo, adminsInfo.size());
            for (Object[] info : adminsInfo) {
                int adminId = (int) info[0];
                BigDecimal saldoDespues = ((BigDecimal) info[1]).add(montoPorAdmin);
                emitirEvento(Eventos.WALLET_AWARDED, new Eventos.MovimientoWallet(
                        Eventos.TipoMovimiento.PREMIO, adminId, montoPorAdmin, saldoDespues, motivo));
            }
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
     * @deprecated La política de comisiones vive en {@link ComisionService#calcularPorcentaje(BigDecimal)} (D4).
     *             Se mantiene como delegado para no romper código existente.
     */
    @Deprecated
    public static BigDecimal calcularPorcentajeComision(BigDecimal pozo) {
        return ComisionService.calcularPorcentaje(pozo);
    }

    // ═══════════════════════════════════════════
    // EMISIÓN DE EVENTOS (A4)
    // ═══════════════════════════════════════════

    /**
     * Emite un evento al bus si existe. Se llama DESPUÉS del commit SQL.
     * No lanza: un listener roto no puede tumbar una operación de dinero ya confirmada.
     */
    private void emitirEvento(String nombre, Object datos) {
        if (eventos != null) {
            eventos.emit(nombre, datos);
        }
    }

    // ═══════════════════════════════════════════
    // HELPERS PRIVADOS
    // ═══════════════════════════════════════════

    /**
     * Lee el saldo actual de un usuario (solo lectura).
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
     * M3: Lee el saldo de un usuario bloqueando la fila con FOR UPDATE dentro de la transacción activa.
     * Devuelve {@code null} si el usuario no existe.
     */
    private BigDecimal leerSaldoBloqueando(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT saldo FROM users WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal saldo = rs.getBigDecimal("saldo");
                    return saldo != null ? saldo : BigDecimal.ZERO;
                }
            }
        }
        return null;
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

    /**
     * D3: Inserta una fila inmutable en wallet_ledger dentro de la transacción activa.
     * Nunca UPDATE ni DELETE sobre esta tabla. // REVIEW-MONEY
     *
     * @param conn            conexión con transacción activa
     * @param userId          usuario afectado (0 para movimientos de sistema/admin como comisiones)
     * @param tipo            uno de TIPO_DEPOSITO, TIPO_RETIRO, TIPO_TORNEO, TIPO_PREMIO, TIPO_COMISION, TIPO_LIQUIDACION
     * @param monto           monto del movimiento (siempre positivo)
     * @param saldoAnterior   saldo del usuario antes del movimiento
     * @param saldoPosterior  saldo del usuario después del movimiento
     * @param referencia      contexto legible (motivo, "Match #42", etc.)
     */
    private void insertarLedger(Connection conn, int userId, String tipo, BigDecimal monto,
                                BigDecimal saldoAnterior, BigDecimal saldoPosterior,
                                String referencia) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallet_ledger (user_id, tipo, monto, saldo_anterior, saldo_posterior, referencia) " +
                "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, userId);
            ps.setString(2, tipo);
            ps.setBigDecimal(3, monto);
            ps.setBigDecimal(4, saldoAnterior);
            ps.setBigDecimal(5, saldoPosterior);
            ps.setString(6, referencia != null && referencia.length() > 200
                    ? referencia.substring(0, 200) : referencia);
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
