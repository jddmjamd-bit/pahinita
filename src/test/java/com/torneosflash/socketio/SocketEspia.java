package com.torneosflash.socketio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Espía de pruebas para capturar eventos emitidos a clientes de SocketIOServer sin abrir red.
 */
public class SocketEspia extends SocketIOServer {

    public record EventoEmitido(String room, String evento, JsonObject payload) {}

    public final List<EventoEmitido> emitidos = new ArrayList<>();
    private final ConcurrentHashMap<String, SocketIOClient> clientes = new ConcurrentHashMap<>();

    public SocketEspia() {
        super(null);
    }

    public void registrarUsuario(int userId) {
        JsonObject datosUsuario = new JsonObject();
        datosUsuario.addProperty("id", userId);
        SocketIOClient cliente = new SocketIOClient("espia-" + userId, null, this, null) {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public void emit(String event, Object... data) {
                JsonObject obj = data != null && data.length > 0 && data[0] instanceof JsonElement el && el.isJsonObject()
                        ? el.getAsJsonObject().deepCopy()
                        : new JsonObject();
                emitidos.add(new EventoEmitido("user:" + userId, event, obj));
            }
        };
        cliente.setUserData(datosUsuario);
        cliente.setAuthenticatedUserId(userId);
        cliente.setAuthenticatedSessionJti("test-jti-" + userId);
        clientes.put("espia-" + userId, cliente);
    }

    @Override
    public ConcurrentHashMap<String, SocketIOClient> getSockets() {
        return clientes;
    }

    @Override
    public ConcurrentHashMap<String, SocketIOClient> getTodosLosSockets() {
        return clientes;
    }

    @Override
    public RoomEmitter to(String room) {
        return new RoomEmitter(this, null, room, null) {
            @Override
            public void emit(String event, Object... data) {
                JsonObject obj = data != null && data.length > 0 && data[0] instanceof JsonElement el && el.isJsonObject()
                        ? el.getAsJsonObject().deepCopy()
                        : new JsonObject();
                emitidos.add(new EventoEmitido(room, event, obj));
            }
        };
    }
}
