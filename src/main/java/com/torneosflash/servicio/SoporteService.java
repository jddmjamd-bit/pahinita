package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;

import java.util.List;
import java.util.Set;

/**
 * Soporte: "Hablar con el admin" (roadmap 24.5). Sistema de tickets: el usuario escribe una consulta (pregunta,
 * sugerencia, bug, problema de su cuenta u otro), al admin le llega un correo y el admin responde desde su panel.
 * No conoce HTTP (A3): recibe tipos simples y lanza {@link ServicioException}.
 *
 * <h3>Flujo</h3>
 * <ol>
 *   <li>{@link #crear}: guarda el ticket ({@code estado = 'abierto'}) y emite {@link Eventos#SOPORTE_TICKET_CREADO};
 *       {@code SoporteListener} avisa al admin por correo.</li>
 *   <li>{@link #responder}: guarda la respuesta, deja el ticket {@code resuelto} y emite
 *       {@link Eventos#SOPORTE_TICKET_RESPONDIDO}; {@code SoporteListener} le avisa al usuario (socket + push) y le
 *       manda la respuesta a su correo. El usuario también la ve en su panel ({@link #misTickets}).</li>
 * </ol>
 *
 * <h3>Seguridad y abuso</h3>
 * <ul>
 *   <li>El usuario sale SIEMPRE de la sesión JWT, nunca del body; un usuario solo ve sus propios tickets.</li>
 *   <li>Los endpoints del admin exigen {@code tipo_suscripcion = 'admin'} (además de {@code AdminMiddleware}).</li>
 *   <li>Todo texto pasa por {@link ChatSanitizer#limpiarTexto} (sin etiquetas HTML ni caracteres de control) y tiene
 *       tope de largo; el frontend lo pinta como texto, nunca como HTML.</li>
 *   <li>Cada ticket manda un correo al admin, así que hay tope de tickets abiertos a la vez
 *       ({@code SOPORTE_MAX_ABIERTOS}) y de tickets por día ({@code SOPORTE_MAX_TICKETS_DIA}) por usuario. El tope
 *       se aplica dentro del mismo {@code INSERT} para que peticiones simultáneas no lo esquiven.</li>
 *   <li>Los datos del usuario que salen hacia el admin son solo id y username: nunca email, teléfono ni hash.</li>
 * </ul>
 */
public class SoporteService {

    public static final String CATEGORIA_PREGUNTA = "pregunta";
    public static final String CATEGORIA_SUGERENCIA = "sugerencia";
    public static final String CATEGORIA_BUG = "bug";
    public static final String CATEGORIA_CUENTA = "cuenta";
    public static final String CATEGORIA_OTRO = "otro";
    private static final Set<String> CATEGORIAS = Set.of(
            CATEGORIA_PREGUNTA, CATEGORIA_SUGERENCIA, CATEGORIA_BUG, CATEGORIA_CUENTA, CATEGORIA_OTRO);

    public static final String ESTADO_ABIERTO = "abierto";
    public static final String ESTADO_RESUELTO = "resuelto";
    private static final String FILTRO_TODOS = "todos";
    private static final Set<String> FILTROS_ADMIN = Set.of(ESTADO_ABIERTO, ESTADO_RESUELTO, FILTRO_TODOS);

    /** Largo máximo del asunto (una línea). */
    static final int MAX_ASUNTO = 100;
    /** Largo máximo del mensaje del usuario. */
    static final int MAX_MENSAJE = 2000;
    /** Largo máximo de la respuesta del admin. */
    static final int MAX_RESPUESTA = 3000;

    /** Cuántos tickets propios ve el usuario en su panel. */
    private static final int MAX_LISTA_USUARIO = 50;
    /** Cuántos tickets ve el admin por consulta. */
    private static final int MAX_LISTA_ADMIN = 200;

    private final GenericDAO db;
    private final EventBus eventos;
    private final AppConfig config;

    public SoporteService(GenericDAO db, EventBus eventos, AppConfig config) {
        this.db = db;
        this.eventos = eventos;
        this.config = config;
    }

    // ==================================================================
    // Usuario
    // ==================================================================

