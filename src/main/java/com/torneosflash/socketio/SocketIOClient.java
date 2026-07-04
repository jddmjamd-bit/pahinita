package com.torneosflash.socketio;

import com.google.gson.*;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Representa un cliente Socket.IO conectado.
 * Equivalente a 'socket' en Node.js socket.io.
 *
 * CONCEPTO POO: Encapsulamiento - datos privados con getters/setters
 * CONCEPTO POO: Collection - HashMap para handlers y Set para rooms
 */
public class SocketIOClient {

    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private final String sid;
    private WsContext wsContext;
    private final SocketIOServer server;
    private boolean connected;
    private final Set<String> rooms = ConcurrentHashMap.newKeySet();

    // Per-client event handlers
    private final ConcurrentHashMap<String, List<BiConsumer<SocketIOClient, Object[]>>> handlers = new ConcurrentHashMap<>();

    // Campos públicos para compatibilidad con el código Node.js:
    // socket.userData, socket.currentRoom, socket.busquedaNotifId
    private JsonObject userData;
    private String currentRoom;
    private String busquedaNotifId;

    // --- Constructor (CONSTRUCTOR) ---
    public SocketIOClient(String sid, WsConnectContext ctx, SocketIOServer server) {
        this.sid = sid;
        this.wsContext = ctx;
        this.server = server;
        this.connected = true;
        this.userData = null;
        this.currentRoom = null;
        this.busquedaNotifId = null;
    }

    // --- Registrar event handler para este socket ---
    public void on(String event, BiConsumer<SocketIOClient, Object[]> handler) {
        handlers.computeIfAbsent(event, k -> new ArrayList<>()).add(handler);
    }

    // --- Emitir evento a ESTE cliente ---
    public void emit(String event, Object... data) {
        if (!connected || wsContext == null) return;
        String packet = server.buildEventPacket(event, data);
        sendRaw(packet);
    }

    // --- Enviar raw packet ---
    void sendRaw(String raw) {
        try {
            if (connected && wsContext != null) {
                wsContext.send(raw);
            }
        } catch (Exception e) {
            System.err.println("Error sending to " + sid + ": " + e.getMessage());
            connected = false;
        }
    }

    // --- Room operations ---
    public void join(String room) {
        rooms.add(room);
        server.joinRoom(this, room);
    }

    public void leave(String room) {
        rooms.remove(room);
        server.leaveRoom(this, room);
    }

    /**
     * Emitir a una room EXCEPTO este socket.
     * Equivalente a socket.to(room).emit() en Node.js
     */
    public SocketIOServer.RoomEmitter to(String room) {
        return new SocketIOServer.RoomEmitter(server, room, this.sid);
    }

    // --- Disconnect ---
    public void disconnect(boolean close) {
        this.connected = false;
        if (close && wsContext != null) {
            try { wsContext.closeSession(); } catch (Exception ignored) {}
        }
    }

    // --- Fire event handlers ---
    void fireEvent(String event, Object[] args) {
        List<BiConsumer<SocketIOClient, Object[]>> handlerList = handlers.get(event);
        if (handlerList != null) {
            for (BiConsumer<SocketIOClient, Object[]> handler : handlerList) {
                try {
                    handler.accept(this, args);
                } catch (Exception e) {
                    System.err.println("Error handling event '" + event + "': " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    // --- Getters y Setters (GETTER Y SETTER) ---
    public String getSid() { return sid; }
    public String getId() { return sid; } // Alias para compatibilidad
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public Set<String> getRooms() { return rooms; }

    public JsonObject getUserData() { return userData; }
    public void setUserData(JsonObject userData) { this.userData = userData; }

    public String getCurrentRoom() { return currentRoom; }
    public void setCurrentRoom(String currentRoom) { this.currentRoom = currentRoom; }

    public String getBusquedaNotifId() { return busquedaNotifId; }
    public void setBusquedaNotifId(String busquedaNotifId) { this.busquedaNotifId = busquedaNotifId; }
}
