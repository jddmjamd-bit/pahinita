package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Persistencia, reparto en vivo y push de los avisos del usuario. */
public final class NotificacionesService {
    private static final Set<String> CATEGORIAS = Set.of(
            "chat", "soporte", "asistente", "ranking", "sorteos", "partida", "finanzas");

    private final GenericDAO db;
    private final SocketIOServer io;
    private final NotificacionPushServicio push;
    private final String siteUrl;
    private final Gson gson = new Gson();

    public NotificacionesService(GenericDAO db, SocketIOServer io,
                                 NotificacionPushServicio push, String siteUrl) {
        this.db = db;
        this.io = io;
        this.push = push;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    /** Guarda el aviso, actualiza el punto del menú y manda push solo cuando el usuario no está conectado. */
    public JsonObject notificar(int userId, String categoria, String titulo, String mensaje,
                                String ruta, String eventoClave, JsonElement datos, boolean pushSiDesconectado) {
        if (userId <= 0 || !CATEGORIAS.contains(categoria)) return null;
        String rutaSegura = rutaSegura(ruta);
        String datosJson = datos == null || datos.isJsonNull() ? "{}" : datos.toString();
        int id = db.insertReturningId(
                "INSERT INTO user_notifications " +
                        "(user_id, categoria, titulo, mensaje, ruta, evento_clave, datos) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) " +
                        "ON CONFLICT (user_id, evento_clave) WHERE evento_clave IS NOT NULL DO NOTHING " +
                        "RETURNING id",
                userId, categoria, limitar(titulo, 140), limitar(mensaje, 500), rutaSegura, eventoClave, datosJson);
        if (id <= 0) return null;

        JsonObject aviso = db.queryOne(
                "SELECT id, categoria, titulo, mensaje, ruta, evento_clave, datos, leida_en, creada_en " +
                        "FROM user_notifications WHERE id = ? AND user_id = ?",
                id, userId);
        if (aviso == null) return null;
        emitirAUsuario(userId, "notificacion_nueva", aviso);

        if (pushSiDesconectado && !estaEnLinea(userId) && push != null) {
            String destino = siteUrl + rutaSegura;
            String link = destino + (destino.contains("?") ? "&" : "?") + "notificacionId=" + id;
            push.enviarPush(db, userId, limitar(titulo, 140), limitar(mensaje, 500), link,
                    eventoClave == null ? "tf-" + id : etiquetaPush(eventoClave), String.valueOf(id));
        }
        return aviso;
    }

    /** Crea un aviso para todos los usuarios salvo los indicados. */
    public void notificarATodos(Set<Integer> excluir, String categoria, String titulo, String mensaje,
                                String ruta, String eventoClave, JsonElement datos, boolean pushSiDesconectado) {
        Set<Integer> excluidos = excluir == null ? Set.of() : new HashSet<>(excluir);
        ArrayList<JsonObject> usuarios = db.query("SELECT id FROM users ORDER BY id");
        for (JsonObject usuario : usuarios) {
            int userId = usuario.get("id").getAsInt();
            if (excluidos.contains(userId)) continue;
            notificar(userId, categoria, titulo, mensaje, ruta,
                    eventoClave == null ? null : eventoClave + ":" + userId, datos, pushSiDesconectado);
        }
    }

    public JsonObject listar(int userId) {
        JsonObject resultado = new JsonObject();
        resultado.add("items", gson.toJsonTree(db.query(
                "SELECT id, categoria, titulo, mensaje, ruta, evento_clave, datos, leida_en, creada_en " +
                        "FROM user_notifications WHERE user_id = ? AND descartada_en IS NULL " +
                        "ORDER BY id DESC LIMIT 100", userId)));
        JsonObject sinLeer = new JsonObject();
        for (JsonObject fila : db.query(
                "SELECT categoria, COUNT(*) AS total FROM user_notifications " +
                        "WHERE user_id = ? AND leida_en IS NULL AND descartada_en IS NULL " +
                        "GROUP BY categoria", userId)) {
            sinLeer.addProperty(fila.get("categoria").getAsString(), fila.get("total").getAsInt());
        }
        resultado.add("sinLeer", sinLeer);
        return resultado;
    }

    public void marcarCategoriaLeida(int userId, String categoria) {
        if (userId <= 0 || !CATEGORIAS.contains(categoria)) return;
        db.update("UPDATE user_notifications SET leida_en = CURRENT_TIMESTAMP " +
                "WHERE user_id = ? AND categoria = ? AND leida_en IS NULL AND descartada_en IS NULL",
                userId, categoria);
        JsonObject datos = new JsonObject();
        datos.addProperty("categoria", categoria);
        emitirAUsuario(userId, "notificaciones_leidas", datos);
    }

    public boolean marcarLeida(int userId, long notificationId) {
        if (userId <= 0 || notificationId <= 0) return false;
        int filas = db.update("UPDATE user_notifications SET leida_en = COALESCE(leida_en, CURRENT_TIMESTAMP) " +
                "WHERE id = ? AND user_id = ? AND descartada_en IS NULL", notificationId, userId);
        if (filas > 0) {
            JsonObject datos = new JsonObject();
            datos.addProperty("id", notificationId);
            emitirAUsuario(userId, "notificacion_leida", datos);
        }
        return filas > 0;
    }

    public boolean descartar(int userId, long notificationId) {
        if (userId <= 0 || notificationId <= 0) return false;
        int filas = db.update("UPDATE user_notifications SET descartada_en = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND user_id = ? AND descartada_en IS NULL", notificationId, userId);
        if (filas > 0) {
            JsonObject datos = new JsonObject();
            datos.addProperty("id", notificationId);
            emitirAUsuario(userId, "notificacion_eliminada", datos);
        }
        return filas > 0;
    }

    /** Elimina el aviso efímero de una búsqueda cuando se empareja, se cancela o vence. */
    public void eliminarPorEvento(String eventoClave) {
        if (eventoClave == null || eventoClave.isBlank()) return;
        ArrayList<JsonObject> usuarios = db.query(
                "SELECT DISTINCT user_id FROM user_notifications WHERE evento_clave LIKE ? " +
                        "AND descartada_en IS NULL", escaparLike(eventoClave) + ":%");
        db.update("DELETE FROM user_notifications WHERE evento_clave LIKE ?", escaparLike(eventoClave) + ":%");
        for (JsonObject fila : usuarios) {
            int userId = fila.get("user_id").getAsInt();
            JsonObject datos = new JsonObject();
            datos.addProperty("eventoClave", eventoClave + ":" + userId);
            emitirAUsuario(userId, "notificacion_eliminada", datos);
            if (!estaEnLinea(userId) && push != null) {
                push.enviarControlPush(db, userId, etiquetaPush(eventoClave));
            }
        }
    }

    public boolean estaEnLinea(int userId) {
        for (SocketIOClient socket : io.getSockets().values()) {
            if (!socket.isConnected()) continue;
            Integer autenticado = socket.getAuthenticatedUserId();
            if (autenticado != null && autenticado == userId) return true;
            JsonObject datos = socket.getUserData();
            if (datos != null && datos.has("id") && !datos.get("id").isJsonNull()
                    && datos.get("id").getAsInt() == userId) return true;
        }
        return false;
    }

    public void emitirAUsuario(int userId, String evento, JsonElement datos) {
        for (SocketIOClient socket : io.getSockets().values()) {
            if (!socket.isConnected()) continue;
            Integer autenticado = socket.getAuthenticatedUserId();
            JsonObject usuario = socket.getUserData();
            boolean corresponde = autenticado != null && autenticado == userId;
            if (!corresponde && usuario != null && usuario.has("id") && !usuario.get("id").isJsonNull()) {
                corresponde = usuario.get("id").getAsInt() == userId;
            }
            if (corresponde) socket.emit(evento, datos);
        }
    }

    private static String rutaSegura(String ruta) {
        if (ruta == null || !ruta.startsWith("/") || ruta.startsWith("//") || ruta.contains("\\")) return "/chat";
        return ruta.length() > 180 ? "/chat" : ruta;
    }

    private static String limitar(String texto, int maximo) {
        if (texto == null) return "";
        String limpio = texto.trim();
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo - 1) + "…";
    }

    private static String etiquetaPush(String valor) {
        // La búsqueda agrega al final el id del destinatario; el mismo tag permite cerrarla
        // al emparejar, cancelar o vencer, sin afectar los avisos de búsquedas distintas.
        if (valor != null && valor.startsWith("busqueda:")) {
            int segundoSeparador = valor.indexOf(':', "busqueda:".length());
            if (segundoSeparador > 0) valor = valor.substring(0, segundoSeparador);
        }
        String limpio = valor == null ? "tf" : valor.replaceAll("[^A-Za-z0-9_-]", "-");
        return limpio.length() <= 40 ? limpio : limpio.substring(0, 40);
    }

    private static String escaparLike(String valor) {
        return valor.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
