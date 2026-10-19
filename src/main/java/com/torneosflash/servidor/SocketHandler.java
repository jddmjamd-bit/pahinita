package com.torneosflash.servidor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.ChatSanitizer;
import com.torneosflash.servicio.ClashApiServicio;
import com.torneosflash.servicio.NotificacionPushServicio;
import com.torneosflash.servicio.RateLimiter;
import com.torneosflash.servicio.UsuarioVista;
import com.torneosflash.servicio.ValidadorMonto;
import com.torneosflash.servicio.WalletService;
import com.torneosflash.servicio.WalletService.LiquidacionResult;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maneja todos los eventos Socket.IO en tiempo real.
 * Equivalente al bloque io.on('connection', ...) de index.js.
 *
 * CONCEPTO POO: Collection - ConcurrentHashMap para matches activos, colas, búsquedas
 * A2: El estado de partidas activas (active_matches) y búsquedas en cola (match_queue)
 * se persiste en PostgreSQL y se recupera al arrancar el servidor.
 */
public class SocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(SocketHandler.class);

    // Estado en memoria respaldado por las tablas active_matches y match_queue (A2)
    private final ConcurrentHashMap<String, ActiveMatch> activeMatches = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SocketIOClient> colaEsperaClash = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Integer, BusquedaActiva> busquedasActivas = new ConcurrentHashMap<>();
    private final Set<Integer> usuariosOnline = ConcurrentHashMap.newKeySet();

    private final GenericDAO db;
    private final SocketIOServer io;
    private final ClashApiServicio clashApi;
    private final NotificacionPushServicio pushService;
    private final WalletService wallet;
    private final ValidadorMonto validador;
    private final EventBus eventos; // A4: avisa "match.finished"; tickets y notificaciones del resultado son listeners (antes iban aquí)
    private final AppConfig config; // A7: timeouts y límites configurables por variable de entorno
    private final ScheduledExecutorService scheduler; // A8: temporizadores cortos (Ejecutores.timers()), nunca HTTP externo
    private final Ejecutores ejecutores; // A8: pool de IO para el sondeo de la API de Clash
    private final RateLimiter socketRateLimiter = new RateLimiter(); // A3: límite por usuario para eventos de socket
    private final ConcurrentHashMap<String, AtomicLong> ultimoPushChatPorCanal = new ConcurrentHashMap<>();
    private static final int LIMITE_CHAT_SOCKET_POR_MIN = 25;
    private static final int LIMITE_BUSQUEDA_SOCKET_POR_MIN = 6;
    private final Gson gson = new Gson();

    public SocketHandler(GenericDAO db, SocketIOServer io, ClashApiServicio clashApi, NotificacionPushServicio pushService, WalletService wallet, ValidadorMonto validador, EventBus eventos, AppConfig config, Ejecutores ejecutores) {
        this.db = db;
        this.io = io;
        this.clashApi = clashApi;
        this.pushService = pushService;
        this.wallet = wallet;
        this.validador = validador;
        this.eventos = eventos;
        this.config = config;
        this.ejecutores = ejecutores;
        this.scheduler = ejecutores.timers();
    }

    /**
     * Registra todos los event handlers del Socket.IO server y recupera el estado persistido en BD (A2).
     */
    public void registrar() {
        List<Eventos.PartidaIniciada> partidasReconciliadas = wallet.reconciliarPartidasTorneoAlReiniciar();
        recuperarEstadoDesdeBD();
        for (Eventos.PartidaIniciada partida : partidasReconciliadas) {
            eventos.emit(Eventos.MATCH_STARTED, partida);
        }

        io.onConnection((socket, args) -> {
            // S2: Si el handshake tenía cookie JWT válida, precargar el usuario desde la BD
            if (socket.getAuthenticatedUserId() != null) {
                JsonObject u = db.queryOne("SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE id = ?", socket.getAuthenticatedUserId());
                if (u != null) {
                    socket.setUserData(u);
                } else {
                    socket.setAuthenticatedUserId(null);
                }
            }

            // Enviar historial de chats
            for (String canal : new String[]{"anuncios", "general", "clash", "clash_logs"}) {
                ArrayList<JsonObject> msgs = db.query(
                        "SELECT * FROM (SELECT * FROM messages WHERE canal = ? ORDER BY id DESC LIMIT ?) t ORDER BY id ASC",
                        canal, config.getChatHistorialLimite()); // A7: CHAT_HISTORIAL_LIMITE (antes 50 fijo)
                JsonObject histData = new JsonObject();
                histData.addProperty("canal", canal);
                histData.add("mensajes", gson.toJsonTree(msgs));
                socket.emit("historial_chat", histData);
            }

            // --- REGISTRAR SOCKET ---
            socket.on("registrar_socket", (client, data) -> {
                try {
                    // S2: La identidad del socket sale SIEMPRE del JWT verificado en el handshake, nunca del payload del cliente
                    Integer authId = client.getAuthenticatedUserId();
                    if (authId == null || authId <= 0) {
                        logger.warn("Intento de registrar_socket sin sesión JWT en handshake (sid={})", client.getSid());
                        return;
                    }
                    JsonObject dbUser = db.queryOne("SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE id = ?", authId);
                    if (dbUser == null) {
                        logger.warn("Usuario {} de JWT ya no existe en BD (sid={})", authId, client.getSid());
                        return;
                    }
                    int userId = dbUser.get("id").getAsInt();
                    String username = dbUser.get("username").getAsString();

                    // Desconectar sesiones anteriores del mismo usuario
                    for (SocketIOClient existing : io.getSockets().values()) {
                        if (existing.getUserData() != null &&
                            existing.getUserData().has("id") &&
                            existing.getUserData().get("id").getAsInt() == userId &&
                            !existing.getSid().equals(client.getSid())) {
                            JsonObject dupData = new JsonObject();
                            dupData.addProperty("mensaje", "Tu cuenta se conectó desde otro dispositivo");
                            existing.emit("sesion_duplicada", dupData);
                            existing.disconnect(true);
                        }
                    }

                    // Remover de cola si estaba
                    colaEsperaClash.removeIf(s -> s.getUserData() != null &&
                            s.getUserData().get("id").getAsInt() == userId && !s.getSid().equals(client.getSid()));

                    client.setUserData(dbUser);
                    usuariosOnline.add(userId);
                    logger.info("✅ Usuario " + username + " (" + userId + ") está ONLINE");

                    // Enviar búsquedas activas de otros usuarios
                    for (Map.Entry<Integer, BusquedaActiva> entry : busquedasActivas.entrySet()) {
                        if (entry.getKey() != userId) {
                            JsonObject bData = new JsonObject();
                            bData.addProperty("username", entry.getValue().username);
                            bData.addProperty("oderId", entry.getValue().oderId);
                            client.emit("alguien_buscando", bData);
                        }
                    }

                    // Verificar si ESTE usuario tiene una búsqueda activa (reconexión o recuperación tras reinicio)
                    if (busquedasActivas.containsKey(userId)) {
                        BusquedaActiva miBusqueda = busquedasActivas.get(userId);
                        // Actualizar referencia del socket en la búsqueda activa
                        miBusqueda.socket = client;
                        // Reemplazar en la cola de espera
                        colaEsperaClash.removeIf(s -> s.getUserData() != null &&
                                s.getUserData().get("id").getAsInt() == userId);
                        colaEsperaClash.add(client);
                        // Notificar al frontend para restaurar estado visual
                        JsonObject buscandoData = new JsonObject();
                        buscandoData.addProperty("mensaje", "Tu búsqueda sigue activa");
                        client.emit("buscando_activo", buscandoData);
                        logger.info("🔄 " + username + " reconectado con búsqueda activa");
                    }

                    // Recuperar sala si existe (por sala_actual en users o buscando en activeMatches)
                    String salaActual = (dbUser != null && dbUser.has("sala_actual") && !dbUser.get("sala_actual").isJsonNull()) ?
                            dbUser.get("sala_actual").getAsString() : null;
                    if (salaActual == null || !activeMatches.containsKey(salaActual)) {
                        String salaEncontrada = buscarSalaDeJugador(userId);
                        if (salaEncontrada != null) {
                            salaActual = salaEncontrada;
                        } else if (salaActual != null) {
                            // Limpiar referencia huérfana si la sala ya no existe
                            db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id = ?", userId);
                            salaActual = null;
                        }
                    }

                    if (salaActual != null && activeMatches.containsKey(salaActual)) {
                        ActiveMatch match = activeMatches.get(salaActual);
                        // Cancelar timer de desconexión si estaba corriendo
                        ScheduledFuture<?> timer = match.disconnectTimers.remove(userId);
                        if (timer != null) {
                            timer.cancel(false);
                            JsonObject reData = new JsonObject();
                            reData.addProperty("username", username);
                            client.to(salaActual).emit("rival_reconectado", reData);
                        }
                        client.join(salaActual);
                        client.setCurrentRoom(salaActual);

                        // Registrar o actualizar el socket del jugador en el match
                        match.actualizarSocketJugador(userId, client);

                        // S6: el rival siempre se lee de la BD con lista blanca (nunca el userData del cliente ni SELECT *)
                        int rivalId = match.getRivalId(userId);
                        JsonObject rivalData = null;
                        if (rivalId > 0) {
                            rivalData = db.queryOne("SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE id = ?", rivalId);
                        }
                        if (rivalData == null) {
                            rivalData = db.queryOne("SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE sala_actual = ? AND id != ?", salaActual, userId);
                        }

                        // Calculo de maxMonto con BigDecimal (D1: usa el saldo real del rival, que NO se envía al cliente) // REVIEW-MONEY
                        BigDecimal miSaldo = dbUser != null && dbUser.has("saldo") && !dbUser.get("saldo").isJsonNull()
                                ? dbUser.get("saldo").getAsBigDecimal() : BigDecimal.ZERO;
                        BigDecimal rivalSaldo = rivalData != null && rivalData.has("saldo") && !rivalData.get("saldo").isJsonNull()
                                ? rivalData.get("saldo").getAsBigDecimal() : BigDecimal.ZERO;
                        BigDecimal maxMonto = miSaldo.min(rivalSaldo);

                        // Datos para restaurar
                        ArrayList<JsonObject> msgs = db.query("SELECT * FROM messages WHERE canal = ? ORDER BY id ASC", salaActual);
                        JsonObject restoreData = new JsonObject();
                        restoreData.addProperty("salaId", salaActual);
                        restoreData.addProperty("iniciado", match.iniciado);
                        restoreData.addProperty("inicioRevision", match.inicioRevision);
                        restoreData.addProperty("estado", match.iniciado ? "jugando" : "partida_encontrada");
                        restoreData.addProperty("maxMonto", maxMonto);
                        if (rivalData != null) restoreData.add("rival", UsuarioVista.paraRival(rivalData)); // S6
                        restoreData.add("historial", gson.toJsonTree(msgs));
                        restoreData.addProperty("lastModo", match.lastModo);
                        restoreData.addProperty("lastDinero", match.lastDinero);

                        client.emit("restaurar_partida", restoreData);
                        if (match.inicioRevision) {
                            client.emit("error_negociacion", new JsonPrimitive(
                                    "El inicio de esta partida está bloqueado para revisión administrativa; no se realizará otro cobro"));
                        }

                        // Si está en negociación y había votos/confirmaciones pendientes, restaurar estado de botones/modal
                        if (!match.iniciado) {
                            if (match.votosInicio.size() == 2) {
                                if (!Boolean.TRUE.equals(match.confirmaciones.get(userId))) {
                                    JsonObject v = match.votosInicio.get(match.player1Id);
                                    if (v != null && v.has("dinero")) {
                                        JsonObject confirmData = new JsonObject();
                                        confirmData.addProperty("modo", v.has("modo") ? v.get("modo").getAsString() : "N/A");
                                        confirmData.addProperty("monto", v.get("dinero").getAsBigDecimal());
                                        client.emit("confirmar_partida", confirmData);
                                    }
                                }
                            } else if (match.votosInicio.containsKey(userId)) {
                                client.emit("esperando_inicio_rival");
                            } else if (rivalId > 0 && match.votosInicio.containsKey(rivalId)) {
                                client.emit("rival_listo_inicio");
                            }
                        }
                    }

                } catch (Exception e) {
                    logger.error("Error registrar_socket: {}", e.getMessage(), e);
                }
            });

            // --- MENSAJES ---
            socket.on("mensaje_chat", (client, data) -> {
                try {
                    // S2: Exigir sesión autenticada en el socket y tomar el username real de la sesión
                    if (client.getUserData() == null || !client.getUserData().has("id")) {
                        logger.warn("Mensaje de chat rechazado: socket sin sesión autenticada (sid={})", client.getSid());
                        return;
                    }
                    int senderId = client.getUserData().get("id").getAsInt();
                    if (!socketRateLimiter.intentar("chat_pub|" + senderId, LIMITE_CHAT_SOCKET_POR_MIN).isPermitido()) {
                        logger.warn("🚦 Rate limit de chat público superado por userId={}", senderId);
                        return;
                    }
                    String usuarioSesion = client.getUserData().get("username").getAsString();

                    JsonObject msg = ((JsonElement) data[0]).getAsJsonObject();
                    // S5 (XSS) + S2: se valida y limpia usando el username de la sesión, nunca el del cliente
                    ChatSanitizer.MensajePublico limpio = ChatSanitizer.validarMensajePublico(
                            str(msg, "canal"), usuarioSesion, str(msg, "texto"), str(msg, "tipo"));
                    if (limpio == null) {
                        logger.warn("Mensaje de chat rechazado por formato inválido (sid={})", client.getSid());
                        return;
                    }
                    String canal = limpio.canal();
                    // S2/S3: Solo un admin (verificado en BD) puede publicar en el canal 'anuncios'
                    if ("anuncios".equals(canal)) {
                        JsonObject uRol = db.queryOne("SELECT tipo_suscripcion FROM users WHERE id = ?", senderId);
                        boolean esAdmin = uRol != null && !uRol.get("tipo_suscripcion").isJsonNull()
                                && "admin".equals(uRol.get("tipo_suscripcion").getAsString());
                        if (!esAdmin) {
                            logger.warn("Usuario {} intentó escribir en 'anuncios' sin ser admin", senderId);
                            return;
                        }
                    }
                    String usuario = limpio.usuario();
                    String texto = limpio.texto();
                    String tipo = limpio.tipo();
                    String fecha = java.time.Instant.now().toString();

                    db.update("INSERT INTO messages (canal, usuario, texto, tipo) VALUES (?, ?, ?, ?)",
                            canal, usuario, texto, tipo);
                    // Solo se reenvían los campos permitidos (nunca propiedades arbitrarias del cliente)
                    JsonObject salida = new JsonObject();
                    salida.addProperty("canal", canal);
                    salida.addProperty("usuario", usuario);
                    salida.addProperty("texto", texto);
                    salida.addProperty("tipo", tipo);
                    salida.addProperty("fecha", fecha);
                    io.emit("mensaje_chat", salida);

                    // Un push por canal/minuto y exclusión de sockets conectados: el mensaje
                    // ya llegó en tiempo real a esos usuarios.
                    if (client.getUserData() != null && permitirPushChat(canal)) {
                        String nombreCanal = canal.equals("general") ? "General"
                                : canal.equals("clash") ? "Clash"
                                : canal.equals("anuncios") ? "Anuncios" : canal;
                        String preview = tipo.equals("texto")
                                ? (texto.length() > 50 ? texto.substring(0, 50) + "..." : texto)
                                : usuario + " envió " + (tipo.equals("imagen") ? "una imagen" : "un video");
                        pushService.enviarPushATodos(db, excluirConectadosEnTiempoReal(senderId),
                                "💬 Mensaje en " + nombreCanal,
                                tipo.equals("texto") ? usuario + ": " + preview : preview);
                    }
                } catch (Exception e) { logger.error("Error mensaje_chat: " + e.getMessage()); }
            });

            // --- BUSCAR PARTIDA ---
            socket.on("buscar_partida", (client, data) -> {
                try {
                    // S2: El userId sale siempre del socket autenticado por JWT, nunca del objeto enviado por el cliente
                    Integer authId = client.getAuthenticatedUserId();
                    if (authId == null && client.getUserData() != null && client.getUserData().has("id")) {
                        authId = client.getUserData().get("id").getAsInt();
                    }
                    if (authId == null || authId <= 0) {
                        client.emit("error_busqueda", new JsonPrimitive("Sesión inválida. Vuelve a iniciar sesión."));
                        return;
                    }
                    int userId = authId;
                    if (!socketRateLimiter.intentar("buscar|" + userId, LIMITE_BUSQUEDA_SOCKET_POR_MIN).isPermitido()) {
                        client.emit("error_busqueda", new JsonPrimitive("Estás buscando demasiado rápido. Espera unos segundos."));
                        return;
                    }

                    JsonObject row = db.queryOne("SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE id = ?", userId);
                    // D1 / D5: el mínimo para buscar partida se compara con BigDecimal directo de la BD // REVIEW-MONEY
                    BigDecimal minTorneo = validador.getMinTorneo();
                    if (row == null || !row.has("saldo") || row.get("saldo").isJsonNull()
                            || row.get("saldo").getAsBigDecimal().compareTo(minTorneo) < 0) {
                        client.emit("error_busqueda", new JsonPrimitive("Saldo insuficiente (mínimo " + ValidadorMonto.formatear(minTorneo) + ")"));
                        return;
                    }

                    client.setUserData(row);
                    if (busquedasActivas.containsKey(userId) || estaEnPartidaActiva(userId)) return;

                    colaEsperaClash.add(client);
                    db.update("UPDATE users SET estado = 'buscando_partida' WHERE id = ?", userId);

                    String username = row.get("username").getAsString();
                    BusquedaActiva busqueda = new BusquedaActiva(userId, username, client);
                    busquedasActivas.put(userId, busqueda);

                    // A2: Persistir la búsqueda en match_queue con su fecha de expiración
                    final int busquedaMinutos = config.getBusquedaTimeoutMinutos();
                    db.update(
                            "INSERT INTO match_queue (user_id, username, creado_en, expira_en) " +
                            "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + make_interval(mins => ?)) " +
                            "ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, " +
                            "creado_en = CURRENT_TIMESTAMP, expira_en = EXCLUDED.expira_en",
                            userId, username, busquedaMinutos);

                    // Timer de búsqueda (BUSQUEDA_TIMEOUT_MINUTOS, default 10) // A7
                    programarTimeoutBusqueda(busqueda, busquedaMinutos * 60L, busquedaMinutos);

                    // Notificar a todos
                    JsonObject buscandoData = new JsonObject();
                    buscandoData.addProperty("username", username);
                    buscandoData.addProperty("oderId", userId);
                    io.emit("alguien_buscando", buscandoData);

                    // Push: notificar a todos que alguien busca partida
                    pushService.enviarPushATodos(db, excluirConectadosEnTiempoReal(userId),
                            "🔍 Partida disponible", username + " está buscando partida!");

                    logClash("🔍 " + username + " busca...");

                    // Intentar matcheo
                    intentarMatcheo(client, userId, row);

                } catch (Exception e) {
                    logger.error("Error buscar_partida: {}", e.getMessage(), e);
                }
            });

            // --- CANCELAR BÚSQUEDA ---
            socket.on("cancelar_busqueda", (client, data) -> {
                colaEsperaClash.removeIf(s -> s.getSid().equals(client.getSid()));
                if (client.getUserData() != null) {
                    int userId = client.getUserData().get("id").getAsInt();
                    BusquedaActiva busqueda = busquedasActivas.remove(userId);
                    if (busqueda != null && busqueda.timeoutFuture != null) busqueda.timeoutFuture.cancel(false);
                    db.update("DELETE FROM match_queue WHERE user_id = ?", userId);
                    db.update("UPDATE users SET estado = 'normal' WHERE id = ?", userId);
                    logClash("🚫 " + client.getUserData().get("username").getAsString() + " canceló manualmente.");
                    JsonObject cancelData = new JsonObject();
                    cancelData.addProperty("oderId", userId);
                    cancelData.addProperty("username", client.getUserData().get("username").getAsString());
                    io.emit("busqueda_cancelada", cancelData);
                }
            });

            // --- NEGOCIACIÓN EN VIVO ---
            socket.on("negociacion_live", (client, data) -> {
                try {
                    // M1: Exigir sesión autenticada y pertenencia real a la sala activa
                    if (client.getUserData() == null || !client.getUserData().has("id")) return;
                    int myId = client.getUserData().get("id").getAsInt();
                    String salaId = client.getCurrentRoom();
                    if (salaId == null || !activeMatches.containsKey(salaId)) return;
                    ActiveMatch match = activeMatches.get(salaId);
                    if (match.iniciado || (myId != match.player1Id && myId != match.player2Id)) return;

                    JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                    JsonObject limpio = new JsonObject();
                    limpio.addProperty("salaId", salaId);
                    if (d.has("modo") && !d.get("modo").isJsonNull()) {
                        match.lastModo = ChatSanitizer.limpiarTexto(d.get("modo").getAsString(), 50);
                        limpio.addProperty("modo", match.lastModo);
                    }
                    if (d.has("dinero") && !d.get("dinero").isJsonNull()) {
                        match.lastDinero = ChatSanitizer.limpiarTexto(d.get("dinero").getAsString(), 20);
                        limpio.addProperty("dinero", match.lastDinero);
                    }
                    db.update("UPDATE active_matches SET last_modo = ?, last_dinero = ?, actualizado_en = CURRENT_TIMESTAMP WHERE sala_id = ?",
                            match.lastModo, match.lastDinero, salaId);
                    client.to(salaId).emit("actualizar_negociacion", limpio);
                } catch (Exception ignored) {}
            });

            // --- INICIAR JUEGO ---
            socket.on("iniciar_juego", (client, data) -> {
                try {
                    if (client.getUserData() == null || !client.getUserData().has("id")) return;
                    int myId = client.getUserData().get("id").getAsInt();
                    JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                    String salaId = client.getCurrentRoom();
                    if (salaId == null || !activeMatches.containsKey(salaId)) return;
                    ActiveMatch match = activeMatches.get(salaId);
                    if (match.iniciado || (myId != match.player1Id && myId != match.player2Id)) return;

                    // D5: el monto lo valida el servidor (número, positivo, entero, dentro del rango de torneo).
                    // Un voto inválido no se guarda y solo se le avisa a quien lo envió.
                    ValidadorMonto.Resultado montoVoto = validador.torneo(d.get("dinero"));
                    if (!montoVoto.isValido()) {
                        client.emit("error_negociacion", new JsonPrimitive(montoVoto.getError()));
                        return;
                    }
                    String modoLimpio = (d.has("modo") && !d.get("modo").isJsonNull())
                            ? ChatSanitizer.limpiarTexto(d.get("modo").getAsString(), 50)
                            : "";
                    if (modoLimpio.length() < 3) {
                        client.emit("error_negociacion", new JsonPrimitive("El modo de juego debe tener al menos 3 caracteres"));
                        return;
                    }

                    // M2: Construir objeto de voto limpio (solo modo y dinero) sin guardar propiedades arbitrarias del cliente
                    JsonObject votoLimpio = new JsonObject();
                    votoLimpio.addProperty("modo", modoLimpio);
                    votoLimpio.addProperty("dinero", montoVoto.getMonto()); // REVIEW-MONEY

                    match.lastModo = modoLimpio;
                    match.lastDinero = montoVoto.getMonto().toPlainString();

                    // Un voto nuevo invalida cualquier confirmación anterior (nadie acepta un monto que cambió después)
                    match.confirmaciones.clear();
                    match.votosInicio.put(myId, votoLimpio);
                    persistirMatchEnBD(salaId, match);
                    client.to(salaId).emit("rival_listo_inicio");

                    // Verificar si ambos votaron
                    List<Integer> ids = match.playerIds();
                    if (ids.size() == 2 && match.votosInicio.size() == 2
                            && match.votosInicio.containsKey(ids.get(0))
                            && match.votosInicio.containsKey(ids.get(1))) {

                        BigDecimal monto1 = match.votosInicio.get(ids.get(0)).get("dinero").getAsBigDecimal();
                        BigDecimal monto2 = match.votosInicio.get(ids.get(1)).get("dinero").getAsBigDecimal();
                        if (monto1.compareTo(monto2) != 0) {
                            io.to(salaId).emit("error_negociacion", new JsonPrimitive("Montos distintos"));
                            return;
                        }

                        String modo1 = match.votosInicio.get(ids.get(0)).has("modo") ? match.votosInicio.get(ids.get(0)).get("modo").getAsString() : "N/A";

                        // Emitir confirmación modal
                        JsonObject confirmData = new JsonObject();
                        confirmData.addProperty("modo", modo1);
                        confirmData.addProperty("monto", monto1);
                        io.to(salaId).emit("confirmar_partida", confirmData);

                        // Limpiar confirmaciones anteriores si las hay
                        match.confirmaciones.clear();
                        persistirMatchEnBD(salaId, match);
                    } else {
                        client.emit("esperando_inicio_rival");
                    }
                } catch (Exception e) {
                    logger.error("Error iniciar_juego: {}", e.getMessage(), e);
                }
            });

            // --- CANCELAR INICIO ---
            socket.on("cancelar_inicio", (client, data) -> {
                try {
                    String salaId = client.getCurrentRoom();
                    if (salaId == null || !activeMatches.containsKey(salaId)) return;
                    ActiveMatch match = activeMatches.get(salaId);
                    if (match.iniciado) return;
                    if (match.inicioRevision) {
                        client.emit("error_negociacion", new JsonPrimitive(
                                "Esta partida requiere revisión administrativa antes de cancelarse"));
                        return;
                    }

                    int myId = client.getUserData().get("id").getAsInt();
                    match.votosInicio.remove(myId);
                    match.confirmaciones.clear();
                    persistirMatchEnBD(salaId, match);
                    client.to(salaId).emit("rival_cancelo_inicio");
                } catch (Exception ignored) {}
            });

            // --- RESPUESTA DE CONFIRMACION ---
            socket.on("confirmar_partida_resp", (client, data) -> {
                try {
                    if (client.getUserData() == null || !client.getUserData().has("id")) return;
                    JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                    boolean acepta = d.get("acepta").getAsBoolean();
                    String salaId = client.getCurrentRoom();
                    if (salaId == null || !activeMatches.containsKey(salaId)) return;
                    ActiveMatch match = activeMatches.get(salaId);

                    int myId = client.getUserData().get("id").getAsInt();
                    if (myId != match.player1Id && myId != match.player2Id) return;
                    if (match.inicioRevision) {
                        client.emit("error_negociacion", new JsonPrimitive(
                                "El inicio requiere revisión administrativa; no se realizará otro cobro"));
                        return;
                    }

                    // C1: Candado por partida para impedir doble inicio y doble cobro si ambos confirman a la vez
                    synchronized (match) {
                        if (match.iniciado) return;

                        if (!acepta) {
                            match.votosInicio.clear();
                            match.confirmaciones.clear();
                            persistirMatchEnBD(salaId, match);
                            io.to(salaId).emit("confirmacion_rechazada");
                            return;
                        }

                        match.confirmaciones.put(myId, true);
                        persistirMatchEnBD(salaId, match);

                        List<Integer> ids = match.playerIds();
                        if (ids.size() == 2 && match.confirmaciones.size() == 2
                                && Boolean.TRUE.equals(match.confirmaciones.get(ids.get(0)))
                                && Boolean.TRUE.equals(match.confirmaciones.get(ids.get(1)))) {
                            // Ambos confirmaron, iniciar el juego de verdad
                            JsonObject voto1 = match.votosInicio.get(ids.get(0));
                            JsonObject voto2 = match.votosInicio.get(ids.get(1));
                            if (voto1 == null || voto2 == null
                                    || voto1.get("dinero").getAsBigDecimal().compareTo(voto2.get("dinero").getAsBigDecimal()) != 0) {
                                io.to(salaId).emit("error_negociacion", new JsonPrimitive("Los montos de ambos jugadores no coinciden"));
                                match.votosInicio.clear();
                                match.confirmaciones.clear();
                                persistirMatchEnBD(salaId, match);
                                return;
                            }

                            BigDecimal montoTorneo = voto1.get("dinero").getAsBigDecimal(); // REVIEW-MONEY
                            String modo = voto1.has("modo") ? ChatSanitizer.limpiarTexto(voto1.get("modo").getAsString(), 50) : "N/A";
                            if (modo.isEmpty()) modo = "N/A";

                            // Obtener tags antes de cobrar (C5: ambos deben tener Player Tag configurado)
                            JsonObject p1 = db.queryOne("SELECT player_tag FROM users WHERE id = ?", match.player1Id);
                            JsonObject p2 = db.queryOne("SELECT player_tag FROM users WHERE id = ?", match.player2Id);
                            String tag1 = p1 != null && p1.has("player_tag") && !p1.get("player_tag").isJsonNull() ? p1.get("player_tag").getAsString().trim() : "";
                            String tag2 = p2 != null && p2.has("player_tag") && !p2.get("player_tag").isJsonNull() ? p2.get("player_tag").getAsString().trim() : "";
                            if (tag1.isEmpty() || tag2.isEmpty()) {
                                io.to(salaId).emit("error_negociacion", new JsonPrimitive("Ambos jugadores deben tener un Player Tag válido para iniciar la partida"));
                                match.votosInicio.clear();
                                match.confirmaciones.clear();
                                persistirMatchEnBD(salaId, match);
                                return;
                            }

                            // C1: Cobrar a ambos jugadores y crear el registro en matches en UNA sola transacción SQL // REVIEW-MONEY
                            String fechaInicio = java.time.Instant.now().toString();
                            WalletService.InicioPartidaResult inicioResult = wallet.iniciarPartidaTorneo(
                                    match.player1Id, match.player1Username,
                                    match.player2Id, match.player2Username,
                                    salaId, modo, montoTorneo, tag1, tag2, fechaInicio);
                            if (!inicioResult.success) {
                                match.inicioRevision = inicioResult.requiereRevision;
                                io.to(salaId).emit("error_negociacion", new JsonPrimitive(inicioResult.error));
                                match.votosInicio.clear();
                                match.confirmaciones.clear();
                                // Guardar el rechazo sin tocar actualizado_en: se usa como límite temporal al
                                // reconciliar un posible cobro legado después de un COMMIT incierto.
                                persistirVotosMatchSinAvanzarFecha(salaId, match);
                                return;
                            }

                            match.iniciado = true;
                            match.dbId = inicioResult.matchId;
                            if (inicioResult.nuevoInicio) {
                                match.matchStartTime = fechaInicio;
                                match.monto = montoTorneo;
                                match.playerTag1 = tag1;
                                match.playerTag2 = tag2;
                                match.lastModo = modo;
                                match.lastDinero = montoTorneo.toPlainString();
                            } else {
                                JsonObject inicioPersistido = db.queryOne(
                                        "SELECT monto, player_tag1, player_tag2, match_start_time, last_modo, last_dinero, poll_count " +
                                        "FROM active_matches WHERE sala_id = ? AND iniciado = TRUE AND match_db_id = ?",
                                        salaId, match.dbId);
                                if (inicioPersistido == null) {
                                    match.iniciado = false;
                                    io.to(salaId).emit("error_negociacion", new JsonPrimitive(
                                            "No se pudo recuperar el estado persistido de la partida. Vuelve a intentar."));
                                    return;
                                }
                                match.monto = inicioPersistido.get("monto").getAsBigDecimal();
                                match.playerTag1 = str(inicioPersistido, "player_tag1");
                                match.playerTag2 = str(inicioPersistido, "player_tag2");
                                match.matchStartTime = str(inicioPersistido, "match_start_time");
                                match.lastModo = str(inicioPersistido, "last_modo");
                                match.lastDinero = str(inicioPersistido, "last_dinero");
                                match.pollCount = inicioPersistido.get("poll_count").getAsInt();
                            }
                            match.votosInicio.clear();
                            match.confirmaciones.clear();
                            for (ScheduledFuture<?> timer : match.disconnectTimers.values()) timer.cancel(false);
                            match.disconnectTimers.clear();

                            // Notificar saldos actualizados a ambos jugadores
                            SocketIOClient sock1 = buscarSocketJugador(match, match.player1Id);
                            if (sock1 != null) {
                                if (sock1.getUserData() != null) sock1.getUserData().addProperty("saldo", inicioResult.nuevoSaldoJugador1);
                                sock1.emit("actualizar_saldo", new JsonPrimitive(inicioResult.nuevoSaldoJugador1));
                            }
                            SocketIOClient sock2 = buscarSocketJugador(match, match.player2Id);
                            if (sock2 != null) {
                                if (sock2.getUserData() != null) sock2.getUserData().addProperty("saldo", inicioResult.nuevoSaldoJugador2);
                                sock2.emit("actualizar_saldo", new JsonPrimitive(inicioResult.nuevoSaldoJugador2));
                            }

                            JsonObject inicioData = new JsonObject();
                            inicioData.addProperty("monto", match.monto);
                            inicioData.addProperty("matchId", match.dbId);
                            io.to(salaId).emit("juego_iniciado", inicioData);
                            if (inicioResult.nuevoInicio && match.dbId > 0) {
                                eventos.emit(Eventos.MATCH_STARTED, new Eventos.PartidaIniciada(match.dbId, ids));
                            }
                            logClash("🎮 INICIO #" + match.dbId + " | $" + match.monto.toPlainString() + " | Buscando resultado via API...");

                            // Iniciar polling de la API
                            iniciarPollingApi(salaId, match, ids);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error confirmar_partida_resp: {}", e.getMessage(), e);
                }
            });

            // --- MENSAJE PRIVADO ---
            socket.on("mensaje_privado", (client, data) -> {
                try {
                    // S2 + M1: Exigir sesión autenticada y comprobar que pertenezca a la partida activa de esa sala
                    if (client.getUserData() == null || !client.getUserData().has("id")) {
                        return;
                    }
                    int senderId = client.getUserData().get("id").getAsInt();
                    if (!socketRateLimiter.intentar("chat_priv|" + senderId, LIMITE_CHAT_SOCKET_POR_MIN).isPermitido()) {
                        return;
                    }
                    String usuarioSesion = client.getUserData().get("username").getAsString();
                    JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                    ChatSanitizer.MensajePrivado limpio = ChatSanitizer.validarMensajePrivado(
                            str(d, "salaId"), usuarioSesion, str(d, "texto"));
                    if (limpio != null) {
                        ActiveMatch match = activeMatches.get(limpio.salaId());
                        if (match == null || (senderId != match.player1Id && senderId != match.player2Id)) {
                            logger.warn("⛔ Usuario {} intentó enviar mensaje_privado a sala ajena {}", senderId, limpio.salaId());
                            return;
                        }
                        d = new JsonObject();
                        d.addProperty("salaId", limpio.salaId());
                        d.addProperty("usuario", limpio.usuario());
                        d.addProperty("texto", limpio.texto());
                        d.addProperty("tipo", "texto");
                        String fecha = java.time.Instant.now().toString();
                        db.update("INSERT INTO messages (canal, usuario, texto, tipo) VALUES (?, ?, ?, 'texto')",
                                limpio.salaId(), limpio.usuario(), limpio.texto());
                        d.addProperty("fecha", fecha);
                        io.to(limpio.salaId()).emit("mensaje_privado", d);

                        int rivalId = match.getRivalId(senderId);
                        if (rivalId > 0 && !usuarioConectadoEnSala(rivalId, limpio.salaId())) {
                            String texto = limpio.texto();
                            String preview = texto.length() > 50 ? texto.substring(0, 50) + "..." : texto;
                            pushService.enviarPush(db, rivalId,
                                    "💬 Mensaje privado", limpio.usuario() + ": " + preview);
                        }
                    }
                } catch (Exception ignored) {}
            });

            // --- CANCELAR MATCH ---
            socket.on("cancelar_match", (client, data) -> {
                JsonObject d = data.length > 0 ? ((JsonElement) data[0]).getAsJsonObject() : new JsonObject();
                String motivo = d.has("motivo") ? d.get("motivo").getAsString() : "Abandonado";
                handleCancelMatch(client, motivo);
            });

            // --- DISCONNECT ---
            socket.on("disconnect", (client, data) -> {
                colaEsperaClash.removeIf(s -> s.getSid().equals(client.getSid()));

                if (client.getUserData() != null) {
                    int userId = client.getUserData().get("id").getAsInt();
                    usuariosOnline.remove(userId);
                    logger.info("❌ Usuario " + client.getUserData().get("username").getAsString() + " está OFFLINE");
                }

                String salaId = client.getCurrentRoom();
                if (salaId != null && activeMatches.containsKey(salaId) && client.getUserData() != null) {
                    ActiveMatch match = activeMatches.get(salaId);
                    int userId = client.getUserData().get("id").getAsInt();
                    String uname = client.getUserData().get("username").getAsString();

                    if (match.iniciado) {
                        // Partida activa - esperamos indefinidamente
                        logger.info("🔌 " + uname + " desconectado de partida ACTIVA");
                        JsonObject disconnData = new JsonObject();
                        disconnData.addProperty("tiempo", "indefinido");
                        disconnData.addProperty("mensaje", "Rival desconectado. Esperando...");
                        client.to(salaId).emit("rival_desconectado", disconnData);
                    } else {
                        // Negociación - timer de desconexión (NEGOCIACION_DESCONEXION_SEGUNDOS, default 90) // A7
                        final int esperaSegundos = config.getNegociacionDesconexionSegundos();
                        logger.info("🔌 " + uname + " se fue en negociación. Timer " + esperaSegundos + "s.");

                        // Cancelar su voto si lo tenía
                        if (match.votosInicio.containsKey(userId)) {
                            match.votosInicio.remove(userId);
                            match.confirmaciones.clear();
                            persistirMatchEnBD(salaId, match);
                            client.to(salaId).emit("rival_cancelo_inicio");
                        }

                        JsonObject timerData = new JsonObject();
                        timerData.addProperty("tiempo", esperaSegundos);
                        client.to(salaId).emit("rival_desconectado", timerData);

                        programarTimerDesconexionNegociacion(salaId, match, userId, uname, true);
                    }
                }
            });
        });
    }

    // --- Recuperación y persistencia en BD (A2) ---

    /**
     * A2: Al arrancar el servidor, restaura desde PostgreSQL las salas activas (active_matches)
     * y las búsquedas en cola (match_queue), reanudando el sondeo de la API y los temporizadores.
     */
    private void recuperarEstadoDesdeBD() {
        try {
            ArrayList<JsonObject> filasMatches = db.query("SELECT * FROM active_matches ORDER BY id ASC");
            int matchesEnJuego = 0;
            int matchesNegociando = 0;

            for (JsonObject row : filasMatches) {
                String salaId = str(row, "sala_id");
                if (salaId == null || salaId.isEmpty()) continue;

                int p1Id = row.get("player1_id").getAsInt();
                int p2Id = row.get("player2_id").getAsInt();
                String p1Name = str(row, "player1_username");
                String p2Name = str(row, "player2_username");

                ActiveMatch match = new ActiveMatch(
                        p1Id, p1Name != null ? p1Name : "Jugador 1",
                        p2Id, p2Name != null ? p2Name : "Jugador 2");
                match.iniciado = row.has("iniciado") && !row.get("iniciado").isJsonNull() && row.get("iniciado").getAsBoolean();
                match.inicioRevision = row.has("inicio_revision") && !row.get("inicio_revision").isJsonNull()
                        && row.get("inicio_revision").getAsBoolean();
                match.monto = row.has("monto") && !row.get("monto").isJsonNull() ? row.get("monto").getAsBigDecimal() : BigDecimal.ZERO;
                match.dbId = row.has("match_db_id") && !row.get("match_db_id").isJsonNull() ? row.get("match_db_id").getAsInt() : 0;
                match.playerTag1 = str(row, "player_tag1") != null ? str(row, "player_tag1") : "";
                match.playerTag2 = str(row, "player_tag2") != null ? str(row, "player_tag2") : "";
                match.matchStartTime = str(row, "match_start_time") != null ? str(row, "match_start_time") : "";
                match.lastModo = str(row, "last_modo") != null ? str(row, "last_modo") : "";
                match.lastDinero = str(row, "last_dinero") != null ? str(row, "last_dinero") : "";
                match.pollCount = row.has("poll_count") && !row.get("poll_count").isJsonNull() ? row.get("poll_count").getAsInt() : 0;
                restaurarVotos(str(row, "votos_json"), match.votosInicio);
                restaurarConfirmaciones(str(row, "confirmaciones_json"), match.confirmaciones);

                // Si la partida ya había sido finalizada o pasada a disputa en matches, limpiar
                if (match.iniciado && match.dbId > 0) {
                    JsonObject mRow = db.queryOne("SELECT estado FROM matches WHERE id = ?", match.dbId);
                    String estadoMatch = mRow != null ? str(mRow, "estado") : null;
                    if (estadoMatch != null && !"en_curso".equals(estadoMatch)) {
                        eliminarMatchDeBD(salaId);
                        db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id IN (?, ?)", p1Id, p2Id);
                        continue;
                    }
                }

                activeMatches.put(salaId, match);
                String estadoUsuario = match.iniciado ? "jugando" : "partida_encontrada";
                db.update("UPDATE users SET estado = ?, sala_actual = ? WHERE id IN (?, ?)", estadoUsuario, salaId, p1Id, p2Id);

                if (match.iniciado) {
                    matchesEnJuego++;
                    iniciarPollingApi(salaId, match, match.playerIds());
                } else {
                    matchesNegociando++;
                    if (match.inicioRevision) {
                        logger.error("Sala {} bloqueada para revisión; se conserva para evitar perder un cobro legado", salaId);
                    } else {
                        // Si tras el reinicio alguno no reconecta dentro del tiempo de negociación, se libera la sala sin penalizar
                        programarTimerDesconexionNegociacion(salaId, match, p1Id, match.player1Username, false);
                        programarTimerDesconexionNegociacion(salaId, match, p2Id, match.player2Username, false);
                    }
                }
            }

            ArrayList<JsonObject> filasCola = db.query(
                    "SELECT user_id, username, EXTRACT(EPOCH FROM (expira_en - CURRENT_TIMESTAMP)) AS segundos_restantes " +
                    "FROM match_queue ORDER BY creado_en ASC");
            int busquedasRecuperadas = 0;

            for (JsonObject row : filasCola) {
                int userId = row.get("user_id").getAsInt();
                String username = str(row, "username") != null ? str(row, "username") : "Jugador";
                long segsRestantes = row.has("segundos_restantes") && !row.get("segundos_restantes").isJsonNull()
                        ? row.get("segundos_restantes").getAsBigDecimal().longValue() : 0L;

                if (segsRestantes <= 0 || estaEnPartidaActiva(userId)) {
                    db.update("DELETE FROM match_queue WHERE user_id = ?", userId);
                    if (!estaEnPartidaActiva(userId)) {
                        db.update("UPDATE users SET estado = 'normal' WHERE id = ? AND estado = 'buscando_partida'", userId);
                    }
                    continue;
                }

                BusquedaActiva busqueda = new BusquedaActiva(userId, username, null);
                busquedasActivas.put(userId, busqueda);
                db.update("UPDATE users SET estado = 'buscando_partida' WHERE id = ?", userId);
                programarTimeoutBusqueda(busqueda, segsRestantes, config.getBusquedaTimeoutMinutos());
                busquedasRecuperadas++;
            }

            // Limpiar estados huérfanos en users que no correspondan a ninguna búsqueda ni partida activa
            db.update(
                    "UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 " +
                    "WHERE (estado <> 'normal' OR sala_actual IS NOT NULL) " +
                    "AND id NOT IN (SELECT user_id FROM match_queue) " +
                    "AND id NOT IN (SELECT player1_id FROM active_matches UNION SELECT player2_id FROM active_matches)");

            logger.info("♻️ Estado recuperado de BD (A2): {} partida(s) en curso, {} sala(s) en negociación, {} búsqueda(s) en cola",
                    matchesEnJuego, matchesNegociando, busquedasRecuperadas);
        } catch (Exception e) {
            logger.error("Error recuperando estado de partidas desde la BD: {}", e.getMessage(), e);
        }
    }

    private void programarTimeoutBusqueda(BusquedaActiva busqueda, long delaySegundos, int busquedaMinutosTexto) {
        final int userId = busqueda.oderId;
        final String username = busqueda.username;
        if (busqueda.timeoutFuture != null) {
            busqueda.timeoutFuture.cancel(false);
        }
        busqueda.timeoutFuture = scheduler.schedule(() -> {
            if (busquedasActivas.containsKey(userId)) {
                colaEsperaClash.removeIf(s -> s.getUserData() != null &&
                        s.getUserData().get("id").getAsInt() == userId);
                db.update("DELETE FROM match_queue WHERE user_id = ?", userId);
                db.update("UPDATE users SET estado = 'normal' WHERE id = ?", userId);
                logClash("⏰ " + username + " - Búsqueda cancelada (" + busquedaMinutosTexto + " min sin rival)");

                if (busqueda.socket != null && busqueda.socket.isConnected()) {
                    JsonObject timeoutData = new JsonObject();
                    timeoutData.addProperty("mensaje", "⏰ Tu búsqueda fue cancelada porque nadie respondió en " + duracionTexto(busquedaMinutosTexto * 60L) + ".");
                    busqueda.socket.emit("busqueda_timeout", timeoutData);
                }

                JsonObject cancelData = new JsonObject();
                cancelData.addProperty("oderId", userId);
                cancelData.addProperty("username", username);
                io.emit("busqueda_cancelada", cancelData);
                busquedasActivas.remove(userId);
            }
        }, Math.max(1L, delaySegundos), TimeUnit.SECONDS);
    }

    private void programarTimerDesconexionNegociacion(String salaId, ActiveMatch match, int userId, String username, boolean penalizarSalida) {
        final int esperaSegundos = config.getNegociacionDesconexionSegundos();
        ScheduledFuture<?> previo = match.disconnectTimers.remove(userId);
        if (previo != null) {
            previo.cancel(false);
        }
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            ActiveMatch actual = activeMatches.get(salaId);
            if (actual != null && !actual.iniciado && !actual.inicioRevision) {
                for (ScheduledFuture<?> f : actual.disconnectTimers.values()) {
                    f.cancel(false);
                }
                actual.disconnectTimers.clear();

                if (penalizarSalida) {
                    db.update("UPDATE users SET salidas_chat = salidas_chat + 1, salidas_desconexion = salidas_desconexion + 1 WHERE id = ?", userId);
                }
                logClash("⚠️ ABANDONO Negociación: " + username + " no volvió (" + esperaSegundos + "s)");

                JsonObject cancelData = new JsonObject();
                cancelData.addProperty("motivo", username + " abandonó por desconexión");
                io.to(salaId).emit("match_cancelado", cancelData);

                db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id IN (?, ?)",
                        actual.player1Id, actual.player2Id);
                for (SocketIOClient p : actual.players) {
                    if (p != null) {
                        p.leave(salaId);
                        p.setCurrentRoom(null);
                    }
                }
                eliminarMatchDeBD(salaId);
                activeMatches.remove(salaId);
            }
        }, esperaSegundos, TimeUnit.SECONDS);
        match.disconnectTimers.put(userId, timer);
    }

    private void persistirMatchEnBD(String salaId, ActiveMatch match) {
        if (salaId == null || match == null) return;
        String votosJson = serializarVotos(match.votosInicio);
        String confirmacionesJson = serializarConfirmaciones(match.confirmaciones);
        db.update(
                "UPDATE active_matches SET " +
                "iniciado = ?, monto = ?, match_db_id = ?, player_tag1 = ?, player_tag2 = ?, " +
                "match_start_time = ?, last_modo = ?, last_dinero = ?, votos_json = ?, " +
                "confirmaciones_json = ?, poll_count = ?, actualizado_en = CURRENT_TIMESTAMP " +
                "WHERE sala_id = ? AND iniciado = FALSE AND inicio_revision = FALSE AND match_db_id = 0",
                match.iniciado,
                match.monto != null ? match.monto : BigDecimal.ZERO,
                match.dbId,
                match.playerTag1 != null ? match.playerTag1 : "",
                match.playerTag2 != null ? match.playerTag2 : "",
                match.matchStartTime != null ? match.matchStartTime : "",
                match.lastModo != null ? match.lastModo : "",
                match.lastDinero != null ? match.lastDinero : "",
                votosJson,
                confirmacionesJson,
                match.pollCount,
                salaId);
    }

    private void persistirVotosMatchSinAvanzarFecha(String salaId, ActiveMatch match) {
        if (salaId == null || match == null) return;
        db.update(
                "UPDATE active_matches SET votos_json = ?, confirmaciones_json = ? " +
                "WHERE sala_id = ? AND iniciado = FALSE AND inicio_revision = FALSE AND match_db_id = 0",
                serializarVotos(match.votosInicio), serializarConfirmaciones(match.confirmaciones), salaId);
    }

    private void eliminarMatchDeBD(String salaId) {
        if (salaId == null) return;
        db.update("DELETE FROM active_matches WHERE sala_id = ?", salaId);
    }

    private String serializarVotos(Map<Integer, JsonObject> votos) {
        JsonObject root = new JsonObject();
        for (Map.Entry<Integer, JsonObject> e : votos.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                root.add(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return gson.toJson(root);
    }

    private void restaurarVotos(String json, ConcurrentHashMap<Integer, JsonObject> destino) {
        destino.clear();
        if (json == null || json.isBlank()) return;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                    if (e.getValue() != null && e.getValue().isJsonObject()) {
                        JsonObject votoObj = e.getValue().getAsJsonObject();
                        if (votoObj.has("dinero") && !votoObj.get("dinero").isJsonNull()) {
                            votoObj.addProperty("dinero", votoObj.get("dinero").getAsBigDecimal());
                        }
                        destino.put(Integer.parseInt(e.getKey()), votoObj);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("No se pudo restaurar votos_json: {}", e.getMessage());
        }
    }

    private String serializarConfirmaciones(Map<Integer, Boolean> confirmaciones) {
        JsonObject root = new JsonObject();
        for (Map.Entry<Integer, Boolean> e : confirmaciones.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                root.addProperty(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return gson.toJson(root);
    }

    private void restaurarConfirmaciones(String json, ConcurrentHashMap<Integer, Boolean> destino) {
        destino.clear();
        if (json == null || json.isBlank()) return;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                    if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                        destino.put(Integer.parseInt(e.getKey()), e.getValue().getAsBoolean());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("No se pudo restaurar confirmaciones_json: {}", e.getMessage());
        }
    }

    private boolean estaEnPartidaActiva(int userId) {
        return buscarSalaDeJugador(userId) != null;
    }

    private String buscarSalaDeJugador(int userId) {
        for (Map.Entry<String, ActiveMatch> entry : activeMatches.entrySet()) {
            ActiveMatch m = entry.getValue();
            if (m.player1Id == userId || m.player2Id == userId) {
                return entry.getKey();
            }
        }
        return null;
    }

    private SocketIOClient buscarSocketJugador(ActiveMatch match, int userId) {
        if (match != null) {
            for (SocketIOClient p : match.players) {
                if (p != null && p.getUserData() != null && p.getUserData().has("id")
                        && p.getUserData().get("id").getAsInt() == userId) {
                    return p;
                }
            }
        }
        for (SocketIOClient s : io.getSockets().values()) {
            if (s.getUserData() != null && s.getUserData().has("id")
                    && s.getUserData().get("id").getAsInt() == userId) {
                return s;
            }
        }
        return null;
    }

    // --- Métodos auxiliares ---

    /** Lee un campo String de un JSON del cliente sin lanzar excepción (null si falta o no es un valor simple). */
    private static String str(JsonObject o, String clave) {
        return (o != null && o.has(clave) && o.get(clave).isJsonPrimitive()) ? o.get(clave).getAsString() : null;
    }

    /** Texto legible de una duración para los mensajes al usuario: "10 minutos", "1 minuto", "90 segundos". */
    static String duracionTexto(long segundos) {
        if (segundos > 0 && segundos % 60 == 0) {
            long minutos = segundos / 60;
            return minutos + (minutos == 1 ? " minuto" : " minutos");
        }
        return segundos + (segundos == 1 ? " segundo" : " segundos");
    }

    private synchronized void intentarMatcheo(SocketIOClient meSocket, int myId, JsonObject myData) {
        if (!busquedasActivas.containsKey(myId) || estaEnPartidaActiva(myId)) return;
        for (Map.Entry<Integer, BusquedaActiva> entry : busquedasActivas.entrySet()) {
            if (entry.getKey() != myId) {
                BusquedaActiva rival = entry.getValue();
                if (estaEnPartidaActiva(rival.oderId)) continue;
                SocketIOClient rivalSocket = rival.socket;
                boolean rivalConectado = rivalSocket != null && rivalSocket.isConnected();

                String myUsername = myData.get("username").getAsString();
                // M1: Sufijo aleatorio de 4 cifras para evitar predecibilidad y colisiones en el mismo milisegundo (cumple ^sala_\d{1,20}$)
                String salaId = "sala_" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 10000);
                meSocket.setCurrentRoom(salaId);
                meSocket.join(salaId);
                if (rivalConectado) { rivalSocket.setCurrentRoom(salaId); rivalSocket.join(salaId); }

                ActiveMatch match = new ActiveMatch(myId, myUsername, rival.oderId, rival.username);
                match.players.add(meSocket);
                if (rivalSocket != null) {
                    match.players.add(rivalSocket);
                }
                activeMatches.put(salaId, match);

                // A2: Persistir la sala en active_matches y retirar a ambos de match_queue
                db.update(
                        "INSERT INTO active_matches (sala_id, player1_id, player1_username, player2_id, player2_username) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (sala_id) DO NOTHING",
                        salaId, myId, myUsername, rival.oderId, rival.username);
                db.update("DELETE FROM match_queue WHERE user_id IN (?, ?)", myId, rival.oderId);

                // Si el rival se emparejó estando temporalmente desconectado (ej. tras un reinicio), darle margen de reconexión
                if (!rivalConectado) {
                    programarTimerDesconexionNegociacion(salaId, match, rival.oderId, rival.username, false);
                }

                JsonObject rivalData = db.queryOne("SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE id = ?", rival.oderId);
                BigDecimal miSaldo = myData.has("saldo") && !myData.get("saldo").isJsonNull()
                        ? myData.get("saldo").getAsBigDecimal() : BigDecimal.ZERO;
                BigDecimal rivalSaldo = rivalData != null && rivalData.has("saldo") && !rivalData.get("saldo").isJsonNull()
                        ? rivalData.get("saldo").getAsBigDecimal() : BigDecimal.ZERO;
                BigDecimal maxMonto = miSaldo.min(rivalSaldo); // REVIEW-MONEY

                db.update("UPDATE users SET estado = 'partida_encontrada', sala_actual = ? WHERE id IN (?, ?)",
                        salaId, myId, rival.oderId);
                logClash("⚔️ MATCH: " + myUsername + " vs " + rival.username);

                JsonObject matchData = new JsonObject();
                matchData.addProperty("salaId", salaId);
                matchData.add("p1", UsuarioVista.paraRival(myData)); // S6: solo vista pública (sin password/email/teléfono/saldo)
                matchData.add("p2", UsuarioVista.paraRival(rivalData));
                matchData.addProperty("maxMonto", maxMonto);

                meSocket.emit("partida_encontrada", matchData);
                if (rivalConectado) rivalSocket.emit("partida_encontrada", matchData);

                // Push: notificar a ambos que se encontró partida
                pushService.enviarPush(db, myId, "🎮 ¡Rival encontrado!", "Se encontró partida contra " + rival.username);
                pushService.enviarPush(db, rival.oderId, "🎮 ¡Rival encontrado!", "Se encontró partida contra " + myUsername);

                // Limpiar búsquedas en memoria
                colaEsperaClash.removeIf(s -> s.getUserData() != null &&
                        (s.getUserData().get("id").getAsInt() == myId || s.getUserData().get("id").getAsInt() == rival.oderId));
                for (int playerId : new int[]{myId, rival.oderId}) {
                    BusquedaActiva b = busquedasActivas.remove(playerId);
                    if (b != null && b.timeoutFuture != null) b.timeoutFuture.cancel(false);
                    JsonObject cancelData = new JsonObject();
                    cancelData.addProperty("oderId", playerId);
                    cancelData.addProperty("username", b != null ? b.username : "");
                    io.emit("busqueda_cancelada", cancelData);
                }
                break;
            }
        }
    }

    private void iniciarPollingApi(String salaId, ActiveMatch match, List<Integer> ids) {
        // A7: POLLING_MAX_INTENTOS x POLLING_INTERVALO_SEGUNDOS (default 120 x 5 s = 10 min) antes de crear la disputa
        final int MAX_POLLS = config.getPollingMaxIntentos();
        final int intervaloSegundos = config.getPollingIntervaloSegundos();

        // A8: el sondeo llama a la API de Clash (HTTP bloqueante, hasta 10 s). Por eso NO corre en el hilo del
        // temporizador: cada tick solo lo encola en el pool de IO, y `enCurso` impide que dos sondeos de la
        // misma partida se solapen (un solape podría liquidar la partida dos veces). // REVIEW-MONEY
        final AtomicBoolean enCurso = new AtomicBoolean(false);
        final Runnable sondeo = () -> {
            try {
                if (!activeMatches.containsKey(salaId)) { match.pollFuture.cancel(false); return; }
                match.pollCount++;
                db.update("UPDATE active_matches SET poll_count = ?, actualizado_en = CURRENT_TIMESTAMP WHERE sala_id = ?",
                        match.pollCount, salaId);
                logger.info("🔍 Buscando resultado #" + match.dbId + " (intento " + match.pollCount + "/" + MAX_POLLS + ")");

                // C5: Si falta algún Player Tag, no dejar la partida colgada: pasar a disputa de inmediato
                if (match.playerTag1.isEmpty() || match.playerTag2.isEmpty()) {
                    match.pollFuture.cancel(false);
                    db.update("UPDATE matches SET estado = 'disputa' WHERE id = ? AND estado = 'en_curso'", match.dbId);
                    logClash("🚨 DISPUTA #" + match.dbId + " (Player Tag faltante)");
                    JsonObject disputaData = new JsonObject();
                    disputaData.addProperty("mensaje", "Falta el Player Tag de un jugador. Disputa creada para revisión.");
                    io.to(salaId).emit("disputa_creada", disputaData);
                    liberarJugadores(salaId, match);
                    return;
                }

                JsonObject battleResult = clashApi.findMatchingBattle(match.playerTag1, match.playerTag2, match.matchStartTime);

                if (battleResult != null) {
                    match.pollFuture.cancel(false);
                    String winnerTag = battleResult.get("winnerTag").getAsString();
                    logClash("🎯 RESULTADO #" + match.dbId + ": " + battleResult.get("teamCrowns").getAsInt() + "-" + battleResult.get("opponentCrowns").getAsInt());

                    int idGanador = 0;
                    String winnerName = "Ganador";
                    for (int pid : ids) {
                        JsonObject pTag = db.queryOne("SELECT username, player_tag FROM users WHERE id = ?", pid);
                        if (pTag != null && pTag.has("player_tag") && !pTag.get("player_tag").isJsonNull()
                                && pTag.get("player_tag").getAsString().replace("#", "").toUpperCase().equals(winnerTag)) {
                            idGanador = pid;
                            winnerName = pTag.has("username") && !pTag.get("username").isJsonNull()
                                    ? pTag.get("username").getAsString()
                                    : match.getUsername(pid);
                            break;
                        }
                    }

                    if (idGanador == 0) {
                        // Disputa
                        db.update("UPDATE matches SET estado = 'disputa' WHERE id = ? AND estado = 'en_curso'", match.dbId);
                        logClash("🚨 DISPUTA #" + match.dbId);
                        JsonObject disputaData = new JsonObject();
                        disputaData.addProperty("mensaje", "Resultado no claro, disputa creada.");
                        io.to(salaId).emit("disputa_creada", disputaData);
                        liberarJugadores(salaId, match);
                        return;
                    }

                    // C2: Liquidar partida y marcarla 'finalizada' (WHERE estado = 'en_curso') en UNA sola transacción // REVIEW-MONEY
                    int idPerdedor = (idGanador == ids.get(0)) ? ids.get(1) : ids.get(0);
                    LiquidacionResult liq = wallet.liquidar(idGanador, idPerdedor,
                            match.monto, match.dbId, "comision_match", "en_curso", winnerName, null);

                    if (!liq.success) {
                        logger.error("❌ Error liquidando match #{}: {}", match.dbId, liq.error);
                        db.update("UPDATE matches SET estado = 'disputa' WHERE id = ? AND estado = 'en_curso'", match.dbId);
                        JsonObject errData = new JsonObject();
                        errData.addProperty("mensaje", "Error al liquidar. Disputa creada.");
                        io.to(salaId).emit("disputa_creada", errData);
                        liberarJugadores(salaId, match);
                        return;
                    }

                    SocketIOClient winnerSocket = buscarSocketJugador(match, idGanador);
                    if (winnerSocket != null && winnerSocket.getUserData() != null) {
                        winnerSocket.getUserData().addProperty("saldo", liq.nuevoSaldoGanador);
                    }

                    logClash("🏆 GANADOR API #" + match.dbId + ": " + winnerName);

                    // A4: la liquidación y el match ya están confirmados en la BD; se avisa al bus y los listeners hacen el resto
                    // (tickets de sorteo + `tickets_ganados`, saldo nuevo del ganador, `resultado_api` y push a ambos jugadores).
                    // emit() no lanza: un listener roto no impide liberar a los jugadores. // REVIEW-MONEY
                    eventos.emit(Eventos.MATCH_FINISHED, new Eventos.PartidaFinalizada(
                            match.dbId, Eventos.Origen.API, ids, idGanador, winnerName,
                            match.monto, liq.premio, liq.nuevoSaldoGanador, liq.desglose));
                    liberarJugadores(salaId, match);

                } else if (match.pollCount >= MAX_POLLS) {
                    match.pollFuture.cancel(false);
                    db.update("UPDATE matches SET estado = 'disputa' WHERE id = ?", match.dbId);
                    logClash("⏰ TIMEOUT #" + match.dbId + " - Disputa creada automáticamente");
                    JsonObject timeoutData = new JsonObject();
                    timeoutData.addProperty("mensaje", "No se encontró el resultado en " + duracionTexto((long) MAX_POLLS * intervaloSegundos) + ". Disputa creada.");
                    io.to(salaId).emit("disputa_timeout", timeoutData);
                    liberarJugadores(salaId, match);
                }
            } catch (Exception e) { logger.error("Error en polling: " + e.getMessage()); }
        };

        match.pollFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!enCurso.compareAndSet(false, true)) return; // el sondeo anterior sigue en curso: se salta este tick
            boolean encolado = ejecutores.ejecutarIO("sondeo Clash #" + match.dbId, () -> {
                try { sondeo.run(); } finally { enCurso.set(false); }
            });
            if (!encolado) enCurso.set(false); // pool de IO lleno: se reintenta en el próximo tick
        }, intervaloSegundos, intervaloSegundos, TimeUnit.SECONDS);
    }

    private void liberarJugadores(String salaId, ActiveMatch match) {
        db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id IN (?, ?)",
                match.player1Id, match.player2Id);
        for (int pid : match.playerIds()) {
            SocketIOClient s = buscarSocketJugador(match, pid);
            if (s != null) {
                s.emit("flujo_completado");
            }
        }
        eliminarMatchDeBD(salaId);
        activeMatches.remove(salaId);
    }

    private void handleCancelMatch(SocketIOClient socket, String motivoCrudo) {
        String salaId = socket.getCurrentRoom();
        if (salaId == null || !activeMatches.containsKey(salaId)) return;
        ActiveMatch match = activeMatches.get(salaId);
        if (match.iniciado) return;
        if (match.inicioRevision) {
            socket.emit("error_negociacion", new JsonPrimitive(
                    "Esta partida requiere revisión administrativa; se conservó para proteger el cobro"));
            return;
        }

        // M2: Sanitizar el motivo antes de guardarlo en clash_logs o reenviarlo a la sala
        String motivo = ChatSanitizer.limpiarTexto(motivoCrudo, 60);
        if (motivo.isEmpty()) motivo = "Abandonado";

        for (ScheduledFuture<?> f : match.disconnectTimers.values()) {
            f.cancel(false);
        }
        match.disconnectTimers.clear();

        if (socket.getUserData() != null) {
            int uid = socket.getUserData().get("id").getAsInt();
            db.update("UPDATE users SET salidas_chat = salidas_chat + 1 WHERE id = ?", uid);
            if ("Oprimió X".equals(motivo)) db.update("UPDATE users SET salidas_x = salidas_x + 1 WHERE id = ?", uid);
            else if ("Salió del chat".equals(motivo)) db.update("UPDATE users SET salidas_canal = salidas_canal + 1 WHERE id = ?", uid);
        }

        String usersStr = match.player1Username + " vs " + match.player2Username;
        logClash("⚠️ Cancelada (" + usersStr + "): " + motivo);

        JsonObject cancelData = new JsonObject();
        cancelData.addProperty("motivo", motivo);
        io.to(salaId).emit("match_cancelado", cancelData);

        db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id IN (?, ?)",
                match.player1Id, match.player2Id);
        for (SocketIOClient p : match.players) {
            if (p != null) {
                p.leave(salaId);
                p.setCurrentRoom(null);
            }
        }
        eliminarMatchDeBD(salaId);
        activeMatches.remove(salaId);
    }

    private boolean permitirPushChat(String canal) {
        AtomicLong ultimo = ultimoPushChatPorCanal.computeIfAbsent(canal, ignorado -> new AtomicLong(0));
        long ahora = System.nanoTime();
        long ventana = TimeUnit.MINUTES.toNanos(1);
        while (true) {
            long anterior = ultimo.get();
            if (anterior != 0 && ahora - anterior < ventana) return false;
            if (ultimo.compareAndSet(anterior, ahora)) return true;
        }
    }

    /** Usuarios autenticados con un socket abierto que ya recibe los broadcasts públicos. */
    private Set<Integer> excluirConectadosEnTiempoReal(int emisorId) {
        Set<Integer> ids = new HashSet<>();
        for (SocketIOClient socket : io.getSockets().values()) {
            if (!socket.isConnected()) continue;
            Integer userId = idAutenticadoSocket(socket);
            if (userId != null) ids.add(userId);
        }
        if (emisorId > 0) ids.add(emisorId);
        return ids;
    }

    /** Un mensaje privado solo evita el push si el destinatario está conectado a esa sala concreta. */
    private boolean usuarioConectadoEnSala(int userId, String salaId) {
        if (userId <= 0 || salaId == null || salaId.isBlank()) return false;
        for (SocketIOClient socket : io.getSockets().values()) {
            if (socket.isConnected()
                    && Objects.equals(idAutenticadoSocket(socket), userId)
                    && socket.getRooms().contains(salaId)) {
                return true;
            }
        }
        return false;
    }

    private Integer idAutenticadoSocket(SocketIOClient socket) {
        Integer authId = socket.getAuthenticatedUserId();
        if (authId != null) return authId;
        JsonObject userData = socket.getUserData();
        if (userData == null || !userData.has("id") || userData.get("id").isJsonNull()) return null;
        try {
            return userData.get("id").getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void logClash(String texto) {
        logger.info(texto);
        String fecha = java.time.Instant.now().toString();
        db.update("INSERT INTO messages (canal, usuario, texto, tipo) VALUES ('clash_logs', 'SISTEMA', ?, 'log')", texto);
        JsonObject logData = new JsonObject();
        logData.addProperty("canal", "clash_logs");
        logData.addProperty("usuario", "SISTEMA");
        logData.addProperty("texto", texto);
        logData.addProperty("tipo", "log");
        logData.addProperty("fecha", fecha);
        io.emit("mensaje_chat", logData);
    }

    // --- Clases internas ---
    static class ActiveMatch {
        List<SocketIOClient> players = new CopyOnWriteArrayList<>();
        int player1Id;
        int player2Id;
        String player1Username;
        String player2Username;
        BigDecimal monto = BigDecimal.ZERO; // REVIEW-MONEY
        boolean iniciado = false;
        boolean inicioRevision = false;
        int dbId = 0;
        String playerTag1 = "", playerTag2 = "";
        String matchStartTime = "";
        ConcurrentHashMap<Integer, JsonObject> votosInicio = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, Boolean> confirmaciones = new ConcurrentHashMap<>();
        String lastModo = "";
        String lastDinero = "";
        int pollCount = 0;
        ScheduledFuture<?> pollFuture;

        ActiveMatch(int player1Id, String player1Username, int player2Id, String player2Username) {
            this.player1Id = player1Id;
            this.player1Username = player1Username != null ? player1Username : "";
            this.player2Id = player2Id;
            this.player2Username = player2Username != null ? player2Username : "";
        }

        List<Integer> playerIds() {
            if (player1Id > 0 && player2Id > 0) {
                return List.of(player1Id, player2Id);
            }
            List<Integer> ids = new ArrayList<>();
            for (SocketIOClient p : players) {
                if (p != null && p.getUserData() != null && p.getUserData().has("id")) {
                    ids.add(p.getUserData().get("id").getAsInt());
                }
            }
            return ids;
        }

        int getRivalId(int myId) {
            if (myId == player1Id) return player2Id;
            if (myId == player2Id) return player1Id;
            return 0;
        }

        String getUsername(int userId) {
            if (userId == player1Id) return player1Username;
            if (userId == player2Id) return player2Username;
            return "Jugador";
        }

        void actualizarSocketJugador(int userId, SocketIOClient client) {
            for (int i = 0; i < players.size(); i++) {
                SocketIOClient p = players.get(i);
                if (p != null && p.getUserData() != null && p.getUserData().has("id")
                        && p.getUserData().get("id").getAsInt() == userId) {
                    players.set(i, client);
                    return;
                }
            }
            players.add(client);
        }
    }

    static class BusquedaActiva {
        int oderId;
        String username;
        SocketIOClient socket;
        ScheduledFuture<?> timeoutFuture;

        BusquedaActiva(int oderId, String username, SocketIOClient socket) {
            this.oderId = oderId;
            this.username = username;
            this.socket = socket;
        }
    }
}
