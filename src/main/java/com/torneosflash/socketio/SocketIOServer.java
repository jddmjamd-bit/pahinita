package com.torneosflash.socketio;

import com.google.gson.*;
import io.javalin.Javalin;
import io.javalin.websocket.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Adaptador Socket.IO compatible con socket.io-client v4.
 * Implementa el protocolo Engine.IO v4 + Socket.IO v4 usando WebSockets de Javalin.
 *
 * CONCEPTO POO: Collection - ConcurrentHashMap para clientes, rooms, handlers
 * CONCEPTO POO: Encapsulamiento - toda la lógica de protocolo es interna
 */
public class SocketIOServer {

    // --- COLLECTION: ConcurrentHashMap para hilos seguros ---
    private final ConcurrentHashMap<String, SocketIOClient> clientsBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SocketIOClient> clientsByWsId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> rooms = new ConcurrentHashMap<>();

    // Handlers para eventos del namespace "/"
    private BiConsumer<SocketIOClient, Object[]> connectionHandler;

    // Engine.IO config
    private static final int PING_INTERVAL = 25000;
    private static final int PING_TIMEOUT = 5000;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // Polling sessions: sid -> pending messages queue
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> pollingQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> upgradedSessions = new ConcurrentHashMap<>();

    /**
     * Registrar handler para evento "connection" (cuando un nuevo cliente se conecta).
     */
    public void onConnection(BiConsumer<SocketIOClient, Object[]> handler) {
        this.connectionHandler = handler;
    }

    /**
     * Emitir evento a TODOS los clientes conectados.
     * CONCEPTO POO: Polimorfismo - opera sobre la colección de clientes
     */
    public void emit(String event, Object... data) {
        String packet = buildEventPacket(event, data);
        for (SocketIOClient client : clientsBySession.values()) {
            if (client.isConnected()) {
                client.sendRaw(packet);
            }
        }
    }

    /**
     * Obtener un emitter para una room específica.
     */
    public RoomEmitter to(String room) {
        return new RoomEmitter(this, room, null);
    }

    /**
     * Obtener mapa de sockets (equivalente a io.sockets.sockets en Node.js).
     */
    public ConcurrentHashMap<String, SocketIOClient> getSockets() {
        return clientsBySession;
    }

