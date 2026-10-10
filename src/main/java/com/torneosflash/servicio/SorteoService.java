package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

/**
 * Lógica de sorteos: tickets, encuesta de categorías, participación, creación/eliminación y ejecución (A3).
 * Antes vivía dentro de {@code RutasSorteos}. No conoce HTTP.
 */
public class SorteoService {
    private static final Logger logger = LoggerFactory.getLogger(SorteoService.class);

    /** D5: tope de minutos de duración de un sorteo (1 año). */
    private static final int DURACION_MAX_MINUTOS = 60 * 24 * 365;
    /** D5: tope de tickets que se pueden sumar o quitar en una sola participación. */
    private static final long TICKETS_DELTA_MAX = 1_000_000L;

    private final GenericDAO db;
    private final SocketIOServer io;
    private final CorreoServicio correo;
    private final NotificacionPushServicio push;
    private final ValidadorMonto validador;

    public SorteoService(GenericDAO db, SocketIOServer io, CorreoServicio correo,
                         NotificacionPushServicio push, ValidadorMonto validador) {
        this.db = db;
        this.io = io;
        this.correo = correo;
        this.push = push;
        this.validador = validador;
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
        JsonObject poolRes = db.queryOne("SELECT COALESCE(SUM(gen_sorteos), 0) as total FROM users");
        JsonObject res = new JsonObject();
        res.addProperty("pool", poolRes != null ? poolRes.get("total").getAsDouble() : 0);
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
        db.update("INSERT INTO raffle_votes (user_id, categoria) VALUES (?, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET categoria = ?", userId, categoria, categoria);
        io.emit("poll_updated", new JsonObject());
    }

    public void reiniciarEncuesta() {
        db.update("DELETE FROM raffle_votes");
        io.emit("poll_reset", new JsonObject());
        if (push != null) {
            push.enviarPushATodos(db, Set.of(), "📊 ¡Nueva Encuesta de Sorteos!",
                    "La encuesta se ha reiniciado. ¡Entra y vota por tu premio favorito!");
        }
    }

    // ------------------------------------------------------------------
    // Participación
    // ------------------------------------------------------------------

    /** Suma (delta > 0) o quita (delta < 0) tickets del usuario en un sorteo. Si se completa, ejecuta el sorteo. */
    public JsonObject participar(int userId, int raffleId, int delta) {
        // D5: la cantidad de tickets la valida el servidor (no puede ser 0 ni absurda)
        if (delta == 0 || Math.abs((long) delta) > TICKETS_DELTA_MAX) {
            throw ServicioException.solicitudInvalida("La cantidad de tickets no es válida");
        }

        JsonObject raffle = db.queryOne("SELECT * FROM raffles WHERE id = ? AND estado = 'activo'", raffleId);
        if (raffle == null) throw ServicioException.solicitudInvalida("Sorteo no disponible");

        JsonObject ticketsRes = db.queryOne("SELECT cantidad FROM user_tickets WHERE user_id = ?", userId);
        int ticketsDisponibles = ticketsRes != null ? (int) ticketsRes.get("cantidad").getAsLong() : 0;

        JsonObject entryRes = db.queryOne("SELECT tickets_asignados FROM raffle_entries WHERE raffle_id = ? AND user_id = ?", raffleId, userId);
        int ticketsActuales = entryRes != null ? (int) entryRes.get("tickets_asignados").getAsLong() : 0;

        int nuevoTotal = ticketsActuales + delta;

        if (nuevoTotal < 0) {
            throw ServicioException.solicitudInvalida("No puedes quitar más tickets de los que tienes asignados");
        }
        if (delta > 0 && ticketsDisponibles < delta) {
            throw ServicioException.solicitudInvalida("No tienes suficientes tickets disponibles");
        }

        int ticketsRestantes = (int) raffle.get("tickets_necesarios").getAsLong() - (int) raffle.get("tickets_actuales").getAsLong();
        if (delta > ticketsRestantes) {
            throw ServicioException.solicitudInvalida("Solo quedan " + ticketsRestantes + " espacios");
        }

        // Tickets del usuario
        if (delta > 0) {
            db.update("UPDATE user_tickets SET cantidad = cantidad - ? WHERE user_id = ?", delta, userId);
        } else {
            db.update("INSERT INTO user_tickets (user_id, cantidad) VALUES (?, ?) " +
                    "ON CONFLICT (user_id) DO UPDATE SET cantidad = user_tickets.cantidad + ?",
                    userId, Math.abs(delta), Math.abs(delta));
        }

        // Participación
        if (nuevoTotal == 0) {
            db.update("DELETE FROM raffle_entries WHERE raffle_id = ? AND user_id = ?", raffleId, userId);
        } else {
            db.update("INSERT INTO raffle_entries (raffle_id, user_id, tickets_asignados) VALUES (?, ?, ?) " +
                    "ON CONFLICT (raffle_id, user_id) DO UPDATE SET tickets_asignados = ?",
                    raffleId, userId, nuevoTotal, nuevoTotal);
        }

        db.update("UPDATE raffles SET tickets_actuales = tickets_actuales + ? WHERE id = ?", delta, raffleId);

        // ¿Se completó?
        JsonObject updated = db.queryOne("SELECT tickets_actuales, tickets_necesarios FROM raffles WHERE id = ?", raffleId);
        int tActuales = (int) updated.get("tickets_actuales").getAsLong();
        int tNecesarios = (int) updated.get("tickets_necesarios").getAsLong();
        if (tActuales >= tNecesarios) {
            ejecutarSorteo(raffleId);
        } else {
            JsonObject updateData = new JsonObject();
            updateData.addProperty("raffleId", raffleId);
            updateData.addProperty("tickets_actuales", tActuales);
            io.emit("sorteo_actualizado", updateData);
        }

        JsonObject newTickets = db.queryOne("SELECT cantidad FROM user_tickets WHERE user_id = ?", userId);
        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.addProperty("ticketsUsuario", newTickets != null ? newTickets.get("cantidad").getAsLong() : 0);
        res.addProperty("misTicketsEnSorteo", nuevoTotal);
        res.addProperty("ticketsEnSorteo", tActuales);
        return res;
    }

