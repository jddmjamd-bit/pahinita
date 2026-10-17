package com.torneosflash.socketio;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Representa un cliente Socket.IO conectado respaldado por {@code netty-socketio} (A1).
 * Mantiene la API usada por {@code SocketHandler} y los servicios, delegando las operaciones
 * de red, rooms y sesión al cliente real de Netty.
 */
public class SocketIOClient {
    private static final Logger logger = LoggerFactory.getLogger(SocketIOClient.class);

    private final String sid;
    private final com.corundumstudio.socketio.SocketIOClient nettyClient;
    private final SocketIOServer server;
    private final com.corundumstudio.socketio.SocketIONamespace namespace;
    private volatile boolean connected;
    private final Set<String> rooms = ConcurrentHashMap.newKeySet();

    // Handlers por cliente registrados con socket.on(...)
    private final ConcurrentHashMap<String, List<BiConsumer<SocketIOClient, Object[]>>> handlers = new ConcurrentHashMap<>();

    // Estado de sesión asociado al socket
    private JsonObject userData;
    private String currentRoom;
    private String busquedaNotifId;
    // S2: userId verificado desde la cookie httpOnly JWT durante el handshake
    private Integer authenticatedUserId;

    public SocketIOClient(
            String sid,
            com.corundumstudio.socketio.SocketIOClient nettyClient,
            SocketIOServer server,
            com.corundumstudio.socketio.SocketIONamespace namespace) {
        this.sid = sid;
        this.nettyClient = nettyClient;
        this.server = server;
        this.namespace = namespace;
        this.connected = true;
        this.userData = null;
        this.currentRoom = null;
        this.busquedaNotifId = null;
        this.authenticatedUserId = null;
    }

    /** Registra un listener de evento para este socket y asegura su registro en el namespace de Netty. */
    public void on(String event, BiConsumer<SocketIOClient, Object[]> handler) {
        if (event == null || handler == null) return;
        handlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(handler);
        server.ensureEventRegistered(namespace, event);
    }

    /** Emite un evento a ESTE cliente usando netty-socketio. */
    public void emit(String event, Object... data) {
        if (!isConnected() || nettyClient == null) return;
        try {
            Object[] payload = SocketIOServer.paraNettyArgs(data);
            nettyClient.sendEvent(event, payload);
        } catch (Exception e) {
            logger.error("Error enviando evento '{}' a {}: {}", event, sid, e.getMessage());
        }
    }

    // --- Operaciones de Rooms delegadas a netty-socketio ---

    public void join(String room) {
        if (room == null || room.isEmpty()) return;
        rooms.add(room);
        if (nettyClient != null) {
            nettyClient.joinRoom(room);
        }
    }

    public void leave(String room) {
        if (room == null || room.isEmpty()) return;
        rooms.remove(room);
        if (nettyClient != null) {
            nettyClient.leaveRoom(room);
        }
    }

    /**
     * Emitir a todos los clientes de una room EXCEPTO este socket.
     * Equivalente a {@code socket.to(room).emit(...)} en Socket.IO.
     */
    public SocketIOServer.RoomEmitter to(String room) {
        return new SocketIOServer.RoomEmitter(server, namespace, room, this.sid);
    }

    public void disconnect(boolean close) {
        this.connected = false;
        if (nettyClient != null) {
            try {
                nettyClient.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    void fireEvent(String event, Object[] args) {
        List<BiConsumer<SocketIOClient, Object[]>> handlerList = handlers.get(event);
        if (handlerList != null) {
            for (BiConsumer<SocketIOClient, Object[]> handler : handlerList) {
                try {
                    handler.accept(this, args);
                } catch (Exception e) {
                    logger.error("Error manejando evento '{}': {}", event, e.getMessage(), e);
                }
            }
        }
    }

    // --- Getters y Setters ---

    public String getSid() { return sid; }
    public String getId() { return sid; }

    public boolean isConnected() {
        return connected && nettyClient != null && nettyClient.isChannelOpen();
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public Set<String> getRooms() {
        if (nettyClient != null) {
            Set<String> all = new HashSet<>(nettyClient.getAllRooms());
            all.remove(""); // netty-socketio incluye la room por defecto ""
            all.addAll(rooms);
            return all;
        }
        return rooms;
    }

    com.corundumstudio.socketio.SocketIOClient getNettyClient() {
        return nettyClient;
    }

    public JsonObject getUserData() { return userData; }
    public void setUserData(JsonObject userData) { this.userData = userData; }

    public Integer getAuthenticatedUserId() { return authenticatedUserId; }
    public void setAuthenticatedUserId(Integer authenticatedUserId) { this.authenticatedUserId = authenticatedUserId; }

    public String getCurrentRoom() { return currentRoom; }
    public void setCurrentRoom(String currentRoom) { this.currentRoom = currentRoom; }

    public String getBusquedaNotifId() { return busquedaNotifId; }
    public void setBusquedaNotifId(String busquedaNotifId) { this.busquedaNotifId = busquedaNotifId; }
}
