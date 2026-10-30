package com.torneosflash.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuración de la aplicación.
 * Lee las variables de entorno necesarias para conectar a PostgreSQL,
 * APIs externas y servicios de correo.
 * 
 * CONCEPTO POO: Encapsulamiento - todos los atributos son private,
 * acceso solo por getters.
 */
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private final Map<String, String> environment;

    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private final int port;
    private final String databaseUrl;
    private final String dbSslMode;
    private final String clashApiToken;
    private final String firebaseServiceAccount;
    private final String brevoApiKey;
    private final String brevoSenderEmail;
    private final String wompiPublicKey;
    private final String wompiPrivateKey;
    private final String wompiIntegritySecret;
    private final String wompiEventsSecret;
    private final String wompiUserPrincipalId;
    private final String wompiXApiKey;
    private final String dbAdminSecret;
    private final long dbAdminMaxBackupBytes;

    // --- Servidor / red (A7 / A1) ---
    private final String appBaseUrl;
    private final String bindHost;
    private final int wsMaxMessageBytes;
    private final int socketIoPort;
    private final Set<String> corsAllowedOrigins;

    // --- Temporizadores y límites de partidas/chat (A7). Los usa SocketHandler /
    // TemporizadorSalaService. ---
    private final int busquedaTimeoutMinutos;
    private final int negociacionGraciaDesconexionSegundos;
    private final int negociacionDesconexionSegundos;
    private final int negociacionTimerAccionSegundos;
    private final int pollingIntervaloSegundos;
    private final int pollingMaxIntentos;
    private final int chatHistorialLimite;

    // --- Sesión JWT ---
    private final String jwtSecret;
    private final long jwtExpirationSeconds;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final int passwordResetTokenMinutes;
    private final int passwordResetCooldownSeconds;

    // --- Límites de montos (COP, pesos enteros). Los valida ValidadorMonto en el
    // servidor. ---
    private final BigDecimal montoMinDeposito;
    private final BigDecimal montoMaxDeposito;
    private final BigDecimal montoMinRetiro;
    private final BigDecimal montoMaxRetiro;
    private final BigDecimal montoMinTorneo;
    private final BigDecimal montoMaxTorneo;
    private final BigDecimal montoMinSorteo;
    private final BigDecimal montoMaxSorteo;

    // --- Rate limiting (S8). Todos por IP y por minuto. Ver RateLimitMiddleware.
    // ---
    private final boolean rateLimitEnabled;
    private final int rateLimitGeneralPorMin;
    private final int rateLimitFinancieroPorMin;
    private final int rateLimitAuthPorMin;
    private final int rateLimitMediaPorMin;
    private final int rateLimitProxyHops;

    // --- Hilos y pools (A8). Ver Ejecutores. ---
    private final int httpMaxThreads;
    private final int httpMinThreads;
    private final int httpIdleTimeoutMs;
    private final int socketIoPollingMaxConcurrent;
    private final int ioPoolThreads;
    private final int ioPoolQueue;
    private final int pushPoolThreads;
    private final int pushPoolQueue;
    private final int pushMaxPorUsuarioPorMin;
    private final int cpuPoolThreads;
    private final int cpuPoolQueue;
    private final int timerPoolThreads;
    private final int dbPoolMax;

    // --- Chatbot IA (roadmap parte 20). Ver ChatbotIaCliente y ChatbotService. ---
    private final String chatbotProveedor;
    private final String chatbotApiKey;
    private final String chatbotModelo;
    private final String chatbotAdminEmail;
    private final int chatbotMaxMensajesPorMin;
    private final int chatbotMaxMensajesDia;
    private final int chatbotMaxConcurrentes;
    private final int chatbotMaxCorreosHora;
    private final int chatbotTimeoutSegundos;
    private final int chatbotContextoMensajes;
    private final int chatbotMaxTokens;
    private final int rateLimitChatbotPorMin;

    // --- Soporte: "Hablar con el admin" (roadmap 24.5). Ver SoporteService y
    // SoporteListener. ---
    private final String soporteAdminEmail;
    private final int soporteMaxAbiertos;
    private final int soporteMaxTicketsDia;
    private final int soporteMaxCorreosHora;

    // --- Evidencias de pago (roadmap 7). Ver EvidenciaPagoService. ---
    private final int pagosMaxImagenChars;
    private final int pagosHistorialLimite;

    // --- Chat de voz con Discord (roadmap 84). Ver VozDiscordService y
    // DiscordApiCliente. ---
    private final String discordClientId;
    private final String discordClientSecret;
    private final String discordBotToken;
    private final String discordGuildId;
    private final String discordRedirectUri;
    private final String discordCategoriaVozId;
    private final String discordInviteUrl;
    private final int discordVozMaxMinutos;

    // --- Constructor (CONSTRUCTOR con parámetros desde env) ---
    public AppConfig() {
        this(System.getenv());
    }

    /**
     * Constructor injectable para pruebas: usa un mapa privado sin modificar el
     * entorno del proceso.
     */
    public AppConfig(Map<String, String> environment) {
        this.environment = Collections.unmodifiableMap(Map.copyOf(environment));
        this.port = getEnvEntero("PORT", 5000, 1, 65535);
        this.databaseUrl = getEnv("DATABASE_URL", "");
        // S10: modo SSL de la conexión a la BD (require | verify-ca | verify-full).
        // Opcional; default require.
        this.dbSslMode = getEnv("DB_SSLMODE", "require");
        this.clashApiToken = getEnv("CLASH_ROYALE_API_TOKEN", "");
        this.firebaseServiceAccount = getEnv("FIREBASE_SERVICE_ACCOUNT", "");
        this.brevoApiKey = getEnv("BREVO_API_KEY", "");
        this.brevoSenderEmail = normalizarCorreo(getEnv("BREVO_SENDER_EMAIL", ""), "BREVO_SENDER_EMAIL");
        this.wompiPublicKey = getEnv("WOMPI_PUBLIC_KEY", "");
        this.wompiPrivateKey = getEnv("WOMPI_PRIVATE_KEY", "");
        this.wompiIntegritySecret = getEnv("WOMPI_INTEGRITY_SECRET", "");
        // S7: secreto de eventos para verificar la firma SHA-256 del webhook de Wompi
        this.wompiEventsSecret = getEnv("WOMPI_EVENTS_SECRET", "").trim();
        this.wompiUserPrincipalId = getEnv("WOMPI_USER_PRINCIPAL_ID", "");
        this.wompiXApiKey = getEnv("WOMPI_X_API_KEY", "");
        // A7: sin valor por defecto. Antes caía a una clave escrita en el código
        // ("torneos2024"); RutasDbAdmin
        // ya deshabilita el panel si la clave está vacía, así que el comportamiento no
        // cambia, solo desaparece el literal.
        this.dbAdminSecret = getEnv("DB_ADMIN_SECRET", "");
        this.dbAdminMaxBackupBytes = getEnvLong(
                "DB_ADMIN_MAX_BACKUP_BYTES", 1_073_741_824L, 1_048_576L, 10_737_418_240L);

        // A7: URL pública del sitio (icono y enlace de las push). Antes estaba escrita
        // en Main.
        this.appBaseUrl = normalizarUrlBase(getEnv("APP_BASE_URL", DEFAULT_APP_BASE_URL));
        // Interfaz en la que escucha el servidor. 0.0.0.0 = todas (necesario en
        // Docker/Render).
        String hostEnv = getEnv("BIND_HOST", "0.0.0.0").trim();
        this.bindHost = hostEnv.isEmpty() ? "0.0.0.0" : hostEnv;
        // A2: Tamaño máximo de un mensaje WebSocket (ChatSanitizer permite hasta 7 MB
        // en base64). Default 8 MB.
        this.wsMaxMessageBytes = getEnvEntero("WS_MAX_MESSAGE_BYTES", 8_000_000, 1024, 50_000_000);
        // A1: puerto interno de netty-socketio (Javalin redirige /socket.io/ desde PORT
        // hacia este puerto).
        this.socketIoPort = getEnvEntero("SOCKET_IO_PORT", 9092, 0, 65535);
        // A4: lista blanca de orígenes permitidos para CORS con credenciales (web en
        // Render + Capacitor + dev local)
        this.corsAllowedOrigins = construirOrigenesCors(this.appBaseUrl, getEnv("CORS_ALLOWED_ORIGINS", ""));

        this.busquedaTimeoutMinutos = getEnvEntero("BUSQUEDA_TIMEOUT_MINUTOS", 10, 1, 1440);
        this.negociacionGraciaDesconexionSegundos = getEnvEntero("NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS", 30, 1, 600);
        this.negociacionDesconexionSegundos = getEnvEntero("NEGOCIACION_DESCONEXION_SEGUNDOS", 90, 1, 3600);
        this.negociacionTimerAccionSegundos = getEnvEntero("NEGOCIACION_TIMER_ACCION_SEGUNDOS", 150, 2, 3600);
        // Cada cuántos segundos se consulta la API de Clash Royale para saber el
        // resultado, y cuántas veces como máximo.
        // Tiempo máximo de espera = intervalo x intentos (default 5 s x 120 = 10 min) y
        // luego se crea la disputa.
        this.pollingIntervaloSegundos = getEnvEntero("POLLING_INTERVALO_SEGUNDOS", 5, 2, 60);
        this.pollingMaxIntentos = getEnvEntero("POLLING_MAX_INTENTOS", 120, 1, 10_000);
        this.chatHistorialLimite = getEnvEntero("CHAT_HISTORIAL_LIMITE", 50, 1, 500);

        this.jwtSecret = resolverJwtSecret(getEnv("JWT_SECRET", ""));
        this.jwtExpirationSeconds = getEnvLong("JWT_EXPIRATION_DAYS", 30L, 1L, 3650L) * 24L * 3600L;
        this.passwordResetTokenMinutes = getEnvEntero("PASSWORD_RESET_TOKEN_MINUTES", 15, 5, 60);
        this.passwordResetCooldownSeconds = getEnvEntero("PASSWORD_RESET_COOLDOWN_SECONDS", 60, 30, 600);
        this.cookieSameSite = normalizarSameSite(getEnv("COOKIE_SAMESITE", "Lax"));
        // SameSite=None exige Secure; en cualquier otro caso se respeta COOKIE_SECURE
        // (default true)
        this.cookieSecure = "None".equals(cookieSameSite)
                || getEnvBoolean("COOKIE_SECURE", true);

        BigDecimal[] deposito = getEnvRangoMonto("MONTO_MIN_DEPOSITO", "1000", "MONTO_MAX_DEPOSITO", "5000000");
        this.montoMinDeposito = deposito[0];
        this.montoMaxDeposito = deposito[1];
        BigDecimal[] retiro = getEnvRangoMonto("MONTO_MIN_RETIRO", "10000", "MONTO_MAX_RETIRO", "5000000");
        this.montoMinRetiro = retiro[0];
        this.montoMaxRetiro = retiro[1];
        BigDecimal[] torneo = getEnvRangoMonto("MONTO_MIN_TORNEO", "1000", "MONTO_MAX_TORNEO", "10000");
        this.montoMinTorneo = torneo[0];
        this.montoMaxTorneo = torneo[1];
        BigDecimal[] sorteo = getEnvRangoMonto("MONTO_MIN_SORTEO", "1000", "MONTO_MAX_SORTEO", "100000000");
        this.montoMinSorteo = sorteo[0];
        this.montoMaxSorteo = sorteo[1];

        this.rateLimitEnabled = getEnvBoolean("RATE_LIMIT_ENABLED", true);
        this.rateLimitGeneralPorMin = getEnvEntero("RATE_LIMIT_GENERAL_PER_MIN", 60, 1);
        this.rateLimitFinancieroPorMin = getEnvEntero("RATE_LIMIT_FINANCIAL_PER_MIN", 10, 1);
        this.rateLimitAuthPorMin = getEnvEntero("RATE_LIMIT_AUTH_PER_MIN", 10, 1);
        this.rateLimitMediaPorMin = getEnvEntero("RATE_LIMIT_MEDIA_PER_MIN", 30, 1);
        // Solo confiar en X-Forwarded-For después de verificar la cadena efectiva del
        // despliegue.
        // 0 ignora la cabecera; valores válidos van de 1 a 10 proxies.
        this.rateLimitProxyHops = getEnvEntero("RATE_LIMIT_PROXY_HOPS", 0, 0, 10);

        // Hilos y pools (A8). Defaults pensados para Render free (poca CPU y 512 MB de
        // RAM).
        int httpMax = getEnvEntero("HTTP_MAX_THREADS", 64, 16);
        this.httpMaxThreads = httpMax;
        this.httpMinThreads = Math.min(getEnvEntero("HTTP_MIN_THREADS", 8, 1), httpMax);
        this.httpIdleTimeoutMs = getEnvEntero("HTTP_IDLE_TIMEOUT_MS", 60_000, 1_000);
        // El polling mantiene GET largos; reserva al menos la mitad del pool HTTP para
        // otras rutas.
        int maxPolling = Math.max(2, httpMax / 2);
        this.socketIoPollingMaxConcurrent = getEnvEntero(
                "SOCKET_IO_POLLING_MAX_CONCURRENT", maxPolling, 2, maxPolling);
        this.ioPoolThreads = getEnvEntero("IO_POOL_THREADS", 16, 1);
        this.ioPoolQueue = getEnvEntero("IO_POOL_QUEUE", 500, 1);
        this.pushPoolThreads = getEnvEntero("PUSH_POOL_THREADS", 4, 1, 32);
        this.pushPoolQueue = getEnvEntero("PUSH_POOL_QUEUE", 200, 1, 10_000);
        this.pushMaxPorUsuarioPorMin = getEnvEntero("PUSH_MAX_PER_USER_PER_MIN", 10, 1, 1_000);
        this.cpuPoolThreads = getEnvEntero("CPU_POOL_THREADS", Math.max(1, Runtime.getRuntime().availableProcessors()),
                1);
        this.cpuPoolQueue = getEnvEntero("CPU_POOL_QUEUE", 100, 1);
        this.timerPoolThreads = getEnvEntero("TIMER_POOL_THREADS", 4, 1);
        this.dbPoolMax = getEnvEntero("DB_POOL_MAX_SIZE", 20, 2);

        // Chatbot IA (roadmap parte 20). Sin CHATBOT_API_KEY el asistente queda
        // desactivado y el resto de la app no cambia.
        String proveedor = getEnv("CHATBOT_PROVIDER", "gemini").trim().toLowerCase(Locale.ROOT);
        if (!proveedor.equals("gemini") && !proveedor.equals("openai")) {
            logger.error("⚠️ CHATBOT_PROVIDER='{}' no es válido (gemini | openai). Se usa gemini.", proveedor);
            proveedor = "gemini";
        }
        this.chatbotProveedor = proveedor;
        this.chatbotApiKey = getEnv("CHATBOT_API_KEY", "").trim();
        // Los nombres de modelo cambian y se retiran seguido: para Gemini el default es
        // el alias oficial
        // "gemini-flash-latest" (apunta siempre al Flash vigente); para OpenAI no se
        // asume ninguno y hay que definir CHATBOT_MODEL.
        String modelo = getEnv("CHATBOT_MODEL", "").trim();
        if (modelo.isEmpty() && proveedor.equals("gemini"))
            modelo = "gemini-flash-latest";
        if (!modelo.isEmpty() && !MODELO_IA_VALIDO.matcher(modelo).matches()) {
            logger.error("⚠️ CHATBOT_MODEL='{}' no es un nombre de modelo válido. El chatbot queda desactivado.",
                    modelo);
            modelo = "";
        }
        this.chatbotModelo = modelo;
        this.chatbotAdminEmail = normalizarCorreo(getEnv("CHATBOT_ADMIN_EMAIL", ""));
        this.chatbotMaxMensajesPorMin = getEnvEntero("CHATBOT_MAX_MENSAJES_POR_MIN", 6, 1, 120);
        this.chatbotMaxMensajesDia = getEnvEntero("CHATBOT_MAX_MENSAJES_DIA", 100, 1, 10_000);
        this.chatbotMaxConcurrentes = getEnvEntero("CHATBOT_MAX_CONCURRENTES", 8, 1, 100);
        this.chatbotMaxCorreosHora = getEnvEntero("CHATBOT_MAX_CORREOS_HORA", 10, 1, 1000);
        this.chatbotTimeoutSegundos = getEnvEntero("CHATBOT_TIMEOUT_SEGUNDOS", 25, 5, 120);
        this.chatbotContextoMensajes = getEnvEntero("CHATBOT_CONTEXTO_MENSAJES", 8, 0, 30);
        this.chatbotMaxTokens = getEnvEntero("CHATBOT_MAX_TOKENS", 1024, 64, 8192);
        this.rateLimitChatbotPorMin = getEnvEntero("RATE_LIMIT_CHATBOT_PER_MIN", 10, 1);

        // Soporte (roadmap 24.5): prioridad de contacto de soporte, chatbot y remitente
        // de Brevo.
        String soporteCorreo = normalizarCorreo(getEnv("SOPORTE_ADMIN_EMAIL", ""), "SOPORTE_ADMIN_EMAIL");
        this.soporteAdminEmail = !soporteCorreo.isEmpty() ? soporteCorreo
                : (!this.chatbotAdminEmail.isEmpty() ? this.chatbotAdminEmail : this.brevoSenderEmail);
        this.soporteMaxAbiertos = getEnvEntero("SOPORTE_MAX_ABIERTOS", 5, 1, 100);
        this.soporteMaxTicketsDia = getEnvEntero("SOPORTE_MAX_TICKETS_DIA", 10, 1, 1000);
        this.soporteMaxCorreosHora = getEnvEntero("SOPORTE_MAX_CORREOS_HORA", 10, 1, 1000);

        // Evidencias de pago (roadmap 7). La imagen viaja en el JSON de un POST (data
        // URI base64): el tope de caracteres
        // no debe pasar de ~900.000 porque Javalin rechaza cuerpos de más de 1 MB por
        // defecto. El frontend ya la comprime.
        this.pagosMaxImagenChars = getEnvEntero("PAGOS_MAX_IMAGEN_CHARS", 800_000, 50_000, 900_000);
        // Cuántas evidencias recientes se cargan al abrir el canal «Pagos» (cada una
        // pesa hasta pagosMaxImagenChars).
        this.pagosHistorialLimite = getEnvEntero("PAGOS_HISTORIAL_LIMITE", 20, 1, 100);

        // Chat de voz (roadmap 84). Sin las cuatro primeras variables el chat de voz
        // queda desactivado y el resto de la
        // app no cambia. Los ids de Discord (servidor, categoria, aplicacion) son
        // numeros de 15 a 25 cifras.
        this.discordClientId = getEnvSnowflake("DISCORD_CLIENT_ID");
        this.discordClientSecret = getEnv("DISCORD_CLIENT_SECRET", "").trim();
        this.discordBotToken = getEnv("DISCORD_BOT_TOKEN", "").trim();
        this.discordGuildId = getEnvSnowflake("DISCORD_GUILD_ID");
        // Debe estar registrada tal cual en Discord Developer Portal > OAuth2 >
        // Redirects
        this.discordRedirectUri = getEnv("DISCORD_REDIRECT_URI", this.appBaseUrl + "/api/voz/discord/callback").trim();
        // Opcional: categoria del servidor donde se crean los canales de voz de las
        // partidas
        this.discordCategoriaVozId = getEnvSnowflake("DISCORD_VOICE_CATEGORY_ID");
        // Opcional: enlace de invitacion al servidor (se le muestra a quien aun no esta
        // dentro)
        this.discordInviteUrl = normalizarUrlHttps(getEnv("DISCORD_INVITE_URL", ""), "DISCORD_INVITE_URL");
        // Tope de vida de un canal de voz aunque la partida siga abierta (el canal se
        // borra solo al terminar la partida)
        this.discordVozMaxMinutos = getEnvEntero("DISCORD_VOICE_MAX_MINUTOS", 90, 5, 1440);
    }

    /**
     * Si no hay JWT_SECRET (o es muy corto) se genera uno aleatorio en memoria.
     * Las sesiones se invalidarán en cada reinicio, así que en producción
     * SIEMPRE debe definirse JWT_SECRET (mínimo 32 caracteres).
     */
    private static String resolverJwtSecret(String fromEnv) {
        if (fromEnv.length() >= 32)
            return fromEnv;
        if (!fromEnv.isEmpty()) {
            logger.error("⚠️ JWT_SECRET tiene menos de 32 caracteres. Se ignora y se usa uno aleatorio.");
        } else {
            logger.error("⚠️ JWT_SECRET no definido. Se usa uno aleatorio: las sesiones se perderán al reiniciar.");
        }
        byte[] bytes = new byte[48];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static final String DEFAULT_APP_BASE_URL = "https://torneos-beta.onrender.com";

    /**
     * Debe ser http(s) y se quita la barra final (el código concatena
     * "/icon-192.png"). Si es inválida, usa el default.
     */
    private static String normalizarUrlBase(String valor) {
        String url = valor.trim();
        while (url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        if (url.startsWith("https://") || url.startsWith("http://"))
            return url;
        logger.error("⚠️ APP_BASE_URL='{}' no es una URL http(s) válida. Se usa {}.", valor, DEFAULT_APP_BASE_URL);
        return DEFAULT_APP_BASE_URL;
    }

    private static Set<String> construirOrigenesCors(String appBaseUrl, String extraEnv) {
        java.util.LinkedHashSet<String> origenes = new java.util.LinkedHashSet<>();
        origenes.add(appBaseUrl.toLowerCase(Locale.ROOT));
        origenes.add("https://localhost");
        origenes.add("http://localhost");
        origenes.add("capacitor://localhost");
        origenes.add("http://localhost:5173");
        origenes.add("http://localhost:5000");
        origenes.add("http://127.0.0.1:5173");
        origenes.add("http://127.0.0.1:5000");
        if (extraEnv != null && !extraEnv.isBlank()) {
            for (String parte : extraEnv.split(",")) {
                String limpio = parte.trim().toLowerCase(Locale.ROOT);
                while (limpio.endsWith("/")) {
                    limpio = limpio.substring(0, limpio.length() - 1);
                }
                if (!limpio.isEmpty()) {
                    origenes.add(limpio);
                }
            }
        }
        return java.util.Collections.unmodifiableSet(origenes);
    }

    private static String normalizarSameSite(String value) {
        switch (value.trim().toLowerCase()) {
            case "strict":
                return "Strict";
            case "none":
                return "None";
            default:
                return "Lax";
        }
    }

    /**
     * Lee un monto (pesos enteros positivos) de una variable de entorno.
     * Si no está definida o es inválida se usa el valor por defecto.
     */
    private BigDecimal getEnvMonto(String key, String defaultValue) {
        String raw = getEnv(key, defaultValue).trim();
        try {
            BigDecimal valor = new BigDecimal(raw);
            if (valor.signum() > 0 && valor.stripTrailingZeros().scale() <= 0) {
                return valor.setScale(0);
            }
        } catch (NumberFormatException ignored) {
            // cae al log de abajo
        }
        logger.error("⚠️ {}='{}' no es un monto válido (entero positivo). Se usa {}.", key, raw, defaultValue);
        return new BigDecimal(defaultValue);
    }

    /**
     * Lee un entero de una variable de entorno. Si no está definida, no es un
     * número o es menor
     * que {@code minimo}, se usa el valor por defecto.
     */
    private int getEnvEntero(String key, int defaultValue, int minimo) {
        return getEnvEntero(key, defaultValue, minimo, Integer.MAX_VALUE);
    }

    /**
     * Igual que el anterior pero además con un máximo (inclusive). Fuera de
     * [minimo, maximo] se usa el default.
     */
    private int getEnvEntero(String key, int defaultValue, int minimo, int maximo) {
        String raw = getEnv(key, String.valueOf(defaultValue)).trim();
        try {
            int valor = Integer.parseInt(raw);
            if (valor >= minimo && valor <= maximo)
                return valor;
        } catch (NumberFormatException ignored) {
            // cae al log de abajo
        }
        logger.error("⚠️ {}='{}' no es válido (entero entre {} y {}). Se usa {}.", key, raw, minimo, maximo,
                defaultValue);
        return defaultValue;
    }

    /**
     * Límite en bytes para los archivos temporales de backup y restauración del
     * panel DB.
     */
    private long getEnvLong(String key, long defaultValue, long minimo, long maximo) {
        String raw = getEnv(key, String.valueOf(defaultValue)).trim();
        try {
            long valor = Long.parseLong(raw);
            if (valor >= minimo && valor <= maximo)
                return valor;
        } catch (NumberFormatException ignored) {
            // cae al log de abajo
        }
        logger.error("⚠️ {} no es válido (valor entre {} y {}). Se usa el valor por defecto.", key, minimo, maximo);
        return defaultValue;
    }

    // Método auxiliar para leer variables de entorno con valor por defecto
    private String getEnv(String key, String defaultValue) {
        String value = environment.get(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private boolean getEnvBoolean(String key, boolean defaultValue) {
        String raw = getEnv(key, Boolean.toString(defaultValue)).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw))
            return true;
        if ("false".equals(raw))
            return false;
        logger.error("⚠️ {} debe ser true o false. Se usa el valor por defecto {}.", key, defaultValue);
        return defaultValue;
    }

    private BigDecimal[] getEnvRangoMonto(String keyMin, String defaultMin, String keyMax, String defaultMax) {
        BigDecimal minimo = getEnvMonto(keyMin, defaultMin);
        BigDecimal maximo = getEnvMonto(keyMax, defaultMax);
        if (minimo.compareTo(maximo) <= 0)
            return new BigDecimal[] { minimo, maximo };
        logger.error("⚠️ {} no puede superar {}. Se usan los dos valores por defecto.", keyMin, keyMax);
        return new BigDecimal[] { new BigDecimal(defaultMin), new BigDecimal(defaultMax) };
    }

    /**
     * Render define RENDER=true. En ese entorno se impide iniciar con secretos de
     * producción ausentes.
     * Los previews de pull request pueden tener un conjunto de variables diferente.
     */
    public void validarConfiguracionProduccion() {
        boolean render = Boolean.parseBoolean(getEnv("RENDER", "false"));
        boolean preview = Boolean.parseBoolean(getEnv("IS_PULL_REQUEST", "false"));
        if (!render || preview)
            return;

        java.util.List<String> faltantes = new ArrayList<>();
        String[] obligatorias = {
                "DATABASE_URL", "CLASH_ROYALE_API_TOKEN", "WOMPI_PUBLIC_KEY", "WOMPI_PRIVATE_KEY",
                "WOMPI_EVENTS_SECRET", "WOMPI_INTEGRITY_SECRET"
        };
        for (String key : obligatorias) {
            if (getEnv(key, "").trim().isEmpty())
                faltantes.add(key);
        }
        String jwtSecret = getEnv("JWT_SECRET", "").trim();
        if (jwtSecret.length() < 32)
            faltantes.add("JWT_SECRET (mínimo 32 caracteres)");
        if (!faltantes.isEmpty()) {
            throw new IllegalStateException("Configuración obligatoria de Render incompleta: "
                    + String.join(", ", faltantes));
        }
    }

    // --- Getters (GETTER) ---
    public int getPort() {
        return port;
    }

    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public String getDbSslMode() {
        return dbSslMode;
    }

    public String getClashApiToken() {
        return clashApiToken;
    }

    public String getFirebaseServiceAccount() {
        return firebaseServiceAccount;
    }

    public String getBrevoApiKey() {
        return brevoApiKey;
    }

    public String getBrevoSenderEmail() {
        return brevoSenderEmail;
    }

    public String getWompiPublicKey() {
        return wompiPublicKey;
    }

    public String getWompiPrivateKey() {
        return wompiPrivateKey;
    }

    public String getWompiIntegritySecret() {
        return wompiIntegritySecret;
    }

    public String getWompiEventsSecret() {
        return wompiEventsSecret;
    }

    public String getWompiUserPrincipalId() {
        return wompiUserPrincipalId;
    }

    public String getWompiXApiKey() {
        return wompiXApiKey;
    }

    public String getDbAdminSecret() {
        return dbAdminSecret;
    }

    public long getDbAdminMaxBackupBytes() {
        return dbAdminMaxBackupBytes;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public String getBindHost() {
        return bindHost;
    }

    public int getWsMaxMessageBytes() {
        return wsMaxMessageBytes;
    }

    public int getSocketIoPort() {
        return socketIoPort;
    }

    public int getBusquedaTimeoutMinutos() {
        return busquedaTimeoutMinutos;
    }

    public int getNegociacionGraciaDesconexionSegundos() {
        return negociacionGraciaDesconexionSegundos;
    }

    public int getNegociacionDesconexionSegundos() {
        return negociacionDesconexionSegundos;
    }

    public int getNegociacionTimerAccionSegundos() {
        return negociacionTimerAccionSegundos;
    }

    public int getPollingIntervaloSegundos() {
        return pollingIntervaloSegundos;
    }

    public int getPollingMaxIntentos() {
        return pollingMaxIntentos;
    }

    public int getChatHistorialLimite() {
        return chatHistorialLimite;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public long getJwtExpirationSeconds() {
        return jwtExpirationSeconds;
    }

    public int getPasswordResetTokenMinutes() {
        return passwordResetTokenMinutes;
    }

    public int getPasswordResetCooldownSeconds() {
        return passwordResetCooldownSeconds;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public BigDecimal getMontoMinDeposito() {
        return montoMinDeposito;
    }

    public BigDecimal getMontoMaxDeposito() {
        return montoMaxDeposito;
    }

    public BigDecimal getMontoMinRetiro() {
        return montoMinRetiro;
    }

    public BigDecimal getMontoMaxRetiro() {
        return montoMaxRetiro;
    }

    public BigDecimal getMontoMinTorneo() {
        return montoMinTorneo;
    }

    public BigDecimal getMontoMaxTorneo() {
        return montoMaxTorneo;
    }

    public BigDecimal getMontoMinSorteo() {
        return montoMinSorteo;
    }

    public BigDecimal getMontoMaxSorteo() {
        return montoMaxSorteo;
    }

    public Set<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    /** Comprueba si un header Origin está en la lista blanca de CORS. */
    public boolean esOrigenPermitido(String origin) {
        if (origin == null || origin.isBlank())
            return false;
        String normalizado = origin.trim();
        while (normalizado.endsWith("/")) {
            normalizado = normalizado.substring(0, normalizado.length() - 1);
        }
        return corsAllowedOrigins.contains(normalizado.toLowerCase(Locale.ROOT));
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public int getRateLimitGeneralPorMin() {
        return rateLimitGeneralPorMin;
    }

    public int getRateLimitFinancieroPorMin() {
        return rateLimitFinancieroPorMin;
    }

    public int getRateLimitAuthPorMin() {
        return rateLimitAuthPorMin;
    }

    public int getRateLimitMediaPorMin() {
        return rateLimitMediaPorMin;
    }

    public int getRateLimitProxyHops() {
        return rateLimitProxyHops;
    }

    public int getHttpMaxThreads() {
        return httpMaxThreads;
    }

    public int getHttpMinThreads() {
        return httpMinThreads;
    }

    public int getHttpIdleTimeoutMs() {
        return httpIdleTimeoutMs;
    }

    public int getSocketIoPollingMaxConcurrent() {
        return socketIoPollingMaxConcurrent;
    }

    public int getIoPoolThreads() {
        return ioPoolThreads;
    }

    public int getIoPoolQueue() {
        return ioPoolQueue;
    }

    public int getPushPoolThreads() {
        return pushPoolThreads;
    }

    public int getPushPoolQueue() {
        return pushPoolQueue;
    }

    public int getPushMaxPorUsuarioPorMin() {
        return pushMaxPorUsuarioPorMin;
    }

    public int getCpuPoolThreads() {
        return cpuPoolThreads;
    }

    public int getCpuPoolQueue() {
        return cpuPoolQueue;
    }

    public int getTimerPoolThreads() {
        return timerPoolThreads;
    }

    public int getDbPoolMax() {
        return dbPoolMax;
    }

    public String getChatbotProveedor() {
        return chatbotProveedor;
    }

    public String getChatbotApiKey() {
        return chatbotApiKey;
    }

    public String getChatbotModelo() {
        return chatbotModelo;
    }

    public String getChatbotAdminEmail() {
        return chatbotAdminEmail;
    }

    public int getChatbotMaxMensajesPorMin() {
        return chatbotMaxMensajesPorMin;
    }

    public int getChatbotMaxMensajesDia() {
        return chatbotMaxMensajesDia;
    }

    public int getChatbotMaxConcurrentes() {
        return chatbotMaxConcurrentes;
    }

    public int getChatbotMaxCorreosHora() {
        return chatbotMaxCorreosHora;
    }

    public int getChatbotTimeoutSegundos() {
        return chatbotTimeoutSegundos;
    }

    public int getChatbotContextoMensajes() {
        return chatbotContextoMensajes;
    }

    public int getChatbotMaxTokens() {
        return chatbotMaxTokens;
    }

    public int getRateLimitChatbotPorMin() {
        return rateLimitChatbotPorMin;
    }

    /** Correo al que llegan los tickets de soporte (vacío = remitente de Brevo). */
    public String getSoporteAdminEmail() {
        return soporteAdminEmail;
    }

    public int getSoporteMaxAbiertos() {
        return soporteMaxAbiertos;
    }

    public int getSoporteMaxTicketsDia() {
        return soporteMaxTicketsDia;
    }

    public int getSoporteMaxCorreosHora() {
        return soporteMaxCorreosHora;
    }

    /**
     * Máximo de caracteres de la imagen (data URI base64) de una evidencia de pago.
     */
    public int getPagosMaxImagenChars() {
        return pagosMaxImagenChars;
    }

    /** Cuántas evidencias recientes carga el canal «Pagos». */
    public int getPagosHistorialLimite() {
        return pagosHistorialLimite;
    }

    public String getDiscordClientId() {
        return discordClientId;
    }

    public String getDiscordClientSecret() {
        return discordClientSecret;
    }

    public String getDiscordBotToken() {
        return discordBotToken;
    }

    public String getDiscordGuildId() {
        return discordGuildId;
    }

    public String getDiscordRedirectUri() {
        return discordRedirectUri;
    }

    /** Categoria donde se crean los canales de voz (vacio = sin categoria). */
    public String getDiscordCategoriaVozId() {
        return discordCategoriaVozId;
    }

    /** Invitacion al servidor de Discord de la plataforma (vacio = no hay). */
    public String getDiscordInviteUrl() {
        return discordInviteUrl;
    }

    public int getDiscordVozMaxMinutos() {
        return discordVozMaxMinutos;
    }

    public boolean hasClashApi() {
        return !clashApiToken.isEmpty();
    }

    public boolean hasFirebase() {
        return !firebaseServiceAccount.isEmpty();
    }

    public boolean hasBrevo() {
        return !brevoApiKey.isEmpty() && !brevoSenderEmail.isEmpty();
    }

    /**
     * El chatbot IA necesita clave y modelo; sin alguno de los dos queda
     * desactivado.
     */
    public boolean hasChatbot() {
        return !chatbotApiKey.isEmpty() && !chatbotModelo.isEmpty();
    }

    /**
     * El chat de voz necesita la aplicacion OAuth2 (id + secreto), el bot (token) y
     * el servidor (id); sin alguno queda desactivado.
     */
    public boolean hasDiscord() {
        return !discordClientId.isEmpty() && !discordClientSecret.isEmpty()
                && !discordBotToken.isEmpty() && !discordGuildId.isEmpty();
    }

    private static final Pattern SNOWFLAKE_VALIDO = Pattern.compile("^[0-9]{15,25}$");

    /**
     * Id de Discord (solo digitos). Si no esta definido o es invalido devuelve
     * vacio.
     */
    private String getEnvSnowflake(String key) {
        String valor = getEnv(key, "").trim();
        if (valor.isEmpty())
            return "";
        if (SNOWFLAKE_VALIDO.matcher(valor).matches())
            return valor;
        logger.error("⚠️ {} no es un id de Discord válido (solo dígitos). Se ignora.", key);
        return "";
    }

    /** URL https opcional. Si no empieza por https:// se ignora (vacio). */
    private static String normalizarUrlHttps(String valor, String variable) {
        String url = valor.trim();
        if (url.isEmpty())
            return "";
        if (url.startsWith("https://") && !url.contains(" "))
            return url;
        logger.error("⚠️ {} debe empezar por https://. Se ignora.", variable);
        return "";
    }

    /**
     * Nombre de modelo permitido: letras, números, punto, guion, guion bajo y dos
     * puntos (se concatena a la URL del proveedor).
     */
    private static final Pattern MODELO_IA_VALIDO = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final Pattern CORREO_VALIDO = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]+$");

    /**
     * Un solo correo, sin espacios ni separadores (evita mandar a varios
     * destinatarios por error). Si es inválido, vacío.
     */
    private static String normalizarCorreo(String valor) {
        return normalizarCorreo(valor, "CHATBOT_ADMIN_EMAIL");
    }

    private static String normalizarCorreo(String valor, String variable) {
        String correo = valor.trim();
        if (correo.isEmpty())
            return "";
        if (CORREO_VALIDO.matcher(correo).matches())
            return correo;
        logger.error("⚠️ {} no es un correo válido. Se avisará al correo remitente de Brevo.", variable);
        return "";
    }
}