    // ------------------------------------------------------------------
    // Administración
    // ------------------------------------------------------------------

    /** Crea un sorteo. {@code precioCrudo} y {@code duracionCruda} vienen tal cual del cliente y se validan aquí. */
    public JsonObject crear(String nombre, String categoria, JsonElement precioCrudo, JsonElement duracionCruda) {
        // D5: precio validado en el servidor (número, positivo, entero, dentro de rango)
        ValidadorMonto.Resultado precioValido = validador.sorteo(precioCrudo);
        if (!precioValido.isValido()) {
            throw ServicioException.solicitudInvalida(precioValido.getError());
        }
        int precio = precioValido.getMonto().intValueExact(); // REVIEW-MONEY (el validador acota a Integer.MAX_VALUE)

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
        int ticketsNecesarios = (int) Math.ceil(precio / 1000.0);

        int newId = db.insertReturningId("INSERT INTO raffles (nombre, categoria, precio, tickets_necesarios, fecha_limite) " +
                "VALUES (?, ?, ?, ?, NOW() + make_interval(mins => ?)) RETURNING id",
                nombre, categoria, precio, ticketsNecesarios, duracionMinutos);

        JsonObject nuevoSorteo = db.queryOne("SELECT * FROM raffles WHERE id = ?", newId);
        io.emit("nuevo_sorteo", nuevoSorteo);

        if (push != null) {
            push.enviarPushATodos(db, Set.of(), "🎁 ¡Nuevo sorteo!", "Sorteo \"" + nombre + "\" disponible");
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.add("sorteo", nuevoSorteo);
        return res;
    }

    /** Elimina un sorteo. Si no estaba completado, devuelve los tickets a quienes participaban. */
    public String eliminar(int raffleId) {
        JsonObject sorteo = db.queryOne("SELECT estado FROM raffles WHERE id = ?", raffleId);
        if (sorteo == null) throw ServicioException.noEncontrado("Sorteo no encontrado");

        String estado = sorteo.get("estado").getAsString();
        boolean completado = "completado".equals(estado);
        if (!completado) {
            ArrayList<JsonObject> entries = db.query("SELECT * FROM raffle_entries WHERE raffle_id = ?", raffleId);
            for (JsonObject entry : entries) {
                db.update("INSERT INTO user_tickets (user_id, cantidad) VALUES (?, ?) " +
                        "ON CONFLICT (user_id) DO UPDATE SET cantidad = user_tickets.cantidad + ?",
                        (int) entry.get("user_id").getAsLong(),
                        (int) entry.get("tickets_asignados").getAsLong(),
                        (int) entry.get("tickets_asignados").getAsLong());
            }
        }
        db.update("DELETE FROM raffles WHERE id = ?", raffleId);

        JsonObject deleteData = new JsonObject();
        deleteData.addProperty("raffleId", raffleId);
        io.emit("sorteo_eliminado", deleteData);

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
            ejecutarSorteo(raffleId);
        }
    }

