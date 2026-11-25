package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.WalletService.LiquidacionResult;
import com.torneosflash.servidor.SocketHandler;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lógica del panel de administración: transacciones pendientes, disputas, estadísticas,
 * control de la zona de pruebas y utilidades (A3).
 * Antes vivía en {@code RutasAdmin}. No conoce HTTP.
 * D1: Todo monto y saldo se maneja con {@link BigDecimal} (nunca {@code double}).
 */
public class AdminService {

    private static final int MAX_NEQUIS_MODERADOR = 20;
    private static final Pattern NEQUI_FORMATO_PERMITIDO = Pattern.compile("^[0-9 +()-]{7,25}$");

    private final GenericDAO db;
    private final UsuarioDAO usuarioDAO;
    private final WalletService wallet;
    private final EventBus eventos; // A4: avisa "match.finished" al resolver una disputa (antes acreditaba los tickets aquí)
    private final NotificadorUsuarios notificador;
    private final SocketIOServer io;
    private final CifradoTokenJugador cifradoTokenJugador;
    private ConexionDB conexion;
    private SocketHandler socketHandler;
    private LeaderboardService leaderboardService;

    public AdminService(GenericDAO db, UsuarioDAO usuarioDAO, WalletService wallet, EventBus eventos,
                        NotificadorUsuarios notificador, SocketIOServer io) {
        this(db, usuarioDAO, wallet, eventos, notificador, io, null);
    }

    public AdminService(GenericDAO db, UsuarioDAO usuarioDAO, WalletService wallet, EventBus eventos,
                        NotificadorUsuarios notificador, SocketIOServer io, CifradoTokenJugador cifradoTokenJugador) {
        this.db = db;
        this.usuarioDAO = usuarioDAO;
        this.wallet = wallet;
        this.eventos = eventos;
        this.notificador = notificador;
        this.io = io;
        this.cifradoTokenJugador = cifradoTokenJugador;
        this.conexion = usuarioDAO != null ? usuarioDAO.getConexionDB() : null;
    }

    public void setConexionDB(ConexionDB conexion) {
        this.conexion = conexion;
    }

    public void setSocketHandler(SocketHandler socketHandler) {
        this.socketHandler = socketHandler;
    }