    /**
     * Registrar las rutas HTTP + WebSocket en Javalin.
     */
    public void register(Javalin app) {
        // --- HTTP Polling transport ---
        app.get("/socket.io/", ctx -> {
            String transport = ctx.queryParam("transport");
            String sid = ctx.queryParam("sid");

            if (!"polling".equals(transport)) {
                ctx.status(400).result("Invalid transport");
                return;
            }

            ctx.header("Access-Control-Allow-Origin", ctx.header("Origin") != null ? ctx.header("Origin") : "*");
            ctx.header("Access-Control-Allow-Credentials", "true");

            if (sid == null || sid.isEmpty()) {
                // New session - Engine.IO OPEN
                String newSid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                pollingQueues.put(newSid, new ConcurrentLinkedQueue<>());
                upgradedSessions.put(newSid, false);

                JsonObject openData = new JsonObject();
                openData.addProperty("sid", newSid);
                openData.add("upgrades", gson.toJsonTree(new String[]{"websocket"}));
                openData.addProperty("pingInterval", PING_INTERVAL);
                openData.addProperty("pingTimeout", PING_TIMEOUT);
                openData.addProperty("maxPayload", 1000000);

                ctx.contentType("text/plain; charset=UTF-8");
                ctx.result("0" + openData.toString());
            } else {
                // Existing session - return queued messages or NOOP
                ConcurrentLinkedQueue<String> queue = pollingQueues.get(sid);
                if (queue != null && !queue.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    String msg;
                    while ((msg = queue.poll()) != null) {
                        if (sb.length() > 0) sb.append("\u001e"); // Record separator
                        sb.append(msg);
                    }
                    ctx.contentType("text/plain; charset=UTF-8");
                    ctx.result(sb.toString());
                } else {
                    ctx.contentType("text/plain; charset=UTF-8");
                    ctx.result("6"); // NOOP
                }
            }
        });

        app.post("/socket.io/", ctx -> {
            String sid = ctx.queryParam("sid");
            ctx.header("Access-Control-Allow-Origin", ctx.header("Origin") != null ? ctx.header("Origin") : "*");
            ctx.header("Access-Control-Allow-Credentials", "true");

            if (sid != null) {
                String body = ctx.body();
                // Handle Socket.IO CONNECT (40) via polling
                if (body.startsWith("4")) {
                    ConcurrentLinkedQueue<String> queue = pollingQueues.get(sid);
                    if (queue != null) {
                        // Queue the connect ack for the next GET poll
                        JsonObject ack = new JsonObject();
                        ack.addProperty("sid", sid);
                        queue.add("40" + ack.toString());
                    }
                }
            }
            ctx.result("ok");
        });

        app.options("/socket.io/", ctx -> {
            ctx.header("Access-Control-Allow-Origin", ctx.header("Origin") != null ? ctx.header("Origin") : "*");
            ctx.header("Access-Control-Allow-Credentials", "true");
            ctx.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type");
            ctx.status(200);
        });

        // --- WebSocket transport ---
        app.ws("/socket.io/", ws -> {
            ws.onConnect(this::handleWsConnect);
            ws.onMessage(this::handleWsMessage);
            ws.onClose(this::handleWsClose);
            ws.onError(ctx -> {
                System.err.println("WebSocket error: " + (ctx.error() != null ? ctx.error().getMessage() : "unknown"));
            });
        });

        // Ping scheduler
        scheduler.scheduleAtFixedRate(() -> {
            for (SocketIOClient client : clientsBySession.values()) {
                if (client.isConnected()) {
                    try { client.sendRaw("2"); } catch (Exception ignored) {} // Engine.IO PING
                }
            }
        }, PING_INTERVAL, PING_INTERVAL, TimeUnit.MILLISECONDS);

        System.out.println("✅ Socket.IO adapter registrado en /socket.io/");
    }

    // --- WebSocket Handlers ---

    private void handleWsConnect(WsConnectContext ctx) {
        String sid = ctx.queryParam("sid");
        if (sid == null) {
            // Direct WebSocket connection (no polling first)
            sid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        }

        SocketIOClient client = new SocketIOClient(sid, ctx, this);
        clientsBySession.put(sid, client);
        clientsByWsId.put(ctx.sessionId(), client);

        // If upgrading from polling, mark as upgraded
        if (upgradedSessions.containsKey(sid)) {
            upgradedSessions.put(sid, true);
        }

        // Send Engine.IO OPEN
        JsonObject openData = new JsonObject();
        openData.addProperty("sid", sid);
        openData.add("upgrades", gson.toJsonTree(new String[0]));
        openData.addProperty("pingInterval", PING_INTERVAL);
        openData.addProperty("pingTimeout", PING_TIMEOUT);
        openData.addProperty("maxPayload", 1000000);
        client.sendRaw("0" + openData.toString());
    }

    private void handleWsMessage(WsMessageContext ctx) {
        String message = ctx.message();
        if (message == null || message.isEmpty()) return;

        SocketIOClient client = clientsByWsId.get(ctx.sessionId());
        if (client == null) return;

        char eioType = message.charAt(0);
        switch (eioType) {
            case '2': // Engine.IO PING (from client)
                if (message.equals("2probe")) {
                    client.sendRaw("3probe"); // PONG probe (upgrade handshake)
                } else {
                    client.sendRaw("3"); // PONG
                }
                break;
            case '3': // Engine.IO PONG (response to our ping)
                break;
            case '4': // Engine.IO MESSAGE (Socket.IO packet)
                handleSocketIOPacket(client, message.substring(1));
                break;
            case '5': // Engine.IO UPGRADE
                // Upgrade complete - stop polling for this session
                if (client.getSid() != null) {
                    upgradedSessions.put(client.getSid(), true);
                    pollingQueues.remove(client.getSid());
                }
                break;
            default:
                break;
        }
    }

