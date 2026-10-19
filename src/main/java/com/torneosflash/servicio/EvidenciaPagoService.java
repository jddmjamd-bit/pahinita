package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evidencias de pago (roadmap 7): cuando el admin paga un retiro por fuera (Nequi, etc.) sube la imagen del
 * comprobante y queda publicada en el canal «🧾 Pagos» del chat, que ven todos los usuarios. No conoce HTTP (A3).
 */
public class EvidenciaPagoService {

    /** Canal del chat donde se muestran las evidencias. */
    public static final String CANAL = "pagos";

    private static final int MAX_NOTA = 200;
    /** Cuántos retiros sin evidencia se le ofrecen al admin. */
    private static final int MAX_LISTA_ADMIN = 50;

    private final GenericDAO db;
    private final EventBus eventos;
    private final AppConfig config;

    public EvidenciaPagoService(GenericDAO db, EventBus eventos, AppConfig config) {
        this.db = db;
        this.eventos = eventos;
        this.config = config;
    }

    // ==================================================================
    // Admin
    // ==================================================================

    /** Retiros ya aprobados que todavía no tienen evidencia (más recientes primero). Solo admin. */
    public List<JsonObject> retirosSinEvidencia(int adminId) {
        exigirAdmin(adminId);
        return db.query("SELECT t.id, t.usuario_id, u.username, t.metodo, t.monto, t.fecha "
                + "FROM transactions t JOIN users u ON u.id = t.usuario_id "
                + "WHERE t.tipo = 'retiro' AND t.estado = 'completado' "
                + "AND NOT EXISTS (SELECT 1 FROM payment_evidences e WHERE e.transaction_id = t.id) "
                + "ORDER BY t.id DESC LIMIT ?", MAX_LISTA_ADMIN);
    }

    /**
     * Publica la evidencia de un retiro.
     *
     * @param imagen data URI base64 de la imagen del comprobante
     * @param nota   texto opcional (se limpia y se recorta); puede ser null
     * @return {@code {success, id}}
     */
    public JsonObject publicar(int adminId, int transaccionId, String imagen, String nota) {
        JsonObject admin = exigirAdmin(adminId);
        validarImagen(imagen);
        String notaLimpia = ChatSanitizer.limpiarTexto(nota, MAX_NOTA);

        // Comprobaciones previas solo para dar un mensaje claro; el INSERT de abajo repite las condiciones
        JsonObject trans = db.queryOne("SELECT id, tipo, estado FROM transactions WHERE id = ?", transaccionId);
        if (trans == null) throw ServicioException.noEncontrado("Ese retiro no existe");
        if (!"retiro".equals(texto(trans, "tipo"))) {
            throw ServicioException.solicitudInvalida("Solo se publica evidencia de retiros");
        }
        if (!"completado".equals(texto(trans, "estado"))) {
            throw ServicioException.solicitudInvalida(
                    "Primero aprueba el retiro: la evidencia solo se publica cuando el pago ya se hizo");
        }

        // Una sola sentencia: usuario, monto y método salen de la transacción, y si dos admins publican a la vez
        // para el mismo retiro, solo una fila entra (UNIQUE) y solo se avisa una vez.
        int id = db.insertReturningId("INSERT INTO payment_evidences (transaction_id, user_id, admin_id, monto, metodo, nota, imagen) "
                + "SELECT t.id, t.usuario_id, ?::integer, t.monto, t.metodo, ?::text, ?::text "
                + "FROM transactions t WHERE t.id = ? AND t.tipo = 'retiro' AND t.estado = 'completado' "
                + "ON CONFLICT (transaction_id) DO NOTHING RETURNING id",
                adminId, notaLimpia, imagen, transaccionId);
        if (id <= 0) {
            JsonObject existente = db.queryOne("SELECT id FROM payment_evidences WHERE transaction_id = ?", transaccionId);
            if (existente != null) throw ServicioException.solicitudInvalida("Ese retiro ya tiene evidencia publicada");
            throw ServicioException.interno("No se pudo publicar la evidencia. Inténtalo de nuevo", null);
        }

        JsonObject fila = db.queryOne("SELECT e.monto, e.metodo, e.nota, e.fecha, e.user_id, u.username "
                + "FROM payment_evidences e JOIN users u ON u.id = e.user_id WHERE e.id = ?", id);
        if (fila != null) {
            // Ya está guardada; los listeners solo la reparten (emit no lanza)
            eventos.emit(Eventos.PAGOS_EVIDENCIA_PUBLICADA, new Eventos.EvidenciaPublicada(
                    id, transaccionId,
                    fila.get("user_id").getAsInt(),
                    texto(fila, "username"),
                    texto(admin, "username"),
                    fila.get("monto").getAsBigDecimal(), // D1: BigDecimal directo // REVIEW-MONEY
                    texto(fila, "metodo"),
                    texto(fila, "nota"),
                    imagen,
                    texto(fila, "fecha")));
        }

        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("id", id);
        return r;
    }