    /**
     * Abre un ticket nuevo.
     *
     * @return {@code {success, ticket}} con el ticket recién creado
     */
    public JsonObject crear(int userId, String categoriaCruda, String asuntoCrudo, String mensajeCrudo) {
        JsonObject usuario = exigirUsuario(userId);

        String categoria = categoriaCruda == null ? "" : categoriaCruda.trim().toLowerCase(java.util.Locale.ROOT);
        if (!CATEGORIAS.contains(categoria)) {
            throw ServicioException.solicitudInvalida(
                    "Categoría no válida. Usa pregunta, sugerencia, bug, cuenta u otro");
        }
        // El asunto es de una sola línea: los saltos y espacios repetidos se juntan
        String asunto = ChatSanitizer.limpiarTexto(asuntoCrudo, MAX_ASUNTO).replaceAll("\\s+", " ").trim();
        String mensaje = ChatSanitizer.limpiarTexto(mensajeCrudo, MAX_MENSAJE);
        if (asunto.isEmpty()) throw ServicioException.solicitudInvalida("Escribe el asunto");
        if (mensaje.isEmpty()) throw ServicioException.solicitudInvalida("Escribe tu mensaje");

        verificarCupo(userId); // mensajes claros; el tope real lo garantiza el INSERT de abajo

        int id = db.insertReturningId("INSERT INTO support_tickets (user_id, categoria, asunto, mensaje) "
                + "SELECT ?::integer, ?::text, ?::text, ?::text "
                + "WHERE (SELECT COUNT(*) FROM support_tickets WHERE user_id = ? AND estado = 'abierto') < ? "
                + "AND (SELECT COUNT(*) FROM support_tickets WHERE user_id = ? "
                + "AND fecha_creacion > NOW() - INTERVAL '1 day') < ? "
                + "RETURNING id",
                userId, categoria, asunto, mensaje,
                userId, config.getSoporteMaxAbiertos(),
                userId, config.getSoporteMaxTicketsDia());
        if (id <= 0) {
            // Sin fila: o una petición simultánea llenó el cupo (verificarCupo lanza el 429 que corresponde) o falló la BD
            verificarCupo(userId);
            throw ServicioException.interno("No se pudo enviar tu mensaje. Inténtalo de nuevo", null);
        }

        // El ticket ya está guardado; los listeners solo avisan (emit no lanza)
        eventos.emit(Eventos.SOPORTE_TICKET_CREADO, new Eventos.TicketCreado(
                id, userId, textoOVacio(usuario, "username"), categoria, asunto, mensaje));

        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.add("ticket", db.queryOne("SELECT id, categoria, asunto, mensaje, estado, respuesta, fecha_creacion, "
                + "fecha_respuesta FROM support_tickets WHERE id = ? AND user_id = ?", id, userId));
        return r;
    }

    /** Los tickets del propio usuario (más recientes primero), con la respuesta del admin si ya la hay. */
    public List<JsonObject> misTickets(int userId) {
        exigirUsuario(userId);
        return db.query("SELECT id, categoria, asunto, mensaje, estado, respuesta, fecha_creacion, fecha_respuesta "
                + "FROM support_tickets WHERE user_id = ? ORDER BY id DESC LIMIT ?", userId, MAX_LISTA_USUARIO);
    }

    // ==================================================================
    // Admin
    // ==================================================================

    /**
     * Bandeja del admin. {@code estado}: {@code abierto} (los más viejos primero), {@code resuelto} o {@code todos}
     * (los más recientes primero). Lleva id y username de quien escribió; nunca email ni teléfono.
     */
    public List<JsonObject> listarAdmin(int adminId, String estado) {
        exigirAdmin(adminId);
        if (estado == null || !FILTROS_ADMIN.contains(estado)) {
            throw ServicioException.solicitudInvalida("Estado no válido. Usa abierto, resuelto o todos");
        }
        String columnas = "SELECT t.id, t.user_id, u.username, t.categoria, t.asunto, t.mensaje, t.estado, "
                + "t.respuesta, t.fecha_creacion, t.fecha_respuesta FROM support_tickets t "
                + "JOIN users u ON u.id = t.user_id ";
        if (FILTRO_TODOS.equals(estado)) {
            return db.query(columnas + "ORDER BY t.id DESC LIMIT ?", MAX_LISTA_ADMIN);
        }
        String orden = ESTADO_ABIERTO.equals(estado) ? "ASC" : "DESC"; // pendientes: primero el que lleva más esperando
        return db.query(columnas + "WHERE t.estado = ? ORDER BY t.id " + orden + " LIMIT ?", estado, MAX_LISTA_ADMIN);
    }

