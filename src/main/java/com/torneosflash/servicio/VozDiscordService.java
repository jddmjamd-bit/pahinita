package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.DiscordApiCliente.DiscordException;
import com.torneosflash.servicio.DiscordApiCliente.UsuarioDiscord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Chat de voz con Discord (roadmap 84). Es OPCIONAL: solo lo usa quien vincula su cuenta de Discord; quien no la
 * vincula juega igual, sin voz.
 *
 * <p><b>Cómo funciona</b></p>
 * <ol>
 *   <li><b>Vincular:</b> el usuario pulsa «Vincular Discord», autoriza en Discord (OAuth2, scopes {@code identify} y
 *       {@code guilds.join}) y volvemos a {@code /api/voz/discord/callback}. Guardamos SOLO su id y su nombre de Discord
 *       (nunca el token) y lo metemos al servidor de la plataforma. Una cuenta de Discord solo puede estar vinculada a un
 *       usuario.</li>
 *   <li><b>Canal por sala y partida:</b> al crearse la sala privada ({@code match.room_created}) o al empezar la partida
 *       ({@code match.started}) se crea un canal de voz privado (2 personas, invisible para el resto del servidor) y se
 *       da acceso a los jugadores que vincularon Discord. El mismo canal sirve durante el chat privado de negociación y
 *       durante la partida. Si nadie lo vinculó al inicio no se crea nada; si alguien lo vincula o lo pide después,
 *       {@link #miCanal} lo crea en ese momento.</li>
 *   <li><b>Cerrar:</b> al cancelarse la sala ({@code match.room_cancelled}) o terminar la partida
 *       ({@code match.finished}) se borra el canal. {@link #limpiarVencidos} es la red de seguridad (disputas, canales
 *       que no se pudieron borrar, partidas muy largas, reinicios del servidor).</li>
 * </ol>
 *
 * <p>El estado de los canales vive en la BD ({@code voice_channels}), no en memoria (A2). Ningún dato de Discord de un
 * jugador se le muestra al rival: el canal solo usa un identificador neutro ({@code sala-<sufijo>} o
 * {@code partida-<id>}).</p>
 */
public class VozDiscordService {
    private static final Logger logger = LoggerFactory.getLogger(VozDiscordService.class);

    /** Cuánto dura el enlace de vinculación (el usuario tiene este tiempo para autorizar en Discord). */
    private static final long VIGENCIA_ESTADO_SEGUNDOS = 600;
    private static final SecureRandom AZAR = new SecureRandom();

    private final GenericDAO db;
    private final DiscordApiCliente discord;
    private final AppConfig config;
    private final EventBus eventos;

    /** Evita crear dos canales para la misma partida si los dos jugadores lo piden a la vez. */
    private final Object candadoCreacion = new Object();

    public VozDiscordService(GenericDAO db, DiscordApiCliente discord, AppConfig config) {
        this(db, discord, config, null);
    }

    public VozDiscordService(GenericDAO db, DiscordApiCliente discord, AppConfig config, EventBus eventos) {
        this.db = db;
        this.discord = discord;
        this.config = config;
        this.eventos = eventos;
    }

    /** ¿Está configurado el chat de voz (variables DISCORD_*)? Si no, todo lo demás queda inactivo. */
    public boolean disponible() {
        return config.hasDiscord();
    }

    // ═══════════════ Vincular la cuenta de Discord ═══════════════

    /** Estado del chat de voz para este usuario: si está disponible y si ya vinculó su Discord. */
    public JsonObject estado(int userId) {
        exigirSesion(userId);
        JsonObject r = new JsonObject();
        r.addProperty("disponible", disponible());
        if (!disponible()) return r;

        JsonObject vinculo = db.queryOne("SELECT discord_username FROM discord_links WHERE user_id = ?", userId);
        r.addProperty("vinculado", vinculo != null);
        if (vinculo != null) r.addProperty("discordUsername", texto(vinculo, "discord_username"));
        if (!config.getDiscordInviteUrl().isEmpty()) r.addProperty("servidorUrl", config.getDiscordInviteUrl());
        return r;
    }

    /** Dirección de Discord a la que hay que mandar al usuario para que autorice. */
    public JsonObject iniciarVinculacion(int userId) {
        exigirSesion(userId);
        exigirDisponible();
        notificarAperturaDiscordEnSala(userId, null);
        JsonObject r = new JsonObject();
        r.addProperty("url", discord.urlAutorizacion(firmarEstado(userId)));
        return r;
    }

    /**
     * Termina la vinculación cuando Discord devuelve al usuario a nuestro servidor.
     *
     * @param codigo        código de autorización de Discord
     * @param estado        el {@code state} que firmamos en {@link #iniciarVinculacion} (dice de qué usuario es)
     * @param sesionUserId  usuario de la sesión JWT si la hay (0 si no); si existe debe coincidir con el del estado
     * @return {@code ok}, {@code discord_en_uso}, {@code estado_invalido}, {@code no_disponible} o {@code error}
     *         (se le pasa tal cual al frontend como {@code ?discord=...})
     */
    public String completarVinculacion(String codigo, String estado, int sesionUserId) {
        if (!disponible()) return "no_disponible";
        int userId = leerEstado(estado);
        if (userId <= 0 || codigo == null || codigo.isBlank() || codigo.length() > 512) return "estado_invalido";
        if (sesionUserId > 0 && sesionUserId != userId) return "estado_invalido";

        try {
            String tokenUsuario = discord.canjearCodigo(codigo);   // el token solo vive en esta variable
            UsuarioDiscord cuenta = discord.obtenerUsuario(tokenUsuario);

            // Una cuenta de Discord = un usuario (evita multicuentas). UNIQUE(discord_id) lo garantiza aunque dos
            // peticiones lleguen a la vez: el INSERT del segundo no escribe ninguna fila.
            JsonObject enUso = db.queryOne("SELECT user_id FROM discord_links WHERE discord_id = ?", cuenta.id());
            if (enUso != null && enUso.get("user_id").getAsInt() != userId) return "discord_en_uso";

            int filas = db.update(
                    "INSERT INTO discord_links (user_id, discord_id, discord_username) VALUES (?, ?, ?) "
                            + "ON CONFLICT (user_id) DO UPDATE SET discord_id = EXCLUDED.discord_id, "
                            + "discord_username = EXCLUDED.discord_username, fecha_vinculo = CURRENT_TIMESTAMP",
                    userId, cuenta.id(), cuenta.username());
            if (filas == 0) return "discord_en_uso";

            // Mejor esfuerzo: si no se pudo meter al servidor, la cuenta queda vinculada y se le pedirá unirse a mano
            try {
                discord.agregarAlServidor(tokenUsuario, cuenta.id());
            } catch (DiscordException e) {
                logger.warn("Usuario {} vinculó Discord pero no se pudo agregar al servidor: {}", userId, e.getMessage());
            }
            logger.info("🎙️ Usuario {} vinculó su cuenta de Discord", userId);
            return "ok";
        } catch (DiscordException e) {
            logger.warn("No se pudo completar la vinculación de Discord del usuario {}: {}", userId, e.getMessage());
            return "error";
        }
    }

    /** Dirección del frontend a la que se devuelve al usuario después de vincular. */
    public String urlResultado(String resultado) {
        return config.getAppBaseUrl() + "/match?discord=" + resultado;
    }

    /** Quita el vínculo del usuario. Si ya está dentro de un canal de partida, ese canal se borra igual al terminar. */
    public JsonObject desvincular(int userId) {
        exigirSesion(userId);
        db.update("DELETE FROM discord_links WHERE user_id = ?", userId);
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        return r;
    }

    // ═══════════════ Canal de voz de la sala / partida ═══════════════

    private record ContextoVoz(String salaId, int matchId, int user1Id, int user2Id) {}

    /**
     * El usuario pide entrar al chat de voz de su sala privada o partida en curso. Crea el canal si todavía no existe y
     * le da acceso. Respuesta: {@code {estado, mensaje, url?, servidorUrl?}} con estado {@code listo} |
     * {@code sin_vincular} | {@code sin_partida} | {@code falta_servidor} | {@code caducado}.
     */
    public JsonObject miCanal(int userId) {
        exigirSesion(userId);
        exigirDisponible();

        JsonObject vinculo = db.queryOne("SELECT discord_id FROM discord_links WHERE user_id = ?", userId);
        if (vinculo == null) {
            return respuesta("sin_vincular", "Vincula tu cuenta de Discord para usar el chat de voz.");
        }
        ContextoVoz contexto = contextoVozActivoDe(userId);
        if (contexto == null) {
            return respuesta("sin_partida", "El chat de voz se abre al entrar a la sala privada con tu rival.");
        }
        notificarAperturaDiscordEnSala(userId, contexto);

        try {
            String canalId = obtenerOCrearCanal(contexto.salaId(), contexto.matchId(),
                    contexto.user1Id(), contexto.user2Id());
            if (!darAcceso(canalId, texto(vinculo, "discord_id"))) {
                JsonObject r = respuesta("falta_servidor",
                        "Primero únete al servidor de Discord de la plataforma y vuelve a intentarlo.");
                if (!config.getDiscordInviteUrl().isEmpty()) r.addProperty("servidorUrl", config.getDiscordInviteUrl());
                return r;
            }
            JsonObject r = respuesta("listo", "Tu canal de voz está listo.");
            r.addProperty("url", "https://discord.com/channels/" + config.getDiscordGuildId() + "/" + canalId);
            return r;
        } catch (DiscordException e) {
            if (e.getStatus() == 409) {
                return respuesta("caducado", "El chat de voz de esta sala ya se cerró.");
            }
            logger.error("No se pudo preparar el canal de voz para el usuario {}: {}", userId, e.getMessage());
            throw ServicioException.noDisponible("No pudimos preparar el canal de voz. Inténtalo de nuevo en unos segundos.");
        }
    }

    /**
     * Lo llama el listener de {@code match.room_created} (desde el pool de IO) al entrar al chat privado de negociación.
     * Si algún jugador vinculó Discord crea el canal y le da acceso; si ninguno lo hizo no crea nada.
     */
    public void prepararCanalSala(String salaId, List<Integer> participantes) {
        if (!disponible() || salaId == null || salaId.isBlank() || participantes == null || participantes.size() != 2) {
            return;
        }
        int jugador1 = participantes.get(0);
        int jugador2 = participantes.get(1);
        ArrayList<JsonObject> vinculos = db.query(
                "SELECT user_id, discord_id FROM discord_links WHERE user_id IN (?, ?)", jugador1, jugador2);
        if (vinculos.isEmpty()) return;

        try {
            String canalId = obtenerOCrearCanal(salaId, 0, jugador1, jugador2);
            concederAccesos(canalId, vinculos, "Sala " + salaId);
            logger.info("🎙️ Canal de voz listo para la sala {}", salaId);
        } catch (DiscordException e) {
            if (e.getStatus() != 409) {
                logger.error("Sala {}: no se pudo crear el canal de voz: {}", salaId, e.getMessage());
            }
        }
    }

    /**
     * Lo llama el listener de {@code match.started} (desde el pool de IO). Reutiliza el canal creado durante la sala
     * privada (asociándole {@code match_id}) o lo crea si aún no existía. No lanza: un fallo de Discord solo se registra.
     */
    public void prepararCanal(int matchId, List<Integer> participantes) {
        if (!disponible() || matchId <= 0 || participantes == null || participantes.size() != 2) return;
        int jugador1 = participantes.get(0);
        int jugador2 = participantes.get(1);
        JsonObject matchFila = db.queryOne("SELECT sala_id, estado FROM matches WHERE id = ?", matchId);
        if (matchFila != null && !"en_curso".equals(texto(matchFila, "estado"))) return;
        String salaId = texto(matchFila, "sala_id");

        ArrayList<JsonObject> vinculos = db.query(
                "SELECT user_id, discord_id FROM discord_links WHERE user_id IN (?, ?)", jugador1, jugador2);
        if (vinculos.isEmpty()) {
            if (!salaId.isEmpty()) {
                db.update("UPDATE voice_channels SET match_id = ? WHERE sala_id = ? AND match_id IS NULL", matchId, salaId);
            }
            return;
        }

        try {
            String canalId = obtenerOCrearCanal(salaId, matchId, jugador1, jugador2);
            concederAccesos(canalId, vinculos, "Partida #" + matchId);
            logger.info("🎙️ Canal de voz listo para la partida #{}", matchId);
        } catch (DiscordException e) {
            if (e.getStatus() != 409) {
                logger.error("Partida #{}: no se pudo crear el canal de voz: {}", matchId, e.getMessage());
            }
        }
    }

    /**
     * Borra el canal de voz de una sala privada cancelada antes de comenzar la partida.
     */
    public void cerrarCanalDeSala(String salaId) {
        if (!disponible() || salaId == null || salaId.isBlank()) return;
        synchronized (candadoCreacion) {
            JsonObject fila = db.queryOne(
                    "SELECT vc.id, vc.discord_channel_id FROM voice_channels vc "
                            + "WHERE vc.sala_id = ? AND vc.estado = 'activo' "
                            + "AND NOT EXISTS (SELECT 1 FROM matches m "
                            + "WHERE (m.id = vc.match_id OR m.sala_id = vc.sala_id) AND m.estado = 'en_curso') "
                            + "LIMIT 1",
                    salaId.trim());
            if (fila == null) return;
            cerrarFilaCanal(fila.get("id").getAsInt(), texto(fila, "discord_channel_id"), "Sala " + salaId);
        }
    }

    /**
     * Borra el canal de voz de la partida. No lanza: si Discord falla el canal sigue «activo» en la BD y
     * {@link #limpiarVencidos} lo reintenta.
     */
    public void cerrarCanal(int matchId) {
        if (!disponible() || matchId <= 0) return;
        synchronized (candadoCreacion) {
            JsonObject fila = db.queryOne(
                    "SELECT vc.id, vc.discord_channel_id FROM voice_channels vc "
                            + "WHERE vc.estado = 'activo' AND (vc.match_id = ? "
                            + "OR (vc.sala_id IS NOT NULL AND vc.sala_id = (SELECT m.sala_id FROM matches m WHERE m.id = ?))) "
                            + "ORDER BY vc.id DESC LIMIT 1",
                    matchId, matchId);
            if (fila == null) return;
            cerrarFilaCanal(fila.get("id").getAsInt(), texto(fila, "discord_channel_id"), "Partida #" + matchId);
        }
    }

    /**
     * Red de seguridad, se ejecuta cada minuto: borra los canales activos cuya sala ya no existe y cuya partida ya no
     * está en curso (terminó, pasó a disputa o fue cancelada) o que superaron {@code DISCORD_VOICE_MAX_MINUTOS}.
     */
    public void limpiarVencidos() {
        if (!disponible()) return;
        ArrayList<JsonObject> filas = db.query(
                "SELECT vc.id, vc.discord_channel_id "
                        + "FROM voice_channels vc "
                        + "LEFT JOIN active_matches am ON vc.sala_id IS NOT NULL AND am.sala_id = vc.sala_id "
                        + "LEFT JOIN matches m ON m.id = vc.match_id "
                        + "OR (vc.sala_id IS NOT NULL AND m.sala_id = vc.sala_id) "
                        + "WHERE vc.estado = 'activo' AND ("
                        + "vc.fecha_creacion < NOW() - make_interval(mins => ?) "
                        + "OR (am.id IS NULL AND (m.id IS NULL OR m.estado <> 'en_curso'))) "
                        + "ORDER BY vc.id LIMIT 20",
                config.getDiscordVozMaxMinutos());
        for (JsonObject fila : filas) {
            synchronized (candadoCreacion) {
                cerrarFilaCanal(fila.get("id").getAsInt(), texto(fila, "discord_channel_id"),
                        "Canal #" + fila.get("id").getAsInt());
            }
        }
    }

    // ═══════════════ Internos ═══════════════

    private void notificarAperturaDiscordEnSala(int userId, ContextoVoz contextoConocido) {
        if (eventos == null || db == null) return;
        ContextoVoz ctx = contextoConocido != null ? contextoConocido : contextoVozActivoDe(userId);
        if (ctx != null && ctx.matchId() <= 0 && !ctx.salaId().isEmpty()) {
            eventos.emit(Eventos.VOICE_DISCORD_OPENED, new Eventos.AperturaDiscordSala(userId, ctx.salaId()));
        }
    }

    /** Sala privada activa o partida en curso del usuario, o null si no está en ninguna. */
    private ContextoVoz contextoVozActivoDe(int userId) {
        JsonObject sala = db.queryOne(
                "SELECT am.sala_id, am.player1_id AS user1_id, am.player2_id AS user2_id, am.iniciado, am.match_db_id "
                        + "FROM active_matches am "
                        + "LEFT JOIN matches m ON m.id = am.match_db_id "
                        + "WHERE (am.player1_id = ? OR am.player2_id = ?) "
                        + "AND (am.iniciado = FALSE OR am.match_db_id <= 0 OR m.estado = 'en_curso') "
                        + "ORDER BY am.id DESC LIMIT 1",
                userId, userId);
        if (sala != null) {
            boolean iniciado = sala.has("iniciado") && !sala.get("iniciado").isJsonNull() && sala.get("iniciado").getAsBoolean();
            int matchId = iniciado && sala.has("match_db_id") && !sala.get("match_db_id").isJsonNull()
                    ? sala.get("match_db_id").getAsInt() : 0;
            return new ContextoVoz(texto(sala, "sala_id"), matchId,
                    sala.get("user1_id").getAsInt(), sala.get("user2_id").getAsInt());
        }

        JsonObject partida = partidaEnCursoDe(userId);
        if (partida != null) {
            return new ContextoVoz(texto(partida, "sala_id"), partida.get("match_id").getAsInt(),
                    partida.get("user1_id").getAsInt(), partida.get("user2_id").getAsInt());
        }
        return null;
    }

    /** La partida en curso del usuario con los ids de los dos jugadores, o null si no tiene (matches guarda nombres). */
    private JsonObject partidaEnCursoDe(int userId) {
        return db.queryOne(
                "SELECT m.id AS match_id, m.sala_id, u1.id AS user1_id, u2.id AS user2_id "
                        + "FROM matches m "
                        + "JOIN users u1 ON u1.username = m.jugador1 "
                        + "JOIN users u2 ON u2.username = m.jugador2 "
                        + "WHERE m.estado = 'en_curso' AND (u1.id = ? OR u2.id = ?) "
                        + "ORDER BY m.id DESC LIMIT 1",
                userId, userId);
    }

    private JsonObject buscarFilaCanal(String salaLimpia, int matchId) {
        if (!salaLimpia.isEmpty() && matchId > 0) {
            return db.queryOne(
                    "SELECT id, match_id, discord_channel_id, estado FROM voice_channels "
                            + "WHERE sala_id = ? OR match_id = ? ORDER BY id DESC LIMIT 1",
                    salaLimpia, matchId);
        }
        if (!salaLimpia.isEmpty()) {
            return db.queryOne(
                    "SELECT id, match_id, discord_channel_id, estado FROM voice_channels WHERE sala_id = ? LIMIT 1",
                    salaLimpia);
        }
        if (matchId > 0) {
            return db.queryOne(
                    "SELECT id, match_id, discord_channel_id, estado FROM voice_channels WHERE match_id = ? LIMIT 1",
                    matchId);
        }
        return null;
    }

    static String nombreCanal(String salaId, int matchId) {
        String salaLimpia = salaId == null ? "" : salaId.trim();
        if (!salaLimpia.isEmpty()) {
            String alfanum = salaLimpia.replaceAll("[^A-Za-z0-9]", "");
            if (alfanum.toLowerCase().startsWith("sala")) {
                alfanum = alfanum.substring(4);
            }
            String sufijo = alfanum.length() > 6 ? alfanum.substring(alfanum.length() - 6) : alfanum;
            return "sala-" + (sufijo.isEmpty() ? "privada" : sufijo.toLowerCase());
        }
        return "partida-" + matchId;
    }

    /**
     * Devuelve el canal activo de la sala/partida o lo crea. Lanza {@link DiscordException} con estado 409 si el canal
     * ya se había cerrado o la sala dejó de estar activa (no se vuelve a abrir uno nuevo).
     */
    private String obtenerOCrearCanal(String salaId, int matchId, int jugador1, int jugador2) throws DiscordException {
        String salaLimpia = salaId == null ? "" : salaId.trim();
        synchronized (candadoCreacion) {
            JsonObject existente = buscarFilaCanal(salaLimpia, matchId);
            if (existente != null) {
                if (!"activo".equals(texto(existente, "estado"))) {
                    throw new DiscordException(409, "El canal de voz de esta sala ya se cerró");
                }
                if (matchId > 0 && (!existente.has("match_id") || existente.get("match_id").isJsonNull())) {
                    db.update("UPDATE voice_channels SET match_id = ? WHERE id = ? AND match_id IS NULL",
                            matchId, existente.get("id").getAsInt());
                }
                return texto(existente, "discord_channel_id");
            }

            if (matchId <= 0 && !salaLimpia.isEmpty()
                    && db.queryOne("SELECT id FROM active_matches WHERE sala_id = ?", salaLimpia) == null) {
                throw new DiscordException(409, "La sala ya no está activa");
            }

            String canalId = discord.crearCanalDeVoz(nombreCanal(salaLimpia, matchId));
            int filas;
            if (!salaLimpia.isEmpty() && matchId > 0) {
                filas = db.update(
                        "INSERT INTO voice_channels (sala_id, match_id, user1_id, user2_id, discord_channel_id) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        salaLimpia, matchId, jugador1, jugador2, canalId);
            } else if (!salaLimpia.isEmpty()) {
                filas = db.update(
                        "INSERT INTO voice_channels (sala_id, user1_id, user2_id, discord_channel_id) "
                                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        salaLimpia, jugador1, jugador2, canalId);
            } else {
                filas = db.update(
                        "INSERT INTO voice_channels (match_id, user1_id, user2_id, discord_channel_id) "
                                + "VALUES (?, ?, ?, ?) ON CONFLICT (match_id) DO NOTHING",
                        matchId, jugador1, jugador2, canalId);
            }
            if (filas == 0) {
                // No quedó guardado: se borra el canal recién creado para no dejarlo huérfano en Discord
                try {
                    discord.borrarCanal(canalId);
                } catch (DiscordException e) {
                    logger.warn("Canal huérfano {} no se pudo borrar: {}", canalId, e.getMessage());
                }
                throw new DiscordException(500, "No se pudo guardar el canal de voz");
            }
            return canalId;
        }
    }

    private void concederAccesos(String canalId, List<JsonObject> vinculos, String etiquetaLog) {
        for (JsonObject vinculo : vinculos) {
            try {
                darAcceso(canalId, texto(vinculo, "discord_id"));
            } catch (DiscordException e) {
                logger.warn("{}: no se pudo dar acceso al canal al usuario {}: {}",
                        etiquetaLog, vinculo.get("user_id").getAsInt(), e.getMessage());
            }
        }
    }

    private void cerrarFilaCanal(int filaId, String discordChannelId, String etiquetaLog) {
        if (discordChannelId.isEmpty()) return;
        try {
            discord.borrarCanal(discordChannelId);
            db.update("UPDATE voice_channels SET estado = 'cerrado', fecha_cierre = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND estado = 'activo'", filaId);
            logger.info("🎙️ Canal de voz cerrado ({})", etiquetaLog);
        } catch (DiscordException e) {
            logger.warn("{}: no se pudo borrar el canal de voz (se reintenta en la próxima limpieza): {}",
                    etiquetaLog, e.getMessage());
        }
    }

    /** Da acceso a la cuenta. false si todavía no está en el servidor de Discord (Discord no deja darle permisos). */
    private boolean darAcceso(String canalId, String discordId) throws DiscordException {
        if (!discord.esMiembro(discordId)) return false;
        discord.concederAcceso(canalId, discordId);
        return true;
    }

    private static JsonObject respuesta(String estado, String mensaje) {
        JsonObject r = new JsonObject();
        r.addProperty("estado", estado);
        r.addProperty("mensaje", mensaje);
        return r;
    }

    private static String texto(JsonObject o, String campo) {
        return (o != null && o.has(campo) && !o.get(campo).isJsonNull()) ? o.get(campo).getAsString() : "";
    }

    private void exigirDisponible() {
        if (!disponible()) {
            throw ServicioException.noDisponible("El chat de voz no está disponible en este momento.");
        }
    }

    private static void exigirSesion(int userId) {
        if (userId <= 0) throw ServicioException.noAutenticado("Debes iniciar sesión.");
    }

    // ═══════════════ Estado firmado del flujo OAuth2 ═══════════════
    // El parámetro `state` lleva el id del usuario, un vencimiento y un valor al azar, firmados con HMAC-SHA256. Así el
    // callback sabe de quién es la vinculación sin guardar nada en memoria (A2) y nadie puede fabricar uno.

    private String firmarEstado(int userId) {
        byte[] azar = new byte[12];
        AZAR.nextBytes(azar);
        String carga = userId + ":" + (Instant.now().getEpochSecond() + VIGENCIA_ESTADO_SEGUNDOS) + ":" + b64(azar);
        String cargaB64 = b64(carga.getBytes(StandardCharsets.UTF_8));
        return cargaB64 + "." + b64(hmac(cargaB64));
    }

    /** Id del usuario del estado si la firma es válida y no venció; 0 en cualquier otro caso. */
    private int leerEstado(String estado) {
        try {
            if (estado == null || estado.length() > 300) return 0;
            int punto = estado.indexOf('.');
            if (punto <= 0 || punto == estado.length() - 1) return 0;
            String cargaB64 = estado.substring(0, punto);
            byte[] firmaRecibida = estado.substring(punto + 1).getBytes(StandardCharsets.UTF_8);
            byte[] firmaEsperada = b64(hmac(cargaB64)).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(firmaEsperada, firmaRecibida)) return 0;

            String carga = new String(Base64.getUrlDecoder().decode(cargaB64), StandardCharsets.UTF_8);
            String[] partes = carga.split(":");
            if (partes.length != 3) return 0;
            if (Long.parseLong(partes[1]) < Instant.now().getEpochSecond()) return 0;
            return Integer.parseInt(partes[0]);
        } catch (IllegalArgumentException e) {
            return 0; // base64 o número mal formado
        }
    }

    private byte[] hmac(String datos) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // Se deriva una clave distinta de la del JWT para que un estado nunca sirva como sesión ni al revés
            mac.init(new SecretKeySpec((config.getJwtSecret() + "|discord-oauth-state").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(datos.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el estado de Discord", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
