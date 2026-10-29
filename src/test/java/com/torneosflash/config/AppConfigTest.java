package com.torneosflash.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    private Logger logAppConfig;
    private Level nivelAnterior;

    @BeforeEach
    void silenciarErroresEsperadosDeValidacion() {
        logAppConfig = (Logger) LoggerFactory.getLogger(AppConfig.class);
        nivelAnterior = logAppConfig.getLevel();
        logAppConfig.setLevel(Level.OFF);
    }

    @AfterEach
    void restaurarNivelDeLog() {
        if (logAppConfig != null) logAppConfig.setLevel(nivelAnterior);
    }

    @Test
    void usaValoresPorDefectoSegurosYDesactivaIntegracionesSinCredenciales() {
        AppConfig config = new AppConfig(Map.of());

        assertAll(
                () -> assertEquals(5000, config.getPort()),
                () -> assertEquals(30, config.getNegociacionGraciaDesconexionSegundos()),
                () -> assertEquals(150, config.getNegociacionTimerAccionSegundos()),
                () -> assertEquals("", config.getDatabaseUrl()),
                () -> assertEquals("require", config.getDbSslMode()),
                () -> assertEquals("0.0.0.0", config.getBindHost()),
                () -> assertEquals(8_000_000, config.getWsMaxMessageBytes()),
                () -> assertEquals(9092, config.getSocketIoPort()),
                () -> assertEquals("https://torneos-beta.onrender.com", config.getAppBaseUrl()),
                () -> assertEquals("Lax", config.getCookieSameSite()),
                () -> assertTrue(config.isCookieSecure()),
                () -> assertTrue(config.isRateLimitEnabled()),
                () -> assertEquals(32, config.getSocketIoPollingMaxConcurrent()),
                () -> assertEquals("gemini", config.getChatbotProveedor()),
                () -> assertEquals("gemini-flash-latest", config.getChatbotModelo()),
                () -> assertEquals("", config.getSoporteAdminEmail()),
                () -> assertEquals("https://torneos-beta.onrender.com/api/voz/discord/callback",
                        config.getDiscordRedirectUri()),
                () -> assertFalse(config.hasClashApi()),
                () -> assertFalse(config.hasFirebase()),
                () -> assertFalse(config.hasBrevo()),
                () -> assertFalse(config.hasChatbot()),
                () -> assertFalse(config.hasDiscord()),
                () -> assertEquals(64, config.getJwtSecret().length())
        );
    }

    @Test
    void aceptaValoresDePruebaParaTodasLasVariablesSinModificarElEntornoDelProceso() {
        Map<String, String> env = new HashMap<>();
        env.putAll(Map.ofEntries(
                Map.entry("PORT", "65535"),
                Map.entry("DATABASE_URL", "postgres://test_user:test_password@db.invalid:5432/torneosflash_test"),
                Map.entry("DB_SSLMODE", "verify-full"),
                Map.entry("CLASH_ROYALE_API_TOKEN", "fake-clash-token"),
                Map.entry("FIREBASE_SERVICE_ACCOUNT", "{\"project_id\":\"unit-test\"}"),
                Map.entry("BREVO_API_KEY", "fake-brevo-key"),
                Map.entry("BREVO_SENDER_EMAIL", "sender@example.test"),
                Map.entry("WOMPI_PUBLIC_KEY", "fake-public-key"),
                Map.entry("WOMPI_PRIVATE_KEY", "fake-private-key"),
                Map.entry("WOMPI_INTEGRITY_SECRET", "fake-integrity-secret"),
                Map.entry("WOMPI_EVENTS_SECRET", "fake-events-secret"),
                Map.entry("WOMPI_USER_PRINCIPAL_ID", "fake-principal"),
                Map.entry("WOMPI_X_API_KEY", "fake-x-api-key"),
                Map.entry("DB_ADMIN_SECRET", "fake-db-admin-secret"),
                Map.entry("DB_ADMIN_MAX_BACKUP_BYTES", "10737418240"),
                Map.entry("APP_BASE_URL", "https://app.example.test/"),
                Map.entry("BIND_HOST", "127.0.0.1"),
                Map.entry("WS_MAX_MESSAGE_BYTES", "1024"),
                Map.entry("SOCKET_IO_PORT", "0"),
                Map.entry("CORS_ALLOWED_ORIGINS", "https://one.example.test/, https://two.example.test"),
                Map.entry("BUSQUEDA_TIMEOUT_MINUTOS", "1"),
                Map.entry("NEGOCIACION_DESCONEXION_SEGUNDOS", "3600"),
                Map.entry("NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS", "45"),
                Map.entry("NEGOCIACION_TIMER_ACCION_SEGUNDOS", "180"),
                Map.entry("POLLING_INTERVALO_SEGUNDOS", "60"),
                Map.entry("POLLING_MAX_INTENTOS", "10000"),
                Map.entry("CHAT_HISTORIAL_LIMITE", "500"),
                Map.entry("JWT_SECRET", "test-only-jwt-secret-with-at-least-32-characters"),
                Map.entry("JWT_EXPIRATION_DAYS", "3650"),
                Map.entry("COOKIE_SECURE", "false"),
                Map.entry("COOKIE_SAMESITE", "Strict"),
                Map.entry("PASSWORD_RESET_TOKEN_MINUTES", "60"),
                Map.entry("PASSWORD_RESET_COOLDOWN_SECONDS", "600"),
                Map.entry("MONTO_MIN_DEPOSITO", "1000"),
                Map.entry("MONTO_MAX_DEPOSITO", "5000000"),
                Map.entry("MONTO_MIN_RETIRO", "10000"),
                Map.entry("MONTO_MAX_RETIRO", "5000000"),
                Map.entry("MONTO_MIN_TORNEO", "1000"),
                Map.entry("MONTO_MAX_TORNEO", "10000"),
                Map.entry("MONTO_MIN_SORTEO", "1000"),
                Map.entry("MONTO_MAX_SORTEO", "100000000"),
                Map.entry("RATE_LIMIT_ENABLED", "false"),
                Map.entry("RATE_LIMIT_GENERAL_PER_MIN", "1"),
                Map.entry("RATE_LIMIT_FINANCIAL_PER_MIN", "2"),
                Map.entry("RATE_LIMIT_AUTH_PER_MIN", "3"),
                Map.entry("RATE_LIMIT_MEDIA_PER_MIN", "4"),
                Map.entry("RATE_LIMIT_PROXY_HOPS", "10"),
                Map.entry("HTTP_MAX_THREADS", "16"),
                Map.entry("HTTP_MIN_THREADS", "40"),
                Map.entry("HTTP_IDLE_TIMEOUT_MS", "1000"),
                Map.entry("SOCKET_IO_POLLING_MAX_CONCURRENT", "8"),
                Map.entry("IO_POOL_THREADS", "1"),
                Map.entry("IO_POOL_QUEUE", "1"),
                Map.entry("PUSH_POOL_THREADS", "32"),
                Map.entry("PUSH_POOL_QUEUE", "10000"),
                Map.entry("PUSH_MAX_PER_USER_PER_MIN", "1000"),
                Map.entry("CPU_POOL_THREADS", "1"),
                Map.entry("CPU_POOL_QUEUE", "1"),
                Map.entry("TIMER_POOL_THREADS", "1"),
                Map.entry("DB_POOL_MAX_SIZE", "2"),
                Map.entry("CHATBOT_PROVIDER", "openai"),
                Map.entry("CHATBOT_API_KEY", "fake-chatbot-key"),
                Map.entry("CHATBOT_MODEL", "gpt-4o-mini"),
                Map.entry("CHATBOT_ADMIN_EMAIL", "chatbot@example.test"),
                Map.entry("CHATBOT_MAX_MENSAJES_POR_MIN", "120"),
                Map.entry("CHATBOT_MAX_MENSAJES_DIA", "10000"),
                Map.entry("CHATBOT_MAX_CONCURRENTES", "100"),
                Map.entry("CHATBOT_MAX_CORREOS_HORA", "1000"),
                Map.entry("CHATBOT_TIMEOUT_SEGUNDOS", "120"),
                Map.entry("CHATBOT_CONTEXTO_MENSAJES", "30"),
                Map.entry("CHATBOT_MAX_TOKENS", "8192"),
                Map.entry("RATE_LIMIT_CHATBOT_PER_MIN", "120"),
                Map.entry("SOPORTE_ADMIN_EMAIL", "support@example.test"),
                Map.entry("SOPORTE_MAX_ABIERTOS", "100"),
                Map.entry("SOPORTE_MAX_TICKETS_DIA", "1000"),
                Map.entry("SOPORTE_MAX_CORREOS_HORA", "1000"),
                Map.entry("PAGOS_MAX_IMAGEN_CHARS", "900000"),
                Map.entry("PAGOS_HISTORIAL_LIMITE", "100"),
                Map.entry("DISCORD_CLIENT_ID", "123456789012345"),
                Map.entry("DISCORD_CLIENT_SECRET", "fake-discord-secret"),
                Map.entry("DISCORD_BOT_TOKEN", "fake-discord-token"),
                Map.entry("DISCORD_GUILD_ID", "123456789012346"),
                Map.entry("DISCORD_REDIRECT_URI", "https://app.example.test/callback"),
                Map.entry("DISCORD_VOICE_CATEGORY_ID", "123456789012347"),
                Map.entry("DISCORD_INVITE_URL", "https://discord.example.test/invite"),
                Map.entry("DISCORD_VOICE_MAX_MINUTOS", "1440")
        ));
        AppConfig config = new AppConfig(env);

        assertAll(
                () -> assertEquals(65535, config.getPort()),
                () -> assertEquals(env.get("DATABASE_URL"), config.getDatabaseUrl()),
                () -> assertEquals("verify-full", config.getDbSslMode()),
                () -> assertEquals("fake-clash-token", config.getClashApiToken()),
                () -> assertEquals(env.get("FIREBASE_SERVICE_ACCOUNT"), config.getFirebaseServiceAccount()),
                () -> assertEquals("fake-brevo-key", config.getBrevoApiKey()),
                () -> assertEquals("sender@example.test", config.getBrevoSenderEmail()),
                () -> assertEquals("fake-public-key", config.getWompiPublicKey()),
                () -> assertEquals("fake-private-key", config.getWompiPrivateKey()),
                () -> assertEquals("fake-integrity-secret", config.getWompiIntegritySecret()),
                () -> assertEquals("fake-events-secret", config.getWompiEventsSecret()),
                () -> assertEquals("fake-principal", config.getWompiUserPrincipalId()),
                () -> assertEquals("fake-x-api-key", config.getWompiXApiKey()),
                () -> assertEquals("fake-db-admin-secret", config.getDbAdminSecret()),
                () -> assertEquals(10_737_418_240L, config.getDbAdminMaxBackupBytes()),
                () -> assertEquals("https://app.example.test", config.getAppBaseUrl()),
                () -> assertEquals("127.0.0.1", config.getBindHost()),
                () -> assertEquals(1024, config.getWsMaxMessageBytes()),
                () -> assertEquals(0, config.getSocketIoPort()),
                () -> assertTrue(config.esOrigenPermitido("https://one.example.test")),
                () -> assertTrue(config.esOrigenPermitido("https://two.example.test/")),
                () -> assertFalse(config.esOrigenPermitido("https://attacker.example.test")),
                () -> assertEquals(1, config.getBusquedaTimeoutMinutos()),
                () -> assertEquals(3600, config.getNegociacionDesconexionSegundos()),
                () -> assertEquals(45, config.getNegociacionGraciaDesconexionSegundos()),
                () -> assertEquals(180, config.getNegociacionTimerAccionSegundos()),
                () -> assertEquals(60, config.getPollingIntervaloSegundos()),
                () -> assertEquals(10000, config.getPollingMaxIntentos()),
                () -> assertEquals(500, config.getChatHistorialLimite()),
                () -> assertEquals(env.get("JWT_SECRET"), config.getJwtSecret()),
                () -> assertEquals(3650L * 24 * 3600, config.getJwtExpirationSeconds()),
                () -> assertFalse(config.isCookieSecure()),
                () -> assertEquals("Strict", config.getCookieSameSite()),
                () -> assertEquals(60, config.getPasswordResetTokenMinutes()),
                () -> assertEquals(600, config.getPasswordResetCooldownSeconds()),
                () -> assertEquals(new BigDecimal("1000"), config.getMontoMinDeposito()),
                () -> assertEquals(new BigDecimal("5000000"), config.getMontoMaxDeposito()),
                () -> assertEquals(new BigDecimal("10000"), config.getMontoMinRetiro()),
                () -> assertEquals(new BigDecimal("5000000"), config.getMontoMaxRetiro()),
                () -> assertEquals(new BigDecimal("1000"), config.getMontoMinTorneo()),
                () -> assertEquals(new BigDecimal("10000"), config.getMontoMaxTorneo()),
                () -> assertEquals(new BigDecimal("1000"), config.getMontoMinSorteo()),
                () -> assertEquals(new BigDecimal("100000000"), config.getMontoMaxSorteo()),
                () -> assertFalse(config.isRateLimitEnabled()),
                () -> assertEquals(1, config.getRateLimitGeneralPorMin()),
                () -> assertEquals(2, config.getRateLimitFinancieroPorMin()),
                () -> assertEquals(3, config.getRateLimitAuthPorMin()),
                () -> assertEquals(4, config.getRateLimitMediaPorMin()),
                () -> assertEquals(10, config.getRateLimitProxyHops()),
                () -> assertEquals(16, config.getHttpMaxThreads()),
                () -> assertEquals(16, config.getHttpMinThreads()),
                () -> assertEquals(1000, config.getHttpIdleTimeoutMs()),
                () -> assertEquals(8, config.getSocketIoPollingMaxConcurrent()),
                () -> assertEquals(1, config.getIoPoolThreads()),
                () -> assertEquals(1, config.getIoPoolQueue()),
                () -> assertEquals(32, config.getPushPoolThreads()),
                () -> assertEquals(10000, config.getPushPoolQueue()),
                () -> assertEquals(1000, config.getPushMaxPorUsuarioPorMin()),
                () -> assertEquals(1, config.getCpuPoolThreads()),
                () -> assertEquals(1, config.getCpuPoolQueue()),
                () -> assertEquals(1, config.getTimerPoolThreads()),
                () -> assertEquals(2, config.getDbPoolMax()),
                () -> assertEquals("openai", config.getChatbotProveedor()),
                () -> assertEquals("fake-chatbot-key", config.getChatbotApiKey()),
                () -> assertEquals("gpt-4o-mini", config.getChatbotModelo()),
                () -> assertEquals("chatbot@example.test", config.getChatbotAdminEmail()),
                () -> assertEquals(120, config.getChatbotMaxMensajesPorMin()),
                () -> assertEquals(10000, config.getChatbotMaxMensajesDia()),
                () -> assertEquals(100, config.getChatbotMaxConcurrentes()),
                () -> assertEquals(1000, config.getChatbotMaxCorreosHora()),
                () -> assertEquals(120, config.getChatbotTimeoutSegundos()),
                () -> assertEquals(30, config.getChatbotContextoMensajes()),
                () -> assertEquals(8192, config.getChatbotMaxTokens()),
                () -> assertEquals(120, config.getRateLimitChatbotPorMin()),
                () -> assertEquals("support@example.test", config.getSoporteAdminEmail()),
                () -> assertEquals(100, config.getSoporteMaxAbiertos()),
                () -> assertEquals(1000, config.getSoporteMaxTicketsDia()),
                () -> assertEquals(1000, config.getSoporteMaxCorreosHora()),
                () -> assertEquals(900000, config.getPagosMaxImagenChars()),
                () -> assertEquals(100, config.getPagosHistorialLimite()),
                () -> assertEquals("123456789012345", config.getDiscordClientId()),
                () -> assertEquals("fake-discord-secret", config.getDiscordClientSecret()),
                () -> assertEquals("fake-discord-token", config.getDiscordBotToken()),
                () -> assertEquals("123456789012346", config.getDiscordGuildId()),
                () -> assertEquals("https://app.example.test/callback", config.getDiscordRedirectUri()),
                () -> assertEquals("123456789012347", config.getDiscordCategoriaVozId()),
                () -> assertEquals("https://discord.example.test/invite", config.getDiscordInviteUrl()),
                () -> assertEquals(1440, config.getDiscordVozMaxMinutos()),
                () -> assertTrue(config.hasClashApi()),
                () -> assertTrue(config.hasFirebase()),
                () -> assertTrue(config.hasBrevo()),
                () -> assertTrue(config.hasChatbot()),
                () -> assertTrue(config.hasDiscord())
        );
    }

    @Test
    void valoresInvalidosUsanDefaultsSegurosYRespetaReglasEntreVariables() {
        AppConfig config = new AppConfig(Map.ofEntries(
                Map.entry("PORT", "0"),
                Map.entry("JWT_EXPIRATION_DAYS", "no-es-numero"),
                Map.entry("DB_ADMIN_MAX_BACKUP_BYTES", "no-es-numero"),
                Map.entry("RATE_LIMIT_ENABLED", "tal-vez"),
                Map.entry("COOKIE_SECURE", "tal-vez"),
                Map.entry("COOKIE_SAMESITE", "desconocido"),
                Map.entry("WS_MAX_MESSAGE_BYTES", "900"),
                Map.entry("SOCKET_IO_POLLING_MAX_CONCURRENT", "99999"),
                Map.entry("MONTO_MIN_DEPOSITO", "9000"),
                Map.entry("MONTO_MAX_DEPOSITO", "2000"),
                Map.entry("HTTP_MAX_THREADS", "16"),
                Map.entry("HTTP_MIN_THREADS", "40"),
                Map.entry("NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS", "0"),
                Map.entry("NEGOCIACION_TIMER_ACCION_SEGUNDOS", "1")
        ));

        assertAll(
                () -> assertEquals(5000, config.getPort()),
                () -> assertEquals(30, config.getNegociacionGraciaDesconexionSegundos()),
                () -> assertEquals(150, config.getNegociacionTimerAccionSegundos()),
                () -> assertEquals(30L * 24 * 3600, config.getJwtExpirationSeconds()),
                () -> assertEquals(1_073_741_824L, config.getDbAdminMaxBackupBytes()),
                () -> assertTrue(config.isRateLimitEnabled()),
                () -> assertTrue(config.isCookieSecure()),
                () -> assertEquals("Lax", config.getCookieSameSite()),
                () -> assertEquals(8_000_000, config.getWsMaxMessageBytes()),
                () -> assertEquals(8, config.getSocketIoPollingMaxConcurrent()),
                () -> assertEquals(new BigDecimal("1000"), config.getMontoMinDeposito()),
                () -> assertEquals(new BigDecimal("5000000"), config.getMontoMaxDeposito()),
                () -> assertEquals(16, config.getHttpMinThreads())
        );
    }

    @Test
    void sameSiteNoneFuerzaCookieSeguraAunSiSeSolicitaFalse() {
        AppConfig config = new AppConfig(Map.of("COOKIE_SAMESITE", "None", "COOKIE_SECURE", "false"));
        assertEquals("None", config.getCookieSameSite());
        assertTrue(config.isCookieSecure());
    }

    @Test
    void rechazaLimitesFueraDeRangoYDireccionesDeCorreoInvalidas() {
        AppConfig config = new AppConfig(Map.ofEntries(
                Map.entry("JWT_EXPIRATION_DAYS", "3651"),
                Map.entry("BREVO_API_KEY", "fake-key"),
                Map.entry("BREVO_SENDER_EMAIL", "esto-no-es-un-correo"),
                Map.entry("CHATBOT_ADMIN_EMAIL", "correo-invalido"),
                Map.entry("SOPORTE_ADMIN_EMAIL", "tambien-invalido"),
                Map.entry("CHATBOT_PROVIDER", "proveedor-inexistente")
        ));

        assertAll(
                () -> assertEquals(30L * 24 * 3600, config.getJwtExpirationSeconds()),
                () -> assertFalse(config.hasBrevo()),
                () -> assertEquals("gemini", config.getChatbotProveedor()),
                () -> assertEquals("", config.getChatbotAdminEmail()),
                () -> assertEquals("", config.getSoporteAdminEmail())
        );
    }

    @Test
    void activaCadaIntegracionSoloConLaConfiguracionMinimaNecesaria() {
        AppConfig soloBrevoSinRemitente = new AppConfig(Map.of("BREVO_API_KEY", "fake"));
        AppConfig brevoCompleto = new AppConfig(Map.of(
                "BREVO_API_KEY", "fake", "BREVO_SENDER_EMAIL", "sender@example.test"));
        AppConfig discordParcial = new AppConfig(Map.of(
                "DISCORD_CLIENT_ID", "123456789012345", "DISCORD_CLIENT_SECRET", "fake"));

        assertFalse(soloBrevoSinRemitente.hasBrevo());
        assertTrue(brevoCompleto.hasBrevo());
        assertFalse(discordParcial.hasDiscord());
        assertEquals("sender@example.test", new AppConfig(Map.of(
                "BREVO_SENDER_EMAIL", "sender@example.test")).getSoporteAdminEmail());
    }

    @Test
    void bloqueaArranqueEnRenderCuandoFaltanVariablesObligatoriasSinImprimirSecretos() {
        Map<String, String> env = new HashMap<>();
        env.put("RENDER", "true");
        env.put("JWT_SECRET", "secreto-corto");
        env.put("WOMPI_PRIVATE_KEY", "clave-que-no-debe-salir-en-el-error");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AppConfig(env).validarConfiguracionProduccion());

        assertTrue(error.getMessage().contains("DATABASE_URL"));
        assertTrue(error.getMessage().contains("JWT_SECRET"));
        assertTrue(error.getMessage().contains("WOMPI_EVENTS_SECRET"));
        assertFalse(error.getMessage().contains("clave-que-no-debe-salir-en-el-error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE_URL", "CLASH_ROYALE_API_TOKEN", "WOMPI_PUBLIC_KEY",
            "WOMPI_PRIVATE_KEY", "WOMPI_EVENTS_SECRET", "WOMPI_INTEGRITY_SECRET"})
    void bloqueaCadaVariableObligatoriaCuandoFalta(String variable) {
        Map<String, String> env = entornoRenderValido();
        env.remove(variable);
        assertThrows(IllegalStateException.class,
                () -> new AppConfig(env).validarConfiguracionProduccion(), variable);
    }

    @Test
    void permiteArranqueEnRenderSoloSiElSecretoJwtTiene32CaracteresOMas() {
        Map<String, String> env = entornoRenderValido();
        env.put("JWT_SECRET", "x".repeat(32));
        new AppConfig(env).validarConfiguracionProduccion();

        env.put("JWT_SECRET", "x".repeat(31));
        assertThrows(IllegalStateException.class,
                () -> new AppConfig(env).validarConfiguracionProduccion());
    }

    @Test
    void noAplicaLaValidacionDeProduccionEnDesarrolloNiEnPreview() {
        new AppConfig(Map.of("RENDER", "false")).validarConfiguracionProduccion();
        new AppConfig(Map.of("RENDER", "true", "IS_PULL_REQUEST", "true"))
                .validarConfiguracionProduccion();
    }

    private static Map<String, String> entornoRenderValido() {
        return new HashMap<>(Map.ofEntries(
                Map.entry("RENDER", "true"),
                Map.entry("DATABASE_URL", "postgres://test:test@db.invalid:5432/torneosflash_test"),
                Map.entry("JWT_SECRET", "fake-production-secret-with-at-least-32-characters"),
                Map.entry("CLASH_ROYALE_API_TOKEN", "fake-clash-token"),
                Map.entry("WOMPI_PUBLIC_KEY", "fake-public-key"),
                Map.entry("WOMPI_PRIVATE_KEY", "fake-private-key"),
                Map.entry("WOMPI_EVENTS_SECRET", "fake-events-secret"),
                Map.entry("WOMPI_INTEGRITY_SECRET", "fake-integrity-secret")
        ));
    }
}
