package com.torneosflash.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
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


    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private final int port;
    private final String databaseUrl;
    private final String clashApiToken;
    private final String firebaseServiceAccount;
    private final String brevoApiKey;
    private final String brevoSenderEmail;
    private final String wompiPublicKey;
    private final String wompiPrivateKey;
    private final String wompiIntegritySecret;
    private final String wompiUserPrincipalId;
    private final String wompiXApiKey;
    private final String dbAdminSecret;

    // --- Sesión JWT ---
    private final String jwtSecret;
    private final long jwtExpirationSeconds;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    // --- Límites de montos (COP, pesos enteros). Los valida ValidadorMonto en el servidor. ---
    private final BigDecimal montoMinDeposito;
    private final BigDecimal montoMaxDeposito;
    private final BigDecimal montoMinRetiro;
    private final BigDecimal montoMaxRetiro;
    private final BigDecimal montoMinTorneo;
    private final BigDecimal montoMaxTorneo;
    private final BigDecimal montoMinSorteo;
    private final BigDecimal montoMaxSorteo;

    // --- Rate limiting (S8). Todos por IP y por minuto. Ver RateLimitMiddleware. ---
    private final boolean rateLimitEnabled;
    private final int rateLimitGeneralPorMin;
    private final int rateLimitFinancieroPorMin;
    private final int rateLimitMediaPorMin;
    private final int rateLimitProxyHops;

    // --- Constructor (CONSTRUCTOR con parámetros desde env) ---
    public AppConfig() {
        this.port = Integer.parseInt(getEnv("PORT", "5000"));
        this.databaseUrl = getEnv("DATABASE_URL", "");
        this.clashApiToken = getEnv("CLASH_ROYALE_API_TOKEN", "");
        this.firebaseServiceAccount = getEnv("FIREBASE_SERVICE_ACCOUNT", "");
        this.brevoApiKey = getEnv("BREVO_API_KEY", "");
        this.brevoSenderEmail = getEnv("BREVO_SENDER_EMAIL", "");
        this.wompiPublicKey = getEnv("WOMPI_PUBLIC_KEY", "");
        this.wompiPrivateKey = getEnv("WOMPI_PRIVATE_KEY", "");
        this.wompiIntegritySecret = getEnv("WOMPI_INTEGRITY_SECRET", "");
        this.wompiUserPrincipalId = getEnv("WOMPI_USER_PRINCIPAL_ID", "");
        this.wompiXApiKey = getEnv("WOMPI_X_API_KEY", "");
        this.dbAdminSecret = getEnv("DB_ADMIN_SECRET", "torneos2024");

        this.jwtSecret = resolverJwtSecret(getEnv("JWT_SECRET", ""));
        this.jwtExpirationSeconds = Long.parseLong(getEnv("JWT_EXPIRATION_DAYS", "30")) * 24L * 3600L;
        this.cookieSameSite = normalizarSameSite(getEnv("COOKIE_SAMESITE", "Lax"));
        // SameSite=None exige Secure; en cualquier otro caso se respeta COOKIE_SECURE (default true)
        this.cookieSecure = "None".equals(cookieSameSite)
                || Boolean.parseBoolean(getEnv("COOKIE_SECURE", "true"));

        this.montoMinDeposito = getEnvMonto("MONTO_MIN_DEPOSITO", "1000");
        this.montoMaxDeposito = getEnvMonto("MONTO_MAX_DEPOSITO", "5000000");
        this.montoMinRetiro = getEnvMonto("MONTO_MIN_RETIRO", "10000");
        this.montoMaxRetiro = getEnvMonto("MONTO_MAX_RETIRO", "5000000");
        this.montoMinTorneo = getEnvMonto("MONTO_MIN_TORNEO", "1000");
        this.montoMaxTorneo = getEnvMonto("MONTO_MAX_TORNEO", "10000");
        this.montoMinSorteo = getEnvMonto("MONTO_MIN_SORTEO", "1000");
        this.montoMaxSorteo = getEnvMonto("MONTO_MAX_SORTEO", "100000000");

        this.rateLimitEnabled = Boolean.parseBoolean(getEnv("RATE_LIMIT_ENABLED", "true"));
        this.rateLimitGeneralPorMin = getEnvEntero("RATE_LIMIT_GENERAL_PER_MIN", 60, 1);
        this.rateLimitFinancieroPorMin = getEnvEntero("RATE_LIMIT_FINANCIAL_PER_MIN", 10, 1);
        this.rateLimitMediaPorMin = getEnvEntero("RATE_LIMIT_MEDIA_PER_MIN", 300, 1);
        // Cuántos proxies de confianza hay delante del servidor (Render = 1). 0 = ignorar X-Forwarded-For.
        this.rateLimitProxyHops = getEnvEntero("RATE_LIMIT_PROXY_HOPS", 1, 0);
    }

    /**
     * Si no hay JWT_SECRET (o es muy corto) se genera uno aleatorio en memoria.
     * Las sesiones se invalidarán en cada reinicio, así que en producción
     * SIEMPRE debe definirse JWT_SECRET (mínimo 32 caracteres).
     */
    private static String resolverJwtSecret(String fromEnv) {
        if (fromEnv.length() >= 32) return fromEnv;
        if (!fromEnv.isEmpty()) {
            logger.error("⚠️ JWT_SECRET tiene menos de 32 caracteres. Se ignora y se usa uno aleatorio.");
        } else {
            logger.error("⚠️ JWT_SECRET no definido. Se usa uno aleatorio: las sesiones se perderán al reiniciar.");
        }
        byte[] bytes = new byte[48];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static String normalizarSameSite(String value) {
        switch (value.trim().toLowerCase()) {
            case "strict": return "Strict";
            case "none": return "None";
            default: return "Lax";
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
     * Lee un entero de una variable de entorno. Si no está definida, no es un número o es menor
     * que {@code minimo}, se usa el valor por defecto.
     */
    private int getEnvEntero(String key, int defaultValue, int minimo) {
        String raw = getEnv(key, String.valueOf(defaultValue)).trim();
        try {
            int valor = Integer.parseInt(raw);
            if (valor >= minimo) return valor;
        } catch (NumberFormatException ignored) {
            // cae al log de abajo
        }
        logger.error("⚠️ {}='{}' no es válido (entero >= {}). Se usa {}.", key, raw, minimo, defaultValue);
        return defaultValue;
    }

    // Método auxiliar para leer variables de entorno con valor por defecto
    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    // --- Getters (GETTER) ---
    public int getPort() { return port; }
    public String getDatabaseUrl() { return databaseUrl; }
    public String getClashApiToken() { return clashApiToken; }
    public String getFirebaseServiceAccount() { return firebaseServiceAccount; }
    public String getBrevoApiKey() { return brevoApiKey; }
    public String getBrevoSenderEmail() { return brevoSenderEmail; }
    public String getWompiPublicKey() { return wompiPublicKey; }
    public String getWompiPrivateKey() { return wompiPrivateKey; }
    public String getWompiIntegritySecret() { return wompiIntegritySecret; }
    public String getWompiUserPrincipalId() { return wompiUserPrincipalId; }
    public String getWompiXApiKey() { return wompiXApiKey; }
    public String getDbAdminSecret() { return dbAdminSecret; }
    public String getJwtSecret() { return jwtSecret; }
    public long getJwtExpirationSeconds() { return jwtExpirationSeconds; }
    public boolean isCookieSecure() { return cookieSecure; }
    public String getCookieSameSite() { return cookieSameSite; }
    public BigDecimal getMontoMinDeposito() { return montoMinDeposito; }
    public BigDecimal getMontoMaxDeposito() { return montoMaxDeposito; }
    public BigDecimal getMontoMinRetiro() { return montoMinRetiro; }
    public BigDecimal getMontoMaxRetiro() { return montoMaxRetiro; }
    public BigDecimal getMontoMinTorneo() { return montoMinTorneo; }
    public BigDecimal getMontoMaxTorneo() { return montoMaxTorneo; }
    public BigDecimal getMontoMinSorteo() { return montoMinSorteo; }
    public BigDecimal getMontoMaxSorteo() { return montoMaxSorteo; }
    public boolean isRateLimitEnabled() { return rateLimitEnabled; }
    public int getRateLimitGeneralPorMin() { return rateLimitGeneralPorMin; }
    public int getRateLimitFinancieroPorMin() { return rateLimitFinancieroPorMin; }
    public int getRateLimitMediaPorMin() { return rateLimitMediaPorMin; }
    public int getRateLimitProxyHops() { return rateLimitProxyHops; }

    public boolean hasClashApi() { return !clashApiToken.isEmpty(); }
    public boolean hasFirebase() { return !firebaseServiceAccount.isEmpty(); }
    public boolean hasBrevo() { return !brevoApiKey.isEmpty() && !brevoSenderEmail.isEmpty(); }
}