    /**
     * El admin responde un ticket abierto. Queda {@code resuelto} y se emite
     * {@link Eventos#SOPORTE_TICKET_RESPONDIDO}. Un ticket ya resuelto no se puede volver a responder (400).
     */
    public JsonObject responder(int adminId, int ticketId, String respuestaCruda) {
        exigirAdmin(adminId);
        String respuesta = ChatSanitizer.limpiarTexto(respuestaCruda, MAX_RESPUESTA);
        if (respuesta.isEmpty()) throw ServicioException.solicitudInvalida("Escribe la respuesta");

        // Una sola sentencia: si dos admins responden a la vez, solo una gana y solo se avisa una vez
        int filas = db.update("UPDATE support_tickets SET respuesta = ?, estado = 'resuelto', respondido_por = ?, "
                + "fecha_respuesta = CURRENT_TIMESTAMP WHERE id = ? AND estado = 'abierto'",
                respuesta, adminId, ticketId);
        if (filas == 0) {
            JsonObject existente = db.queryOne("SELECT id FROM support_tickets WHERE id = ?", ticketId);
            if (existente == null) throw ServicioException.noEncontrado("Ese ticket no existe");
            throw ServicioException.solicitudInvalida("Ese ticket ya fue respondido");
        }

        JsonObject ticket = db.queryOne("SELECT t.id, t.user_id, u.username, t.categoria, t.asunto "
                + "FROM support_tickets t JOIN users u ON u.id = t.user_id WHERE t.id = ?", ticketId);
        if (ticket != null) {
            // La respuesta ya está guardada; los listeners solo la reparten (emit no lanza)
            eventos.emit(Eventos.SOPORTE_TICKET_RESPONDIDO, new Eventos.TicketRespondido(
                    ticketId,
                    ticket.get("user_id").getAsInt(),
                    textoOVacio(ticket, "username"),
                    textoOVacio(ticket, "categoria"),
                    textoOVacio(ticket, "asunto"),
                    respuesta));
        }

        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("id", ticketId);
        return r;
    }

    // ==================================================================
    // Piezas internas
    // ==================================================================

    /**
     * Correo de la cuenta del usuario, o {@code ""} si no tiene. Solo lo usa {@code SoporteListener} para enviarle
     * SU propia respuesta; nunca sale hacia el admin ni hacia otros usuarios.
     */
    public String correoDeUsuario(int userId) {
        JsonObject fila = db.queryOne("SELECT email FROM users WHERE id = ?", userId);
        return fila == null ? "" : textoOVacio(fila, "email").trim();
    }

    /** Lanza 429 si el usuario ya llegó al tope de tickets abiertos o de tickets en las últimas 24 horas. */
    private void verificarCupo(int userId) {
        JsonObject fila = db.queryOne("SELECT "
                + "(SELECT COUNT(*) FROM support_tickets WHERE user_id = ? AND estado = 'abierto') AS abiertos, "
                + "(SELECT COUNT(*) FROM support_tickets WHERE user_id = ? "
                + "AND fecha_creacion > NOW() - INTERVAL '1 day') AS hoy", userId, userId);
        if (fila == null) throw ServicioException.interno("No se pudo enviar tu mensaje. Inténtalo de nuevo", null);

        if (fila.get("abiertos").getAsLong() >= config.getSoporteMaxAbiertos()) {
            throw ServicioException.demasiadasSolicitudes("Ya tienes " + config.getSoporteMaxAbiertos()
                    + " consultas sin responder. Espera la respuesta del administrador antes de enviar otra");
        }
        if (fila.get("hoy").getAsLong() >= config.getSoporteMaxTicketsDia()) {
            throw ServicioException.demasiadasSolicitudes(
                    "Llegaste al límite de consultas de hoy. Vuelve a intentarlo mañana");
        }
    }

    /** Usuario de la sesión; 401 si no hay sesión o el usuario ya no existe. */
    private JsonObject exigirUsuario(int userId) {
        if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión para hablar con el administrador");
        JsonObject usuario = db.queryOne("SELECT id, username, tipo_suscripcion FROM users WHERE id = ?", userId);
        if (usuario == null) throw ServicioException.noAutenticado("Sesión inválida. Vuelve a iniciar sesión");
        return usuario;
    }

    /** Usuario de la sesión, que además debe ser admin ({@code tipo_suscripcion = 'admin'}); 403 si no lo es. */
    private JsonObject exigirAdmin(int userId) {
        JsonObject usuario = exigirUsuario(userId);
        if (!"admin".equals(textoOVacio(usuario, "tipo_suscripcion"))) {
            throw ServicioException.prohibido("Solo los administradores pueden hacer esto");
        }
        return usuario;
    }

    private static String textoOVacio(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        return (valor == null || valor.isJsonNull()) ? "" : valor.getAsString();
    }
}
