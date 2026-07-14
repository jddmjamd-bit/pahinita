package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.servicio.ClashApiServicio;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Maneja todos los eventos Socket.IO en tiempo real.
 * Equivalente al bloque io.on('connection', ...) de index.js.
 *
 * CONCEPTO POO: Collection - ConcurrentHashMap para matches activos, colas, búsquedas
 */
public class SocketHandler {

    // Estado en memoria (equivalente a las variables globales de index.js)
    private final ConcurrentHashMap<String, ActiveMatch> activeMatches = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SocketIOClient> colaEsperaClash = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Integer, BusquedaActiva> busquedasActivas = new ConcurrentHashMap<>();
    private final Set<Integer> usuariosOnline = ConcurrentHashMap.newKeySet();

    private final GenericDAO db;
    private final SocketIOServer io;
    private final ClashApiServicio clashApi;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Gson gson = new Gson();

    public SocketHandler(GenericDAO db, SocketIOServer io, ClashApiServicio clashApi) {
        this.db = db;
        this.io = io;
        this.clashApi = clashApi;
    }

    /**
     * Registra todos los event handlers del Socket.IO server.
     */
    public void registrar() {
        io.onConnection((socket, args) -> {
            // Enviar historial de chats
            for (String canal : new String[]{"anuncios", "general", "clash", "clash_logs"}) {
                ArrayList<JsonObject> msgs = db.query(
                        "SELECT * FROM (SELECT * FROM messages WHERE canal = ? ORDER BY id DESC LIMIT 50) t ORDER BY id ASC", canal);
                JsonObject histData = new JsonObject();
                histData.addProperty("canal", canal);
                histData.add("mensajes", gson.toJsonTree(msgs));
                socket.emit("historial_chat", histData);
            }

            // --- REGISTRAR SOCKET ---
            socket.on("registrar_socket", (client, data) -> {
                try {
                    System.out.println("INTENTO DE REGISTRAR SOCKET: " + data[0]);
                    JsonObject user = ((JsonElement) data[0]).getAsJsonObject();
                    int userId = user.get("id").getAsInt();
                    String username = user.get("username").getAsString();

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

                    client.setUserData(user);
                    usuariosOnline.add(userId);
                    System.out.println("✅ Usuario " + username + " (" + userId + ") está ONLINE");

                    // Enviar búsquedas activas de otros usuarios
                    for (Map.Entry<Integer, BusquedaActiva> entry : busquedasActivas.entrySet()) {
                        if (entry.getKey() != userId) {
                            JsonObject bData = new JsonObject();
                            bData.addProperty("username", entry.getValue().username);
                            bData.addProperty("oderId", entry.getValue().oderId);
                            client.emit("alguien_buscando", bData);
                        }
                    }

                    // Verificar si ESTE usuario tiene una búsqueda activa (reconexión)
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
                        System.out.println("🔄 " + username + " reconectado con búsqueda activa");
                    }

                    // Recuperar sala si existe
                    String salaActual = user.has("sala_actual") && !user.get("sala_actual").isJsonNull() ?
                            user.get("sala_actual").getAsString() : null;
                    if (salaActual != null && activeMatches.containsKey(salaActual)) {
                        ActiveMatch match = activeMatches.get(salaActual);
                        // Cancelar timer de desconexión
                        if (match.disconnectTimers.containsKey(userId)) {
                            match.disconnectTimers.get(userId).cancel(false);
                            match.disconnectTimers.remove(userId);
                            client.to(salaActual).emit("rival_reconectado");
                        }
                        client.join(salaActual);
                        client.setCurrentRoom(salaActual);

                        // Reemplazar socket en el match
                        for (int i = 0; i < match.players.size(); i++) {
                            if (match.players.get(i).getUserData() != null &&
                                match.players.get(i).getUserData().get("id").getAsInt() == userId) {
                                match.players.set(i, client);
                                break;
                            }
                        }

                        // Datos para restaurar
                        ArrayList<JsonObject> msgs = db.query("SELECT * FROM messages WHERE canal = ? ORDER BY id ASC", salaActual);
                        JsonObject restoreData = new JsonObject();
                        restoreData.addProperty("salaId", salaActual);
                        restoreData.addProperty("iniciado", match.iniciado);
                        restoreData.add("historial", gson.toJsonTree(msgs));
                        client.emit("restaurar_partida", restoreData);
                    }

                } catch (Exception e) {
                    System.err.println("Error registrar_socket: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            // --- MENSAJES ---
            socket.on("mensaje_chat", (client, data) -> {
                try {
                    JsonObject msg = ((JsonElement) data[0]).getAsJsonObject();
                    String canal = msg.get("canal").getAsString();
                    String usuario = msg.get("usuario").getAsString();
                    String texto = msg.get("texto").getAsString();
                    String tipo = msg.has("tipo") ? msg.get("tipo").getAsString() : "texto";
                    String fecha = java.time.Instant.now().toString();

                    db.update("INSERT INTO messages (canal, usuario, texto, tipo) VALUES (?, ?, ?, ?)",
                            canal, usuario, texto, tipo);
                    msg.addProperty("fecha", fecha);
                    io.emit("mensaje_chat", msg);
                } catch (Exception e) { System.err.println("Error mensaje_chat: " + e.getMessage()); }
            });

            // --- BUSCAR PARTIDA ---
            socket.on("buscar_partida", (client, data) -> {
                try {
                    JsonObject usuario = ((JsonElement) data[0]).getAsJsonObject();
                    int userId = usuario.get("id").getAsInt();

                    JsonObject row = db.queryOne("SELECT * FROM users WHERE id = ?", userId);
                    if (row == null || row.get("saldo").getAsDouble() < 1000) {
                        client.emit("error_busqueda", new JsonPrimitive("Saldo insuficiente"));
                        return;
                    }

                    client.setUserData(row);
                    if (busquedasActivas.containsKey(userId)) return;

                    colaEsperaClash.add(client);
                    db.update("UPDATE users SET estado = 'buscando_partida' WHERE id = ?", userId);

                    String username = row.get("username").getAsString();
                    BusquedaActiva busqueda = new BusquedaActiva(userId, username, client);
                    busquedasActivas.put(userId, busqueda);

                    // Timer de 10 minutos
                    busqueda.timeoutFuture = scheduler.schedule(() -> {
                        if (busquedasActivas.containsKey(userId)) {
                            colaEsperaClash.removeIf(s -> s.getUserData() != null &&
                                    s.getUserData().get("id").getAsInt() == userId);
                            db.update("UPDATE users SET estado = 'normal' WHERE id = ?", userId);
                            logClash("⏰ " + username + " - Búsqueda cancelada (10 min sin rival)");

                            if (busqueda.socket != null && busqueda.socket.isConnected()) {
                                JsonObject timeoutData = new JsonObject();
                                timeoutData.addProperty("mensaje", "⏰ Tu búsqueda fue cancelada porque nadie respondió en 10 minutos.");
                                busqueda.socket.emit("busqueda_timeout", timeoutData);
                            }

                            JsonObject cancelData = new JsonObject();
                            cancelData.addProperty("oderId", userId);
                            cancelData.addProperty("username", username);
                            io.emit("busqueda_cancelada", cancelData);
                            busquedasActivas.remove(userId);
                        }
                    }, 10, TimeUnit.MINUTES);

                    // Notificar a todos
                    JsonObject buscandoData = new JsonObject();
                    buscandoData.addProperty("username", username);
                    buscandoData.addProperty("oderId", userId);
                    io.emit("alguien_buscando", buscandoData);

                    logClash("🔍 " + username + " busca...");

                    // Intentar matcheo
                    intentarMatcheo(client, userId, row);

                } catch (Exception e) {
                    System.err.println("Error buscar_partida: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            // --- CANCELAR BÚSQUEDA ---
            socket.on("cancelar_busqueda", (client, data) -> {
                colaEsperaClash.removeIf(s -> s.getSid().equals(client.getSid()));
                if (client.getUserData() != null) {
                    int userId = client.getUserData().get("id").getAsInt();
                    BusquedaActiva busqueda = busquedasActivas.remove(userId);
                    if (busqueda != null && busqueda.timeoutFuture != null) busqueda.timeoutFuture.cancel(false);
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
                JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                String salaId = d.get("salaId").getAsString();
                client.to(salaId).emit("actualizar_negociacion", d);
            });

            // --- INICIAR JUEGO ---
            socket.on("iniciar_juego", (client, data) -> {
                try {
                    JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                    String salaId = client.getCurrentRoom();
                    if (salaId == null || !activeMatches.containsKey(salaId)) return;
                    ActiveMatch match = activeMatches.get(salaId);
                    if (match.iniciado) return;

                    int myId = client.getUserData().get("id").getAsInt();
                    match.votosInicio.put(myId, d);
                    client.to(salaId).emit("rival_listo_inicio");

                    // Verificar si ambos votaron
                    if (match.players.size() == 2 && match.votosInicio.size() == 2) {
                        List<Integer> ids = new ArrayList<>();
                        for (SocketIOClient p : match.players) ids.add(p.getUserData().get("id").getAsInt());

                        int ap1 = match.votosInicio.get(ids.get(0)).get("dinero").getAsInt();
                        int ap2 = match.votosInicio.get(ids.get(1)).get("dinero").getAsInt();
                        if (ap1 != ap2) {
                            io.to(salaId).emit("error_negociacion", new JsonPrimitive("Montos distintos"));
                            return;
                        }

                        match.iniciado = true;
                        match.apuesta = ap1;


                        // Obtener tags
                        JsonObject p1 = db.queryOne("SELECT player_tag FROM users WHERE id = ?", ids.get(0));
                        JsonObject p2 = db.queryOne("SELECT player_tag FROM users WHERE id = ?", ids.get(1));
                        match.playerTag1 = p1 != null ? p1.get("player_tag").getAsString() : "";
                        match.playerTag2 = p2 != null ? p2.get("player_tag").getAsString() : "";

                        // Descontar saldo
                        for (SocketIOClient p : match.players) {
                            int pid = p.getUserData().get("id").getAsInt();
                            db.update("UPDATE users SET saldo = saldo - ?, estado = 'jugando', paso_juego = 0, total_apostado = total_apostado + ? WHERE id = ?",
                                    (double) match.apuesta, (double) match.apuesta, pid);
                            JsonObject saldoRes = db.queryOne("SELECT saldo FROM users WHERE id = ?", pid);
                            double nuevoSaldo = saldoRes != null ? saldoRes.get("saldo").getAsDouble() : 0;
                            p.getUserData().addProperty("saldo", nuevoSaldo);
                            p.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));
                        }

                        // Crear match en BD
                        String j1 = match.players.get(0).getUserData().get("username").getAsString();
                        String j2 = match.players.get(1).getUserData().get("username").getAsString();
                        String modo = match.votosInicio.get(ids.get(0)).has("modo") ?
                                match.votosInicio.get(ids.get(0)).get("modo").getAsString() : "N/A";
                        int dbId = db.insertReturningId("INSERT INTO matches (jugador1, jugador2, modo, apuesta) VALUES (?, ?, ?, ?) RETURNING id",
                                j1, j2, modo, (double) match.apuesta);
                        match.dbId = dbId;

                        JsonObject inicioData = new JsonObject();
                        inicioData.addProperty("monto", match.apuesta);
                        inicioData.addProperty("matchId", dbId);
                        io.to(salaId).emit("juego_iniciado", inicioData);
                        logClash("🎮 INICIO #" + dbId + " | $" + match.apuesta + " | Buscando resultado via API...");

                        // Iniciar polling de la API
                        iniciarPollingApi(salaId, match, ids);
                    } else {
                        client.emit("esperando_inicio_rival");
                    }
                } catch (Exception e) {
                    System.err.println("Error iniciar_juego: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            // --- MENSAJE PRIVADO ---
            socket.on("mensaje_privado", (client, data) -> {
                try {
                    JsonObject d = ((JsonElement) data[0]).getAsJsonObject();
                    if (d.has("salaId")) {
                        String fecha = java.time.Instant.now().toString();
                        db.update("INSERT INTO messages (canal, usuario, texto, tipo) VALUES (?, ?, ?, 'texto')",
                                d.get("salaId").getAsString(), d.get("usuario").getAsString(), d.get("texto").getAsString());
                        d.addProperty("fecha", fecha);
                        io.to(d.get("salaId").getAsString()).emit("mensaje_privado", d);
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
                    System.out.println("❌ Usuario " + client.getUserData().get("username").getAsString() + " está OFFLINE");
                }

                String salaId = client.getCurrentRoom();
                if (salaId != null && activeMatches.containsKey(salaId) && client.getUserData() != null) {
                    ActiveMatch match = activeMatches.get(salaId);
                    int userId = client.getUserData().get("id").getAsInt();

                    if (match.iniciado) {
                        // Partida activa - esperamos indefinidamente
                        System.out.println("🔌 " + client.getUserData().get("username").getAsString() + " desconectado de partida ACTIVA");
                        JsonObject disconnData = new JsonObject();
                        disconnData.addProperty("tiempo", "indefinido");
                        disconnData.addProperty("mensaje", "Rival desconectado. Esperando...");
                        client.to(salaId).emit("rival_desconectado", disconnData);
                    } else {
                        // Negociación - timer 90 segundos
                        System.out.println("🔌 " + client.getUserData().get("username").getAsString() + " se fue en negociación. Timer 90s.");

                        JsonObject timerData = new JsonObject();
                        timerData.addProperty("tiempo", 90);
                        client.to(salaId).emit("rival_desconectado", timerData);

                        ScheduledFuture<?> timer = scheduler.schedule(() -> {
                            if (activeMatches.containsKey(salaId) && !activeMatches.get(salaId).iniciado) {
                                String uname = client.getUserData().get("username").getAsString();
                                db.update("UPDATE users SET salidas_chat = salidas_chat + 1, salidas_desconexion = salidas_desconexion + 1 WHERE id = ?", userId);
                                logClash("⚠️ ABANDONO Negociación: " + uname + " no volvió (90s)");

                                JsonObject cancelData = new JsonObject();
                                cancelData.addProperty("motivo", uname + " abandonó por desconexión");
                                io.to(salaId).emit("match_cancelado", cancelData);

                                for (SocketIOClient p : match.players) {
                                    if (p.getUserData() != null) {
                                        db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id = ?",
                                                p.getUserData().get("id").getAsInt());
                                    }
                                    p.leave(salaId);
                                    p.setCurrentRoom(null);
                                }
                                activeMatches.remove(salaId);
                            }
                        }, 90, TimeUnit.SECONDS);
                        match.disconnectTimers.put(userId, timer);
                    }
                }
            });
        });
    }

    // --- Métodos auxiliares ---

    private void intentarMatcheo(SocketIOClient meSocket, int myId, JsonObject myData) {
        for (Map.Entry<Integer, BusquedaActiva> entry : busquedasActivas.entrySet()) {
            if (entry.getKey() != myId) {
                BusquedaActiva rival = entry.getValue();
                SocketIOClient rivalSocket = rival.socket;
                boolean rivalConectado = rivalSocket != null && rivalSocket.isConnected();

                String salaId = "sala_" + System.currentTimeMillis();
                meSocket.setCurrentRoom(salaId);
                meSocket.join(salaId);
                if (rivalConectado) { rivalSocket.setCurrentRoom(salaId); rivalSocket.join(salaId); }

                ActiveMatch match = new ActiveMatch();
                match.players.add(meSocket);
                match.players.add(rivalSocket);
                activeMatches.put(salaId, match);

                JsonObject rivalData = db.queryOne("SELECT * FROM users WHERE id = ?", rival.oderId);
                double maxAp = Math.min(myData.get("saldo").getAsDouble(),
                        rivalData != null ? rivalData.get("saldo").getAsDouble() : 0);

                db.update("UPDATE users SET estado = 'partida_encontrada', sala_actual = ? WHERE id IN (?, ?)",
                        salaId, myId, rival.oderId);
                logClash("⚔️ MATCH: " + myData.get("username").getAsString() + " vs " + rival.username);

                JsonObject matchData = new JsonObject();
                matchData.addProperty("salaId", salaId);
                matchData.add("p1", myData);
                matchData.add("p2", rivalData);
                matchData.addProperty("maxApuesta", maxAp);

                meSocket.emit("partida_encontrada", matchData);
                if (rivalConectado) rivalSocket.emit("partida_encontrada", matchData);

                // Limpiar búsquedas
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
        final int MAX_POLLS = 120;
        match.pollFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!activeMatches.containsKey(salaId)) { match.pollFuture.cancel(false); return; }
                match.pollCount++;
                System.out.println("🔍 Buscando resultado #" + match.dbId + " (intento " + match.pollCount + "/" + MAX_POLLS + ")");

                if (match.playerTag1.isEmpty() || match.playerTag2.isEmpty()) return;

                JsonObject battleResult = clashApi.findMatchingBattle(match.playerTag1, match.playerTag2);

                if (battleResult != null) {
                    match.pollFuture.cancel(false);
                    String winnerTag = battleResult.get("winnerTag").getAsString();
                    logClash("🎯 RESULTADO #" + match.dbId + ": " + battleResult.get("teamCrowns").getAsInt() + "-" + battleResult.get("opponentCrowns").getAsInt());

                    int idGanador = 0;
                    SocketIOClient winnerSocket = null;
                    for (SocketIOClient p : match.players) {
                        if (p.getUserData() == null) continue;
                        JsonObject pTag = db.queryOne("SELECT player_tag FROM users WHERE id = ?", p.getUserData().get("id").getAsInt());
                        if (pTag != null && pTag.get("player_tag").getAsString().replace("#", "").toUpperCase().equals(winnerTag)) {
                            idGanador = p.getUserData().get("id").getAsInt();
                            winnerSocket = p;
                            break;
                        }
                    }

                    if (idGanador == 0) {
                        // Disputa
                        db.update("UPDATE matches SET estado = 'disputa' WHERE id = ?", match.dbId);
                        logClash("🚨 DISPUTA #" + match.dbId);
                        JsonObject disputaData = new JsonObject();
                        disputaData.addProperty("mensaje", "Resultado no claro, disputa creada.");
                        io.to(salaId).emit("disputa_creada", disputaData);
                        liberarJugadores(salaId, match);
                        return;
                    }

                    // Procesar ganador
                    double pozo = match.apuesta * 2;
                    double comision = pozo * 0.20;
                    double premio = pozo - comision;
                    double util = comision / 2;

                    db.update("UPDATE users SET saldo = saldo + ?, total_ganado = total_ganado + ? WHERE id = ?", premio, premio, idGanador);
                    db.update("UPDATE users SET ganancia_generada = ganancia_generada + ? WHERE id IN (?, ?)", util, ids.get(0), ids.get(1));
                    db.update("UPDATE users SET total_victorias = total_victorias + 1, victorias_normales = victorias_normales + 1, total_partidas = total_partidas + 1, victorias_dia = victorias_dia + 1, victorias_semana = victorias_semana + 1, victorias_mes = victorias_mes + 1, victorias_ano = victorias_ano + 1 WHERE id = ?", idGanador);
                    int idPerdedor = (idGanador == ids.get(0)) ? ids.get(1) : ids.get(0);
                    db.update("UPDATE users SET total_derrotas = total_derrotas + 1, derrotas_normales = derrotas_normales + 1, total_partidas = total_partidas + 1 WHERE id = ?", idPerdedor);
                    db.update("INSERT INTO admin_wallet (monto, razon, detalle) VALUES (?, 'comision_match', ?)", comision, "Match #" + match.dbId);

                    // Acumular tickets
                    for (SocketIOClient p : match.players) {
                        if (p.getUserData() == null) continue;
                        int ticketsGanados = RutasSorteos.acumularTickets(db, p.getUserData().get("id").getAsInt(), match.apuesta);
                        if (ticketsGanados > 0) {
                            JsonObject ticketData = new JsonObject();
                            ticketData.addProperty("cantidad", ticketsGanados);
                            p.emit("tickets_ganados", ticketData);
                        }
                    }

                    // Actualizar saldo del ganador
                    if (winnerSocket != null) {
                        JsonObject saldoRes = db.queryOne("SELECT saldo FROM users WHERE id = ?", idGanador);
                        double nuevoSaldo = saldoRes != null ? saldoRes.get("saldo").getAsDouble() : 0;
                        winnerSocket.emit("actualizar_saldo", new JsonPrimitive(nuevoSaldo));
                    }

                    String winnerName = winnerSocket != null ? winnerSocket.getUserData().get("username").getAsString() : "Ganador";
                    db.update("UPDATE matches SET estado = 'finalizada', ganador = ? WHERE id = ?", winnerName, match.dbId);
                    logClash("🏆 GANADOR API #" + match.dbId + ": " + winnerName);

                    // Notificar resultado
                    for (SocketIOClient p : match.players) {
                        if (p.getUserData() == null) continue;
                        boolean esGanador = p.getUserData().get("id").getAsInt() == idGanador;
                        JsonObject resultData = new JsonObject();
                        resultData.addProperty("ganador", winnerName);
                        resultData.addProperty("premio", premio);
                        resultData.addProperty("esGanador", esGanador);
                        resultData.addProperty("mensaje", esGanador ? "🏆 ¡GANASTE! Recibiste $" + (int) premio : "💀 Perdiste. " + winnerName + " ganó la partida.");
                        p.emit("resultado_api", resultData);
                    }
                    liberarJugadores(salaId, match);

                } else if (match.pollCount >= MAX_POLLS) {
                    match.pollFuture.cancel(false);
                    db.update("UPDATE matches SET estado = 'disputa' WHERE id = ?", match.dbId);
                    logClash("⏰ TIMEOUT #" + match.dbId + " - Disputa creada automáticamente");
                    JsonObject timeoutData = new JsonObject();
                    timeoutData.addProperty("mensaje", "No se encontró el resultado en 10 minutos. Disputa creada.");
                    io.to(salaId).emit("disputa_timeout", timeoutData);
                    liberarJugadores(salaId, match);
                }
            } catch (Exception e) { System.err.println("Error en polling: " + e.getMessage()); }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void liberarJugadores(String salaId, ActiveMatch match) {
        for (SocketIOClient p : match.players) {
            if (p.getUserData() != null) {
                db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id = ?",
                        p.getUserData().get("id").getAsInt());
            }
            p.emit("flujo_completado");
        }
        activeMatches.remove(salaId);
    }

    private void handleCancelMatch(SocketIOClient socket, String motivo) {
        String salaId = socket.getCurrentRoom();
        if (salaId == null || !activeMatches.containsKey(salaId)) return;
        ActiveMatch match = activeMatches.get(salaId);
        if (match.iniciado) return;

        if (socket.getUserData() != null) {
            int uid = socket.getUserData().get("id").getAsInt();
            db.update("UPDATE users SET salidas_chat = salidas_chat + 1 WHERE id = ?", uid);
            if ("Oprimió X".equals(motivo)) db.update("UPDATE users SET salidas_x = salidas_x + 1 WHERE id = ?", uid);
            else if ("Salió del chat".equals(motivo)) db.update("UPDATE users SET salidas_canal = salidas_canal + 1 WHERE id = ?", uid);
        }

        String usersStr = match.players.stream()
                .map(p -> p.getUserData() != null ? p.getUserData().get("username").getAsString() : "???")
                .reduce((a, b) -> a + " vs " + b).orElse("???");
        logClash("⚠️ Cancelada (" + usersStr + "): " + motivo);

        JsonObject cancelData = new JsonObject();
        cancelData.addProperty("motivo", motivo);
        io.to(salaId).emit("match_cancelado", cancelData);

        for (SocketIOClient p : match.players) {
            p.leave(salaId);
            p.setCurrentRoom(null);
            if (p.getUserData() != null) {
                db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE id = ?",
                        p.getUserData().get("id").getAsInt());
            }
        }
        activeMatches.remove(salaId);
    }

    private void logClash(String texto) {
        System.out.println(texto);
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
        double apuesta = 0;
        boolean iniciado = false;
        int dbId = 0;
        String playerTag1 = "", playerTag2 = "";
        ConcurrentHashMap<Integer, JsonObject> votosInicio = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
        int pollCount = 0;
        ScheduledFuture<?> pollFuture;
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