    /** Elige al ganador (ponderado por tickets; los espacios vacíos cuentan para el admin) y avisa a todos. */
    public void ejecutarSorteo(int raffleId) {
        try {
            JsonObject raffle = db.queryOne("SELECT * FROM raffles WHERE id = ? AND estado = 'activo'", raffleId);
            if (raffle == null) return;

            ArrayList<JsonObject> entries = db.query("SELECT * FROM raffle_entries WHERE raffle_id = ?", raffleId);

            // Pool ponderado
            ArrayList<Integer> pool = new ArrayList<>();
            for (JsonObject entry : entries) {
                int uid = (int) entry.get("user_id").getAsLong();
                int tickets = (int) entry.get("tickets_asignados").getAsLong();
                for (int i = 0; i < tickets; i++) pool.add(uid);
            }

            int tNecesarios = (int) raffle.get("tickets_necesarios").getAsLong();
            int tActuales = (int) raffle.get("tickets_actuales").getAsLong();
            int ticketsFaltantes = tNecesarios - tActuales;
            for (int i = 0; i < ticketsFaltantes; i++) pool.add(-1);

            if (pool.isEmpty()) return;

            int ganadorId = pool.get(new Random().nextInt(pool.size()));

            String ganadorNombre = "Admin (Vacío)";
            String emailGanador = "N/A";

            if (ganadorId != -1) {
                JsonObject ganador = db.queryOne("SELECT username, email, telefono, player_tag FROM users WHERE id = ?", ganadorId);
                if (ganador != null) {
                    ganadorNombre = ganador.get("username").getAsString();
                    emailGanador = ganador.get("email").getAsString();
                }
            }

            if (ganadorId == -1) {
                db.update("UPDATE raffles SET estado = 'completado', ganador_id = NULL, ganador_nombre = ?, fecha_completado = NOW() WHERE id = ?",
                        ganadorNombre, raffleId);
            } else {
                db.update("UPDATE raffles SET estado = 'completado', ganador_id = ?, ganador_nombre = ?, fecha_completado = NOW() WHERE id = ?",
                        ganadorId, ganadorNombre, raffleId);
            }

            String nombre = raffle.get("nombre").getAsString();
            correo.notificarAdmin("SORTEO COMPLETADO: " + nombre,
                    "Ganador: " + ganadorNombre + " | Email: " + emailGanador);

            JsonObject ganadorData = new JsonObject();
            ganadorData.addProperty("raffleId", raffleId);
            ganadorData.addProperty("nombre", nombre);
            ganadorData.addProperty("ganadorNombre", ganadorNombre);
            ganadorData.addProperty("ganadorId", ganadorId);
            io.emit("sorteo_ganador", ganadorData);

            if (push != null && ganadorId != -1) {
                push.enviarPush(db, ganadorId, "🏆 ¡Ganaste el sorteo!", "¡Felicidades! Ganaste \"" + nombre + "\"");
            }

            logger.info("🏆 Sorteo #{} completado. Ganador: {}", raffleId, ganadorNombre);
        } catch (Exception e) {
            logger.error("Error ejecutando sorteo: " + e.getMessage());
        }
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
        try {
            JsonObject ticketRes = db.queryOne("SELECT * FROM user_tickets WHERE user_id = ?", userId);
            if (ticketRes == null) {
                db.update("INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, 0, 0)", userId);
                ticketRes = db.queryOne("SELECT * FROM user_tickets WHERE user_id = ?", userId);
            }

            int acumuladoAnterior = (int) ticketRes.get("acumulado").getAsLong();
            int nuevoAcumulado = acumuladoAnterior + montoComisionSorteo.intValue(); // REVIEW-MONEY (trunca a pesos enteros, igual que antes)
            int ticketsAnteriores = acumuladoAnterior / 1000;
            int ticketsGanados = nuevoAcumulado / 1000;
            int residuo = nuevoAcumulado % 1000;
            int ticketsNuevos = ticketsGanados - ticketsAnteriores;

            if (ticketsNuevos > 0) {
                db.update("UPDATE user_tickets SET cantidad = cantidad + ?, acumulado = ? WHERE user_id = ?",
                        ticketsNuevos, residuo, userId);
                return new int[]{ticketsNuevos, residuo};
            } else {
                db.update("UPDATE user_tickets SET acumulado = ? WHERE user_id = ?", nuevoAcumulado, userId);
                return new int[]{0, nuevoAcumulado};
            }
        } catch (Exception e) {
            logger.error("Error acumulando tickets: " + e.getMessage());
            return new int[]{0, 0};
        }
    }
}
