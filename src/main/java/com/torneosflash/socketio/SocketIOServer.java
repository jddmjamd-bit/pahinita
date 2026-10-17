package com.torneosflash.socketio;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIONamespace;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servidor.AuthMiddleware;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Servidor Socket.IO respaldado por {@code netty-socketio} (A1).
 * <ul>
 *   <li>Soporta protocolo Engine.IO v3/v4 y Socket.IO v3/v4 ({@code socket.io-client} v4).</li>
 *   <li>Soporta rooms nativas ({@link #to(String)}, {@link SocketIOClient#join(String)}, {@link SocketIOClient#leave(String)}).</li>
 *   <li>Soporta namespaces ({@link #of(String)}) además del namespace raíz {@code "/"}.</li>
 *   <li>Expone un puente HTTP + WebSocket en {@code /socket.io/} dentro de Javalin para que en Render
 *       (donde solo existe un único puerto público {@code PORT}) todo el tráfico llegue a {@code netty-socketio}.</li>
 * </ul>
 */
public class SocketIOServer {
    private static final Logger logger = LoggerFactory.getLogger(SocketIOServer.class);

    private static final int PING_INTERVAL_MS = 25_000;
    private static final int PING_TIMEOUT_MS = 20_000;
    private static final int DEFAULT_NETTY_PORT = 9092;
    private static final int DEFAULT_MAX_BYTES = 50_000_000;

    private static final String[] EVENTOS_BASE = {
            "registrar_socket",
            "mensaje_chat",
            "buscar_partida",
            "cancelar_busqueda",
            "negociacion_live",
            "iniciar_juego",
            "cancelar_inicio",
            "confirmar_partida_resp",
            "mensaje_privado",
            "cancelar_match",
            "reportar_resultado"
    };

    private final int configuredPort;
    private final int httpPort;
    private final int maxPayloadBytes;
    private int activeNettyPort;
    private com.corundumstudio.socketio.SocketIOServer nettyServer;

    private final ConcurrentHashMap<String, SocketIOClient> clientsBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamespaceAdapter> namespaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> registeredEventsByNs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PuenteWs> bridgesByJavalinId = new ConcurrentHashMap<>();

    private BiConsumer<SocketIOClient, Object[]> connectionHandler;
    private final ConcurrentHashMap<String, List<BiConsumer<SocketIOClient, Object[]>>> serverEventHandlers = new ConcurrentHashMap<>();

    private static final Gson GSON = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public SocketIOServer(ScheduledExecutorService scheduler) {
        this.configuredPort = DEFAULT_NETTY_PORT;
        this.httpPort = 5000;
        this.maxPayloadBytes = DEFAULT_MAX_BYTES;
    }

    public SocketIOServer(AppConfig config, ScheduledExecutorService scheduler) {
        this.configuredPort = config != null ? config.getSocketIoPort() : DEFAULT_NETTY_PORT;
        this.httpPort = config != null ? config.getPort() : 5000;
        this.maxPayloadBytes = config != null ? config.getWsMaxMessageBytes() : DEFAULT_MAX_BYTES;
    }

    /** Registra el handler de nuevas conexiones en el namespace raíz {@code "/"}. */
    public void onConnection(BiConsumer<SocketIOClient, Object[]> handler) {
        this.connectionHandler = handler;
    }

    /** Registra un listener global para un evento en el namespace raíz {@code "/"}. */
    public void on(String event, BiConsumer<SocketIOClient, Object[]> handler) {
        if (event == null || handler == null) return;
        serverEventHandlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (nettyServer != null) {
            ensureEventRegistered(nettyServer.getNamespace(""), event);
        }
    }

    /** Emite un evento a TODOS los clientes conectados en el namespace raíz. */
    public void emit(String event, Object... data) {
        if (nettyServer == null) return;
        Object[] payload = paraNettyArgs(data);
        nettyServer.getBroadcastOperations().sendEvent(event, payload);
    }

    /** Obtiene un emisor para una room específica del namespace raíz. */
    public RoomEmitter to(String room) {
        return new RoomEmitter(this, nettyServer != null ? nettyServer.getNamespace("") : null, room, null);
    }

    /** Obtiene los clientes actualmente unidos a una room en el namespace raíz. */
    public Collection<SocketIOClient> getClientsInRoom(String room) {
        if (nettyServer == null || room == null) return Collections.emptyList();
        Collection<com.corundumstudio.socketio.SocketIOClient> raw = nettyServer.getRoomOperations(room).getClients();
        List<SocketIOClient> result = new ArrayList<>(raw.size());
        for (com.corundumstudio.socketio.SocketIOClient c : raw) {
            SocketIOClient wrapped = clientsBySession.get(c.getSessionId().toString());
            if (wrapped != null && wrapped.isConnected()) {
                result.add(wrapped);
            }
        }
        return result;
    }

    /** Obtiene o crea un namespace de Socket.IO (por ejemplo {@code io.of("/clanes")}). */
    public NamespaceAdapter of(String namespaceName) {
        String norm = normalizarNamespace(namespaceName);
        return namespaces.computeIfAbsent(norm, n -> {
            SocketIONamespace ns = nettyServer != null ? nettyServer.addNamespace(n) : null;
            NamespaceAdapter adapter = new NamespaceAdapter(this, n, ns);
            if (ns != null) {
                configurarListenersNamespace(ns, adapter);
            }
            return adapter;
        });
    }

    /** Mapa de sockets activos en el servidor (id de sesión -> {@link SocketIOClient}). */
    public ConcurrentHashMap<String, SocketIOClient> getSockets() {
        return clientsBySession;
    }

    /**
     * Arranca {@code netty-socketio} y registra el puente HTTP + WebSocket en {@code /socket.io/} sobre Javalin.
     */
    public void register(Javalin app) {
        this.activeNettyPort = elegirPuertoNetty(configuredPort, httpPort);

        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(this.activeNettyPort);
        config.setMaxFramePayloadLength(this.maxPayloadBytes);
        config.setMaxHttpContentLength(this.maxPayloadBytes);
        config.setPingInterval(PING_INTERVAL_MS);
        config.setPingTimeout(PING_TIMEOUT_MS);
        config.setAllowCustomRequests(true);

        SocketConfig sockConfig = new SocketConfig();
        sockConfig.setReuseAddress(true);
        config.setSocketConfig(sockConfig);

        this.nettyServer = new com.corundumstudio.socketio.SocketIOServer(config);

        SocketIONamespace rootNs = this.nettyServer.getNamespace("");
        configurarNamespaceRaiz(rootNs);

        // Inicializar namespaces adicionales que se hubieran registrado antes de register(app)
        for (NamespaceAdapter nsAdapter : namespaces.values()) {
            SocketIONamespace ns = this.nettyServer.addNamespace(nsAdapter.getName());
            nsAdapter.bindNamespace(ns);
            configurarListenersNamespace(ns, nsAdapter);
        }

        this.nettyServer.start();

        // --- Puente HTTP Polling + WebSocket en Javalin (mismo puerto PORT para Render) ---
        app.get("/socket.io/", this::proxyHttpPolling);
        app.post("/socket.io/", this::proxyHttpPolling);
        app.options("/socket.io/", ctx -> {
            aplicarCors(ctx);
            ctx.status(200);
        });

        app.ws("/socket.io/", ws -> {
            ws.onConnect(this::handleBridgeConnect);
            ws.onMessage(this::handleBridgeMessage);
            ws.onClose(this::handleBridgeClose);
            ws.onError(ctx -> {
                if (ctx.error() != null) {
                    logger.debug("WebSocket bridge error: {}", ctx.error().getMessage());
                }
            });
        });

        logger.info("✅ Servidor netty-socketio activo (puerto interno {}, expuesto en /socket.io/)", activeNettyPort);
    }

    /** Detiene limpiamente el servidor Netty y libera sus event loops. */
    public void stop() {
        for (PuenteWs puente : bridgesByJavalinId.values()) {
            puente.cerrar();
        }
        bridgesByJavalinId.clear();
        if (nettyServer != null) {
            try {
                nettyServer.stop();
                logger.info("🛑 Servidor netty-socketio detenido");
            } catch (Exception e) {
                logger.warn("Error deteniendo netty-socketio: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Configuración de namespaces y eventos en netty-socketio
    // ------------------------------------------------------------------

    private void configurarNamespaceRaiz(SocketIONamespace rootNs) {
        for (String ev : EVENTOS_BASE) {
            ensureEventRegistered(rootNs, ev);
        }
        for (String ev : serverEventHandlers.keySet()) {
            ensureEventRegistered(rootNs, ev);
        }

        rootNs.addConnectListener(nettyClient -> {
            String sid = nettyClient.getSessionId().toString();
            SocketIOClient client = new SocketIOClient(sid, nettyClient, this, rootNs);
            autenticarHandshake(nettyClient, client);
            clientsBySession.put(sid, client);

            if (connectionHandler != null) {
                try {
                    connectionHandler.accept(client, new Object[0]);
                } catch (Exception e) {
                    logger.error("Error en connectionHandler de Socket.IO: {}", e.getMessage(), e);
                }
            }
        });

        rootNs.addDisconnectListener(nettyClient -> {
            String sid = nettyClient.getSessionId().toString();
            SocketIOClient client = clientsBySession.remove(sid);
            if (client != null) {
                client.setConnected(false);
                client.fireEvent("disconnect", new Object[0]);
            }
        });
    }

    private void configurarListenersNamespace(SocketIONamespace ns, NamespaceAdapter adapter) {
        for (String ev : adapter.getRegisteredEvents()) {
            ensureEventRegistered(ns, ev);
        }
        ns.addConnectListener(nettyClient -> {
            String sid = nettyClient.getSessionId().toString();
            SocketIOClient client = new SocketIOClient(sid, nettyClient, this, ns);
            autenticarHandshake(nettyClient, client);
            adapter.getSockets().put(sid, client);
            adapter.fireConnect(client);
        });
        ns.addDisconnectListener(nettyClient -> {
            String sid = nettyClient.getSessionId().toString();
            SocketIOClient client = adapter.getSockets().remove(sid);
            if (client != null) {
                client.setConnected(false);
                client.fireEvent("disconnect", new Object[0]);
            }
        });
    }

    private void autenticarHandshake(com.corundumstudio.socketio.SocketIOClient nettyClient, SocketIOClient client) {
        try {
            HandshakeData hd = nettyClient.getHandshakeData();
            if (hd != null && hd.getHttpHeaders() != null) {
                String cookieHeader = hd.getHttpHeaders().get("Cookie");
                if (cookieHeader == null) {
                    cookieHeader = hd.getHttpHeaders().get("cookie");
                }
                Integer authUserId = AuthMiddleware.userIdDesdeCookieHeader(cookieHeader);
                if (authUserId != null) {
                    client.setAuthenticatedUserId(authUserId);
                }
            }
        } catch (Exception ignored) {
        }
    }

    void ensureEventRegistered(SocketIONamespace ns, String event) {
        if (ns == null || event == null || "connect".equals(event) || "disconnect".equals(event)) {
            return;
        }
        String nsName = ns.getName() != null ? ns.getName() : "";
        Set<String> set = registeredEventsByNs.computeIfAbsent(nsName, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(event)) {
            return;
        }

        ns.addEventListener(event, Object.class, (nettyClient, data, ackSender) -> {
            String sid = nettyClient.getSessionId().toString();
            SocketIOClient client = buscarCliente(nsName, sid);
            if (client == null) return;

            Object[] args = (data == null) ? new Object[0] : new Object[]{ aJsonElement(data) };

            if (nsName.isEmpty() || "/".equals(nsName)) {
                List<BiConsumer<SocketIOClient, Object[]>> glob = serverEventHandlers.get(event);
                if (glob != null) {
                    for (BiConsumer<SocketIOClient, Object[]> h : glob) {
                        try {
                            h.accept(client, args);
                        } catch (Exception e) {
                            logger.error("Error en listener global '{}': {}", event, e.getMessage(), e);
                        }
                    }
                }
            } else {
                NamespaceAdapter adapter = namespaces.get(nsName);
                if (adapter != null) {
                    adapter.fireServerEvent(event, client, args);
                }
            }

            client.fireEvent(event, args);
        });
    }

    private SocketIOClient buscarCliente(String nsName, String sid) {
        if (nsName == null || nsName.isEmpty() || "/".equals(nsName)) {
            return clientsBySession.get(sid);
        }
        NamespaceAdapter adapter = namespaces.get(nsName);
        return adapter != null ? adapter.getSockets().get(sid) : null;
    }

    void emitToRoom(SocketIONamespace ns, String room, String excludeSid, String event, Object... data) {
        SocketIONamespace targetNs = ns != null ? ns : (nettyServer != null ? nettyServer.getNamespace("") : null);
        if (targetNs == null || room == null) return;
        Object[] payload = paraNettyArgs(data);

        if (excludeSid == null) {
            targetNs.getRoomOperations(room).sendEvent(event, payload);
            return;
        }

        for (com.corundumstudio.socketio.SocketIOClient c : targetNs.getRoomOperations(room).getClients()) {
            if (!excludeSid.equals(c.getSessionId().toString()) && c.isChannelOpen()) {
                c.sendEvent(event, payload);
            }
        }
    }

    // ------------------------------------------------------------------
    // Puente HTTP + WebSocket (Javalin PORT -> netty-socketio 127.0.0.1)
    // ------------------------------------------------------------------

    private void proxyHttpPolling(Context ctx) {
        aplicarCors(ctx);
        String query = ctx.queryString();
        String uriStr = "http://127.0.0.1:" + activeNettyPort + "/socket.io/" + (query != null && !query.isEmpty() ? "?" + query : "");
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(uriStr))
                    .timeout(Duration.ofSeconds(30));

            String cookie = ctx.header("Cookie");
            if (cookie != null && !cookie.isEmpty()) {
                reqBuilder.header("Cookie", cookie);
            }
            String xff = ctx.header("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                reqBuilder.header("X-Forwarded-For", xff);
            }
            String contentType = ctx.contentType();
            if (contentType != null && !contentType.isEmpty()) {
                reqBuilder.header("Content-Type", contentType);
            }

            if ("POST".equalsIgnoreCase(ctx.req().getMethod())) {
                reqBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes()));
            } else {
                reqBuilder.GET();
            }

            HttpResponse<byte[]> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            ctx.status(resp.statusCode());
            resp.headers().firstValue("Content-Type").ifPresent(ctx::contentType);
            ctx.result(resp.body());
        } catch (Exception e) {
            logger.debug("Error en proxy HTTP polling de Socket.IO: {}", e.getMessage());
            ctx.status(502).result("Bad Gateway");
        }
    }

    private void handleBridgeConnect(WsConnectContext ctx) {
        String query = ctx.queryString();
        String uriStr = "ws://127.0.0.1:" + activeNettyPort + "/socket.io/" + (query != null && !query.isEmpty() ? "?" + query : "");
        PuenteWs puente = new PuenteWs(ctx);
        bridgesByJavalinId.put(ctx.sessionId(), puente);

        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5));
            String cookie = ctx.header("Cookie");
            if (cookie != null && !cookie.isEmpty()) {
                builder.header("Cookie", cookie);
            }
            String xff = ctx.header("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                builder.header("X-Forwarded-For", xff);
            }
            WebSocket upstream = builder.buildAsync(URI.create(uriStr), puente).get(5, TimeUnit.SECONDS);
            puente.fijarUpstream(upstream);
        } catch (Exception e) {
            logger.warn("No se pudo abrir puente WS hacia netty-socketio: {}", e.getMessage());
            bridgesByJavalinId.remove(ctx.sessionId());
            try {
                ctx.closeSession();
            } catch (Exception ignored) {
            }
        }
    }

    private void handleBridgeMessage(WsMessageContext ctx) {
        PuenteWs puente = bridgesByJavalinId.get(ctx.sessionId());
        if (puente != null) {
            puente.enviarAlServidorNetty(ctx.message());
        }
    }

    private void handleBridgeClose(WsCloseContext ctx) {
        PuenteWs puente = bridgesByJavalinId.remove(ctx.sessionId());
        if (puente != null) {
            puente.cerrar();
        }
    }

    private static void aplicarCors(Context ctx) {
        String origin = ctx.header("Origin");
        ctx.header("Access-Control-Allow-Origin", origin != null && !origin.isEmpty() ? origin : "*");
        ctx.header("Access-Control-Allow-Credentials", "true");
        ctx.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type,Authorization,Cookie");
    }

    private static int elegirPuertoNetty(int preferido, int httpPort) {
        if (preferido > 0 && preferido != httpPort && puertoDisponible(preferido)) {
            return preferido;
        }
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (Exception e) {
            return preferido > 0 && preferido != httpPort ? preferido : DEFAULT_NETTY_PORT;
        }
    }

    private static boolean puertoDisponible(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizarNamespace(String ns) {
        if (ns == null || ns.isEmpty() || "/".equals(ns)) return "";
        return ns.startsWith("/") ? ns : "/" + ns;
    }

    // ------------------------------------------------------------------
    // Conversión entre Gson (JsonElement) y tipos nativos para Jackson/Netty
    // ------------------------------------------------------------------

    static Object[] paraNettyArgs(Object... data) {
        if (data == null || data.length == 0) {
            return new Object[0];
        }
        Object[] res = new Object[data.length];
        for (int i = 0; i < data.length; i++) {
            res[i] = paraNetty(data[i]);
        }
        return res;
    }

    static Object paraNetty(Object valor) {
        if (valor == null || valor instanceof JsonNull) {
            return null;
        }
        if (valor instanceof JsonPrimitive prim) {
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isNumber()) {
                BigDecimal bd = prim.getAsBigDecimal();
                if (bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0) {
                    try {
                        return bd.longValueExact();
                    } catch (ArithmeticException ignored) {
                        return bd;
                    }
                }
                return bd;
            }
            return prim.getAsString();
        }
        if (valor instanceof JsonObject obj) {
            Map<String, Object> mapa = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                mapa.put(entry.getKey(), paraNetty(entry.getValue()));
            }
            return mapa;
        }
        if (valor instanceof JsonArray arr) {
            List<Object> lista = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                lista.add(paraNetty(el));
            }
            return lista;
        }
        return valor;
    }

    static JsonElement aJsonElement(Object data) {
        if (data == null) return JsonNull.INSTANCE;
        if (data instanceof JsonElement el) return el;
        return GSON.toJsonTree(data);
    }

    // ------------------------------------------------------------------
    // Clases auxiliares: RoomEmitter, NamespaceAdapter y PuenteWs
    // ------------------------------------------------------------------

    public static class RoomEmitter {
        private final SocketIOServer server;
        private final SocketIONamespace namespace;
        private final String room;
        private final String excludeSid;

        RoomEmitter(SocketIOServer server, SocketIONamespace namespace, String room, String excludeSid) {
            this.server = server;
            this.namespace = namespace;
            this.room = room;
            this.excludeSid = excludeSid;
        }

        public void emit(String event, Object... data) {
            server.emitToRoom(namespace, room, excludeSid, event, data);
        }
    }

    public static class NamespaceAdapter {
        private final SocketIOServer server;
        private final String name;
        private volatile SocketIONamespace nettyNamespace;
        private BiConsumer<SocketIOClient, Object[]> connectionHandler;
        private final ConcurrentHashMap<String, SocketIOClient> sockets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, List<BiConsumer<SocketIOClient, Object[]>>> eventHandlers = new ConcurrentHashMap<>();

        NamespaceAdapter(SocketIOServer server, String name, SocketIONamespace nettyNamespace) {
            this.server = server;
            this.name = name;
            this.nettyNamespace = nettyNamespace;
        }

        void bindNamespace(SocketIONamespace ns) {
            this.nettyNamespace = ns;
        }

        public String getName() { return name; }
        public ConcurrentHashMap<String, SocketIOClient> getSockets() { return sockets; }
        Set<String> getRegisteredEvents() { return eventHandlers.keySet(); }

        public void onConnection(BiConsumer<SocketIOClient, Object[]> handler) {
            this.connectionHandler = handler;
        }

        public void on(String event, BiConsumer<SocketIOClient, Object[]> handler) {
            if (event == null || handler == null) return;
            eventHandlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(handler);
            if (nettyNamespace != null) {
                server.ensureEventRegistered(nettyNamespace, event);
            }
        }

        public void emit(String event, Object... data) {
            if (nettyNamespace == null) return;
            nettyNamespace.getBroadcastOperations().sendEvent(event, paraNettyArgs(data));
        }

        public RoomEmitter to(String room) {
            return new RoomEmitter(server, nettyNamespace, room, null);
        }

        void fireConnect(SocketIOClient client) {
            if (connectionHandler != null) {
                try {
                    connectionHandler.accept(client, new Object[0]);
                } catch (Exception e) {
                    logger.error("Error en connectionHandler de namespace '{}': {}", name, e.getMessage(), e);
                }
            }
        }

        void fireServerEvent(String event, SocketIOClient client, Object[] args) {
            List<BiConsumer<SocketIOClient, Object[]>> list = eventHandlers.get(event);
            if (list != null) {
                for (BiConsumer<SocketIOClient, Object[]> h : list) {
                    try {
                        h.accept(client, args);
                    } catch (Exception e) {
                        logger.error("Error en listener '{}' de namespace '{}': {}", event, name, e.getMessage(), e);
                    }
                }
            }
        }
    }

    private static final class PuenteWs implements WebSocket.Listener {
        private final WsConnectContext javalinCtx;
        private final StringBuilder textBuffer = new StringBuilder();
        private final List<String> pendingToNetty = new ArrayList<>();
        private WebSocket upstream;
        private volatile boolean closed = false;

        PuenteWs(WsConnectContext javalinCtx) {
            this.javalinCtx = javalinCtx;
        }

        synchronized void fijarUpstream(WebSocket ws) {
            this.upstream = ws;
            for (String msg : pendingToNetty) {
                enviarDirecto(msg);
            }
            pendingToNetty.clear();
        }

        synchronized void enviarAlServidorNetty(String mensaje) {
            if (closed || mensaje == null) return;
            if (upstream == null) {
                pendingToNetty.add(mensaje);
                return;
            }
            enviarDirecto(mensaje);
        }

        private void enviarDirecto(String mensaje) {
            try {
                upstream.sendText(mensaje, true).join();
            } catch (Exception e) {
                cerrar();
            }
        }

        synchronized void cerrar() {
            if (closed) return;
            closed = true;
            pendingToNetty.clear();
            if (upstream != null) {
                try {
                    upstream.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
                } catch (Exception ignored) {
                }
            }
            try {
                if (javalinCtx.session.isOpen()) {
                    javalinCtx.closeSession();
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String full = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    if (!closed && javalinCtx.session.isOpen()) {
                        javalinCtx.send(full);
                    }
                } catch (Exception ignored) {
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            cerrar();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            cerrar();
        }
    }
}