    public void setLeaderboardService(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    private ConexionDB obtenerConexion() {
        if (conexion != null) return conexion;
        return usuarioDAO != null ? usuarioDAO.getConexionDB() : null;
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Solo las transacciones pendientes de aprobar (vista general del administrador). */
    public ArrayList<JsonObject> transaccionesPendientes() {
        return db.query(
                "SELECT t.* FROM transactions t " +
                "LEFT JOIN users u ON u.id = t.usuario_id " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "LEFT JOIN users m ON m.id = COALESCE(u.referido_por_id, r.referrer_user_id) " +
                "WHERE t.estado = 'pendiente' AND t.metodo IS DISTINCT FROM 'wompi' " +
                "AND (m.id IS NULL OR m.id = u.id OR LOWER(COALESCE(m.tipo_suscripcion, '')) <> 'moderador') " +
                "ORDER BY t.id DESC");
    }

    /** Transacciones pendientes filtradas por rol: el moderador ve solo las de sus usuarios; el admin ve las propias. */
    public ArrayList<JsonObject> transaccionesPendientes(int actorUserId) {
        if (esModerador(actorUserId)) {
            return db.query(
                    "SELECT t.* FROM transactions t " +
                    "JOIN users u ON u.id = t.usuario_id " +
                    "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                    "JOIN users m ON m.id = COALESCE(u.referido_por_id, r.referrer_user_id) " +
                    "WHERE t.estado = 'pendiente' AND t.metodo IS DISTINCT FROM 'wompi' " +
                    "AND m.id = ? AND m.id <> u.id AND LOWER(COALESCE(m.tipo_suscripcion, '')) = 'moderador' " +
                    "ORDER BY t.id DESC",
                    actorUserId);
        }
        return transaccionesPendientes();
    }

    /** Devuelve tokens descifrados solo al panel protegido para que el administrador los verifique en Discord. */
    public ArrayList<JsonObject> verificacionesClashPendientes() {
        if (cifradoTokenJugador == null) {
            throw ServicioException.interno("El servicio de verificación manual no está disponible", null);
        }
        ArrayList<JsonObject> filas = db.query(
                "SELECT id, username, player_tag, player_verification_token_ciphertext " +
                        "FROM users WHERE player_tag_verificado = FALSE " +
                        "AND player_verification_token_ciphertext IS NOT NULL ORDER BY id ASC");
        ArrayList<JsonObject> pendientes = new ArrayList<>();
        for (JsonObject fila : filas) {
            JsonObject pendiente = new JsonObject();
            pendiente.add("id", fila.get("id"));
            pendiente.add("username", fila.get("username"));
            pendiente.add("player_tag", fila.get("player_tag"));
            try {
                pendiente.addProperty("token", cifradoTokenJugador.descifrar(
                        fila.get("player_verification_token_ciphertext").getAsString()));
            } catch (RuntimeException e) {
                throw ServicioException.interno("No se pudo leer un token pendiente de verificación", e);
            }
            pendientes.add(pendiente);
        }
        return pendientes;
    }

    /** Marca como verificado y borra el token cifrado en la misma actualización. */
    public void confirmarVerificacionClash(int userId, int adminUserId) {
        if (esModerador(adminUserId)) {
            throw ServicioException.prohibido("Solo los administradores pueden verificar cuentas de Clash");
        }
        if (userId <= 0 || adminUserId <= 0) throw ServicioException.solicitudInvalida("Usuario no válido");
        int filas = db.update(
                "UPDATE users SET player_tag_verificado = TRUE, player_tag_verificado_en = CURRENT_TIMESTAMP, " +
                        "player_tag_verificado_por = ?, player_verification_token_ciphertext = NULL " +
                        "WHERE id = ? AND player_tag_verificado = FALSE " +
                        "AND player_verification_token_ciphertext IS NOT NULL",
                adminUserId, userId);
        if (filas == 1) {
            ConexionDB connDb = obtenerConexion();
            if (connDb != null && connDb.tieneZonaPruebas()) {
                connDb.sincronizarUsuarioConPruebas(userId);
            }
            return;
        }

        JsonObject usuario = db.queryOne("SELECT player_tag_verificado FROM users WHERE id = ?", userId);
        if (usuario == null) throw ServicioException.noEncontrado("No se encontró esa cuenta");
        if (usuario.has("player_tag_verificado") && usuario.get("player_tag_verificado").getAsBoolean()) {
            throw ServicioException.solicitudInvalida("Esa cuenta ya está verificada");
        }
        throw ServicioException.solicitudInvalida("No hay un token pendiente para esa cuenta");
    }

    public ArrayList<JsonObject> disputas() {
        return db.query("SELECT * FROM matches WHERE estado = 'disputa' ORDER BY id DESC");
    }

    // REVIEW-MONEY
    public JsonObject estadisticas(int adminUserId) {
        if (esModerador(adminUserId)) {
            return estadisticasModerador(adminUserId);
        }
        JsonObject stats = new JsonObject();
        stats.addProperty("esModerador", false);
        ConexionDB connDb = obtenerConexion();
        stats.addProperty("zonaActiva", ConexionDB.zonaActual());
        stats.addProperty("enZonaPruebas", ConexionDB.esZonaPrueba());
        stats.addProperty("zonaPruebasDisponible", connDb != null && connDb.tieneZonaPruebas());
        if (ConexionDB.esZonaPrueba() && connDb != null && connDb.tieneZonaPruebas()) {
            connDb.sincronizarUsuariosPruebaYAdmin();
        }

        // Dinero en circulación = suma de saldos de todos los usuarios // REVIEW-MONEY
        JsonObject saldoSum = db.queryOne("SELECT COALESCE(SUM(saldo),0) as total FROM users");
        stats.addProperty("totalUsuarios", obtenerMontoJson(saldoSum, "total"));

        // Ingreso histórico por comisiones: los cierres de leaderboard son distribución de fondos, no comisiones negativas.
        JsonObject walletSum = db.queryOne(
                "SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet WHERE monto > 0");
        stats.addProperty("totalGanancias", obtenerMontoJson(walletSum, "total"));

        // Participación del administrador en los sobrantes del ranking ya pagados a sus cuentas.
        JsonObject rankingAdminSum = db.queryOne(
                "SELECT COALESCE(SUM(monto),0) as total FROM leaderboard_cut_payments " +
                        "WHERE tipo_pago = 'admin' AND estado = 'pagado' AND user_id = ?", adminUserId);
        stats.addProperty("gananciaAdminLeaderboard",
                obtenerMontoJson(rankingAdminSum, "total"));

        // Desglose por categorías: Histórico (todo lo que se acumuló) y Actual (lo que sigue disponible en cada
        // categoría tras descontar lo ya repartido, pagado o transferido al dueño). // REVIEW-MONEY
        JsonObject desglose = new JsonObject();
        for (ComisionService.Categoria categoria : ComisionService.Categoria.values()) {
            String cat = categoria.getClave();
            JsonObject catSum = db.queryOne(
                    "SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet WHERE categoria = ? AND monto > 0", cat);
            desglose.addProperty(cat, obtenerMontoJson(catSum, "total"));
        }
        stats.add("desglose", desglose);

        JsonObject desgloseHistorico = new JsonObject();
        JsonObject desgloseActual = new JsonObject();
        JsonObject desgloseDescontado = new JsonObject();
        for (ComisionService.Categoria categoria : ComisionService.Categoria.values()) {
            String cat = categoria.getClave();
            BigDecimal historico = obtenerMontoJson(desglose, cat);
            BigDecimal actual = calcularActualCategoria(categoria, historico);
            desgloseHistorico.addProperty(cat, historico);
            desgloseActual.addProperty(cat, actual);
            desgloseDescontado.addProperty(cat, historico.subtract(actual).max(BigDecimal.ZERO));
        }
        stats.add("desgloseHistorico", desgloseHistorico);
        stats.add("desgloseActual", desgloseActual);
        stats.add("desgloseDescontado", desgloseDescontado);

        // Resumen combinado de ganancias del dueño: Ganancia base (25%) + divisiones inactivas (30%) + sobrantes de ranking. // REVIEW-MONEY
        BigDecimal gananciaAdminLeaderboard = obtenerMontoJson(stats, "gananciaAdminLeaderboard");
        BigDecimal gananciaBaseComisionesAdmin = obtenerMontoJson(desglose, ComisionService.Categoria.GANANCIA.getClave());
        BigDecimal gananciaInactivasAdmin = BigDecimal.ZERO;
        for (ComisionService.Categoria categoria : ComisionService.Categoria.values()) {
            if (!categoria.isActiva()) {
                gananciaInactivasAdmin = gananciaInactivasAdmin.add(obtenerMontoJson(desglose, categoria.getClave()));
            }
        }
        BigDecimal gananciaComisionesAdmin = gananciaBaseComisionesAdmin.add(gananciaInactivasAdmin);
        BigDecimal gananciaTotalCombinadaAdmin = gananciaComisionesAdmin.add(gananciaAdminLeaderboard);
        stats.addProperty("gananciaBaseComisionesAdmin", gananciaBaseComisionesAdmin);
        stats.addProperty("gananciaInactivasAdmin", gananciaInactivasAdmin);
        stats.addProperty("gananciaComisionesAdmin", gananciaComisionesAdmin);
        stats.addProperty("gananciaTotalCombinadaAdmin", gananciaTotalCombinadaAdmin);

        // Comisión de referidos acreditada al admin por partidas de jugadores sin referidor // REVIEW-MONEY
        JsonObject refAdminSum = db.queryOne(
                "SELECT COALESCE(SUM(monto_admin), 0) AS total_admin, " +
                "COALESCE(SUM(monto_referidor), 0) AS total_referidores, " +
                "COALESCE(SUM(monto_devuelto_referido), 0) AS total_devuelto, " +
                "COALESCE(SUM(monto_base), 0) AS total_base " +
                "FROM referral_payouts");
        JsonObject refConteo = db.queryOne("SELECT COUNT(*) AS total FROM referrals");
        BigDecimal gananciaAdminReferidos = obtenerMontoJson(refAdminSum, "total_admin");
        stats.addProperty("gananciaAdminReferidos", gananciaAdminReferidos);
        stats.addProperty("gananciaTotalConReferidosAdmin",
                gananciaTotalCombinadaAdmin.add(gananciaAdminReferidos));

        JsonObject resumenReferidos = new JsonObject();
        resumenReferidos.addProperty("totalRelaciones",
                refConteo != null && refConteo.has("total") ? refConteo.get("total").getAsLong() : 0L);
        resumenReferidos.addProperty("totalComisionBase", obtenerMontoJson(refAdminSum, "total_base"));
        resumenReferidos.addProperty("totalPagadoReferidores", obtenerMontoJson(refAdminSum, "total_referidores"));
        resumenReferidos.addProperty("totalDevueltoReferidos", obtenerMontoJson(refAdminSum, "total_devuelto"));
        resumenReferidos.addProperty("totalAdminSinReferidor", gananciaAdminReferidos);
        stats.add("resumenReferidos", resumenReferidos);

        // Lista de todos los usuarios con sus detalles
        ArrayList<JsonObject> usuarios = db.query(
                "SELECT id, username, email, saldo, tipo_suscripcion, COALESCE(zona_admin, 'normal') AS zona_admin, " +
                "COALESCE(ganancia_generada, 0) AS ganancia_generada, " +
                "COALESCE(gen_sorteos, 0) AS gen_sorteos, COALESCE(gen_misiones, 0) AS gen_misiones, " +
                "COALESCE(gen_logros, 0) AS gen_logros, COALESCE(gen_leaderboard, 0) AS gen_leaderboard, " +
                "COALESCE(gen_devolucion, 0) AS gen_devolucion, COALESCE(gen_referidos, 0) AS gen_referidos, " +
                "total_partidas, total_victorias, victorias_normales, victorias_disputa, " +
                "total_derrotas, derrotas_normales, derrotas_disputa, " +
                "faltas, salidas_chat, salidas_x, salidas_canal, salidas_desconexion " +
                "FROM users ORDER BY saldo DESC");
        JsonArray listaUsuarios = new JsonArray();
        for (JsonObject u : usuarios) {
            BigDecimal gananciaGenerada = normalizarColumnaMonto(u, "ganancia_generada");
            BigDecimal genSorteos = normalizarColumnaMonto(u, "gen_sorteos");
            BigDecimal genMisiones = normalizarColumnaMonto(u, "gen_misiones");
            BigDecimal genLogros = normalizarColumnaMonto(u, "gen_logros");
            BigDecimal genLeaderboard = normalizarColumnaMonto(u, "gen_leaderboard");
            BigDecimal genDevolucion = normalizarColumnaMonto(u, "gen_devolucion");
            BigDecimal genReferidos = normalizarColumnaMonto(u, "gen_referidos");
            BigDecimal gananciaInactivasAdminUsuario = BigDecimal.ZERO;
            for (ComisionService.Categoria categoria : ComisionService.Categoria.values()) {
                if (!categoria.isActiva()) {
                    gananciaInactivasAdminUsuario = gananciaInactivasAdminUsuario
                            .add(normalizarColumnaMonto(u, "gen_" + categoria.getClave()));
                }
            }
            BigDecimal gananciaEfectivaAdminUsuario = gananciaGenerada.add(gananciaInactivasAdminUsuario);
            BigDecimal comisionTotalAportada = gananciaGenerada
                    .add(genSorteos)
                    .add(genMisiones)
                    .add(genLogros)
                    .add(genLeaderboard)
                    .add(genDevolucion)
                    .add(genReferidos);
            u.addProperty("ganancia_inactivas_admin", gananciaInactivasAdminUsuario);
            u.addProperty("ganancia_efectiva_admin", gananciaEfectivaAdminUsuario);
            u.addProperty("comision_total_aportada", comisionTotalAportada);
            listaUsuarios.add(u);
        }
        stats.add("listaUsuarios", listaUsuarios);

        stats.addProperty("usuariosOnline", io != null ? io.getSockets().size() : 0);
        return stats;
    }

    /**
     * Monto «actual» de una categoría de comisión: lo que todavía está disponible para esa categoría. // REVIEW-MONEY
     * <ul>
     *   <li>Categorías inactivas (misiones, logros, devolución): ya pasaron a la ganancia del dueño, quedan en 0.</li>
     *   <li>Referidos: se reparte al liquidar cada partida (referidor, cashback o dueño), siempre queda en 0.</li>
     *   <li>Ranking: pozos vivos (día/semana/mes/año) más pagos de un corte aún pendientes.</li>
     *   <li>Sorteos: saldo del pozo de sorteos (ya descuenta premios reservados, devoluciones y mitad del admin).</li>
     *   <li>Ganancia: utilidad base del dueño, no se descuenta.</li>
     * </ul>
     */
    private BigDecimal calcularActualCategoria(ComisionService.Categoria categoria, BigDecimal historico) {
        if (!categoria.isActiva() || categoria == ComisionService.Categoria.REFERIDOS) {
            return BigDecimal.ZERO;
        }
        if (categoria == ComisionService.Categoria.LEADERBOARD) {
            JsonObject pozos = db.queryOne(
                    "SELECT (COALESCE(dia, 0) + COALESCE(semana, 0) + COALESCE(mes, 0) + COALESCE(ano, 0)) AS total " +
                    "FROM leaderboard_pools WHERE id = 1");
            if (pozos != null) {
                JsonObject pendientes = db.queryOne(
                        "SELECT COALESCE(SUM(monto), 0) AS total FROM leaderboard_cut_payments WHERE estado = 'pendiente'");
                return obtenerMontoJson(pozos, "total").add(obtenerMontoJson(pendientes, "total"))
                        .max(BigDecimal.ZERO);
            }
            JsonObject pagados = db.queryOne(
                    "SELECT COALESCE(SUM(monto), 0) AS total FROM leaderboard_cut_payments WHERE estado = 'pagado'");
            JsonObject historialLegado = db.queryOne(
                    "SELECT COALESCE(SUM(premio), 0) AS total FROM leaderboard_history WHERE corte_pago_id IS NULL");
            BigDecimal totalPagado = obtenerMontoJson(pagados, "total").add(obtenerMontoJson(historialLegado, "total"));
            return historico.subtract(totalPagado).max(BigDecimal.ZERO);
        }
        if (categoria == ComisionService.Categoria.SORTEOS) {
            JsonObject pozoSorteos = db.queryOne("SELECT monto AS total FROM raffle_pool WHERE id = 1");
            if (pozoSorteos != null) {
                return obtenerMontoJson(pozoSorteos, "total").max(BigDecimal.ZERO);
            }
            JsonObject usados = db.queryOne(
                    "SELECT COALESCE(SUM(precio), 0) AS total FROM raffles WHERE estado IN ('activo', 'completado')");
            return usados != null
                    ? historico.subtract(obtenerMontoJson(usados, "total")).max(BigDecimal.ZERO)
                    : historico;
        }
        return historico;
    }

    /**
     * Estadísticas exclusivas del moderador: dinero en circulación de sus usuarios, comisiones de partidas
     * acreditadas a su billetera, comisiones de referidos en su billetera y lista de sus usuarios propios
     * (sin rankings, sorteos ni categorías inactivas del dueño). // REVIEW-MONEY
     */
    private JsonObject estadisticasModerador(int moderatorId) {
        ConexionDB connDb = obtenerConexion();
        boolean disponiblePruebas = connDb != null && connDb.tieneZonaPruebas();
        if (ConexionDB.esZonaPrueba() && disponiblePruebas) {
            connDb.sincronizarUsuariosPruebaYAdmin();
        }
        JsonObject stats = new JsonObject();
        stats.addProperty("esModerador", true);
        stats.addProperty("zonaActiva", ConexionDB.zonaActual());
        stats.addProperty("enZonaPruebas", ConexionDB.esZonaPrueba());
        stats.addProperty("zonaPruebasDisponible", disponiblePruebas);

        JsonObject modInfo = db.queryOne(
                "SELECT id, username, codigo_referido, COALESCE(referido_porcentaje_compartido, 0) AS pct " +
                "FROM users WHERE id = ?", moderatorId);
        stats.addProperty("codigoReferido", textoAdmin(modInfo, "codigo_referido"));
        stats.addProperty("porcentajeCompartido", obtenerMontoJson(modInfo, "pct"));

        JsonObject saldoSum = db.queryOne(
                "SELECT COALESCE(SUM(u.saldo), 0) AS total " +
                "FROM users u " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "WHERE COALESCE(u.referido_por_id, r.referrer_user_id) = ? AND u.id <> ?",
                moderatorId, moderatorId);
        stats.addProperty("totalUsuarios", obtenerMontoJson(saldoSum, "total"));

        JsonObject walletPartidas = db.queryOne(
                "SELECT COALESCE(SUM(monto), 0) AS total FROM moderator_wallet " +
                "WHERE moderator_id = ? AND categoria = 'ganancia'",
                moderatorId);
        JsonObject walletReferidos = db.queryOne(
                "SELECT COALESCE(SUM(monto), 0) AS total FROM moderator_wallet " +
                "WHERE moderator_id = ? AND categoria = 'referidos'",
                moderatorId);
        BigDecimal gananciaPartidas = obtenerMontoJson(walletPartidas, "total");
        BigDecimal gananciaReferidos = obtenerMontoJson(walletReferidos, "total");
        BigDecimal totalBilletera = gananciaPartidas.add(gananciaReferidos);

        stats.addProperty("gananciaComisionesModerador", gananciaPartidas);
        stats.addProperty("gananciaReferidosModerador", gananciaReferidos);
        stats.addProperty("gananciaTotalModerador", totalBilletera);

        JsonObject refConteo = db.queryOne(
                "SELECT COUNT(*) AS total " +
                "FROM users u LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "WHERE COALESCE(u.referido_por_id, r.referrer_user_id) = ? AND u.id <> ?",
                moderatorId, moderatorId);
        JsonObject refPagosSum = db.queryOne(
                "SELECT COALESCE(SUM(monto_base), 0) AS total_base, " +
                "COALESCE(SUM(monto_referidor), 0) AS total_referidor, " +
                "COALESCE(SUM(monto_devuelto_referido), 0) AS total_devuelto " +
                "FROM referral_payouts WHERE referrer_user_id = ?",
                moderatorId);

        JsonObject resumenReferidos = new JsonObject();
        resumenReferidos.addProperty("totalRelaciones",
                refConteo != null && refConteo.has("total") ? refConteo.get("total").getAsLong() : 0L);
        resumenReferidos.addProperty("totalComisionBase", obtenerMontoJson(refPagosSum, "total_base"));
        resumenReferidos.addProperty("totalPagadoReferidores", gananciaReferidos);
        resumenReferidos.addProperty("totalDevueltoReferidos", obtenerMontoJson(refPagosSum, "total_devuelto"));
        stats.add("resumenReferidos", resumenReferidos);

        List<JsonObject> usuarios = db.query(
                "SELECT u.id, u.username, u.email, COALESCE(u.saldo, 0) AS saldo, " +
                "COALESCE(u.tipo_suscripcion, 'free') AS tipo_suscripcion, " +
                "COALESCE(mw_gan.total, 0) AS mod_ganancia_partidas, " +
                "COALESCE(mw_ref.total, 0) AS mod_ganancia_referidos, " +
                "COALESCE(rp_dev.total_devuelto, 0) AS devuelto_referido, " +
                "u.total_partidas, u.total_victorias, u.victorias_normales, u.victorias_disputa, " +
                "u.total_derrotas, u.derrotas_normales, u.derrotas_disputa, " +
                "u.faltas, u.salidas_chat " +
                "FROM users u " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "LEFT JOIN (SELECT jugador_id, SUM(monto) AS total FROM moderator_wallet " +
                "           WHERE moderator_id = ? AND categoria = 'ganancia' GROUP BY jugador_id) mw_gan " +
                "  ON mw_gan.jugador_id = u.id " +
                "LEFT JOIN (SELECT jugador_id, SUM(monto) AS total FROM moderator_wallet " +
                "           WHERE moderator_id = ? AND categoria = 'referidos' GROUP BY jugador_id) mw_ref " +
                "  ON mw_ref.jugador_id = u.id " +
                "LEFT JOIN (SELECT jugador_id, SUM(monto_devuelto_referido) AS total_devuelto FROM referral_payouts " +
                "           WHERE referrer_user_id = ? GROUP BY jugador_id) rp_dev " +
                "  ON rp_dev.jugador_id = u.id " +
                "WHERE COALESCE(u.referido_por_id, r.referrer_user_id) = ? AND u.id <> ? " +
                "ORDER BY COALESCE(u.saldo, 0) DESC, u.id DESC",
                moderatorId, moderatorId, moderatorId, moderatorId, moderatorId);

        JsonArray listaUsuarios = new JsonArray();
        for (JsonObject u : usuarios) {
            BigDecimal genPartidas = normalizarColumnaMonto(u, "mod_ganancia_partidas");
            BigDecimal genReferidos = normalizarColumnaMonto(u, "mod_ganancia_referidos");
            BigDecimal devueltoRef = normalizarColumnaMonto(u, "devuelto_referido");
            BigDecimal totalAportado = genPartidas.add(genReferidos);
            u.addProperty("ganancia_generada", genPartidas);
            u.addProperty("gen_referidos", genReferidos);
            u.addProperty("devuelto_referido", devueltoRef);
            u.addProperty("comision_total_aportada", totalAportado);
            listaUsuarios.add(u);
        }
        stats.add("listaUsuarios", listaUsuarios);
        stats.addProperty("usuariosOnline", io != null ? io.getSockets().size() : 0);
        return stats;
    }

    // ------------------------------------------------------------------
    // Zona de Pruebas / Copia Temporal (respaldada por TEST_DATABASE_URL)
    // ------------------------------------------------------------------

    /**
     * Devuelve el estado completo de la zona de pruebas, la zona en la que está operando el admin
     * y el listado de usuarios con su {@code tipo_suscripcion} para cambiarlos entre {@code free} y {@code prueba}.
     */
    public JsonObject estadoZonaPruebas(int adminUserId) {
        if (esModerador(adminUserId)) {
            throw ServicioException.prohibido("Solo los administradores pueden acceder a la zona de pruebas");
        }
        ConexionDB connDb = obtenerConexion();
        boolean disponible = connDb != null && connDb.tieneZonaPruebas();
        String zonaActiva = ConexionDB.zonaActual();
        if (connDb != null && adminUserId > 0) {
            zonaActiva = connDb.resolverZonaUsuario(adminUserId);
        }

        JsonObject res = new JsonObject();
        res.addProperty("disponible", disponible);
        res.addProperty("zonaActiva", zonaActiva);
        res.addProperty("enZonaPruebas", ConexionDB.ZONA_PRUEBA.equals(zonaActiva));
        res.add("resumen", connDb != null ? connDb.resumenZonaPruebas() : new JsonObject());

        Map<Integer, BigDecimal> saldosPrueba = new HashMap<>();
        if (disponible) {
            try (Connection connPrueba = connDb.getConnectionPruebas();
                 PreparedStatement ps = connPrueba.prepareStatement("SELECT id, saldo FROM users");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal s = rs.getBigDecimal("saldo");
                    saldosPrueba.put(rs.getInt("id"), s != null ? s : BigDecimal.ZERO);
                }
            } catch (Exception ignored) {
            }
        }

        JsonArray usuariosArr = new JsonArray();
        if (connDb != null) {
            try (Connection connPrin = connDb.getConnectionPrincipal();
                 PreparedStatement ps = connPrin.prepareStatement(
                         "SELECT u.id, u.username, u.email, u.player_tag, u.saldo, u.tipo_suscripcion, " +
                         "u.zona_admin, u.estado, u.codigo_referido, u.referido_por_id, " +
                         "ur.username AS referido_por_username " +
                         "FROM users u " +
                         "LEFT JOIN users ur ON ur.id = u.referido_por_id " +
                         "ORDER BY u.id ASC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String tipo = rs.getString("tipo_suscripcion");
                    JsonObject u = new JsonObject();
                    u.addProperty("id", id);
                    u.addProperty("username", rs.getString("username"));
                    u.addProperty("email", rs.getString("email"));
                    u.addProperty("player_tag", rs.getString("player_tag"));
                    BigDecimal saldoReal = rs.getBigDecimal("saldo");
                    u.addProperty("saldo", saldoReal != null ? saldoReal : BigDecimal.ZERO);
                    u.addProperty("saldo_prueba", saldosPrueba.getOrDefault(id, BigDecimal.ZERO));
                    u.addProperty("tipo_suscripcion", tipo != null ? tipo : ConexionDB.SUSCRIPCION_FREE);
                    u.addProperty("zona_admin", rs.getString("zona_admin"));
                    u.addProperty("estado", rs.getString("estado"));
                    u.addProperty("codigo_referido", rs.getString("codigo_referido"));
                    Object refPorObj = rs.getObject("referido_por_id");
                    Number refPorNum = refPorObj instanceof Number num ? num.intValue() : null;
                    u.addProperty("referido_por_id", refPorNum);
                    u.addProperty("referido_por_username", rs.getString("referido_por_username"));
                    u.addProperty("en_copia_pruebas", saldosPrueba.containsKey(id));
                    usuariosArr.add(u);
                }
            } catch (Exception e) {
                throw ServicioException.interno("No se pudo consultar el listado de usuarios para la zona de pruebas", e);
            }
        } else {
            ArrayList<JsonObject> filas = db.query(
                    "SELECT id, username, email, player_tag, saldo, tipo_suscripcion, estado FROM users ORDER BY id ASC");
            for (JsonObject fila : filas) {
                usuariosArr.add(fila);
            }
        }
        res.add("usuarios", usuariosArr);
        return res;
    }

    /**
     * Cambia {@code users.tipo_suscripcion} de un usuario exclusivamente entre {@code free} y {@code prueba}.
     * Solo puede invocarse desde el panel de administración ({@code /admin}).
     */
    public JsonObject cambiarSuscripcionUsuario(int adminUserId, int targetUserId, String tipoSuscripcionCrudo) {
        if (esModerador(adminUserId)) {
            throw ServicioException.prohibido("Solo los administradores pueden cambiar suscripciones");
        }
        if (adminUserId <= 0 || targetUserId <= 0) {
            throw ServicioException.solicitudInvalida("Usuario no válido");
        }
        if (adminUserId == targetUserId) {
            throw ServicioException.solicitudInvalida(
                    "Una cuenta administradora no cambia su rol; usa el selector de zona del panel para alternar entre normal y prueba");
        }
        if (tipoSuscripcionCrudo == null || tipoSuscripcionCrudo.isBlank()) {
            throw ServicioException.solicitudInvalida("Debes indicar el tipo de suscripción ('free' o 'prueba')");
        }
        String nuevoTipo = tipoSuscripcionCrudo.trim().toLowerCase(Locale.ROOT);
        if (!ConexionDB.SUSCRIPCION_FREE.equals(nuevoTipo) && !ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo)) {
            throw ServicioException.solicitudInvalida("El tipo de suscripción solo puede ser 'free' o 'prueba'");
        }

        ConexionDB connDb = obtenerConexion();
        if (ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo) && connDb != null && !connDb.tieneZonaPruebas()) {
            throw ServicioException.solicitudInvalida("La zona de pruebas no está habilitada en este servidor");
        }

