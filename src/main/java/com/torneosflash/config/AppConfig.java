package com.torneosflash.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public boolean hasClashApi() { return !clashApiToken.isEmpty(); }
    public boolean hasFirebase() { return !firebaseServiceAccount.isEmpty(); }
    public boolean hasBrevo() { return !brevoApiKey.isEmpty() && !brevoSenderEmail.isEmpty(); }
}
