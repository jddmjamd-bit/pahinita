package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.torneosflash.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cliente mínimo de la API REST de Discord para el chat de voz (roadmap 84).
 *
 * <p>Solo usa HTTPS: el "bot" no necesita estar conectado al gateway de Discord, le basta su token para crear y
 * borrar canales. Por eso no hay un proceso aparte y todo vive en este servidor.</p>
 *
 * <p>Dos tipos de llamada:</p>
 * <ul>
 *   <li><b>OAuth2 del usuario</b> ({@link #canjearCodigo}, {@link #obtenerUsuario}, {@link #agregarAlServidor}): sirven para
 *       saber QUÉ cuenta de Discord es de quién y meterla al servidor. El token del usuario solo existe en memoria durante
 *       esas tres llamadas; nunca se guarda ni se escribe en el log.</li>
 *   <li><b>Bot</b> (el resto): crear, dar acceso y borrar el canal de voz. Usan {@code DISCORD_BOT_TOKEN}.</li>
 * </ul>
 *
 * <p>Todas las llamadas son bloqueantes (hasta {@link #TIMEOUT}), así que NO deben llamarse desde un temporizador ni
 * desde un listener del bus: se hacen desde el pool de IO o desde un hilo de petición HTTP. Cada fallo se lanza como
 * {@link DiscordException} con el código HTTP; quien llama decide si reintenta (la limpieza periódica reintenta sola).</p>
 */
public class DiscordApiCliente {
    private static final Logger logger = LoggerFactory.getLogger(DiscordApiCliente.class);

    private static final String API = "https://discord.com/api/v10";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // Permisos de Discord (bits). Se mandan como texto decimal, que es lo que pide la API.
    private static final long PERM_VER_CANAL = 1L << 10;
    private static final long PERM_CONECTAR = 1L << 20;
    private static final long PERM_HABLAR = 1L << 21;
    private static final long PERM_DETECCION_VOZ = 1L << 25;
    private static final long PERM_JUGADOR = PERM_VER_CANAL | PERM_CONECTAR | PERM_HABLAR | PERM_DETECCION_VOZ;

    /** Tipo de canal "GUILD_VOICE". */
    private static final int CANAL_DE_VOZ = 2;

    /** Cuenta de Discord devuelta por OAuth2. */
    public record UsuarioDiscord(String id, String username) {}

    /** Error de una llamada a Discord. {@code status} es el código HTTP (0 si ni siquiera hubo respuesta). */
    public static class DiscordException extends Exception {
        private final int status;

        public DiscordException(int status, String mensaje) {
            super(mensaje);
            this.status = status;
        }

        public DiscordException(String mensaje, Throwable causa) {
            super(mensaje, causa);
            this.status = 0;
        }

        public int getStatus() {
            return status;
        }
    }

    private final AppConfig config;
    private final HttpClient http;

    public DiscordApiCliente(AppConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (config.hasDiscord()) {
            logger.info("✅ Chat de voz con Discord configurado (servidor {})", config.getDiscordGuildId());
        } else {
            logger.info("⚠️ Chat de voz desactivado (faltan DISCORD_CLIENT_ID/DISCORD_CLIENT_SECRET/DISCORD_BOT_TOKEN/DISCORD_GUILD_ID)");
        }
    }

    // ═══════════════ OAuth2 del usuario ═══════════════

    /** Dirección de Discord donde el usuario autoriza la vinculación. {@code state} es el valor firmado por el servicio. */
    public String urlAutorizacion(String state) {
        return "https://discord.com/oauth2/authorize"
                + "?client_id=" + codificar(config.getDiscordClientId())
                + "&response_type=code"
                + "&redirect_uri=" + codificar(config.getDiscordRedirectUri())
                + "&scope=" + codificar("identify guilds.join")
                + "&state=" + codificar(state)
                + "&prompt=consent";
    }

    /** Cambia el código que devuelve Discord por un token de acceso del usuario (se usa y se descarta). */
    public String canjearCodigo(String codigo) throws DiscordException {
        String formulario = "client_id=" + codificar(config.getDiscordClientId())
                + "&client_secret=" + codificar(config.getDiscordClientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + codificar(codigo)
                + "&redirect_uri=" + codificar(config.getDiscordRedirectUri());
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(API + "/oauth2/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", agente())
                .POST(HttpRequest.BodyPublishers.ofString(formulario))
                .build();
        JsonObject json = comoObjeto(enviar(peticion, "canjear el código de Discord", false));
        String token = texto(json, "access_token");
        if (token.isEmpty()) throw new DiscordException(502, "Discord no devolvió el token de acceso");
        return token;
    }

    /** Quién es el dueño del token (scope {@code identify}). */
    public UsuarioDiscord obtenerUsuario(String tokenUsuario) throws DiscordException {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(API + "/users/@me"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + tokenUsuario)
                .header("User-Agent", agente())
                .GET()
                .build();
        JsonObject json = comoObjeto(enviar(peticion, "leer la cuenta de Discord", true));
        String id = texto(json, "id");
        if (id.isEmpty()) throw new DiscordException(502, "Discord no devolvió el id de la cuenta");
        return new UsuarioDiscord(id, texto(json, "username"));
    }

    /**
     * Mete a la cuenta en el servidor de la plataforma (scope {@code guilds.join}). Si ya estaba dentro no hace nada.
     * El bot necesita el permiso «Crear invitación» para poder hacerlo.
     */
    public void agregarAlServidor(String tokenUsuario, String discordId) throws DiscordException {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("access_token", tokenUsuario);
        enviar(bot("/guilds/" + config.getDiscordGuildId() + "/members/" + discordId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build(), "meter al usuario en el servidor de Discord", true); // 201 = lo agregó, 204 = ya estaba
    }

    // ═══════════════ Bot: canales de voz ═══════════════

    /** ¿La cuenta ya está dentro del servidor? Sin eso Discord no deja darle acceso a un canal. */
    public boolean esMiembro(String discordId) throws DiscordException {
        HttpResponse<String> respuesta = ejecutar(bot("/guilds/" + config.getDiscordGuildId() + "/members/" + discordId).GET().build(),
                "consultar si el usuario está en el servidor");
        if (respuesta.statusCode() == 404) return false;
        verificar(respuesta, "consultar si el usuario está en el servidor", true);
        return true;
    }

    /**
     * Crea un canal de voz PRIVADO (nadie lo ve ni entra salvo a quien después se le dé acceso con
     * {@link #concederAcceso}) con capacidad para 2 personas. Devuelve el id del canal.
     */
    public String crearCanalDeVoz(String nombre) throws DiscordException {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("name", nombre);
        cuerpo.addProperty("type", CANAL_DE_VOZ);
        cuerpo.addProperty("user_limit", 2);
        if (!config.getDiscordCategoriaVozId().isEmpty()) {
            cuerpo.addProperty("parent_id", config.getDiscordCategoriaVozId());
        }
        JsonArray permisos = new JsonArray();
        // @everyone (su id es el del servidor): ni ver ni conectarse
        permisos.add(sobrescritura(config.getDiscordGuildId(), 0, 0L, PERM_VER_CANAL | PERM_CONECTAR));
        // El propio bot (su id de usuario es el de la aplicación) conserva la vista del canal para poder borrarlo
        permisos.add(sobrescritura(config.getDiscordClientId(), 1, PERM_VER_CANAL, 0L));
        cuerpo.add("permission_overwrites", permisos);

        HttpRequest peticion = bot("/guilds/" + config.getDiscordGuildId() + "/channels")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build();
        String id = texto(comoObjeto(enviar(peticion, "crear el canal de voz", true)), "id");
        if (id.isEmpty()) throw new DiscordException(502, "Discord no devolvió el id del canal");
        return id;
    }

    /** Deja que esta cuenta vea el canal, entre y hable. La cuenta debe estar en el servidor. */
    public void concederAcceso(String canalId, String discordId) throws DiscordException {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("type", 1); // 1 = miembro
        cuerpo.addProperty("allow", String.valueOf(PERM_JUGADOR));
        cuerpo.addProperty("deny", "0");
        enviar(bot("/channels/" + canalId + "/permissions/" + discordId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build(), "dar acceso al canal de voz", true);
    }

    /** Borra el canal. Si ya no existe (404) se considera borrado. */
    public void borrarCanal(String canalId) throws DiscordException {
        HttpResponse<String> respuesta = ejecutar(bot("/channels/" + canalId).DELETE().build(), "borrar el canal de voz");
        if (respuesta.statusCode() == 404) return;
        verificar(respuesta, "borrar el canal de voz", true);
    }

    // ═══════════════ Internos ═══════════════

    private HttpRequest.Builder bot(String ruta) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API + ruta))
                .timeout(TIMEOUT)
                .header("Authorization", "Bot " + config.getDiscordBotToken())
                .header("User-Agent", agente());
    }

    private JsonObject sobrescritura(String id, int tipo, long permite, long niega) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", tipo); // 0 = rol, 1 = miembro
        o.addProperty("allow", String.valueOf(permite));
        o.addProperty("deny", String.valueOf(niega));
        return o;
    }

    /** Discord exige un User-Agent con este formato. */
    private String agente() {
        return "DiscordBot (" + config.getAppBaseUrl() + ", 1.0)";
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }

    private static String texto(JsonObject o, String campo) {
        return (o != null && o.has(campo) && o.get(campo).isJsonPrimitive()) ? o.get(campo).getAsString() : "";
    }

    private HttpResponse<String> ejecutar(HttpRequest peticion, String accion) throws DiscordException {
        try {
            return http.send(peticion, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscordException("Interrumpido al " + accion, e);
        } catch (Exception e) {
            throw new DiscordException("No se pudo conectar con Discord para " + accion + ": " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Envía la petición y exige un 2xx. {@code registrarCuerpo} dice si el cuerpo del error se puede escribir en el log
     * (en el canje del código se omite por si el error repite el código o el secreto).
     */
    private String enviar(HttpRequest peticion, String accion, boolean registrarCuerpo) throws DiscordException {
        HttpResponse<String> respuesta = ejecutar(peticion, accion);
        verificar(respuesta, accion, registrarCuerpo);
        return respuesta.body();
    }

    private void verificar(HttpResponse<String> respuesta, String accion, boolean registrarCuerpo) throws DiscordException {
        int estado = respuesta.statusCode();
        if (estado >= 200 && estado < 300) return;
        String detalle = registrarCuerpo ? recortar(respuesta.body(), 300) : "(cuerpo omitido)";
        logger.warn("Discord respondió {} al {}: {}", estado, accion, detalle);
        throw new DiscordException(estado, "Discord respondió " + estado + " al " + accion);
    }

    private static JsonObject comoObjeto(String cuerpo) throws DiscordException {
        try {
            JsonElement e = JsonParser.parseString(cuerpo == null ? "" : cuerpo);
            if (e.isJsonObject()) return e.getAsJsonObject();
        } catch (Exception ignorada) {
            // cae al error de abajo
        }
        throw new DiscordException(502, "Respuesta de Discord ilegible");
    }

    private static String recortar(String texto, int max) {
        if (texto == null) return "";
        String t = texto.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