        if (connDb != null) {
            String tipoActual;
            String username;
            boolean objetivoEsModerador;
            try (Connection connPrin = connDb.getConnectionPrincipal()) {
                try (PreparedStatement sel = connPrin.prepareStatement(
                        "SELECT username, tipo_suscripcion, COALESCE(zona_admin, 'normal') AS zona_admin, sala_actual FROM users WHERE id = ?")) {
                    sel.setInt(1, targetUserId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            throw ServicioException.noEncontrado("No se encontró el usuario solicitado");
                        }
                        username = rs.getString("username");
                        tipoActual = rs.getString("tipo_suscripcion");
                        String zonaAdminActual = ConexionDB.normalizarZona(rs.getString("zona_admin"));
                        String salaPrincipal = rs.getString("sala_actual");
                        if (ConexionDB.SUSCRIPCION_ADMIN.equalsIgnoreCase(tipoActual)) {
                            throw ServicioException.prohibido("No se puede modificar la suscripción de esta cuenta");
                        }
                        objetivoEsModerador = ConexionDB.SUSCRIPCION_MODERADOR.equalsIgnoreCase(tipoActual);
                        boolean estabaEnPruebas = ConexionDB.SUSCRIPCION_PRUEBA.equalsIgnoreCase(tipoActual)
                                || (objetivoEsModerador && ConexionDB.ZONA_PRUEBA.equalsIgnoreCase(zonaAdminActual));
                        if (salaPrincipal != null && !salaPrincipal.isBlank() && !estabaEnPruebas) {
                            throw ServicioException.solicitudInvalida(
                                    "El usuario tiene una sala o partida activa en la zona normal; espera a que termine antes de pasarlo a prueba");
                        }
                    }
                }

                if (connDb.tieneZonaPruebas() && (ConexionDB.SUSCRIPCION_PRUEBA.equalsIgnoreCase(tipoActual)
                        || objetivoEsModerador)) {
                    try (Connection connPrueba = connDb.getConnectionPruebas();
                         PreparedStatement psPrueba = connPrueba.prepareStatement(
                                 "SELECT sala_actual FROM users WHERE id = ?")) {
                        psPrueba.setInt(1, targetUserId);
                        try (ResultSet rsP = psPrueba.executeQuery()) {
                            if (rsP.next()) {
                                String salaPrueba = rsP.getString("sala_actual");
                                if (salaPrueba != null && !salaPrueba.isBlank()
                                        && !ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo)) {
                                    throw ServicioException.solicitudInvalida(
                                            "El usuario tiene una sala o partida activa en la zona de pruebas; espera a que termine antes de cambiar su suscripción");
                                }
                            }
                        }
                    }
                }

