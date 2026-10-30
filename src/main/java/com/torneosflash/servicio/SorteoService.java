package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lógica de sorteos: tickets, encuesta de categorías, participación, creación/eliminación y ejecución (A3).
 * Antes vivía dentro de {@code RutasSorteos}. No conoce HTTP.
 * D1: El pozo y el cálculo de tickets usan {@link BigDecimal} (nunca {@code double}).
 */
public class SorteoService {
    private static final Logger logger = LoggerFactory.getLogger(SorteoService.class);

    /** D5: tope de minutos de duración de un sorteo (1 año). */
    private static final int DURACION_MAX_MINUTOS = 60 * 24 * 365;
    /** D5: tope de tickets que se pueden sumar o quitar en una sola participación. */
    private static final long TICKETS_DELTA_MAX = 1_000_000L;
    private static final BigDecimal MIL = BigDecimal.valueOf(1000);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> CATEGORIAS_VALIDAS = Set.of(
            "gemas", "pass", "evoluciones", "emotes", "cartas", "comodines", "especial");
    private static final Pattern CORREO_VALIDO = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]+$");

    private final GenericDAO db;
    private final SocketIOServer io;
    private final CorreoServicio correo;
    private final NotificacionPushServicio push;
    private final ValidadorMonto validador;
    private final EventBus eventos;

    public SorteoService(GenericDAO db, SocketIOServer io, CorreoServicio correo,
                         NotificacionPushServicio push, ValidadorMonto validador) {
        this(db, io, correo, push, validador, null);
    }

    public SorteoService(GenericDAO db, SocketIOServer io, CorreoServicio correo,
                         NotificacionPushServicio push, ValidadorMonto validador, EventBus eventos) {
        this.db = db;
        this.io = io;
        this.correo = correo;
        this.push = push;
        this.validador = validador;
        this.eventos = eventos;
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    public JsonObject ticketsDeUsuario(int userId) {
        JsonObject result = db.queryOne("SELECT cantidad, acumulado FROM user_tickets WHERE user_id = ?", userId);
        JsonObject res = new JsonObject();
        res.addProperty("tickets", result != null ? result.get("cantidad").getAsLong() : 0);
        res.addProperty("acumulado", result != null ? result.get("acumulado").getAsLong() : 0);
        return res;
    }

    public JsonObject pozo() {
        JsonObject poolRes = db.queryOne("SELECT monto AS total FROM raffle_pool WHERE id = 1");
        JsonObject res = new JsonObject();
        res.addProperty("pool", poolRes != null ? poolRes.get("total").getAsBigDecimal() : BigDecimal.ZERO);
        return res;
    }

    /** Resultados de la encuesta de categorías y el voto del usuario (userId 0 = sin sesión). */
    public JsonObject encuesta(int userId) {
        ArrayList<JsonObject> counts = db.query("SELECT categoria, COUNT(*) as votos FROM raffle_votes GROUP BY categoria");

        JsonObject miVoto = null;
        if (userId > 0) {
            miVoto = db.queryOne("SELECT categoria FROM raffle_votes WHERE user_id = ?", userId);
        }

        JsonArray categorias = new JsonArray();
        for (JsonObject count : counts) categorias.add(count);

        JsonObject res = new JsonObject();
        res.add("resultados", categorias);
        res.addProperty("miVoto", miVoto != null ? miVoto.get("categoria").getAsString() : null);
        return res;
    }

    /** Sorteos visibles para el usuario (el admin ve también todos los completados). */
    public ArrayList<JsonObject> ofertas(int userId) {
        boolean esAdmin = false;
        if (userId > 0) {
            JsonObject user = db.queryOne("SELECT tipo_suscripcion FROM users WHERE id = ?", userId);
            esAdmin = user != null && "admin".equals(user.get("tipo_suscripcion").getAsString());
        }

        if (esAdmin) {
            return db.query("SELECT r.*, COALESCE(e.tickets_asignados, 0) as mis_tickets " +
                    "FROM raffles r LEFT JOIN raffle_entries e ON r.id = e.raffle_id AND e.user_id = ? " +
                    "WHERE r.estado IN ('activo', 'completado') ORDER BY r.estado ASC, r.fecha_limite ASC", userId);
        }
        return db.query("SELECT r.*, COALESCE(e.tickets_asignados, 0) as mis_tickets " +
                "FROM raffles r LEFT JOIN raffle_entries e ON r.id = e.raffle_id AND e.user_id = ? " +
                "WHERE r.estado = 'activo' OR (r.estado = 'completado' AND r.fecha_completado > NOW() - INTERVAL '1 hour') " +
                "ORDER BY r.estado ASC, r.fecha_limite ASC", userId);
    }

    public ArrayList<JsonObject> todos(int userId) {
        return db.query("SELECT r.*, COALESCE(e.tickets_asignados, 0) as mis_tickets " +
                "FROM raffles r LEFT JOIN raffle_entries e ON r.id = e.raffle_id AND e.user_id = ? " +
                "ORDER BY r.fecha_creacion DESC LIMIT 50", userId);
    }

    public ArrayList<JsonObject> listarAdmin() {
        return db.query("SELECT * FROM raffles ORDER BY fecha_creacion DESC");
    }

    // ------------------------------------------------------------------
    // Encuesta
    // ------------------------------------------------------------------

    public void votar(int userId, String categoria) {
        String cat = categoria == null ? "" : categoria.trim().toLowerCase();
        if (!CATEGORIAS_VALIDAS.contains(cat)) {
            throw ServicioException.solicitudInvalida("Categoría de encuesta no válida");
        }
        db.update("INSERT INTO raffle_votes (user_id, categoria) VALUES (?, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET categoria = ?", userId, cat, cat);
        io.emit("poll_updated", new JsonObject());
    }

    public void reiniciarEncuesta() {
        db.update("DELETE FROM raffle_votes");
        io.emit("poll_reset", new JsonObject());
        if (eventos != null) {
            eventos.emit(Eventos.RAFFLE_POLL_RESET, new Eventos.EncuestaSorteosReiniciada());
        } else if (push != null) {
            push.enviarPushATodos(db, Set.of(), "📊 ¡Nueva Encuesta de Sorteos!",
                    "La encuesta se ha reiniciado. ¡Entra y vota por tu premio favorito!");
        }
    }

    // ------------------------------------------------------------------
    // Participación
    // ------------------------------------------------------------------

    /**
     * C6: Suma (delta > 0) o quita (delta < 0) tickets del usuario en un sorteo dentro de UNA sola transacción SQL
     * con bloqueo FOR UPDATE sobre {@code raffles} y {@code user_tickets}, impidiendo fabricar o gastar tickets dobles.
     */
    public JsonObject participar(int userId, int raffleId, int delta) {
        if (delta == 0 || Math.abs((long) delta) > TICKETS_DELTA_MAX) {
            throw ServicioException.solicitudInvalida("La cantidad de tickets no es válida");
        }

        int nuevoTotal;
        int tActuales;
        int tNecesarios;
        int ticketsUsuarioFinal;

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // 1. Bloquear el sorteo activo
            int ticketsActualesSorteo;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tickets_necesarios, tickets_actuales FROM raffles WHERE id = ? AND estado = 'activo' FOR UPDATE")) {
                ps.setInt(1, raffleId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        throw ServicioException.solicitudInvalida("Sorteo no disponible");
                    }
                    tNecesarios = rs.getInt("tickets_necesarios");
                    ticketsActualesSorteo = rs.getInt("tickets_actuales");
                }
            }

            // Comprueba el vencimiento después de adquirir el bloqueo, usando el reloj real de PostgreSQL.
            // NOW() queda fijo al inicio de la transacción y podría aceptar una participación tras esperar el bloqueo.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(fecha_limite > clock_timestamp(), FALSE) AS vigente " +
                    "FROM raffles WHERE id = ? AND estado = 'activo'")) {
                ps.setInt(1, raffleId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean("vigente")) {
                        conn.rollback();
                        throw ServicioException.solicitudInvalida("El sorteo ya venció");
                    }
                }
            }

            // 2. Asegurar y bloquear fila de tickets del usuario
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, 0, 0) ON CONFLICT (user_id) DO NOTHING")) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            }
            int ticketsDisponibles;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cantidad FROM user_tickets WHERE user_id = ? FOR UPDATE")) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    ticketsDisponibles = rs.next() ? rs.getInt("cantidad") : 0;
                }
            }

            // 3. Leer participación actual bloqueando si existe
            int ticketsEnEntrada = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tickets_asignados FROM raffle_entries WHERE raffle_id = ? AND user_id = ? FOR UPDATE")) {
                ps.setInt(1, raffleId);
                ps.setInt(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        ticketsEnEntrada = rs.getInt("tickets_asignados");
                    }
                }
            }

            nuevoTotal = ticketsEnEntrada + delta;
            if (nuevoTotal < 0) {
                conn.rollback();
                throw ServicioException.solicitudInvalida("No puedes quitar más tickets de los que tienes asignados");
            }
            if (delta > 0 && ticketsDisponibles < delta) {
                conn.rollback();
                throw ServicioException.solicitudInvalida("No tienes suficientes tickets disponibles");
            }

            int ticketsRestantes = tNecesarios - ticketsActualesSorteo;
            if (delta > ticketsRestantes) {
                conn.rollback();
                throw ServicioException.solicitudInvalida("Solo quedan " + ticketsRestantes + " espacios");
            }

            // 4. Actualizar tickets del usuario con guarda WHERE cantidad >= ?
            if (delta > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE user_tickets SET cantidad = cantidad - ? WHERE user_id = ? AND cantidad >= ?")) {
                    ps.setInt(1, delta);
                    ps.setInt(2, userId);
                    ps.setInt(3, delta);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        throw ServicioException.solicitudInvalida("No tienes suficientes tickets disponibles");
                    }
                }
                ticketsUsuarioFinal = ticketsDisponibles - delta;
            } else {
                int devolver = Math.abs(delta);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE user_tickets SET cantidad = cantidad + ? WHERE user_id = ?")) {
                    ps.setInt(1, devolver);
                    ps.setInt(2, userId);
                    ps.executeUpdate();
                }
                ticketsUsuarioFinal = ticketsDisponibles + devolver;
            }

            // 5. Actualizar participación en raffle_entries
            if (nuevoTotal == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM raffle_entries WHERE raffle_id = ? AND user_id = ?")) {
                    ps.setInt(1, raffleId);
                    ps.setInt(2, userId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO raffle_entries (raffle_id, user_id, tickets_asignados) VALUES (?, ?, ?) " +
                        "ON CONFLICT (raffle_id, user_id) DO UPDATE SET tickets_asignados = EXCLUDED.tickets_asignados")) {
                    ps.setInt(1, raffleId);
                    ps.setInt(2, userId);
                    ps.setInt(3, nuevoTotal);
                    ps.executeUpdate();
                }
            }

            // 6. Actualizar conteo del sorteo
            tActuales = ticketsActualesSorteo + delta;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE raffles SET tickets_actuales = ? WHERE id = ?")) {
                ps.setInt(1, tActuales);
                ps.setInt(2, raffleId);
                ps.executeUpdate();
            }

            conn.commit();
        } catch (ServicioException e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            throw e;
        } catch (SQLException e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            logger.error("Error SQL en participar sorteo #{}: {}", raffleId, e.getMessage());
            throw ServicioException.interno("Error al actualizar participación en el sorteo", e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }

        if (tActuales >= tNecesarios) {
            ejecutarSorteo(raffleId, "se llenaron los tickets");
        } else {
            JsonObject updateData = new JsonObject();
            updateData.addProperty("raffleId", raffleId);
            updateData.addProperty("tickets_actuales", tActuales);
            io.emit("sorteo_actualizado", updateData);
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.addProperty("ticketsUsuario", ticketsUsuarioFinal);
        res.addProperty("misTicketsEnSorteo", nuevoTotal);
        res.addProperty("ticketsEnSorteo", tActuales);
        return res;
    }

    // ------------------------------------------------------------------
    // Administración
    // ------------------------------------------------------------------

    /** Crea un sorteo. {@code precioCrudo} y {@code duracionCruda} vienen tal cual del cliente y se validan aquí. */
    public JsonObject crear(String nombre, String categoria, JsonElement precioCrudo, JsonElement duracionCruda) {
        String nombreValidado = nombre == null ? "" : nombre.trim();
        int longitudNombre = nombreValidado.codePointCount(0, nombreValidado.length());
        if (longitudNombre < 1 || longitudNombre > 100 || nombreValidado.codePoints().anyMatch(Character::isISOControl)) {
            throw ServicioException.solicitudInvalida("El nombre del sorteo debe tener entre 1 y 100 caracteres válidos");
        }
        String categoriaValidada = categoria == null ? "" : categoria.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORIAS_VALIDAS.contains(categoriaValidada)) {
            throw ServicioException.solicitudInvalida("La categoría del sorteo no es válida");
        }

        // D5: precio validado en el servidor (número, positivo, entero, dentro de rango)
        ValidadorMonto.Resultado precioValido = validador.sorteo(precioCrudo);
        if (!precioValido.isValido()) {
            throw ServicioException.solicitudInvalida(precioValido.getError());
        }
        BigDecimal montoPrecio = precioValido.getMonto(); // REVIEW-MONEY (el validador acota a Integer.MAX_VALUE)
        int precio = montoPrecio.intValueExact();

        int duracionMinutos;
        try {
            duracionMinutos = duracionCruda.getAsInt();
        } catch (Exception e) {
            throw ServicioException.solicitudInvalida("La duración no es válida");
        }
        if (duracionMinutos < 1 || duracionMinutos > DURACION_MAX_MINUTOS) {
            throw ServicioException.solicitudInvalida(
                    "La duración debe estar entre 1 minuto y " + DURACION_MAX_MINUTOS + " minutos");
        }
        int ticketsNecesarios = montoPrecio.divide(MIL, 0, RoundingMode.CEILING).intValueExact();

        int newId;
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);
            BigDecimal saldoPozo;
            try (PreparedStatement ps = conn.prepareStatement("SELECT monto FROM raffle_pool WHERE id = 1 FOR UPDATE")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("No existe el saldo del pozo de sorteos");
                    saldoPozo = rs.getBigDecimal("monto");
                }
            }
            if (saldoPozo == null || saldoPozo.compareTo(montoPrecio) < 0) {
                throw ServicioException.solicitudInvalida("El pozo acumulado no alcanza para reservar el premio.");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE raffle_pool SET monto = monto - ?, actualizado_en = CURRENT_TIMESTAMP WHERE id = 1")) {
                ps.setBigDecimal(1, montoPrecio);
                if (ps.executeUpdate() != 1) throw new SQLException("No se pudo reservar el premio del sorteo");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO raffles (nombre, categoria, precio, tickets_necesarios, fecha_limite) " +
                            "VALUES (?, ?, ?, ?, NOW() + make_interval(mins => ?)) RETURNING id")) {
                ps.setString(1, nombreValidado);
                ps.setString(2, categoriaValidada);
                ps.setInt(3, precio);
                ps.setInt(4, ticketsNecesarios);
                ps.setInt(5, duracionMinutos);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("No se pudo crear el sorteo");
                    newId = rs.getInt(1);
                }
            }
            registrarMovimientoPozo(conn, "reserva", montoPrecio.negate(), "Sorteo #" + newId + " creado");
            conn.commit();
        } catch (ServicioException e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            throw e;
        } catch (SQLException e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            logger.error("Error reservando el premio del sorteo: {}", e.getMessage());
            throw ServicioException.interno("No se pudo reservar el premio del sorteo", e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }

        JsonObject nuevoSorteo = db.queryOne("SELECT * FROM raffles WHERE id = ?", newId);
        io.emit("nuevo_sorteo", nuevoSorteo);

        if (eventos != null) {
            eventos.emit(Eventos.RAFFLE_CREATED, new Eventos.SorteoCreado(newId, nombreValidado));
        } else if (push != null) {
            push.enviarPushATodos(db, Set.of(), "🎁 ¡Nuevo sorteo!", "Sorteo \"" + nombreValidado + "\" disponible");
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.add("sorteo", nuevoSorteo);
        return res;
    }

    /** Elimina un sorteo. Si no estaba completado, devuelve los tickets a quienes participaban. */
    public String eliminar(int raffleId) {
        boolean completado;
        BigDecimal precio;
        String nombre;
        ArrayList<Integer> participantes = new ArrayList<>();
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT estado, precio, nombre FROM raffles WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, raffleId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        throw ServicioException.noEncontrado("Sorteo no encontrado");
                    }
                    completado = "completado".equals(rs.getString("estado"));
                    precio = rs.getBigDecimal("precio");
                    nombre = rs.getString("nombre");
                }
            }

            if (!completado) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE raffle_pool SET monto = monto + ?, actualizado_en = CURRENT_TIMESTAMP WHERE id = 1")) {
                    ps.setBigDecimal(1, precio);
                    if (ps.executeUpdate() != 1) throw new SQLException("No se pudo devolver el premio al pozo");
                }
                registrarMovimientoPozo(conn, "devolucion_cancelacion", precio,
                        "Sorteo #" + raffleId + " cancelado");
                ArrayList<int[]> entriesToRefund = new ArrayList<>();
                try (PreparedStatement entries = conn.prepareStatement(
                        "SELECT user_id, tickets_asignados FROM raffle_entries WHERE raffle_id = ? ORDER BY user_id FOR UPDATE")) {
                    entries.setInt(1, raffleId);
                    try (ResultSet rs = entries.executeQuery()) {
                        while (rs.next()) {
                            entriesToRefund.add(new int[]{rs.getInt("user_id"), rs.getInt("tickets_asignados")});
                            participantes.add(rs.getInt("user_id"));
                        }
                    }
                }
                try (PreparedStatement refund = conn.prepareStatement(
                        "INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, ?, 0) " +
                        "ON CONFLICT (user_id) DO UPDATE SET cantidad = user_tickets.cantidad + EXCLUDED.cantidad")) {
                    for (int[] entry : entriesToRefund) {
                        refund.setInt(1, entry[0]);
                        refund.setInt(2, entry[1]);
                        refund.executeUpdate();
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM raffles WHERE id = ?")) {
                ps.setInt(1, raffleId);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("El sorteo dejó de estar disponible durante su eliminación");
                }
            }
            conn.commit();
        } catch (ServicioException e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            throw e;
        } catch (SQLException e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            logger.error("Error SQL eliminando sorteo #{}: {}", raffleId, e.getMessage());
            throw ServicioException.interno("No se pudo eliminar el sorteo", e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }

        JsonObject deleteData = new JsonObject();
        deleteData.addProperty("raffleId", raffleId);
        io.emit("sorteo_eliminado", deleteData);
        if (!completado && eventos != null) {
            eventos.emit(Eventos.RAFFLE_CANCELLED,
                    new Eventos.SorteoCancelado(raffleId, nombre, participantes));
        }

        return completado ? "Sorteo completado eliminado" : "Sorteo eliminado, tickets devueltos";
    }

    // ------------------------------------------------------------------
    // Ejecución del sorteo
    // ------------------------------------------------------------------

    /** Ejecuta el sorteo de todos los que ya pasaron su fecha límite (lo llama el scheduler cada minuto). */
    public void ejecutarSorteosExpirados() {
        ArrayList<JsonObject> expirados = db.query(
                "SELECT * FROM raffles WHERE estado = 'activo' AND fecha_limite < NOW()");

        for (JsonObject raffle : expirados) {
            int raffleId = raffle.get("id").getAsNumber().intValue();
            String nombre = raffle.get("nombre").getAsString();
            logger.info("⏰ Sorteo #{} ({}) expirado", raffleId, nombre);

            // Se completa con espacios vacíos si no se llenaron los tickets
            ejecutarSorteo(raffleId, "venció el plazo");
        }
    }

    /**
     * C6: Elige al ganador con {@link SecureRandom} bloqueando la fila del sorteo con {@code FOR UPDATE}
     * para impedir que el scheduler y el último ticket ejecuten el mismo sorteo dos veces.
     */
    public void ejecutarSorteo(int raffleId) {
        ejecutarSorteo(raffleId, "completado");
    }

    private void ejecutarSorteo(int raffleId, String motivo) {
        String nombre;
        int ganadorId;
        BigDecimal precio;
        boolean administradorGano = false;
        String ganadorNombre = "Admin (Vacío)";
        String emailGanador = "N/A";
        ArrayList<Integer> participantes = new ArrayList<>();
        Set<Integer> participantesUnicos = new HashSet<>();

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            int tNecesarios;
            int tActuales;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT nombre, precio, tickets_necesarios, tickets_actuales FROM raffles WHERE id = ? AND estado = 'activo' FOR UPDATE")) {
                ps.setInt(1, raffleId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return;
                    }
                    nombre = rs.getString("nombre");
                    precio = rs.getBigDecimal("precio");
                    tNecesarios = rs.getInt("tickets_necesarios");
                    tActuales = rs.getInt("tickets_actuales");
                }
            }

            ArrayList<Integer> pool = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, tickets_asignados FROM raffle_entries WHERE raffle_id = ? ORDER BY id ASC")) {
                ps.setInt(1, raffleId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int uid = rs.getInt("user_id");
                        int tickets = rs.getInt("tickets_asignados");
                        if (participantesUnicos.add(uid)) participantes.add(uid);
                        for (int i = 0; i < tickets; i++) pool.add(uid);
                    }
                }
            }

            int ticketsFaltantes = Math.max(0, tNecesarios - tActuales);
            for (int i = 0; i < ticketsFaltantes; i++) pool.add(-1);

            if (pool.isEmpty()) {
                conn.rollback();
                return;
            }

            ganadorId = pool.get(SECURE_RANDOM.nextInt(pool.size()));

            if (ganadorId != -1) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT username, email, tipo_suscripcion FROM users WHERE id = ?")) {
                    ps.setInt(1, ganadorId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            ganadorNombre = rs.getString("username");
                            emailGanador = rs.getString("email");
                            administradorGano = "admin".equalsIgnoreCase(rs.getString("tipo_suscripcion"));
                        }
                    }
                }
            }
            // Cuando se vence sin participaciones, el premio queda asignado al administrador.
            administradorGano = administradorGano || ganadorId == -1;

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE raffles SET estado = 'completado', ganador_id = ?, ganador_nombre = ?, fecha_completado = NOW() " +
                    "WHERE id = ? AND estado = 'activo'")) {
                if (ganadorId == -1) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(1, ganadorId);
                }
                ps.setString(2, ganadorNombre);
                ps.setInt(3, raffleId);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    return;
                }
            }

            if (administradorGano) {
                BigDecimal devolucionPozo = precio.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                BigDecimal gananciaAdmin = precio.subtract(devolucionPozo);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE raffle_pool SET monto = monto + ?, actualizado_en = CURRENT_TIMESTAMP WHERE id = 1")) {
                    ps.setBigDecimal(1, devolucionPozo);
                    if (ps.executeUpdate() != 1) throw new SQLException("No se pudo devolver la mitad del premio al pozo");
                }
                registrarMovimientoPozo(conn, "devolucion_admin", devolucionPozo,
                        "Sorteo #" + raffleId + " ganado por el administrador");
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO admin_wallet (monto, razon, detalle, categoria) VALUES (?, ?, ?, 'sorteos')")) {
                    ps.setBigDecimal(1, gananciaAdmin);
                    ps.setString(2, "Premio de sorteo ganado por administrador");
                    ps.setString(3, "Sorteo #" + raffleId + ": " + nombre);
                    ps.executeUpdate();
                }
            }

            conn.commit();

            if (correo != null) {
                correo.notificarAdmin("SORTEO COMPLETADO: " + nombre,
                        "Ganador: " + ganadorNombre + (ganadorId != -1 ? " (id " + ganadorId + ")" : ""));
                enviarCorreoGanadorSorteo(nombre, ganadorId, ganadorNombre, emailGanador);
            }


            JsonObject ganadorData = new JsonObject();
            ganadorData.addProperty("raffleId", raffleId);
            ganadorData.addProperty("nombre", nombre);
            ganadorData.addProperty("ganadorNombre", ganadorNombre);
            ganadorData.addProperty("ganadorId", ganadorId);
            if (io != null) io.emit("sorteo_ganador", ganadorData);

            if (eventos != null) {
                eventos.emit(Eventos.RAFFLE_FINISHED, new Eventos.SorteoFinalizado(
                        raffleId, nombre, ganadorId, ganadorNombre, participantes, motivo));
            } else if (push != null && ganadorId != -1) {
                push.enviarPush(db, ganadorId, "🏆 ¡Ganaste el sorteo!", "¡Felicidades! Ganaste \"" + nombre + "\"");
            }

            logger.info("🏆 Sorteo #{} completado. Ganador: {}", raffleId, ganadorNombre);
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            logger.error("Error ejecutando sorteo: " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------
    void enviarCorreoGanadorSorteo(String nombreSorteo, int ganadorId, String ganadorNombre, String emailGanador) {
        if (correo == null || ganadorId == -1 || emailGanador == null) return;
        String destino = emailGanador.trim();
        if (destino.isEmpty() || destino.length() > 254 || !CORREO_VALIDO.matcher(destino).matches()) return;
        String asunto = "🏆 ¡Ganaste el sorteo \"" + nombreSorteo + "\"!";
        String cuerpo = "¡Felicidades, " + ganadorNombre + "!\n\n"
                + "Ganaste el sorteo «" + nombreSorteo + "» en Torneos Flash.\n\n"
                + "El administrador se pondrá en contacto contigo pronto para coordinar la entrega de tu premio. "
                + "También puedes escribir desde la sección de Soporte en la plataforma si tienes cualquier consulta.\n\n"
                + "— Torneos Flash";
        correo.enviar(destino, asunto, cuerpo);
    }

    // ------------------------------------------------------------------
    // Tickets por partida
    // ------------------------------------------------------------------

    /**
     * Acumula los tickets de sorteo que le corresponden a un jugador al terminar una partida (D4).
     * Toma del desglose la mitad de la comisión de sorteos, así los llamadores no repiten la cuenta.
     *
     * @return {ticketsNuevos, acumuladoResidual}
     */
    public int[] acumularTicketsPorPartida(int userId, ComisionService.Desglose desglose) {
        return acumularTickets(userId, desglose.porJugador(ComisionService.Categoria.SORTEOS));
    }

    /**
     * Acumula tickets: recibe la porción de comisión de sorteos de este jugador y genera 1 ticket
     * por cada 1000 pesos acumulados.
     */
    public int[] acumularTickets(int userId, BigDecimal montoComisionSorteo) {
        if (userId <= 0 || montoComisionSorteo == null) {
            logger.warn("Se omitió la acumulación de tickets por datos inválidos (userId={})", userId);
            return new int[]{0, 0};
        }

        final int montoPesos;
        try {
            montoPesos = montoComisionSorteo.setScale(0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException e) {
            logger.error("Monto fuera de rango al acumular tickets para userId={}", userId);
            return new int[]{0, 0};
        }
        if (montoPesos < 0) {
            logger.warn("Se omitió una acumulación negativa de tickets para userId={}", userId);
            return new int[]{0, 0};
        }

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, 0, 0) " +
                    "ON CONFLICT (user_id) DO NOTHING")) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            }

            int acumuladoAnterior;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT acumulado FROM user_tickets WHERE user_id = ? FOR UPDATE")) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("No se pudo crear el registro de tickets");
                    acumuladoAnterior = rs.getInt("acumulado");
                }
            }

            // REVIEW-MONEY: conserva el truncamiento a pesos enteros; el bloqueo hace atómica la suma y el cálculo del residuo.
            long acumuladoNuevo = (long) acumuladoAnterior + montoPesos;
            int ticketsNuevos = (int) (acumuladoNuevo / 1000 - acumuladoAnterior / 1000);
            int residuo = (int) (acumuladoNuevo % 1000);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE user_tickets SET cantidad = cantidad + ?, acumulado = ? WHERE user_id = ?")) {
                ps.setInt(1, ticketsNuevos);
                ps.setInt(2, residuo);
                ps.setInt(3, userId);
                if (ps.executeUpdate() != 1) throw new SQLException("No se pudo actualizar el registro de tickets");
            }
            conn.commit();
            return new int[]{ticketsNuevos, residuo};
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            logger.error("Error acumulando tickets para userId={}: {}", userId, e.getMessage());
            return new int[]{0, 0};
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    private static void registrarMovimientoPozo(Connection conn, String tipo, BigDecimal variacion,
                                                String referencia) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO raffle_pool_movements (tipo, variacion, referencia) VALUES (?, ?, ?)")) {
            ps.setString(1, tipo);
            ps.setBigDecimal(2, variacion);
            ps.setString(3, referencia);
            ps.executeUpdate();
        }
    }
}
