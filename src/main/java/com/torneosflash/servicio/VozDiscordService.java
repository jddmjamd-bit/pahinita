package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
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
 *   <li><b>Canal por partida:</b> al empezar la partida ({@code match.started}) se crea un canal de voz privado
 *       ({@code partida-<id>}, 2 personas, invisible para el resto del servidor) y se da acceso a los jugadores que
 *       vincularon Discord. Si nadie lo vinculó no se crea nada; si alguien lo vincula o lo pide después,
 *       {@link #miCanal} lo crea en ese momento.</li>
 *   <li><b>Cerrar:</b> al terminar la partida ({@code match.finished}) se borra el canal. {@link #limpiarVencidos} es la
 *       red de seguridad (disputas, canales que no se pudieron borrar, partidas muy largas, reinicios del servidor).</li>
 * </ol>
 *
 * <p>El estado de los canales vive en la BD ({@code voice_channels}), no en memoria (A2). Ningún dato de Discord de un
 * jugador se le muestra al rival: el canal solo se llama {@code partida-<id>}.</p>
 */
public class VozDiscordService {
    private static final Logger logger = LoggerFactory.getLogger(VozDiscordService.class);

    /** Cuánto dura el enlace de vinculación (el usuario tiene este tiempo para autorizar en Discord). */
    private static final long VIGENCIA_ESTADO_SEGUNDOS = 600;
    private static final SecureRandom AZAR = new SecureRandom();

    private final GenericDAO db;
    private final DiscordApiCliente discord;
    private final AppConfig config;

    /** Evita crear dos canales para la misma partida si los dos jugadores lo piden a la vez. */
    private final Object candadoCreacion = new Object();

    public VozDiscordService(GenericDAO db, DiscordApiCliente discord, AppConfig config) {
        this.db = db;
        this.discord = discord;
        this.config = config;
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

    // ═══════════════ Canal de voz de la partida ═══════════════

    /**
     * El usuario pide entrar al chat de voz de su partida en curso. Crea el canal si todavía no existe y le da acceso.
     * Respuesta: {@code {estado, mensaje, url?, servidorUrl?}} con estado {@code listo} | {@code sin_vincular} |
     * {@code sin_partida} | {@code falta_servidor} | {@code caducado}.
     */
    public JsonObject miCanal(int userId) {
        exigirSesion(userId);
        exigirDisponible();

        JsonObject vinculo = db.queryOne("SELECT discord_id FROM discord_links WHERE user_id = ?", userId);
        if (vinculo == null) {
            return respuesta("sin_vincular", "Vincula tu cuenta de Discord para usar el chat de voz.");
        }
        JsonObject partida = partidaEnCursoDe(userId);
        if (partida == null) {
            return respuesta("sin_partida", "El chat de voz se abre cuando empieza tu partida.");
        }

        try {
            String canalId = obtenerOCrearCanal(partida.get("match_id").getAsInt(),
                    partida.get("user1_id").getAsInt(), partida.get("user2_id").getAsInt());
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
                return respuesta("caducado", "El chat de voz de esta partida ya se cerró.");
            }
            logger.error("No se pudo preparar el canal de voz para el usuario {}: {}", userId, e.getMessage());
            throw ServicioException.noDisponible("No pudimos preparar el canal de voz. Inténtalo de nuevo en unos segundos.");
        }
    }

    /**
     * Lo llama el listener de {@code match.started} (desde el pool de IO). Si algún jugador vinculó Discord crea el
     * canal y le da acceso; si ninguno lo hizo no crea nada. No lanza: un fallo de Discord solo se registra.
     */
    public void prepararCanal(int matchId, List<Integer> participantes) {
        if (!disponible() || participantes == null || participantes.size() != 2) return;
        int jugador1 = participantes.get(0);
        int jugador2 = participantes.get(1);
        ArrayList<JsonObject> vinculos = db.query(
                "SELECT user_id, discord_id FROM discord_links WHERE user_id IN (?, ?)", jugador1, jugador2);
        if (vinculos.isEmpty()) return;

        try {
            String canalId = obtenerOCrearCanal(matchId, jugador1, jugador2);
            for (JsonObject vinculo : vinculos) {
                try {
                    darAcceso(canalId, texto(vinculo, "discord_id"));
                } catch (DiscordException e) {
                    logger.warn("Partida #{}: no se pudo dar acceso al canal al usuario {}: {}",
                            matchId, vinculo.get("user_id").getAsInt(), e.getMessage());
                }
            }
            logger.info("🎙️ Canal de voz listo para la partida #{}", matchId);
        } catch (DiscordException e) {
            logger.error("Partida #{}: no se pudo crear el canal de voz: {}", matchId, e.getMessage());
        }
    }

    /**
     * Borra el canal de voz de la partida. No lanza: si Discord falla el canal sigue «activo» en la BD y
     * {@link #limpiarVencidos} lo reintenta.
     */
    public void cerrarCanal(int matchId) {
        JsonObject fila = db.queryOne(
                "SELECT id, discord_channel_id FROM voice_channels WHERE match_id = ? AND estado = 'activo'", matchId);
        if (fila == null) return;
        try {
            discord.borrarCanal(texto(fila, "discord_channel_id"));
            db.update("UPDATE voice_channels SET estado = 'cerrado', fecha_cierre = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND estado = 'activo'", fila.get("id").getAsInt());
            logger.info("🎙️ Canal de voz de la partida #{} cerrado", matchId);
        } catch (DiscordException e) {
            logger.warn("Partida #{}: no se pudo borrar el canal de voz (se reintenta en la próxima limpieza): {}",
                    matchId, e.getMessage());
        }
    }

    /**
     * Red de seguridad, se ejecuta cada minuto: borra los canales activos cuya partida ya no está en curso (terminó,
     * pasó a disputa o fue cancelada) o que superaron {@code DISCORD_VOICE_MAX_MINUTOS}. Procesa pocos por pasada
     * para no saturar a Discord.
     */
    public void limpiarVencidos() {
        if (!disponible()) return;
        ArrayList<JsonObject> filas = db.query(
                "SELECT vc.match_id FROM voice_channels vc LEFT JOIN matches m ON m.id = vc.match_id "
                        + "WHERE vc.estado = 'activo' AND (m.id IS NULL OR m.estado <> 'en_curso' "
                        + "OR vc.fecha_creacion < NOW() - make_interval(mins => ?)) "
                        + "ORDER BY vc.id LIMIT 20",
                config.getDiscordVozMaxMinutos());
        for (JsonObject fila : filas) {
            cerrarCanal(fila.get("match_id").getAsInt());
        }
    }

    // ═══════════════ Internos ═══════════════

    /** La partida en curso del usuario con los ids de los dos jugadores, o null si no tiene (matches guarda nombres). */
    private JsonObject partidaEnCursoDe(int userId) {
        return db.queryOne(
                "SELECT m.id AS match_id, u1.id AS user1_id, u2.id AS user2_id "
                        + "FROM matches m "
                        + "JOIN users u1 ON u1.username = m.jugador1 "
                        + "JOIN users u2 ON u2.username = m.jugador2 "
                        + "WHERE m.estado = 'en_curso' AND (u1.id = ? OR u2.id = ?) "
                        + "ORDER BY m.id DESC LIMIT 1",
                userId, userId);
    }

    /**
     * Devuelve el canal activo de la partida o lo crea. Lanza {@link DiscordException} con estado 409 si el canal
     * de esta partida ya se había cerrado (no se vuelve a abrir uno nuevo).
     */
    private String obtenerOCrearCanal(int matchId, int jugador1, int jugador2) throws DiscordException {
        synchronized (candadoCreacion) {
            JsonObject existente = db.queryOne("SELECT discord_channel_id, estado FROM voice_channels WHERE match_id = ?", matchId);
            if (existente != null) {
                if ("activo".equals(texto(existente, "estado"))) return texto(existente, "discord_channel_id");
                throw new DiscordException(409, "El canal de la partida #" + matchId + " ya se cerró");
            }

            String canalId = discord.crearCanalDeVoz("partida-" + matchId);
            int filas = db.update(
                    "INSERT INTO voice_channels (match_id, user1_id, user2_id, discord_channel_id) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (match_id) DO NOTHING",
                    matchId, jugador1, jugador2, canalId);
            if (filas == 0) {
                // No quedó guardado: se borra el canal recién creado para no dejarlo huérfano en Discord
                try {
                    discord.borrarCanal(canalId);
                } catch (DiscordException e) {
                    logger.warn("Canal huérfano {} no se pudo borrar: {}", canalId, e.getMessage());
                }
                throw new DiscordException(500, "No se pudo guardar el canal de voz de la partida #" + matchId);
            }
            return canalId;
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