                if (objetivoEsModerador) {
                    String zonaDestinoMod = ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo)
                            ? ConexionDB.ZONA_PRUEBA : ConexionDB.ZONA_NORMAL;
                    try (PreparedStatement upd = connPrin.prepareStatement(
                            "UPDATE users SET zona_admin = ? WHERE id = ? AND LOWER(COALESCE(tipo_suscripcion, '')) = 'moderador'")) {
                        upd.setString(1, zonaDestinoMod);
                        upd.setInt(2, targetUserId);
                        if (upd.executeUpdate() != 1) {
                            throw ServicioException.solicitudInvalida("No se pudo actualizar la zona del usuario");
                        }
                    }
                } else {
                    try (PreparedStatement upd = connPrin.prepareStatement(
                            "UPDATE users SET tipo_suscripcion = ? WHERE id = ? AND tipo_suscripcion NOT IN ('admin', 'moderador')")) {
                        upd.setString(1, nuevoTipo);
                        upd.setInt(2, targetUserId);
                        if (upd.executeUpdate() != 1) {
                            throw ServicioException.solicitudInvalida("No se pudo actualizar la suscripción del usuario");
                        }
                    }
                }
            } catch (ServicioException e) {
                throw e;
            } catch (Exception e) {
                throw ServicioException.interno("No se pudo actualizar el tipo de suscripción", e);
            }

            connDb.invalidarCacheUsuario(targetUserId);
            connDb.sincronizarUsuarioConPruebas(targetUserId);
            String nuevaZona = ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo)
                    ? ConexionDB.ZONA_PRUEBA : ConexionDB.ZONA_NORMAL;
            if (socketHandler != null) {
                socketHandler.notificarCambioZonaUsuario(targetUserId, nuevaZona);
            }

            String tipoResultado = objetivoEsModerador ? ConexionDB.SUSCRIPCION_MODERADOR : nuevoTipo;
            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.addProperty("userId", targetUserId);
            res.addProperty("username", username);
            res.addProperty("tipoSuscripcion", tipoResultado);
            res.addProperty("zona_admin", nuevaZona);
            res.addProperty("zonaAsignada", nuevaZona);
            res.addProperty("message", ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo)
                    ? "Usuario " + username + " asignado a la copia temporal de pruebas"
                    : "Usuario " + username + " devuelto a la zona normal");
            return res;
        }

        // Fallback para pruebas unitarias con GenericDAO mockeado sin ConexionDB
        JsonObject actual = db.queryOne("SELECT id, username, tipo_suscripcion FROM users WHERE id = ?", targetUserId);
        if (actual == null) {
            throw ServicioException.noEncontrado("No se encontró el usuario solicitado");
        }
        String tipoActual = actual.has("tipo_suscripcion") && !actual.get("tipo_suscripcion").isJsonNull()
                ? actual.get("tipo_suscripcion").getAsString() : "";
        if (ConexionDB.SUSCRIPCION_ADMIN.equalsIgnoreCase(tipoActual)) {
            throw ServicioException.prohibido("No se puede modificar la suscripción de esta cuenta");
        }
        boolean objetivoEsModerador = ConexionDB.SUSCRIPCION_MODERADOR.equalsIgnoreCase(tipoActual);
        String nuevaZona = ConexionDB.SUSCRIPCION_PRUEBA.equals(nuevoTipo)
                ? ConexionDB.ZONA_PRUEBA : ConexionDB.ZONA_NORMAL;
        int filas = objetivoEsModerador
                ? db.update("UPDATE users SET zona_admin = ? WHERE id = ?", nuevaZona, targetUserId)
                : db.update("UPDATE users SET tipo_suscripcion = ? WHERE id = ?", nuevoTipo, targetUserId);
        if (filas != 1) {
            throw ServicioException.solicitudInvalida("No se pudo actualizar la suscripción del usuario");
        }
        String tipoResultado = objetivoEsModerador ? ConexionDB.SUSCRIPCION_MODERADOR : nuevoTipo;
        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.addProperty("userId", targetUserId);
        res.addProperty("tipoSuscripcion", tipoResultado);
        res.addProperty("zona_admin", nuevaZona);
        res.addProperty("zonaAsignada", nuevaZona);
        res.addProperty("message", "Suscripción actualizada a '" + tipoResultado + "'");
        return res;
    }

    /**
     * Permite al administrador elegir en qué zona operar ({@code normal} o {@code prueba}).
     */
    public JsonObject cambiarZonaAdmin(int adminUserId, String zonaCruda) {
        if (esModerador(adminUserId)) {
            throw ServicioException.prohibido("Solo un administrador puede cambiar de zona");
        }
        if (adminUserId <= 0) {
            throw ServicioException.noAutenticado("Inicia sesión como administrador");
        }
        if (zonaCruda == null || zonaCruda.isBlank()) {
            throw ServicioException.solicitudInvalida("Debes indicar la zona ('normal' o 'prueba')");
        }
        String zonaLimpia = zonaCruda.trim().toLowerCase(Locale.ROOT);
        if (!ConexionDB.ZONA_NORMAL.equals(zonaLimpia) && !ConexionDB.ZONA_PRUEBA.equals(zonaLimpia)) {
            throw ServicioException.solicitudInvalida("La zona solo puede ser 'normal' o 'prueba'");
        }

        ConexionDB connDb = obtenerConexion();
        if (ConexionDB.ZONA_PRUEBA.equals(zonaLimpia) && connDb != null && !connDb.tieneZonaPruebas()) {
            throw ServicioException.solicitudInvalida("La zona de pruebas no está habilitada en este servidor");
        }

        if (connDb != null) {
            try (Connection connPrin = connDb.getConnectionPrincipal();
                 PreparedStatement ps = connPrin.prepareStatement(
                         "UPDATE users SET zona_admin = ? WHERE id = ? AND tipo_suscripcion = 'admin'")) {
                ps.setString(1, zonaLimpia);
                ps.setInt(2, adminUserId);
                if (ps.executeUpdate() != 1) {
                    throw ServicioException.prohibido("Solo un administrador puede cambiar de zona");
                }
            } catch (ServicioException e) {
                throw e;
            } catch (Exception e) {
                throw ServicioException.interno("No se pudo cambiar la zona activa del administrador", e);
            }
            connDb.invalidarCacheUsuario(adminUserId);
            connDb.sincronizarUsuarioConPruebas(adminUserId);
            ConexionDB.fijarZonaActual(zonaLimpia);
            if (socketHandler != null) {
                socketHandler.notificarCambioZonaUsuario(adminUserId, zonaLimpia);
            }
        } else {
            int filas = db.update("UPDATE users SET zona_admin = ? WHERE id = ? AND tipo_suscripcion = 'admin'",
                    zonaLimpia, adminUserId);
            if (filas != 1) {
                throw ServicioException.prohibido("Solo un administrador puede cambiar de zona");
            }
            ConexionDB.fijarZonaActual(zonaLimpia);
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.addProperty("zonaActiva", zonaLimpia);
        res.addProperty("zona_activa", zonaLimpia);
        res.addProperty("enZonaPruebas", ConexionDB.ZONA_PRUEBA.equals(zonaLimpia));
        res.addProperty("en_zona_pruebas", ConexionDB.ZONA_PRUEBA.equals(zonaLimpia));
        String msg = ConexionDB.ZONA_PRUEBA.equals(zonaLimpia)
                ? "Ahora estás operando en la Zona de Pruebas (copia temporal)"
                : "Ahora estás operando en la Zona Normal (producción)";
        res.addProperty("message", msg);
        res.addProperty("mensaje", msg);
        return res;
    }

    /**
     * Borra todos los datos acumulados en la zona de pruebas (mensajes, partidas, transacciones, sorteos,
     * saldos y estadísticas de prueba) por decisión explícita del administrador y vuelve a sincronizar las
     * cuentas con suscripción {@code prueba} y {@code admin} con saldo 0.
     */
    public JsonObject reiniciarZonaPruebas(int adminUserId) {
        if (esModerador(adminUserId)) {
            throw ServicioException.prohibido("Solo los administradores pueden reiniciar la zona de pruebas");
        }
        if (adminUserId <= 0) {
            throw ServicioException.noAutenticado("Inicia sesión como administrador");
        }
        ConexionDB connDb = obtenerConexion();
        if (connDb == null || !connDb.tieneZonaPruebas()) {
            throw ServicioException.solicitudInvalida("La zona de pruebas no está configurada");
        }

        try {
            connDb.reiniciarDatosZonaPruebas();
        } catch (Exception e) {
            throw ServicioException.interno("No se pudieron borrar los datos de la zona de pruebas", e);
        }
        if (socketHandler != null) {
            socketHandler.limpiarEstadoZonaPrueba();
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        String msg = "Todos los datos de la zona de pruebas fueron borrados y reiniciados.";
        res.addProperty("message", msg);
        res.addProperty("mensaje", msg);
        res.add("resumen", connDb.resumenZonaPruebas());
        return res;
    }

    // ------------------------------------------------------------------
    private static BigDecimal obtenerMontoJson(JsonObject fila, String clave) {
        if (fila == null || !fila.has(clave) || fila.get(clave).isJsonNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return fila.get(clave).getAsBigDecimal();
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal normalizarColumnaMonto(JsonObject fila, String clave) {
        BigDecimal valor = obtenerMontoJson(fila, clave);
        fila.addProperty(clave, valor);
        return valor;
    }

    // Transacciones (aprobar / rechazar)
    // ------------------------------------------------------------------

    /**
     * C3: Aprueba ("approve") o rechaza ("reject") una transacción pendiente validando que el actor
     * tenga permiso sobre el usuario de la transacción (moderador solo sus usuarios; admin los propios).
     */
    public String procesarTransaccion(int actorUserId, int transId, String action) {
        if (actorUserId > 0) {
            JsonObject tx = db.queryOne("SELECT usuario_id FROM transactions WHERE id = ?", transId);
            if (tx == null) {
                throw ServicioException.noEncontrado("Transacción no encontrada");
            }
            int txUserId = enteroAdmin(tx, "usuario_id");
            Integer modDueno = txUserId > 0 ? obtenerModeradorDeUsuario(txUserId) : null;
            if (esModerador(actorUserId)) {
                if (modDueno == null || modDueno != actorUserId) {
                    throw ServicioException.prohibido("No tienes permiso para procesar esta transacción");
                }
            } else if (modDueno != null) {
                throw ServicioException.prohibido("Esta transacción pertenece a otra billetera de referidos");
            }
        }
        return procesarTransaccion(transId, action);
    }

    /**
     * C3: Aprueba ("approve") o rechaza ("reject") una transacción pendiente de forma atómica.
     *
     * @return el mensaje para el cliente ("Rechazada" / "Aprobada")
     */
    public String procesarTransaccion(int transId, String action) {
        WalletService.ResultadoTransaccionAdmin res = wallet.procesarTransaccionAdmin(transId, action);

        if (!res.aprobada) {
            if ("retiro".equals(res.tipo)) {
                notificador.notificarSaldo(res.userId, "❌ Retiro rechazado. Saldo devuelto.", res.nuevoSaldo);
            } else {
                notificador.notificarSaldo(res.userId, "❌ Recarga rechazada.", res.nuevoSaldo);
            }
            return "Rechazada";
        }

        if ("deposito".equals(res.tipo)) {
            notificador.notificarSaldo(res.userId, "✅ Recarga aprobada.", res.nuevoSaldo);
        } else {
            notificador.notificarSaldo(res.userId, "✅ Tu retiro ha sido enviado.", res.nuevoSaldo);
        }
        return "Aprobada";
    }

    // ------------------------------------------------------------------
    // Disputas
    // ------------------------------------------------------------------

    /**
     * C2: Resuelve una disputa de forma atómica e idempotente.
     * Exige que la partida esté en estado {@code disputa}, que el ganador sea uno de los dos jugadores
     * de la partida y cierra el match dentro de la misma transacción SQL de {@link WalletService#liquidar}.
     */
    public void resolverDisputa(int matchId, String ganadorNombre, String culpableNombre) {
        JsonObject match = db.queryOne("SELECT * FROM matches WHERE id = ?", matchId);
        if (match == null) throw ServicioException.respuestaConError("No existe");

        String estadoActual = match.has("estado") && !match.get("estado").isJsonNull()
                ? match.get("estado").getAsString() : "";
        if (!"disputa".equals(estadoActual)) {
            throw ServicioException.respuestaConError("Esta disputa ya fue resuelta (" + estadoActual + ")");
        }

        String j1 = match.get("jugador1").getAsString();
        String j2 = match.get("jugador2").getAsString();
        if (ganadorNombre == null || (!ganadorNombre.equals(j1) && !ganadorNombre.equals(j2))) {
            throw ServicioException.respuestaConError("El ganador debe ser uno de los dos jugadores de la partida");
        }
        String culpableSeguro = (culpableNombre == null || culpableNombre.isBlank()) ? "nadie" : culpableNombre;
        if (!"nadie".equals(culpableSeguro) && !culpableSeguro.equals(j1) && !culpableSeguro.equals(j2)) {
            throw ServicioException.respuestaConError("El culpable debe ser uno de los dos jugadores o 'nadie'");
        }

        JsonObject winner = db.queryOne("SELECT id, saldo FROM users WHERE username = ?", ganadorNombre);
        if (winner == null) throw ServicioException.respuestaConError("Ganador no encontrado");

        int winnerId = winner.get("id").getAsNumber().intValue();
        BigDecimal monto = match.get("monto").getAsBigDecimal(); // REVIEW-MONEY

        String perdedor = ganadorNombre.equals(j1) ? j2 : j1;
        JsonObject perdedorData = db.queryOne("SELECT id FROM users WHERE username = ?", perdedor);
        int perdedorId = perdedorData != null ? perdedorData.get("id").getAsNumber().intValue() : 0;

        // C2: Liquidar partida y cerrarla (WHERE id = ? AND estado = 'disputa') en UNA sola transacción // REVIEW-MONEY
        LiquidacionResult liq = wallet.liquidar(winnerId, perdedorId, monto, matchId,
                "comision_disputa", "disputa", ganadorNombre, culpableSeguro);
        if (!liq.success) {
            throw ServicioException.respuestaConError("Error al liquidar: " + liq.error);
        }

        // Liberar jugadores y limpiar cualquier sala activa residual
        db.update("DELETE FROM active_matches WHERE match_db_id = ?", matchId);
        db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE username IN (?, ?)", j1, j2);

        // A4: la liquidación y el match ya están confirmados; se avisa al bus (los listeners acreditan los tickets de sorteo
        // a ambos jugadores y les envían `tickets_ganados`, antes `acreditarTickets` en esta clase). emit() no lanza.
        // Participantes = los dos jugadores del match (no solo el ganador), igual que antes. // REVIEW-MONEY
        List<Integer> participantes = new ArrayList<>();
        for (String nombreJugador : new String[]{j1, j2}) {
            JsonObject jugador = db.queryOne("SELECT id FROM users WHERE username = ?", nombreJugador);
            if (jugador != null) participantes.add(jugador.get("id").getAsNumber().intValue());
        }
        eventos.emit(Eventos.MATCH_FINISHED, new Eventos.PartidaFinalizada(
                matchId, Eventos.Origen.DISPUTA, participantes, winnerId, ganadorNombre,
                monto, liq.premio, liq.nuevoSaldoGanador, liq.desglose));

        // Notificar sockets
        for (SocketIOClient s : io.getSockets().values()) {
            if (s.getUserData() != null) {
                int sId = s.getUserData().get("id").getAsNumber().intValue();
                if (sId == winnerId) {
                    s.getUserData().addProperty("saldo", liq.nuevoSaldoGanador);
                    s.emit("actualizar_saldo", new JsonPrimitive(liq.nuevoSaldoGanador));
                }
                String uname = s.getUserData().get("username").getAsString();
                if (j1.equals(uname) || j2.equals(uname)) {
                    s.emit("flujo_completado");
                    s.getUserData().addProperty("estado", "normal");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * S3: ¿este usuario es admin AHORA? Lo consulta {@code AdminMiddleware} en cada petición a una ruta de admin.
     * Lee {@code users.tipo_suscripcion} de la BD principal (no el claim {@code rol} del JWT, que puede estar viejo).
     * Cualquier duda (id inválido, usuario inexistente, error de BD) devuelve false.
     */
    public boolean esAdmin(int userId) {
        if (userId <= 0) return false;
        if (usuarioDAO != null && usuarioDAO.getConexionDB() != null) {
            return usuarioDAO.esAdminEnPrincipal(userId);
        }
        JsonObject fila = db.queryOne("SELECT tipo_suscripcion FROM users WHERE id = ?", userId);
        if (fila == null || !fila.has("tipo_suscripcion") || fila.get("tipo_suscripcion").isJsonNull()) {
            return false;
        }
        return "admin".equals(fila.get("tipo_suscripcion").getAsString());
    }

    /** ¿Este usuario tiene rol {@code moderador} activo en la BD principal? */
    public boolean esModerador(int userId) {
        if (userId <= 0) return false;
        if (usuarioDAO != null && usuarioDAO.getConexionDB() != null) {
            return usuarioDAO.esModeradorEnPrincipal(userId);
        }
        JsonObject fila = db.queryOne("SELECT tipo_suscripcion FROM users WHERE id = ?", userId);
        if (fila == null || !fila.has("tipo_suscripcion") || fila.get("tipo_suscripcion").isJsonNull()) {
            return false;
        }
        return ConexionDB.SUSCRIPCION_MODERADOR.equalsIgnoreCase(fila.get("tipo_suscripcion").getAsString());
    }

    /**
     * Devuelve el {@code id} del moderador dueño de {@code userId}, o {@code null} si el usuario
     * entró sin referido de moderador (pertenece al administrador principal).
     */
    public Integer obtenerModeradorDeUsuario(int userId) {
        if (userId <= 0) return null;
        JsonObject mod = db.queryOne(
                "SELECT m.id AS moderator_id " +
                "FROM users u " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "JOIN users m ON m.id = COALESCE(u.referido_por_id, r.referrer_user_id) " +
                "WHERE u.id = ? AND m.id <> u.id AND LOWER(COALESCE(m.tipo_suscripcion, '')) = 'moderador' " +
                "LIMIT 1",
                userId);
        if (mod == null || !mod.has("moderator_id") || mod.get("moderator_id").isJsonNull()) {
            return null;
        }
        int id = enteroAdmin(mod, "moderator_id");
        return id > 0 ? id : null;
    }

    private MapaUsuariosModeradores cargarMapaUsuariosDeModeradores() {
        Map<Integer, Integer> porId = new HashMap<>();
        Map<String, Integer> porUser = new HashMap<>();
        List<JsonObject> rows = db.query(
                "SELECT u.id AS user_id, u.username, m.id AS moderator_id " +
                "FROM users u " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "JOIN users m ON m.id = COALESCE(u.referido_por_id, r.referrer_user_id) " +
                "WHERE m.id <> u.id AND LOWER(COALESCE(m.tipo_suscripcion, '')) = 'moderador'");
        for (JsonObject row : rows) {
            int uid = enteroAdmin(row, "user_id");
            int mid = enteroAdmin(row, "moderator_id");
            String uname = textoAdmin(row, "username").toLowerCase(Locale.ROOT);
            if (uid > 0 && mid > 0) {
                porId.put(uid, mid);
            }
            if (!uname.isBlank() && mid > 0) {
                porUser.put(uname, mid);
            }
        }
        return new MapaUsuariosModeradores(porId, porUser);
    }

    /**
     * Resetea el estado de un jugador a normal. S3: ya no verifica aquí si quien lo pide es admin (antes recibía el
     * nombre de un admin por la URL y bastaba con escribir el de cualquiera); eso lo hace {@code AdminMiddleware}
     * con la sesión JWT antes de llegar a este método.
     */
    public void resetearEstado(String targetUser) {
        usuarioDAO.resetearEstado(targetUser);
    }

    /**
     * Historial unificado de movimientos administrativos y de plataforma: recargas/retiros pendientes y resueltos,
     * partidas en curso/disputa/finalizadas con su comisión admin, referidos, premios/sobrantes de ranking,
     * movimientos del pozo de sorteos y depósitos manuales. Si el actor es moderador, devuelve solo los suyos.
     */
    public JsonObject obtenerMovimientosAdmin(int adminUserId) {
        if (esModerador(adminUserId)) {
            return obtenerMovimientosModerador(adminUserId);
        }
        ConexionDB connDb = obtenerConexion();
        if (ConexionDB.esZonaPrueba() && connDb != null && connDb.tieneZonaPruebas()) {
            connDb.sincronizarUsuariosPruebaYAdmin();
        }

        MapaUsuariosModeradores mapaMods = cargarMapaUsuariosDeModeradores();
        List<MovimientoAdminOrdenado> lista = new ArrayList<>();
        ComisionService comisionService = new ComisionService();

        AcumuladorTxMovimientos acumTx = new AcumuladorTxMovimientos();
        int partidasEnCursoCount = 0;
        BigDecimal comisionesPartidasTotal = BigDecimal.ZERO;
        BigDecimal gananciaNetaAdminPartidas = BigDecimal.ZERO;
        BigDecimal referidosSinReferidorTotal = BigDecimal.ZERO;

        // 1. Transacciones de usuarios (recargas y retiros únicamente de usuarios del Admin)
        List<JsonObject> txs = db.query(
                "SELECT id, usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado, fecha " +
                "FROM transactions ORDER BY fecha DESC, id DESC LIMIT 150");
        for (JsonObject tx : txs) {
            int usuarioId = enteroAdmin(tx, "usuario_id");
            if (mapaMods.porUserId().containsKey(usuarioId)) {
                continue;
            }
            agregarMovimientoTransaccion(lista, tx, acumTx);
        }

        // 2. Partidas (en curso, disputa y finalizadas con utilidad efectiva del admin)
        List<JsonObject> matches = db.query(
                "SELECT id, sala_id, jugador1, jugador2, modo, monto, ganador, estado, fecha " +
                "FROM matches ORDER BY fecha DESC, id DESC LIMIT 150");
        for (JsonObject m : matches) {
            int matchId = enteroAdmin(m, "id");
            String j1 = textoAdmin(m, "jugador1");
            String j2 = textoAdmin(m, "jugador2");
            String modo = textoAdmin(m, "modo");
            String ganador = textoAdmin(m, "ganador");
            String estado = textoAdmin(m, "estado");
            String fecha = textoAdmin(m, "fecha");
            BigDecimal montoEntrada = obtenerMontoJson(m, "monto");
            BigDecimal pozo = montoEntrada.multiply(BigDecimal.valueOf(2));

            JsonObject item = new JsonObject();
            item.addProperty("id", "match-" + matchId);
            item.addProperty("matchId", matchId);
            item.addProperty("categoriaMovimiento", "partida");
            item.addProperty("tipo", "partida");
            item.addProperty("estado", estado);
            item.addProperty("fecha", fecha);
            item.addProperty("origen", "Partida #" + matchId + " (@" + j1 + " vs @" + j2
                    + (!modo.isBlank() ? " · " + modo : "") + ")");

            if ("en_curso".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "En curso");
                item.addProperty("signo", "");
                item.addProperty("monto", pozo);
                item.addProperty("titulo", "Partida #" + matchId + " en curso");
                item.addProperty("detalle", "Pozo en juego $" + formatoCopAdmin(pozo)
                        + " ($" + formatoCopAdmin(montoEntrada) + " por jugador)");
                partidasEnCursoCount++;
            } else if ("disputa".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "En disputa");
                item.addProperty("signo", "");
                item.addProperty("monto", pozo);
                item.addProperty("titulo", "Partida #" + matchId + " en disputa");
                item.addProperty("detalle", "Requiere resolución del administrador · Pozo $" + formatoCopAdmin(pozo));
                partidasEnCursoCount++;
            } else if ("Cancelada".equalsIgnoreCase(ganador)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "Cancelada");
                item.addProperty("signo", "");
                item.addProperty("monto", montoEntrada);
                item.addProperty("titulo", "Partida #" + matchId + " cancelada");
                item.addProperty("detalle", "Entradas reembolsadas a @" + j1 + " y @" + j2 + " (sin comisión)");
            } else {
                ComisionService.Desglose d = comisionService.calcular(montoEntrada);
                Integer modJ1 = mapaMods.porUsernameLower().get(j1.toLowerCase(Locale.ROOT));
                Integer modJ2 = mapaMods.porUsernameLower().get(j2.toLowerCase(Locale.ROOT));
                BigDecimal mitadBase = d.porJugador(ComisionService.Categoria.GANANCIA);
                BigDecimal gananciaBase = d.getGananciaBaseAdmin();
                if (modJ1 != null) gananciaBase = gananciaBase.subtract(mitadBase);
                if (modJ2 != null) gananciaBase = gananciaBase.subtract(mitadBase);
                if (gananciaBase.compareTo(BigDecimal.ZERO) < 0) {
                    gananciaBase = BigDecimal.ZERO;
                }
                BigDecimal gananciaInactivas = d.getGananciaInactivasAdmin();
                BigDecimal gananciaAdmin = gananciaBase.add(gananciaInactivas);
                BigDecimal comisionTotal = d.getComisionTotal();
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Finalizada");
                item.addProperty("signo", "+");
                item.addProperty("monto", gananciaAdmin);
                item.addProperty("gananciaBaseAdmin", gananciaBase);
                item.addProperty("gananciaInactivasAdmin", gananciaInactivas);
                item.addProperty("montoSecundario", comisionTotal);
                item.addProperty("titulo", "Comisión Partida #" + matchId + " (Ganó @" + ganador + ")");
                item.addProperty("detalle", "Ganancia admin $" + formatoCopAdmin(gananciaAdmin)
                        + " (Base $" + formatoCopAdmin(gananciaBase)
                        + " + Inactivas $" + formatoCopAdmin(gananciaInactivas)
                        + ": Misiones $" + formatoCopAdmin(d.getMonto(ComisionService.Categoria.MISIONES))
                        + ", Logros $" + formatoCopAdmin(d.getMonto(ComisionService.Categoria.LOGROS))
                        + ", Devolución $" + formatoCopAdmin(d.getMonto(ComisionService.Categoria.DEVOLUCION))
                        + ") · Comisión total $" + formatoCopAdmin(comisionTotal)
                        + " (Sorteos $" + formatoCopAdmin(d.getMonto(ComisionService.Categoria.SORTEOS))
                        + ", Ranking $" + formatoCopAdmin(d.getMonto(ComisionService.Categoria.LEADERBOARD))
                        + ", Referidos $" + formatoCopAdmin(d.getMonto(ComisionService.Categoria.REFERIDOS))
                        + ") · Premio jugador $" + formatoCopAdmin(d.getPremio()));
                comisionesPartidasTotal = comisionesPartidasTotal.add(comisionTotal);
                gananciaNetaAdminPartidas = gananciaNetaAdminPartidas.add(gananciaAdmin);
            }
            lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), matchId, item));
        }

        // 3. Reparto de comisiones de referidos (referidores y cuentas admin cuando no hay referidor)
        List<JsonObject> refs = db.query(
                "SELECT rp.id, rp.match_id, rp.destino_tipo, rp.monto_referidor, rp.monto_devuelto_referido, " +
                "rp.monto_admin, rp.porcentaje_compartido, rp.creado_en, " +
                "uj.username AS jugador_username, ur.username AS referidor_username " +
                "FROM referral_payouts rp " +
                "LEFT JOIN users uj ON uj.id = rp.jugador_id " +
                "LEFT JOIN users ur ON ur.id = rp.referrer_user_id " +
                "ORDER BY rp.creado_en DESC, rp.id DESC LIMIT 120");
        for (JsonObject rp : refs) {
            int rpId = enteroAdmin(rp, "id");
            int matchId = enteroAdmin(rp, "match_id");
            String destino = textoAdmin(rp, "destino_tipo");
            if ("moderador".equalsIgnoreCase(destino)) {
                continue;
            }
            BigDecimal montoRef = obtenerMontoJson(rp, "monto_referidor");
            BigDecimal montoDev = obtenerMontoJson(rp, "monto_devuelto_referido");
            BigDecimal montoAdmin = obtenerMontoJson(rp, "monto_admin");
            BigDecimal pct = obtenerMontoJson(rp, "porcentaje_compartido");
            String jugUsername = textoAdmin(rp, "jugador_username");
            String refUsername = textoAdmin(rp, "referidor_username");
            String fecha = textoAdmin(rp, "creado_en");
            String tagJug = !jugUsername.isBlank() ? "@" + jugUsername : "jugador";
            String tagRef = !refUsername.isBlank() ? "@" + refUsername : "referidor";

            JsonObject item = new JsonObject();
            item.addProperty("id", "ref-" + rpId);
            item.addProperty("categoriaMovimiento", "referido");
            item.addProperty("fecha", fecha);

            if ("admin".equalsIgnoreCase(destino)) {
                item.addProperty("tipo", "referido_admin");
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Ingreso admin");
                item.addProperty("signo", "+");
                item.addProperty("monto", montoAdmin);
                if (!refUsername.isBlank()) {
                    item.addProperty("titulo", "Comisión de referido (" + tagRef + " · panel admin)");
                    item.addProperty("origen", "Referidos · " + tagJug + " -> " + tagRef
                            + (matchId > 0 ? " (Partida #" + matchId + ")" : ""));
                    item.addProperty("detalle", "La cuota de referidos de " + tagJug
                            + " se registró en el panel del administrador");
                } else {
                    item.addProperty("titulo", "Referido sin invitador (panel admin)");
                    item.addProperty("origen", "Referidos · " + tagJug
                            + (matchId > 0 ? " (Partida #" + matchId + ")" : ""));
                    item.addProperty("detalle", "La cuota de referidos de " + tagJug
                            + " se registró en el panel del administrador por no tener referidor");
                }
                referidosSinReferidorTotal = referidosSinReferidorTotal.add(montoAdmin);
                lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), rpId, item));
            } else {
                BigDecimal totalRepartido = montoRef.add(montoDev);
                if (totalRepartido.compareTo(BigDecimal.ZERO) > 0) {
                    item.addProperty("tipo", "referido_reparto");
                    item.addProperty("estadoVisual", "negativo");
                    item.addProperty("estadoTexto", "Comisión repartida");
                    item.addProperty("signo", "-");
                    item.addProperty("monto", totalRepartido);
                    item.addProperty("titulo", "Comisión de referido · " + tagRef);
                    item.addProperty("origen", "Referidos · Invitado " + tagJug + " -> " + tagRef
                            + (matchId > 0 ? " (Partida #" + matchId + ")" : ""));
                    item.addProperty("detalle", "Referidor $" + formatoCopAdmin(montoRef)
                            + (montoDev.compareTo(BigDecimal.ZERO) > 0
                                    ? " · Devolución a " + tagJug + " $" + formatoCopAdmin(montoDev)
                                      + " (" + pct.stripTrailingZeros().toPlainString() + "%)"
                                    : ""));
                    lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), rpId, item));
                }
            }
        }

        // 4. Pagos y sobrantes de ranking
        List<JsonObject> lbPagos = db.query(
                "SELECT lcp.id, lcp.tipo_pago, lcp.username, lcp.posicion, lcp.monto, lcp.estado, lcp.creado_en, lcp.pagado_en, " +
                "lc.periodo, lc.clave_corte " +
                "FROM leaderboard_cut_payments lcp " +
                "JOIN leaderboard_cuts lc ON lc.id = lcp.corte_id " +
                "ORDER BY COALESCE(lcp.pagado_en, lcp.creado_en) DESC, lcp.id DESC LIMIT 100");
        for (JsonObject lb : lbPagos) {
            int lbId = enteroAdmin(lb, "id");
            String tipoPago = textoAdmin(lb, "tipo_pago");
            String username = textoAdmin(lb, "username");
            int posicion = enteroAdmin(lb, "posicion");
            BigDecimal monto = obtenerMontoJson(lb, "monto");
            String estado = textoAdmin(lb, "estado");
            String periodo = textoAdmin(lb, "periodo");
            String claveCorte = textoAdmin(lb, "clave_corte");
            String pagadoEn = textoAdmin(lb, "pagado_en");
            String creadoEn = textoAdmin(lb, "creado_en");
            String fecha = !pagadoEn.isBlank() ? pagadoEn : creadoEn;
            boolean esAdminPago = "admin".equalsIgnoreCase(tipoPago);
            boolean pagado = "pagado".equalsIgnoreCase(estado);

            JsonObject item = new JsonObject();
            item.addProperty("id", "lb-" + lbId);
            item.addProperty("categoriaMovimiento", "ranking");
            item.addProperty("tipo", "ranking");
            item.addProperty("estadoVisual", !pagado ? "pendiente" : (esAdminPago ? "positivo" : "negativo"));
            item.addProperty("estadoTexto", !pagado ? "Pendiente" : (esAdminPago ? "Sobrante admin" : "Premio pagado"));
            item.addProperty("signo", esAdminPago ? "+" : "-");
            item.addProperty("monto", monto);
            item.addProperty("titulo", esAdminPago
                    ? "Sobrante de Ranking (" + periodo + ") -> @" + username
                    : "Premio Ranking #" + posicion + " (" + periodo + ") -> @" + username);
            item.addProperty("origen", "Ranking " + periodo + (!claveCorte.isBlank() ? " · " + claveCorte : ""));
            item.addProperty("detalle", esAdminPago
                    ? "Sobrante de pozo de ranking registrado en panel admin"
                    : "Pago a jugador por posición #" + posicion + " en corte " + claveCorte);
            item.addProperty("fecha", fecha);
            lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), lbId, item));
        }

        // 5. Movimientos del pozo de sorteos
        List<JsonObject> pozoSorteos = db.query(
                "SELECT id, tipo, variacion, referencia, fecha FROM raffle_pool_movements " +
                "ORDER BY fecha DESC, id DESC LIMIT 100");
        for (JsonObject ps : pozoSorteos) {
            int psId = enteroAdmin(ps, "id");
            String tipo = textoAdmin(ps, "tipo");
            BigDecimal variacion = obtenerMontoJson(ps, "variacion");
            String ref = textoAdmin(ps, "referencia");
            String fecha = textoAdmin(ps, "fecha");
            boolean positivo = variacion.compareTo(BigDecimal.ZERO) >= 0;

            JsonObject item = new JsonObject();
            item.addProperty("id", "raffle-pool-" + psId);
            item.addProperty("categoriaMovimiento", "sorteos");
            item.addProperty("tipo", "sorteo_pozo");
            item.addProperty("estadoVisual", positivo ? "positivo" : "negativo");
            item.addProperty("estadoTexto", positivo ? "Ingreso pozo" : "Uso pozo");
            item.addProperty("signo", positivo ? "+" : "-");
            item.addProperty("monto", variacion.abs());
            item.addProperty("titulo", tituloPozoSorteo(tipo));
            item.addProperty("origen", "Pozo de Sorteos · " + tipo);
            item.addProperty("detalle", ref);
            item.addProperty("fecha", fecha);
            lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), psId, item));
        }

        // 6. Depósitos manuales directos del admin desde ledger (excluye usuarios de moderadores)
        List<JsonObject> manuales = db.query(
                "SELECT wl.id, wl.monto, wl.referencia, wl.fecha, u.username " +
                "FROM wallet_ledger wl JOIN users u ON u.id = wl.user_id " +
                "WHERE wl.tipo = 'deposito' AND wl.referencia = 'Depósito manual admin' " +
                "ORDER BY wl.fecha DESC, wl.id DESC LIMIT 80");
        for (JsonObject man : manuales) {
            int wlId = enteroAdmin(man, "id");
            BigDecimal monto = obtenerMontoJson(man, "monto");
            String username = textoAdmin(man, "username");
            if (mapaMods.porUsernameLower().containsKey(username.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String fecha = textoAdmin(man, "fecha");

            JsonObject item = new JsonObject();
            item.addProperty("id", "manual-" + wlId);
            item.addProperty("categoriaMovimiento", "recarga");
            item.addProperty("tipo", "deposito_manual");
            item.addProperty("estadoVisual", "positivo");
            item.addProperty("estadoTexto", "Acreditado");
            item.addProperty("signo", "+");
            item.addProperty("monto", monto);
            item.addProperty("titulo", "Depósito manual admin a @" + username);
            item.addProperty("origen", "Admin manual · @" + username);
            item.addProperty("detalle", "Acreditación directa desde panel admin");
            item.addProperty("fecha", fecha);
            acumTx.recargasAprobadasTotal = acumTx.recargasAprobadasTotal.add(monto);
            lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), wlId, item));
        }

        lista.sort(Comparator.comparing(MovimientoAdminOrdenado::instante).reversed()
                .thenComparing(Comparator.comparingLong(MovimientoAdminOrdenado::tieId).reversed()));

        JsonArray arr = new JsonArray();
        for (MovimientoAdminOrdenado m : lista) {
            arr.add(m.datos());
        }

        JsonObject resumen = new JsonObject();
        resumen.addProperty("pendientesAccionCount", acumTx.pendientesAccionCount);
        resumen.addProperty("partidasEnCursoCount", partidasEnCursoCount);
        resumen.addProperty("comisionesPartidasTotal", comisionesPartidasTotal);
        resumen.addProperty("gananciaNetaAdminPartidas", gananciaNetaAdminPartidas);
        resumen.addProperty("referidosSinReferidorTotal", referidosSinReferidorTotal);
        resumen.addProperty("recargasAprobadasTotal", acumTx.recargasAprobadasTotal);
        resumen.addProperty("retirosEnviadosTotal", acumTx.retirosEnviadosTotal);
        resumen.addProperty("totalMovimientos", arr.size());

        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("esModerador", false);
        resp.addProperty("zonaActiva", ConexionDB.zonaActual());
        resp.addProperty("enZonaPruebas", ConexionDB.esZonaPrueba());
        resp.add("movimientos", arr);
        resp.add("resumen", resumen);
        return resp;
    }

    /**
     * Movimientos aislados para una cuenta moderador: recargas/retiros de sus usuarios,
     * partidas donde participan sus jugadores (con su ganancia en {@code moderator_wallet})
     * y comisiones de referidos acreditadas a su billetera. Sin ranking, sorteos ni inactivas.
     */
    private JsonObject obtenerMovimientosModerador(int moderatorId) {
        ConexionDB connDb = obtenerConexion();
        if (ConexionDB.esZonaPrueba() && connDb != null && connDb.tieneZonaPruebas()) {
            connDb.sincronizarUsuariosPruebaYAdmin();
        }
        List<MovimientoAdminOrdenado> lista = new ArrayList<>();
        AcumuladorTxMovimientos acumTx = new AcumuladorTxMovimientos();
        int partidasEnCursoCount = 0;
        BigDecimal gananciaPartidas = BigDecimal.ZERO;
        BigDecimal gananciaReferidos = BigDecimal.ZERO;

        // 1. Transacciones únicamente de los usuarios del moderador
        List<JsonObject> txs = db.query(
                "SELECT t.id, t.usuario_id, t.usuario_nombre, t.tipo, t.metodo, t.monto, t.referencia, t.estado, t.fecha " +
                "FROM transactions t " +
                "JOIN users u ON u.id = t.usuario_id " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "WHERE COALESCE(u.referido_por_id, r.referrer_user_id) = ? AND u.id <> ? " +
                "ORDER BY t.fecha DESC, t.id DESC LIMIT 150",
                moderatorId, moderatorId);
        for (JsonObject tx : txs) {
            agregarMovimientoTransaccion(lista, tx, acumTx);
        }

        // 2. Partidas en las que participa al menos un jugador del moderador
        List<JsonObject> matches = db.query(
                "SELECT m.id, m.sala_id, m.jugador1, m.jugador2, m.modo, m.monto, m.ganador, m.estado, m.fecha, " +
                "COALESCE((SELECT SUM(mw.monto) FROM moderator_wallet mw " +
                "          WHERE mw.match_id = m.id AND mw.moderator_id = ? AND mw.categoria = 'ganancia'), 0) AS comision_moderador " +
                "FROM matches m " +
                "WHERE EXISTS (" +
                "  SELECT 1 FROM users u " +
                "  LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "  WHERE LOWER(u.username) IN (LOWER(m.jugador1), LOWER(m.jugador2)) " +
                "    AND COALESCE(u.referido_por_id, r.referrer_user_id) = ? " +
                "    AND u.id <> ?" +
                ") " +
                "ORDER BY m.fecha DESC, m.id DESC LIMIT 150",
                moderatorId, moderatorId, moderatorId);
        for (JsonObject m : matches) {
            int matchId = enteroAdmin(m, "id");
            String j1 = textoAdmin(m, "jugador1");
            String j2 = textoAdmin(m, "jugador2");
            String modo = textoAdmin(m, "modo");
            String ganador = textoAdmin(m, "ganador");
            String estado = textoAdmin(m, "estado");
            String fecha = textoAdmin(m, "fecha");
            BigDecimal montoEntrada = obtenerMontoJson(m, "monto");
            BigDecimal pozo = montoEntrada.multiply(BigDecimal.valueOf(2));

            JsonObject item = new JsonObject();
            item.addProperty("id", "match-" + matchId);
            item.addProperty("matchId", matchId);
            item.addProperty("categoriaMovimiento", "partida");
            item.addProperty("tipo", "partida");
            item.addProperty("estado", estado);
            item.addProperty("fecha", fecha);
            item.addProperty("origen", "Partida #" + matchId + " (@" + j1 + " vs @" + j2
                    + (!modo.isBlank() ? " · " + modo : "") + ")");

            if ("en_curso".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "En curso");
                item.addProperty("signo", "");
                item.addProperty("monto", pozo);
                item.addProperty("titulo", "Partida #" + matchId + " en curso");
                item.addProperty("detalle", "Pozo en juego $" + formatoCopAdmin(pozo)
                        + " ($" + formatoCopAdmin(montoEntrada) + " por jugador)");
                partidasEnCursoCount++;
            } else if ("disputa".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "En disputa");
                item.addProperty("signo", "");
                item.addProperty("monto", pozo);
                item.addProperty("titulo", "Partida #" + matchId + " en disputa");
                item.addProperty("detalle", "En revisión de soporte · Pozo $" + formatoCopAdmin(pozo));
                partidasEnCursoCount++;
            } else if ("Cancelada".equalsIgnoreCase(ganador)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "Cancelada");
                item.addProperty("signo", "");
                item.addProperty("monto", montoEntrada);
                item.addProperty("titulo", "Partida #" + matchId + " cancelada");
                item.addProperty("detalle", "Entradas reembolsadas a @" + j1 + " y @" + j2 + " (sin comisión)");
            } else {
                BigDecimal comisionMod = obtenerMontoJson(m, "comision_moderador");
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Finalizada");
                item.addProperty("signo", "+");
                item.addProperty("monto", comisionMod);
                item.addProperty("titulo", "Comisión Partida #" + matchId + " (Ganó @" + ganador + ")");
                item.addProperty("detalle", "Comisión acreditada a tu billetera: $" + formatoCopAdmin(comisionMod)
                        + " · Pozo $" + formatoCopAdmin(pozo));
                gananciaPartidas = gananciaPartidas.add(comisionMod);
            }
            lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), matchId, item));
        }

        // 3. Comisiones de referidos acreditadas a su billetera
        List<JsonObject> refs = db.query(
                "SELECT rp.id, rp.match_id, rp.monto_referidor, rp.monto_devuelto_referido, " +
                "rp.porcentaje_compartido, rp.creado_en, uj.username AS jugador_username " +
                "FROM referral_payouts rp " +
                "LEFT JOIN users uj ON uj.id = rp.jugador_id " +
                "WHERE rp.referrer_user_id = ? AND rp.destino_tipo = 'moderador' " +
                "ORDER BY rp.creado_en DESC, rp.id DESC LIMIT 120",
                moderatorId);
        for (JsonObject rp : refs) {
            int rpId = enteroAdmin(rp, "id");
            int matchId = enteroAdmin(rp, "match_id");
            BigDecimal montoRef = obtenerMontoJson(rp, "monto_referidor");
            BigDecimal montoDev = obtenerMontoJson(rp, "monto_devuelto_referido");
            BigDecimal pct = obtenerMontoJson(rp, "porcentaje_compartido");
            String jugUsername = textoAdmin(rp, "jugador_username");
            String fecha = textoAdmin(rp, "creado_en");
            String tagJug = !jugUsername.isBlank() ? "@" + jugUsername : "jugador";

            if (montoRef.compareTo(BigDecimal.ZERO) > 0) {
                JsonObject item = new JsonObject();
                item.addProperty("id", "ref-" + rpId);
                item.addProperty("categoriaMovimiento", "referido");
                item.addProperty("tipo", "referido_moderador");
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Ingreso referido");
                item.addProperty("signo", "+");
                item.addProperty("monto", montoRef);
                item.addProperty("titulo", "Comisión de referido · " + tagJug);
                item.addProperty("origen", "Referidos · Invitado " + tagJug
                        + (matchId > 0 ? " (Partida #" + matchId + ")" : ""));
                item.addProperty("detalle", "Acreditado a tu billetera $" + formatoCopAdmin(montoRef)
                        + (montoDev.compareTo(BigDecimal.ZERO) > 0
                                ? " · Devolución a " + tagJug + " $" + formatoCopAdmin(montoDev)
                                  + " (" + pct.stripTrailingZeros().toPlainString() + "%)"
                                : ""));
                item.addProperty("fecha", fecha);
                gananciaReferidos = gananciaReferidos.add(montoRef);
                lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), rpId, item));
            }
        }

        lista.sort(Comparator.comparing(MovimientoAdminOrdenado::instante).reversed()
                .thenComparing(Comparator.comparingLong(MovimientoAdminOrdenado::tieId).reversed()));

        JsonArray arr = new JsonArray();
        for (MovimientoAdminOrdenado m : lista) {
            arr.add(m.datos());
        }

        JsonObject resumen = new JsonObject();
        resumen.addProperty("pendientesAccionCount", acumTx.pendientesAccionCount);
        resumen.addProperty("partidasEnCursoCount", partidasEnCursoCount);
        resumen.addProperty("gananciaComisionesModerador", gananciaPartidas);
        resumen.addProperty("gananciaReferidosModerador", gananciaReferidos);
        resumen.addProperty("gananciaTotalModerador", gananciaPartidas.add(gananciaReferidos));
        resumen.addProperty("recargasAprobadasTotal", acumTx.recargasAprobadasTotal);
        resumen.addProperty("retirosEnviadosTotal", acumTx.retirosEnviadosTotal);
        resumen.addProperty("totalMovimientos", arr.size());

        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("esModerador", true);
        resp.addProperty("zonaActiva", ConexionDB.zonaActual());
        resp.addProperty("enZonaPruebas", ConexionDB.esZonaPrueba());
        resp.add("movimientos", arr);
        resp.add("resumen", resumen);
        return resp;
    }

    private void agregarMovimientoTransaccion(List<MovimientoAdminOrdenado> lista,
                                              JsonObject tx,
                                              AcumuladorTxMovimientos acum) {
        int txId = enteroAdmin(tx, "id");
        int usuarioId = enteroAdmin(tx, "usuario_id");
        String usuario = textoAdmin(tx, "usuario_nombre");
        String tipo = textoAdmin(tx, "tipo");
        String metodo = textoAdmin(tx, "metodo");
        String referencia = textoAdmin(tx, "referencia");
        String estado = textoAdmin(tx, "estado");
        String fecha = textoAdmin(tx, "fecha");
        BigDecimal monto = obtenerMontoJson(tx, "monto");
        String tagUsuario = !usuario.isBlank() ? "@" + usuario : "usuario#" + usuarioId;
        String metodoLegible = metodoLegibleAdmin(metodo, tipo);

        JsonObject item = new JsonObject();
        item.addProperty("id", "tx-" + txId);
        item.addProperty("transaccionId", txId);
        item.addProperty("usuarioId", usuarioId);
        item.addProperty("usuario", usuario);
        item.addProperty("tipoTransaccion", tipo);
        item.addProperty("metodo", metodo);
        item.addProperty("referencia", referencia);
        item.addProperty("estado", estado);
        item.addProperty("fecha", fecha);
        item.addProperty("monto", monto);
        item.addProperty("puedeProcesar", "pendiente".equalsIgnoreCase(estado) && !"wompi".equalsIgnoreCase(metodo));

        if ("deposito".equalsIgnoreCase(tipo)) {
            item.addProperty("categoriaMovimiento", "recarga");
            item.addProperty("tipo", "recarga");
            item.addProperty("origen", metodoLegible + " · " + tagUsuario);
            if ("pendiente".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "wompi".equalsIgnoreCase(metodo) ? "Pendiente Wompi" : "Pendiente");
                item.addProperty("signo", "+");
                item.addProperty("titulo", "Recarga pendiente de " + tagUsuario);
                acum.pendientesAccionCount++;
            } else if ("completado".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Aprobado");
                item.addProperty("signo", "+");
                item.addProperty("titulo", "Recarga aprobada de " + tagUsuario);
                acum.recargasAprobadasTotal = acum.recargasAprobadasTotal.add(monto);
            } else {
                item.addProperty("estadoVisual", "negativo");
                item.addProperty("estadoTexto", "Rechazado");
                item.addProperty("signo", "");
                item.addProperty("titulo", "Recarga rechazada de " + tagUsuario);
            }
        } else {
            item.addProperty("categoriaMovimiento", "retiro");
            item.addProperty("tipo", "retiro");
            if ("pendiente".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "Pendiente");
                item.addProperty("signo", "-");
                item.addProperty("titulo", "Retiro pendiente de " + tagUsuario);
                item.addProperty("origen", metodoLegible + " · " + tagUsuario);
                acum.pendientesAccionCount++;
            } else if ("completado".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "negativo");
                item.addProperty("estadoTexto", "Enviado");
                item.addProperty("signo", "-");
                item.addProperty("titulo", "Retiro enviado a " + tagUsuario);
                item.addProperty("origen", metodoLegible + " · " + tagUsuario);
                acum.retirosEnviadosTotal = acum.retirosEnviadosTotal.add(monto);
            } else {
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Rechazado (devuelto)");
                item.addProperty("signo", "+");
                item.addProperty("titulo", "Retiro rechazado a " + tagUsuario);
                item.addProperty("origen", "Reembolso de retiro · " + tagUsuario);
            }
        }
        item.addProperty("detalle", !referencia.isBlank() ? "Ref: " + referencia : "");
        lista.add(new MovimientoAdminOrdenado(parsearFechaAdmin(fecha), txId, item));
    }

    // ------------------------------------------------------------------
    // Nequis propios del moderador
    // ------------------------------------------------------------------

    /** Lista los números Nequi configurados por el moderador autenticado. */
    public List<JsonObject> listarNequisModerador(int actorUserId) {
        if (!esModerador(actorUserId)) {
            throw ServicioException.prohibido("Opción exclusiva para cuentas autorizadas");
        }
        return db.query(
                "SELECT id, numero, titular, creado_en FROM moderator_nequis " +
                "WHERE moderator_id = ? ORDER BY id ASC",
                actorUserId);
    }

    /** Agrega un nuevo número Nequi para que los usuarios de ese moderador puedan recargarle. */
    public List<JsonObject> agregarNequiModerador(int actorUserId, String numeroCrudo, String titularCrudo) {
        if (!esModerador(actorUserId)) {
            throw ServicioException.prohibido("Opción exclusiva para cuentas autorizadas");
        }
        String textoEntrada = numeroCrudo == null ? "" : numeroCrudo.trim();
        if (textoEntrada.isEmpty() || !NEQUI_FORMATO_PERMITIDO.matcher(textoEntrada).matches()) {
            throw ServicioException.solicitudInvalida("Ingresa un número Nequi válido (ej: 3001234567)");
        }
        String soloDigitos = textoEntrada.replaceAll("\\D", "");
        if (soloDigitos.length() < 10 || soloDigitos.length() > 15) {
            throw ServicioException.solicitudInvalida("El número Nequi debe tener entre 10 y 15 dígitos");
        }
        String titular = ChatSanitizer.limpiarTexto(titularCrudo, 80);

        JsonObject conteo = db.queryOne(
                "SELECT COUNT(*) AS total FROM moderator_nequis WHERE moderator_id = ?",
                actorUserId);
        int totalActual = conteo != null ? enteroAdmin(conteo, "total") : 0;
        if (totalActual >= MAX_NEQUIS_MODERADOR) {
            throw ServicioException.solicitudInvalida("Puedes registrar máximo " + MAX_NEQUIS_MODERADOR + " números Nequi");
        }

        JsonObject duplicado = db.queryOne(
                "SELECT id FROM moderator_nequis WHERE moderator_id = ? AND numero = ?",
                actorUserId, soloDigitos);
        if (duplicado != null) {
            throw ServicioException.solicitudInvalida("Ese número Nequi ya está en tu lista");
        }

        db.update(
                "INSERT INTO moderator_nequis (moderator_id, numero, titular) VALUES (?, ?, ?)",
                actorUserId, soloDigitos, titular);
        ConexionDB connDb = obtenerConexion();
        if (!ConexionDB.esZonaPrueba() && connDb != null && connDb.tieneZonaPruebas()) {
            connDb.sincronizarNequisModeradorConPruebas(actorUserId);
        }
        return listarNequisModerador(actorUserId);
    }

    /** Elimina un número Nequi propio del moderador autenticado. */
    public List<JsonObject> eliminarNequiModerador(int actorUserId, int nequiId) {
        if (!esModerador(actorUserId)) {
            throw ServicioException.prohibido("Opción exclusiva para cuentas autorizadas");
        }
        if (nequiId <= 0) {
            throw ServicioException.solicitudInvalida("Número Nequi no válido");
        }
        int borrados = db.update(
                "DELETE FROM moderator_nequis WHERE id = ? AND moderator_id = ?",
                nequiId, actorUserId);
        if (borrados != 1) {
            throw ServicioException.noEncontrado("No se encontró ese número Nequi en tu cuenta");
        }
        ConexionDB connDb = obtenerConexion();
        if (!ConexionDB.esZonaPrueba() && connDb != null && connDb.tieneZonaPruebas()) {
            connDb.sincronizarNequisModeradorConPruebas(actorUserId);
        }
        return listarNequisModerador(actorUserId);
    }

    private record MapaUsuariosModeradores(Map<Integer, Integer> porUserId, Map<String, Integer> porUsernameLower) {}

    private static final class AcumuladorTxMovimientos {
        int pendientesAccionCount = 0;
        BigDecimal recargasAprobadasTotal = BigDecimal.ZERO;
        BigDecimal retirosEnviadosTotal = BigDecimal.ZERO;
    }

    private static String tituloPozoSorteo(String tipo) {
        return switch (tipo == null ? "" : tipo) {
            case "acumulacion" -> "Acumulación pozo de sorteos";
            case "reserva" -> "Reserva de premio para sorteo";
            case "devolucion_cancelacion" -> "Devolución al pozo por sorteo cancelado";
            case "devolucion_admin" -> "Sobrante de sorteo devuelto";
            case "ajuste_inicial" -> "Ajuste inicial pozo de sorteos";
            default -> "Movimiento pozo de sorteos";
        };
    }

    private static String metodoLegibleAdmin(String metodo, String tipo) {
        if ("wompi".equalsIgnoreCase(metodo)) return "Wompi";
        if ("nequi_retiro".equalsIgnoreCase(metodo)) return "Retiro Nequi";
        if ("nequi".equalsIgnoreCase(metodo)) return "Nequi Manual";
        if (!metodo.isBlank()) return metodo;
        return "retiro".equalsIgnoreCase(tipo) ? "Retiro manual" : "Recarga manual";
    }

    private static String textoAdmin(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        try { return obj.get(key).getAsString().trim(); } catch (Exception e) { return ""; }
    }

    private static int enteroAdmin(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    private static Instant parsearFechaAdmin(String fecha) {
        if (fecha == null || fecha.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(fecha.trim());
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static String formatoCopAdmin(BigDecimal monto) {
        if (monto == null) return "0";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return nf.format(monto);
    }

    private record MovimientoAdminOrdenado(Instant instante, long tieId, JsonObject datos) {}

    /** IP pública de salida del servidor (la que hay que autorizar en servicios externos). Con timeout de 5 s (A8). */
    public String ipPublicaServidor() {
        try {
            java.net.URLConnection conexionUrl = new URI("https://api.ipify.org").toURL().openConnection();
            conexionUrl.setConnectTimeout(5000);
            conexionUrl.setReadTimeout(5000);
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conexionUrl.getInputStream()))) {
                return in.readLine();
            }
        } catch (Exception e) {
            return "Error obteniendo IP: " + e.getMessage();
        }
    }
}