    private void handleWsClose(WsCloseContext ctx) {
        SocketIOClient client = clientsByWsId.remove(ctx.sessionId());
        if (client != null) {
            client.setConnected(false);
            clientsBySession.remove(client.getSid());
            pollingQueues.remove(client.getSid());
            upgradedSessions.remove(client.getSid());

            // Remove from all rooms
            for (String room : client.getRooms()) {
                leaveRoom(client, room);
            }

            // Fire disconnect handlers
            client.fireEvent("disconnect", new Object[]{});
        }
    }

    private void handleSocketIOPacket(SocketIOClient client, String packet) {
        if (packet.isEmpty()) return;

        char sioType = packet.charAt(0);
        String data = packet.length() > 1 ? packet.substring(1) : "";

        switch (sioType) {
            case '0': // CONNECT
                // Send CONNECT ack
                JsonObject ack = new JsonObject();
                ack.addProperty("sid", client.getSid());
                client.sendRaw("40" + ack.toString());

                // Fire connection handler
                if (connectionHandler != null) {
                    try {
                        connectionHandler.accept(client, new Object[]{});
                    } catch (Exception e) {
                        System.err.println("Error in connection handler: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                break;

            case '1': // DISCONNECT
                break;

            case '2': // EVENT
                handleEvent(client, data);
                break;

            default:
                break;
        }
    }

    private void handleEvent(SocketIOClient client, String data) {
        try {
            JsonArray array = JsonParser.parseString(data).getAsJsonArray();
            if (array.size() == 0) return;

            String eventName = array.get(0).getAsString();
            Object[] args = new Object[array.size() - 1];
            for (int i = 1; i < array.size(); i++) {
                args[i - 1] = array.get(i);
            }

            // Fire per-client handlers first
            client.fireEvent(eventName, args);
        } catch (Exception e) {
            System.err.println("Error parsing Socket.IO event: " + e.getMessage());
        }
    }

    // --- Room management ---

    void joinRoom(SocketIOClient client, String room) {
        rooms.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(client.getSid());
    }

    void leaveRoom(SocketIOClient client, String room) {
        Set<String> roomClients = rooms.get(room);
        if (roomClients != null) {
            roomClients.remove(client.getSid());
            if (roomClients.isEmpty()) rooms.remove(room);
        }
    }

    void emitToRoom(String room, String excludeSid, String packet) {
        Set<String> roomClients = rooms.get(room);
        if (roomClients == null) return;
        for (String sid : roomClients) {
            if (excludeSid != null && excludeSid.equals(sid)) continue;
            SocketIOClient client = clientsBySession.get(sid);
            if (client != null && client.isConnected()) {
                client.sendRaw(packet);
            }
        }
    }

    /**
     * Construye un packet de evento Socket.IO: 42["event",data...]
     */
    String buildEventPacket(String event, Object... data) {
        JsonArray array = new JsonArray();
        array.add(event);
        for (Object d : data) {
            if (d instanceof JsonElement) {
                array.add((JsonElement) d);
            } else if (d instanceof String) {
                array.add((String) d);
            } else if (d instanceof Number) {
                array.add((Number) d);
            } else if (d instanceof Boolean) {
                array.add((Boolean) d);
            } else if (d != null) {
                array.add(gson.toJsonTree(d));
            } else {
                array.add(JsonNull.INSTANCE);
            }
        }
        return "42" + array.toString();
    }

    /**
     * Room emitter - para io.to(room).emit() y socket.to(room).emit()
     */
    public static class RoomEmitter {
        private final SocketIOServer server;
        private final String room;
        private final String excludeSid;

        RoomEmitter(SocketIOServer server, String room, String excludeSid) {
            this.server = server;
            this.room = room;
            this.excludeSid = excludeSid;
        }

        public void emit(String event, Object... data) {
            String packet = server.buildEventPacket(event, data);
            server.emitToRoom(room, excludeSid, packet);
        }
    }
}
