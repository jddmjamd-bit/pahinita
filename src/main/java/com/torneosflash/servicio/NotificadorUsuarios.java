package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

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
    private final NotificacionesService notificaciones;

    public NotificadorUsuarios(SocketIOServer io, GenericDAO db, NotificacionPushServicio push) {
        this(io, db, push, null);
    }

    public NotificadorUsuarios(SocketIOServer io, GenericDAO db, NotificacionPushServicio push,
                               NotificacionesService notificaciones) {
        this.io = io;
        this.db = db;
        this.push = push;
        this.notificaciones = notificaciones;
    }

    /** Notificación in-app + saldo actualizado por socket (D1: BigDecimal), y push. // REVIEW-MONEY */
    public void notificarSaldo(int userId, String mensaje, BigDecimal saldo) {
        BigDecimal saldoSeguro = saldo != null ? saldo : BigDecimal.ZERO;
        JsonObject notifData = new JsonObject();
        notifData.addProperty("mensaje", mensaje);
        notifData.addProperty("saldo", saldoSeguro);

        boolean encontrado = false;
        for (SocketIOClient client : io.getSockets().values()) {
            if (esUsuario(client, userId)) {
                encontrado = true;
                if (client.getUserData() != null) {
                    client.getUserData().addProperty("saldo", saldoSeguro);
                }
                client.emit("notificacion", notifData);
                client.emit("actualizar_saldo", new JsonPrimitive(saldoSeguro));
            }
        }
        if (!encontrado) {
            logger.debug("Usuario {} sin socket conectado; solo se envía push", userId);
        }

        if (notificaciones != null) {
            notificar(userId, "finanzas", "Movimiento de saldo", mensaje, "/finance", null,
                    notifData, true);
        } else if (push != null) {
            // Compatibilidad con llamadas antiguas y pruebas que crean el notificador sin el centro nuevo.
            push.enviarPush(db, userId, "Torneos Flash", mensaje);
        }
    }

    /** Solo push (llega con el navegador cerrado). No hace nada si el push no está configurado. */
    public void enviarPush(int userId, String titulo, String mensaje) {
        if (push != null) {
            push.enviarPush(db, userId, titulo, mensaje);
        }
    }

    public JsonObject notificar(int userId, String categoria, String titulo, String mensaje,
                                String ruta, String eventoClave, JsonElement datos, boolean pushSiDesconectado) {
        if (notificaciones != null) {
            return notificaciones.notificar(userId, categoria, titulo, mensaje, ruta,
                    eventoClave, datos, pushSiDesconectado);
        }
        JsonObject payload = new JsonObject();
        if (datos != null) payload.add("datos", datos);
        emitirA(userId, "notificacion_nueva", payload);
        if (pushSiDesconectado && push != null) push.enviarPush(db, userId, titulo, mensaje);
        return null;
    }

    public void notificarATodos(java.util.Set<Integer> excluir, String categoria, String titulo,
                                String mensaje, String ruta, String eventoClave, JsonElement datos,
                                boolean pushSiDesconectado) {
        if (notificaciones != null) {
            notificaciones.notificarATodos(excluir, categoria, titulo, mensaje, ruta,
                    eventoClave, datos, pushSiDesconectado);
        }
    }

    public void eliminarPorEvento(String eventoClave) {
        if (notificaciones != null) notificaciones.eliminarPorEvento(eventoClave);
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
