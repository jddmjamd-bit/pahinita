package com.torneosflash.config;

/**
 * Configuración de la aplicación.
 * Lee las variables de entorno necesarias para conectar a PostgreSQL,
 * APIs externas y servicios de correo.
 * 
 * CONCEPTO POO: Encapsulamiento - todos los atributos son private,
 * acceso solo por getters.
 */
public class AppConfig {

    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private final int port;
    private final String databaseUrl;
    private final String clashApiToken;
    private final String firebaseServiceAccount;
    private final String gmailUser;
    private final String gmailPass;
    private final String wompiPublicKey;
    private final String wompiPrivateKey;
    private final String wompiIntegritySecret;
    private final String wompiUserPrincipalId;
    private final String wompiXApiKey;
    private final String dbAdminSecret;
    private final String cookieSecret;

    // --- Constructor (CONSTRUCTOR con parámetros desde env) ---
    public AppConfig() {
        this.port = Integer.parseInt(getEnv("PORT", "5000"));
        this.databaseUrl = getEnv("DATABASE_URL", "");
        this.clashApiToken = getEnv("CLASH_ROYALE_API_TOKEN", "");
        this.firebaseServiceAccount = getEnv("FIREBASE_SERVICE_ACCOUNT", "");
        this.gmailUser = getEnv("GMAIL_USER", "");
        this.gmailPass = getEnv("GMAIL_PASS", "");
        this.wompiPublicKey = getEnv("WOMPI_PUBLIC_KEY", "");
        this.wompiPrivateKey = getEnv("WOMPI_PRIVATE_KEY", "");
        this.wompiIntegritySecret = getEnv("WOMPI_INTEGRITY_SECRET", "");
        this.wompiUserPrincipalId = getEnv("WOMPI_USER_PRINCIPAL_ID", "");
        this.wompiXApiKey = getEnv("WOMPI_X_API_KEY", "");
        this.dbAdminSecret = getEnv("DB_ADMIN_SECRET", "torneos2024");
        this.cookieSecret = "secreto_super_seguro";
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
    public String getGmailUser() { return gmailUser; }
    public String getGmailPass() { return gmailPass; }
    public String getWompiPublicKey() { return wompiPublicKey; }
    public String getWompiPrivateKey() { return wompiPrivateKey; }
    public String getWompiIntegritySecret() { return wompiIntegritySecret; }
    public String getWompiUserPrincipalId() { return wompiUserPrincipalId; }
    public String getWompiXApiKey() { return wompiXApiKey; }
    public String getDbAdminSecret() { return dbAdminSecret; }
    public String getCookieSecret() { return cookieSecret; }

    public boolean hasClashApi() { return !clashApiToken.isEmpty(); }
    public boolean hasFirebase() { return !firebaseServiceAccount.isEmpty(); }
    public boolean hasGmail() { return !gmailUser.isEmpty() && !gmailPass.isEmpty(); }
}
    