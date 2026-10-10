package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Avisa a un usuario por socket (si está conectado) y por push (llega con el navegador cerrado).
 * Antes era un método {@code static} dentro de {@code RutasFinanzas} que usaba otro {@code static} para el
 * servicio de push; ahora es un servicio normal que comparten finanzas, admin, etc. (A3).
 */
public class NotificadorUsuarios {
    private static final Logger logger = LoggerFactory.getLogger(NotificadorUsuarios.class);

    private final SocketIOServer io;
    private final GenericDAO db;
    private final NotificacionPushServicio push;

    public NotificadorUsuarios(SocketIOServer io, GenericDAO db, NotificacionPushServicio push) {
        this.io = io;
        this.db = db;
        this.push = push;
    }

    /** Notificación in-app + saldo actualizado por socket, y push. */
    public void notificarSaldo(int userId, String mensaje, double saldo) {
        JsonObject notifData = new JsonObject();
        notifData.addProperty("mensaje", mensaje);
        notifData.addProperty("saldo", saldo);

        boolean encontrado = false;
        for (SocketIOClient client : io.getSockets().values()) {
            if (esUsuario(client, userId)) {
                encontrado = true;
                client.emit("notificacion", notifData);
                client.emit("actualizar_saldo", new JsonPrimitive(saldo));
            }
        }
        if (!encontrado) {
            logger.debug("Usuario {} sin socket conectado; solo se envía push", userId);
        }

        // Push (llega incluso con el navegador cerrado)
        if (push != null) {
            push.enviarPush(db, userId, "Torneos Flash", mensaje);
        }
    }

    /** Solo push (llega con el navegador cerrado). No hace nada si el push no está configurado. */
    public void enviarPush(int userId, String titulo, String mensaje) {
        if (push != null) {
            push.enviarPush(db, userId, titulo, mensaje);
        }
    }

    /** Emite un evento a todos los sockets abiertos de un usuario. */
    public void emitirA(int userId, String evento, JsonElement datos) {
        for (SocketIOClient client : io.getSockets().values()) {
            if (esUsuario(client, userId)) {
                client.emit(evento, datos);
            }
        }
    }

    private static boolean esUsuario(SocketIOClient client, int userId) {
        JsonObject datos = client.getUserData();
        return datos != null && datos.has("id") && datos.get("id").getAsInt() == userId;
    }
}