    // ==================================================================
    // Cualquier usuario con sesión
    // ==================================================================

    /**
     * Las últimas evidencias, de la más vieja a la más nueva, ya con la forma de un mensaje de chat
     * ({@link #mensajeChat}). Se pide al abrir el canal «Pagos» (no viajan en la conexión del socket por su peso).
     */
    public List<JsonObject> historial(int userId) {
        JsonObject usuario = exigirUsuario(userId);
        String consultaBase = "SELECT e.id, e.transaction_id, e.monto, e.metodo, e.nota, e.imagen, e.fecha, "
                + "uu.username AS para, COALESCE(ua.username, 'admin') AS admin "
                + "FROM payment_evidences e JOIN users uu ON uu.id = e.user_id "
                + "LEFT JOIN users ua ON ua.id = e.admin_id ";

        List<JsonObject> filas;
        if ("admin".equals(texto(usuario, "tipo_suscripcion"))) {
            filas = db.query(consultaBase + "ORDER BY e.id DESC LIMIT ?", config.getPagosHistorialLimite());
        } else {
            filas = db.query(consultaBase + "WHERE e.user_id = ? ORDER BY e.id DESC LIMIT ?",
                    userId, config.getPagosHistorialLimite());
        }
        Collections.reverse(filas); // la consulta trae las más nuevas primero; el chat las muestra de vieja a nueva
        List<JsonObject> mensajes = new ArrayList<>();
        for (JsonObject f : filas) {
            mensajes.add(mensajeChat(
                    f.get("id").getAsInt(), f.get("transaction_id").getAsInt(),
                    texto(f, "admin"), texto(f, "para"), f.get("monto").getAsBigDecimal(),
                    texto(f, "metodo"), texto(f, "nota"), texto(f, "imagen"), texto(f, "fecha")));
        }
        return mensajes;
    }

    /**
     * Forma de un mensaje del canal «pagos»: la misma que los demás mensajes de chat ({@code canal, usuario, texto,
     * tipo, fecha}) más {@code evidencia} con los datos del pago. Lo usan el historial y el listener en vivo.
     */
    public static JsonObject mensajeChat(int evidenciaId, int transaccionId, String admin, String para,
                                         Number monto, String metodo, String nota, String imagen, String fecha) {
        JsonObject evidencia = new JsonObject();
        evidencia.addProperty("id", evidenciaId);
        evidencia.addProperty("transaccionId", transaccionId);
        evidencia.addProperty("para", para);
        evidencia.addProperty("monto", monto);
        evidencia.addProperty("metodo", metodo);
        evidencia.addProperty("nota", nota);

        JsonObject mensaje = new JsonObject();
        mensaje.addProperty("canal", CANAL);
        mensaje.addProperty("usuario", admin);
        mensaje.addProperty("texto", imagen);
        mensaje.addProperty("tipo", "imagen");
        mensaje.addProperty("fecha", fecha);
        mensaje.add("evidencia", evidencia);
        return mensaje;
    }

    // ==================================================================
    // Piezas internas
    // ==================================================================

    /** Solo data URI base64 de png/jpeg/gif/webp y dentro del tope; nada de /api/media ni de SVG. */
    private void validarImagen(String imagen) {
        if (imagen == null || imagen.isBlank()) throw ServicioException.solicitudInvalida("Adjunta la imagen del comprobante");
        if (imagen.length() > config.getPagosMaxImagenChars()) {
            throw ServicioException.solicitudInvalida("La imagen es muy pesada. Usa una captura más pequeña");
        }
        if (!imagen.startsWith("data:image/") || !ChatSanitizer.esImagenValida(imagen)) {
            throw ServicioException.solicitudInvalida("La imagen no es válida. Usa png, jpg, gif o webp");
        }
    }

    /** Usuario de la sesión; 401 si no hay sesión o el usuario ya no existe. */
    private JsonObject exigirUsuario(int userId) {
        if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión para ver las evidencias de pago");
        JsonObject usuario = db.queryOne("SELECT id, username, tipo_suscripcion FROM users WHERE id = ?", userId);
        if (usuario == null) throw ServicioException.noAutenticado("Sesión inválida. Vuelve a iniciar sesión");
        return usuario;
    }

    /** Usuario de la sesión que además es admin ({@code tipo_suscripcion = 'admin'}); 403 si no lo es. */
    private JsonObject exigirAdmin(int userId) {
        JsonObject usuario = exigirUsuario(userId);
        if (!"admin".equals(texto(usuario, "tipo_suscripcion"))) {
            throw ServicioException.prohibido("Solo los administradores pueden hacer esto");
        }
        return usuario;
    }

    private static String texto(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        return (valor == null || valor.isJsonNull()) ? "" : valor.getAsString();
    }
}
