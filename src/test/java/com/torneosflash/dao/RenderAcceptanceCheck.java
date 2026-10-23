package com.torneosflash.dao;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.*;
import com.torneosflash.servidor.AdminMiddleware;
import com.torneosflash.servidor.AuthMiddleware;
import com.torneosflash.servidor.ManejadorErrores;
import com.torneosflash.servidor.RutasAuth;
import com.torneosflash.servidor.RutasFinanzas;
import com.torneosflash.servidor.RutasVoz;
import com.torneosflash.servidor.RutasDbAdmin;
import com.torneosflash.servidor.RateLimitMiddleware;
import com.torneosflash.socketio.SocketIOServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.FlywayException;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de aceptación de servicios contra un esquema efímero dentro de TEST_DATABASE_URL.
 * No usa DATABASE_URL, no contacta proveedores externos y elimina únicamente el esquema generado aquí.
 */
class RenderAcceptanceCheck {
    private static final Pattern DB_DE_PRUEBAS = Pattern.compile("(^|[_-])(test|pruebas?|qa)([_-]|$)");
    private static final String CLAVE = "test-only-jwt-secret-with-at-least-32-characters";
    private static ConexionDB conexion;
    private static GenericDAO dao;
    private static AppConfig config;
    private static WalletService wallet;
    private static FinanzasService finanzas;
    private static SorteoService sorteos;
    private static SoporteService soporte;
    private static EvidenciaPagoService evidencias;
    private static AuthService auth;
    private static AdminService admin;
    private static LeaderboardService leaderboard;
    private static SocketIOServer io;
    private static NotificadorUsuarios notificadorMudo;
    private static UsuarioDAO usuarios;
    private static EventBus eventos;
    private static Ejecutores ejecutores;
    private static int usuarioId;
    private static int otroUsuarioId;
    private static int adminId;
    private static int authUserId;
    private static String esquema;
    private static String urlBaseJdbc;
    private static ConexionDB.EnlaceBD enlaceBase;
    private static String sslMode;

    @BeforeAll
    static void prepararEsquemaAislado() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        assertNotNull(url, "TEST_DATABASE_URL debe apuntar a una base de pruebas separada.");
        assertFalse(url.isBlank(), "TEST_DATABASE_URL no puede estar vacía.");
        sslMode = System.getenv().getOrDefault("DB_SSLMODE", "require");
        enlaceBase = ConexionDB.EnlaceBD.desde(url, sslMode);
        assertTrue(DB_DE_PRUEBAS.matcher(enlaceBase.nombreBase.toLowerCase(Locale.ROOT)).find(),
                "El nombre de TEST_DATABASE_URL debe incluir test, pruebas o qa.");
        String produccion = System.getenv("DATABASE_URL");
        if (produccion != null && !produccion.isBlank()) {
            ConexionDB.EnlaceBD enlaceProduccion = ConexionDB.EnlaceBD.desde(produccion, sslMode);
            assertNotEquals(enlaceProduccion.nombreBase.toLowerCase(Locale.ROOT),
                    enlaceBase.nombreBase.toLowerCase(Locale.ROOT),
                    "TEST_DATABASE_URL y DATABASE_URL no pueden ser la misma base.");
        }

        esquema = "tf_qa_" + UUID.randomUUID().toString().replace("-", "");
        urlBaseJdbc = enlaceBase.jdbcUrl;
        try (HikariDataSource adminDb = crearPool(urlBaseJdbc, enlaceBase, "torneos-qa-bootstrap")) {
            try (Connection c = adminDb.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "CREATE SCHEMA \"" + esquema + "\"")) {
                ps.execute();
            }
            String urlEsquema = conEsquema(urlBaseJdbc, esquema);
            try (HikariDataSource scoped = crearPool(urlEsquema, enlaceBase, "torneos-qa-migrations")) {
                Flyway.configure()
                        .dataSource(scoped)
                        .schemas(esquema)
                        .defaultSchema(esquema)
                        .createSchemas(true)
                        .locations("classpath:db/migration")
                        .validateOnMigrate(true)
                        .cleanDisabled(true)
                        .load()
                        .migrate();
            }

            Map<String, String> env = new HashMap<>();
            env.put("DATABASE_URL", urlEsquema);
            env.put("JWT_SECRET", CLAVE);
            env.put("WOMPI_PUBLIC_KEY", "pub_test_acceptance");
            env.put("WOMPI_PRIVATE_KEY", "prv_test_acceptance");
            env.put("WOMPI_INTEGRITY_SECRET", "integrity-test-only");
            env.put("WOMPI_EVENTS_SECRET", "events-test-only");
            env.put("APP_BASE_URL", "https://example.invalid");
            env.put("SOPORTE_MAX_ABIERTOS", "2");
            env.put("SOPORTE_MAX_TICKETS_DIA", "10");
            env.put("RATE_LIMIT_PROXY_HOPS", "0");
            env.put("CPU_POOL_THREADS", "2");
            env.put("CPU_POOL_QUEUE", "10");
            env.put("IO_POOL_THREADS", "1");
            env.put("PUSH_POOL_THREADS", "1");
            env.put("TIMER_POOL_THREADS", "1");
            config = new AppConfig(env);
            conexion = ConexionDB.getInstancia(urlEsquema, sslMode, 4);
            dao = new GenericDAO(conexion);
            eventos = new EventBus();
            ejecutores = new Ejecutores(config);
            usuarios = new UsuarioDAO(conexion);
            wallet = new WalletService(conexion, new ComisionService(), eventos);
            ValidadorMonto validador = new ValidadorMonto(config);
            io = new SocketIOServer(config, null);
            notificadorMudo = new NotificadorUsuarios(null, dao, null) {
                @Override
                public void notificarSaldo(int userId, String mensaje, BigDecimal saldo) { }
            };
            finanzas = new FinanzasService(dao, config, wallet, validador, notificadorMudo);
            sorteos = new SorteoService(dao, io, null, null, validador);
            soporte = new SoporteService(dao, eventos, config);
            evidencias = new EvidenciaPagoService(dao, eventos, config);
            admin = new AdminService(dao, usuarios, wallet, eventos, notificadorMudo, io);
            leaderboard = new LeaderboardService(dao, io, wallet);

            ClashApiServicio clashFalso = new ClashApiServicio("") {
                @Override
                public ResultadoVerificacionPropiedad verificarPropiedadTag(String tag, String tokenJugador) {
                    return ResultadoVerificacionPropiedad.VERIFICADO;
                }
            };
            auth = new AuthService(usuarios, dao, clashFalso, ejecutores, config, eventos);

            String sufijo = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            usuarioId = crearUsuario("qa_user_" + sufijo, "user", new BigDecimal("25000"));
            otroUsuarioId = crearUsuario("qa_other_" + sufijo, "user", new BigDecimal("0"));
            adminId = crearUsuario("qa_admin_" + sufijo, "admin", new BigDecimal("0"));
            authUserId = crearUsuario("qa_login_" + sufijo, "user", BigDecimal.ZERO);
            dao.update("UPDATE users SET password = ? WHERE id = ?",
                    BCrypt.hashpw("ClaveSegura!123", BCrypt.gensalt(4)), authUserId);
        }
    }

    @AfterAll
    static void eliminarEsquemaAislado() throws Exception {
        if (ejecutores != null) ejecutores.cerrar();
        if (conexion != null) conexion.cerrar();
        if (esquema == null || enlaceBase == null) return;
        // El nombre solo se compone del prefijo fijo y un UUID hex generado en este proceso.
        assertTrue(esquema.matches("tf_qa_[a-f0-9]{32}"));
        try (HikariDataSource adminDb = crearPool(urlBaseJdbc, enlaceBase, "torneos-qa-cleanup");
             Connection c = adminDb.getConnection();
             PreparedStatement ps = c.prepareStatement("DROP SCHEMA IF EXISTS \"" + esquema + "\" CASCADE")) {
            ps.execute();
        }
    }

    @Test
    void registroAceptaCuentaNuevaYOcultaLasColisionesDeIdentidad() {
        String aleatorio = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        String username = "alta_" + aleatorio;
        String email = "alta_" + aleatorio.toLowerCase(Locale.ROOT) + "@example.test";
        String tag = "#PQ" + String.format("%06d", Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000));
        String telefono = "311" + String.format("%07d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000));
        long antes = dao.queryOne("SELECT COUNT(*) AS total FROM users").get("total").getAsLong();
        auth.registrar(username, email, "ClaveSegura!123", tag, telefono,
                "token-falso-aceptado-solo-por-el-test", true);
        long despuesAlta = dao.queryOne("SELECT COUNT(*) AS total FROM users").get("total").getAsLong();
        assertEquals(antes + 1, despuesAlta);
        int id = dao.queryOne("SELECT id FROM users WHERE username = ?", username).get("id").getAsInt();
        assertEquals(2, dao.queryOne("SELECT COUNT(*) AS total FROM legal_acceptances WHERE user_id = ?", id)
                .get("total").getAsInt());

        assertDoesNotThrow(() -> auth.registrar(username, "otro_" + email, "ClaveSegura!123", "#PY" + String.format("%06d", Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000)),
                "3129999999", "token-falso", true));
        assertEquals(despuesAlta, dao.queryOne("SELECT COUNT(*) AS total FROM users").get("total").getAsLong());
        assertDoesNotThrow(() -> auth.registrar("tag_repetido_" + aleatorio, "tag_" + aleatorio.toLowerCase(Locale.ROOT)
                        + "@example.test", "ClaveSegura!123", tag, "3139999999", "token-falso", true));
        assertEquals(despuesAlta, dao.queryOne("SELECT COUNT(*) AS total FROM users").get("total").getAsLong());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> auth.registrar("usuario_sin_token", "sin-token@example.test", "ClaveSegura!123",
                        "#" + aleatorio + "3", "3128888888", "", true)).getStatus());
    }

    @Test
    void rutasHttpDeAuthYAdminValidanCookiesSesionRevocableYRespuestasGenericas() throws Exception {
        int idCuentaHttp = crearUsuario("qa_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "user", BigDecimal.ZERO);
        dao.update("UPDATE users SET password = ? WHERE id = ?",
                BCrypt.hashpw("ClaveSegura!123", BCrypt.gensalt(4)), idCuentaHttp);
        int port;
        try (ServerSocket reservador = new ServerSocket(0)) {
            port = reservador.getLocalPort();
        }

        Javalin servidor = Javalin.create();
        ManejadorErrores.registrar(servidor);
        JwtServicio jwt = new JwtServicio(CLAVE, 3600);
        AuthMiddleware.register(servidor, jwt, config, usuarios);
        AdminMiddleware.register(servidor, admin);
        RutasAuth.register(servidor, auth);
        RutasFinanzas.register(servidor, finanzas);
        RutasVoz.register(servidor, new VozDiscordService(dao, new DiscordApiCliente(config), config));
        RutasDbAdmin.register(servidor, new DbAdminService(dao, conexion, config), config);
        servidor.get("/api/admin/probe", ctx -> ctx.result("ok"));
        servidor.start("127.0.0.1", port);
        HttpClient cliente = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        try {
            String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String tag = "#PQ" + String.format("%06d", Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000));
            String telefono = "310" + String.format("%07d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000));
            String registro = """
                    {"username":"http_reg_%s","email":"http_reg_%s@example.test","password":"ClaveSegura!123",
                    "playerTag":"%s","telefono":"%s","playerApiToken":"fake-token","aceptaTerminos":true}
                    """.formatted(random, random, tag, telefono);
            var alta = enviar(cliente, base, "POST", "/api/register", null, registro);
            var duplicado = enviar(cliente, base, "POST", "/api/register", null, registro);
            assertEquals(200, alta.statusCode());
            assertEquals(JsonParser.parseString(alta.body()).getAsJsonObject().get("message"),
                    JsonParser.parseString(duplicado.body()).getAsJsonObject().get("message"));
            assertTrue(alta.headers().allValues("set-cookie").isEmpty(), "El registro no inicia sesión.");

            var login = enviar(cliente, base, "POST", "/api/login", null,
                    "{\"email\":\"" + dao.queryOne("SELECT email FROM users WHERE id = ?", idCuentaHttp)
                            .get("email").getAsString() + "\",\"password\":\"ClaveSegura!123\"}");
            assertEquals(200, login.statusCode());
            JsonObject loginJson = JsonParser.parseString(login.body()).getAsJsonObject();
            assertFalse(loginJson.getAsJsonObject("user").has("password"));
            String tokenUno = cookieSession(login);
            assertNotNull(tokenUno);
            String jtiUno = jwt.verificar(tokenUno).jti();
            assertTrue(login.headers().allValues("set-cookie").stream()
                    .anyMatch(c -> c.startsWith("userId=;") && c.toLowerCase(Locale.ROOT).contains("max-age=0")),
                    "La cookie legacy userId solo debe borrarse; nunca debe contener la identidad.");

            var sesion = enviar(cliente, base, "GET", "/api/session", "session=" + tokenUno, null);
            assertEquals(200, sesion.statusCode());
            String tokenRenovado = cookieSession(sesion);
            assertNotNull(tokenRenovado);
            assertEquals(jtiUno, jwt.verificar(tokenRenovado).jti());

            var invalido = enviar(cliente, base, "GET", "/api/session",
                    "session=manipulado; userId=" + idCuentaHttp, null);
            assertEquals(401, invalido.statusCode());
            assertTrue(invalido.headers().allValues("set-cookie").stream()
                    .anyMatch(c -> c.startsWith("session=;")));

            var segundoLogin = enviar(cliente, base, "POST", "/api/login", null,
                    "{\"email\":\"" + dao.queryOne("SELECT email FROM users WHERE id = ?", idCuentaHttp)
                            .get("email").getAsString() + "\",\"password\":\"ClaveSegura!123\"}");
            String tokenDos = cookieSession(segundoLogin);
            assertNotNull(tokenDos);
            assertNotEquals(jtiUno, jwt.verificar(tokenDos).jti());

            assertEquals(401, enviar(cliente, base, "GET", "/api/voz/mi-canal", null, null).statusCode());
            var voz = enviar(cliente, base, "GET", "/api/voz/estado", "session=" + tokenDos, null);
            assertEquals(200, voz.statusCode());
            assertFalse(JsonParser.parseString(voz.body()).getAsJsonObject().get("disponible").getAsBoolean(),
                    "Sin las variables Discord, el chat de voz debe permanecer desactivado.");
            assertEquals(404, enviar(cliente, base, "GET", "/api/db-admin/tables", null, null).statusCode(),
                    "El panel de base de datos debe estar apagado sin DB_ADMIN_SECRET.");

            var recarga = enviar(cliente, base, "POST", "/api/wompi/init", "session=" + tokenDos,
                    "{\"userId\":" + adminId + ",\"monto\":1000}");
            assertEquals(200, recarga.statusCode());
            String referencia = JsonParser.parseString(recarga.body()).getAsJsonObject().get("reference").getAsString();
            assertEquals(idCuentaHttp, dao.queryOne("SELECT usuario_id FROM transactions WHERE referencia = ?", referencia)
                    .get("usuario_id").getAsInt(), "El endpoint financiero debe ignorar el userId del body.");

            assertEquals(401, enviar(cliente, base, "GET", "/api/admin/probe", null, null).statusCode());
            assertEquals(403, enviar(cliente, base, "GET", "/api/admin/probe", "session=" + tokenDos, null).statusCode());
            var tokenAdmin = jwt.generarToken(adminId, "free"); // El middleware debe consultar el rol real en la BD.
            usuarios.crearSesion(tokenAdmin.jti(), adminId, tokenAdmin.expiraEn());
            assertEquals(200, enviar(cliente, base, "GET", "/api/admin/probe",
                    "session=" + tokenAdmin.valor(), null).statusCode());

            var logout = enviar(cliente, base, "POST", "/api/logout", "session=" + tokenRenovado, "{}");
            assertEquals(200, logout.statusCode());
            assertFalse(usuarios.sesionActiva(jtiUno, idCuentaHttp));
            assertEquals(401, enviar(cliente, base, "GET", "/api/session", "session=" + tokenUno, null).statusCode());
            assertEquals(200, enviar(cliente, base, "GET", "/api/session", "session=" + tokenDos, null).statusCode());

            final Eventos.PasswordResetSolicitado[] pedido = new Eventos.PasswordResetSolicitado[1];
            eventos.on(Eventos.PASSWORD_RESET_REQUESTED, "render-http-reset-" + random, e ->
                    pedido[0] = e.datos(Eventos.PasswordResetSolicitado.class));
            String email = dao.queryOne("SELECT email FROM users WHERE id = ?", idCuentaHttp).get("email").getAsString();
            var olvidada = enviar(cliente, base, "POST", "/api/password/forgot", null,
                    "{\"email\":\"" + email + "\"}");
            var desconocida = enviar(cliente, base, "POST", "/api/password/forgot", null,
                    "{\"email\":\"desconocido-" + random + "@example.test\"}");
            assertEquals(olvidada.body(), desconocida.body());
            assertNotNull(pedido[0]);
            String tokenRecuperacion = pedido[0].enlace().substring(pedido[0].enlace().indexOf("#reset=") + 7);
            var reset = enviar(cliente, base, "POST", "/api/password/reset", null,
                    "{\"token\":\"" + tokenRecuperacion + "\",\"password\":\"NuevaClaveSegura!456\"}");
            assertEquals(200, reset.statusCode());
            assertTrue(reset.headers().allValues("set-cookie").stream().anyMatch(c -> c.startsWith("session=;")));
            assertEquals(401, enviar(cliente, base, "GET", "/api/session", "session=" + tokenDos, null).statusCode());
            assertEquals(400, enviar(cliente, base, "POST", "/api/password/reset", null,
                    "{\"token\":\"" + tokenRecuperacion + "\",\"password\":\"OtraClaveSegura!789\"}").statusCode());
        } finally {
            servidor.stop();
        }
    }

    @Test
    void rateLimitHttpResponde429EnAuthYFinanzasIgnoraXffYEximeWebhook() throws Exception {
        AppConfig rateConfig = new AppConfig(Map.of(
                "RATE_LIMIT_GENERAL_PER_MIN", "4",
                "RATE_LIMIT_FINANCIAL_PER_MIN", "1",
                "RATE_LIMIT_AUTH_PER_MIN", "1",
                "RATE_LIMIT_PROXY_HOPS", "0"));
        Javalin servidor = Javalin.create();
        RateLimitMiddleware.register(servidor, rateConfig, new RateLimiter());
        servidor.post("/api/login", ctx -> ctx.result("login"));
        servidor.post("/api/transaction/create", ctx -> ctx.result("finanzas"));
        servidor.post("/api/wompi/webhook", ctx -> ctx.result("webhook"));
        servidor.get("/api/probe", ctx -> ctx.result("general"));
        int port;
        try (ServerSocket reservador = new ServerSocket(0)) {
            port = reservador.getLocalPort();
        }
        servidor.start("127.0.0.1", port);
        HttpClient cliente = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        try {
            var authPrimera = enviarConXff(cliente, base, "/api/login", "198.51.100.10");
            var authSegunda = enviarConXff(cliente, base, "/api/login", "203.0.113.20");
            assertEquals(200, authPrimera.statusCode());
            assertEquals(429, authSegunda.statusCode(), "Con cero proxies confiables, cambiar XFF no evade el límite.");

            assertEquals(200, enviar(cliente, base, "POST", "/api/transaction/create", null, "{}").statusCode());
            assertEquals(429, enviar(cliente, base, "POST", "/api/transaction/create", null, "{}").statusCode());
            assertEquals(429, enviar(cliente, base, "GET", "/api/probe", null, null).statusCode());
            assertEquals(200, enviar(cliente, base, "POST", "/api/wompi/webhook", null, "{}").statusCode(),
                    "El webhook servidor-a-servidor debe quedar fuera del rate limit por IP.");
        } finally {
            servidor.stop();
        }
    }

    @Test
    void loginEsGenericoYRecuperacionConsumeTokenRevocaSesionesYAplicaCooldown() throws Exception {
        var login = auth.login(" " + dao.queryOne("SELECT email FROM users WHERE id = ?", authUserId)
                .get("email").getAsString().toUpperCase(Locale.ROOT) + " ", "ClaveSegura!123");
        assertEquals(authUserId, login.userId());
        assertFalse(login.user().has("password"));
        String errorExistente = assertThrows(ServicioException.class,
                () -> auth.login("qa-login-invalid@example.test", "incorrecta")).getMessage();
        String errorDesconocido = assertThrows(ServicioException.class,
                () -> auth.login("inexistente@example.test", "incorrecta")).getMessage();
        assertEquals(errorExistente, errorDesconocido);

        JwtServicio jwt = new JwtServicio(CLAVE, 3600);
        var sesionUno = jwt.generarToken(authUserId, "free", "qa-device-one-" + UUID.randomUUID());
        var sesionDos = jwt.generarToken(authUserId, "free", "qa-device-two-" + UUID.randomUUID());
        usuarios.crearSesion(sesionUno.jti(), authUserId, sesionUno.expiraEn());
        usuarios.crearSesion(sesionDos.jti(), authUserId, sesionDos.expiraEn());
        assertTrue(usuarios.sesionActiva(sesionUno.jti(), authUserId));
        assertTrue(usuarios.sesionActiva(sesionDos.jti(), authUserId));
        assertTrue(usuarios.renovarSesion(sesionDos.jti(), authUserId,
                java.time.Instant.now().plusSeconds(7200)));
        assertTrue(usuarios.revocarSesion(sesionUno.jti(), authUserId));
        assertFalse(usuarios.sesionActiva(sesionUno.jti(), authUserId));
        assertTrue(usuarios.sesionActiva(sesionDos.jti(), authUserId));

        final Eventos.PasswordResetSolicitado[] pedido = new Eventos.PasswordResetSolicitado[1];
        eventos.on(Eventos.PASSWORD_RESET_REQUESTED, "render-acceptance-reset", e ->
                pedido[0] = e.datos(Eventos.PasswordResetSolicitado.class));
        String email = dao.queryOne("SELECT email FROM users WHERE id = ?", authUserId).get("email").getAsString();
        auth.solicitarRecuperacionPassword(email);
        assertNotNull(pedido[0]);
        assertTrue(pedido[0].enlace().contains("#reset="));
        long tokens = dao.queryOne("SELECT COUNT(*) AS total FROM password_reset_tokens WHERE user_id = ?", authUserId)
                .get("total").getAsLong();
        assertEquals(1, tokens);
        auth.solicitarRecuperacionPassword(email);
        auth.solicitarRecuperacionPassword("nadie@example.test");
        assertEquals(tokens, dao.queryOne("SELECT COUNT(*) AS total FROM password_reset_tokens WHERE user_id = ?", authUserId)
                .get("total").getAsLong());

        String token = pedido[0].enlace().substring(pedido[0].enlace().indexOf("#reset=") + 7);
        String jti = sesionDos.jti();
        assertTrue(usuarios.sesionActiva(jti, authUserId));
        auth.restablecerPassword(token, "NuevaClaveSegura!456");
        assertFalse(usuarios.sesionActiva(jti, authUserId));
        assertEquals(authUserId, auth.login(email, "NuevaClaveSegura!456").userId());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> auth.restablecerPassword(token, "OtraClaveSegura!789")).getStatus());
    }

    @Test
    void iniciaWompiConReferenciaUnicaYRechazaMontosFueraDeRango() {
        var inicio = finanzas.iniciarWompi(usuarioId, JsonParser.parseString("1000"));
        String referencia = inicio.get("reference").getAsString();
        assertTrue(referencia.startsWith("TF-"));
        assertEquals("pub_test_acceptance", inicio.get("publicKey").getAsString());
        assertEquals(64, inicio.get("signature").getAsString().length());
        assertEquals("pendiente", dao.queryOne("SELECT estado FROM transactions WHERE referencia = ?", referencia)
                .get("estado").getAsString());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> finanzas.iniciarWompi(usuarioId, JsonParser.parseString("999"))).getStatus());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> finanzas.iniciarWompi(usuarioId, JsonParser.parseString("1000.5"))).getStatus());
    }

    @Test
    void laAcreditacionWompiEsAtomicaYNoDuplicaSaldoNiLedger() {
        int idUsuario = usuarioConSaldo(BigDecimal.ZERO);
        var inicio = finanzas.iniciarWompi(idUsuario, JsonParser.parseString("2000"));
        String referencia = inicio.get("reference").getAsString();
        long centavos = inicio.get("amountInCents").getAsLong();
        var primera = wallet.acreditarDepositoWompi(referencia, "qa-wompi-" + UUID.randomUUID(), centavos,
                monto -> centavos);
        assertNotNull(primera);
        assertTrue(primera.isSuccess());
        var duplicada = wallet.acreditarDepositoWompi(referencia, null, centavos, monto -> centavos);
        assertNull(duplicada, "Una segunda entrega no debe volver a acreditar la misma referencia.");
        assertEquals(new BigDecimal("2000.00"), wallet.obtenerSaldo(idUsuario));
        assertEquals(1, dao.queryOne("SELECT COUNT(*) AS total FROM wallet_ledger WHERE user_id = ? AND referencia LIKE ?",
                idUsuario, "%" + referencia + "%").get("total").getAsInt());
    }

    @Test
    void webhookConChecksumInvalidoOMontoDistintoNoAcreditaSaldo() {
        int idUsuario = usuarioConSaldo(BigDecimal.ZERO);
        var inicio = finanzas.iniciarWompi(idUsuario, JsonParser.parseString("1000"));
        String referencia = inicio.get("reference").getAsString();
        long centavos = inicio.get("amountInCents").getAsLong();
        JsonObject webhook = JsonParser.parseString("""
                {"timestamp":"1720000000","data":{"transaction":{"id":"qa-invalid-signature",
                "status":"APPROVED","amount_in_cents":100,"reference":"%s","currency":"COP"}},
                "signature":{"properties":["transaction.id","transaction.status","transaction.amount_in_cents"],
                "checksum":"firma-incorrecta"}}
                """.formatted(referencia)).getAsJsonObject();
        assertEquals(401, assertThrows(ServicioException.class,
                () -> finanzas.procesarWebhookWompi(webhook, null)).getStatus());
        assertEquals(BigDecimal.ZERO.setScale(2), wallet.obtenerSaldo(idUsuario));
        assertEquals(400, assertThrows(ServicioException.class,
                () -> wallet.acreditarDepositoWompi(referencia, "qa-wrong-amount-" + UUID.randomUUID(),
                        centavos + 1, monto -> centavos)).getStatus());
        assertEquals("pendiente", dao.queryOne("SELECT estado FROM transactions WHERE referencia = ?", referencia)
                .get("estado").getAsString());
        assertEquals(BigDecimal.ZERO.setScale(2), wallet.obtenerSaldo(idUsuario));
    }

    @Test
    void retiroDescuentaEnUnaTransaccionYConservaLaEcuacionDelLedger() {
        int idUsuario = usuarioConSaldo(new BigDecimal("25000"));
        BigDecimal saldoNuevo = finanzas.solicitarRetiro(idUsuario, "nequi", "destino de prueba",
                JsonParser.parseString("10000"));
        assertEquals(new BigDecimal("15000.00"), saldoNuevo);
        var fila = dao.queryOne("SELECT saldo_anterior, monto, saldo_posterior FROM wallet_ledger "
                + "WHERE user_id = ? AND tipo = 'retiro' ORDER BY id DESC LIMIT 1", idUsuario);
        assertNotNull(fila);
        assertEquals(0, fila.get("saldo_anterior").getAsBigDecimal()
                .subtract(fila.get("monto").getAsBigDecimal())
                .compareTo(fila.get("saldo_posterior").getAsBigDecimal()));
        assertEquals(400, assertThrows(ServicioException.class,
                () -> finanzas.solicitarRetiro(idUsuario, "nequi", "destino", JsonParser.parseString("20000")))
                .getStatus());
        assertEquals(new BigDecimal("15000.00"), wallet.obtenerSaldo(idUsuario));
    }

    @Test
    void adminApruebaRetiroSinDescontarElSaldoDosVecesYLeeRolDesdeLaBase() {
        int idUsuario = usuarioConSaldo(new BigDecimal("25000"));
        String referencia = "QA-ADMIN-" + UUID.randomUUID();
        finanzas.solicitarRetiro(idUsuario, "nequi", referencia,
                JsonParser.parseString("10000"));
        int transaccionId = dao.queryOne("SELECT id FROM transactions WHERE usuario_id = ? AND tipo = 'retiro' "
                + "AND referencia = ? AND estado = 'pendiente'", idUsuario, referencia).get("id").getAsInt();
        BigDecimal saldoAntesDeAprobar = wallet.obtenerSaldo(idUsuario);
        assertTrue(admin.esAdmin(adminId));
        assertFalse(admin.esAdmin(idUsuario));
        assertEquals("Aprobada", admin.procesarTransaccion(transaccionId, "approve"));
        assertEquals(saldoAntesDeAprobar, wallet.obtenerSaldo(idUsuario));
        assertEquals("completado", dao.queryOne("SELECT estado FROM transactions WHERE id = ?", transaccionId)
                .get("estado").getAsString());
    }

    @Test
    void adminResuelveDisputaYUnSegundoIntentoNoLiquidaDosVeces() {
        int ganador = usuarioConSaldo(new BigDecimal("9000"));
        int perdedor = usuarioConSaldo(new BigDecimal("9000"));
        String nombreGanador = dao.queryOne("SELECT username FROM users WHERE id = ?", ganador)
                .get("username").getAsString();
        String nombrePerdedor = dao.queryOne("SELECT username FROM users WHERE id = ?", perdedor)
                .get("username").getAsString();
        int matchId = dao.insertReturningId("INSERT INTO matches "
                + "(jugador1, jugador2, modo, monto, estado, fecha) "
                + "VALUES (?, ?, 'friendly', 1000, 'disputa', CURRENT_TIMESTAMP) RETURNING id",
                nombreGanador, nombrePerdedor);

        admin.resolverDisputa(matchId, nombreGanador, nombrePerdedor);

        assertEquals("finalizada", dao.queryOne("SELECT estado FROM matches WHERE id = ?", matchId)
                .get("estado").getAsString());
        assertTrue(wallet.obtenerSaldo(ganador).compareTo(new BigDecimal("9000")) > 0);
        assertEquals(new BigDecimal("9000.00"), wallet.obtenerSaldo(perdedor));
        assertEquals(1, dao.queryOne("SELECT total_victorias FROM users WHERE id = ?", ganador)
                .get("total_victorias").getAsInt());
        assertEquals(1, dao.queryOne("SELECT COUNT(*) AS total FROM wallet_ledger "
                + "WHERE user_id = ? AND tipo = 'liquidacion' AND referencia = ?", ganador,
                "Match #" + matchId).get("total").getAsInt());
        assertThrows(ServicioException.class, () -> admin.resolverDisputa(matchId, nombreGanador, nombrePerdedor));
        assertEquals(1, dao.queryOne("SELECT total_victorias FROM users WHERE id = ?", ganador)
                .get("total_victorias").getAsInt());
    }

    @Test
    void soporteAplicaCuotasFiltraPorUsuarioYSoloElAdminPuedeResponder() {
        var primero = soporte.crear(usuarioId, SoporteService.CATEGORIA_PREGUNTA, "<b>Ayuda</b>", "No puedo entrar");
        soporte.crear(usuarioId, SoporteService.CATEGORIA_BUG, "Error", "Se cierra la pantalla");
        assertEquals(429, assertThrows(ServicioException.class,
                () -> soporte.crear(usuarioId, SoporteService.CATEGORIA_OTRO, "Tercero", "Texto")).getStatus());
        int ticketId = primero.getAsJsonObject("ticket").get("id").getAsInt();
        assertEquals(2, soporte.misTickets(usuarioId).size());
        assertTrue(soporte.misTickets(otroUsuarioId).isEmpty());
        assertEquals(403, assertThrows(ServicioException.class,
                () -> soporte.responder(usuarioId, ticketId, "Respuesta")).getStatus());
        soporte.responder(adminId, ticketId, "<script>Resuelto</script>");
        int otroTicketId = soporte.misTickets(usuarioId).stream()
                .filter(t -> t.get("id").getAsInt() != ticketId).findFirst().orElseThrow().get("id").getAsInt();
        soporte.responder(adminId, otroTicketId, "También resuelto");
        assertEquals("Resuelto", soporte.misTickets(usuarioId).stream()
                .filter(t -> t.get("id").getAsInt() == ticketId).findFirst().orElseThrow()
                .get("respuesta").getAsString());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> soporte.responder(adminId, ticketId, "Doble respuesta")).getStatus());
        assertEquals(429, assertThrows(ServicioException.class,
                () -> soporte.crear(usuarioId, SoporteService.CATEGORIA_OTRO, "Cuota diaria", "Texto")).getStatus());
    }

    @Test
    void chatbotDesactivadoEsSeguroYConProveedorFalsoAplicaLimitesPorMinutoYDia() {
        ChatbotService desactivado = new ChatbotService(dao, new ChatbotIaCliente(new AppConfig(Map.of())),
                eventos, new AppConfig(Map.of()), new RateLimiter());
        assertFalse(desactivado.estado(usuarioId).get("disponible").getAsBoolean());
        assertEquals(503, assertThrows(ServicioException.class,
                () -> desactivado.responder(usuarioId, "¿Cómo recargo?")).getStatus());

        AppConfig chatbotConfig = new AppConfig(Map.of(
                "CHATBOT_API_KEY", "fake-only-never-used",
                "CHATBOT_MAX_MENSAJES_POR_MIN", "1",
                "CHATBOT_MAX_MENSAJES_DIA", "2"));
        ChatbotIaCliente proveedorFalso = new ChatbotIaCliente(chatbotConfig) {
            @Override public boolean isDisponible() { return true; }
            @Override public String generar(String instrucciones, String datos, List<Mensaje> conversacion) {
                return "[RESUELTA] Respuesta simulada para aceptación.";
            }
        };
        int usuarioMinuto = usuarioConSaldo(BigDecimal.ZERO);
        ChatbotService porMinuto = new ChatbotService(dao, proveedorFalso, eventos,
                chatbotConfig, new RateLimiter());
        assertTrue(porMinuto.responder(usuarioMinuto, "Primera pregunta de prueba").get("resuelta").getAsBoolean());
        assertEquals(429, assertThrows(ServicioException.class,
                () -> porMinuto.responder(usuarioMinuto, "Segunda pregunta de prueba")).getStatus());

        AppConfig limiteDiario = new AppConfig(Map.of(
                "CHATBOT_API_KEY", "fake-only-never-used",
                "CHATBOT_MAX_MENSAJES_POR_MIN", "5",
                "CHATBOT_MAX_MENSAJES_DIA", "1"));
        ChatbotService porDia = new ChatbotService(dao, proveedorFalso, eventos,
                limiteDiario, new RateLimiter());
        int usuarioDiario = usuarioConSaldo(BigDecimal.ZERO);
        assertTrue(porDia.responder(usuarioDiario, "Pregunta permitida de prueba").get("resuelta").getAsBoolean());
        assertEquals(429, assertThrows(ServicioException.class,
                () -> porDia.responder(usuarioDiario, "Pregunta que supera el día")).getStatus());
    }

    @Test
    void corteLeaderboardPersisteSnapshotPagaJugadoresYAdminsYEsIdempotente() {
        int jugador = usuarioConSaldo(BigDecimal.ZERO);
        dao.update("UPDATE users SET victorias_dia = 1 WHERE id = ?", jugador);
        BigDecimal saldoAdminAntes = wallet.obtenerSaldo(adminId);
        dao.update("UPDATE leaderboard_pools SET dia = 1000 WHERE id = 1");
        String claveCorte = "qa-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        leaderboard.premiarYResetear("dia", "victorias_dia", claveCorte);

        var corte = dao.queryOne("SELECT id, estado FROM leaderboard_cuts WHERE periodo = 'dia' AND clave_corte = ?",
                claveCorte);
        assertNotNull(corte);
        long corteId = corte.get("id").getAsLong();
        assertEquals("completado", corte.get("estado").getAsString());
        assertEquals(BigDecimal.ZERO, dao.queryOne("SELECT dia FROM leaderboard_pools WHERE id = 1")
                .get("dia").getAsBigDecimal());
        assertEquals(0, dao.queryOne("SELECT victorias_dia FROM users WHERE id = ?", jugador)
                .get("victorias_dia").getAsInt());
        assertEquals(new BigDecimal("625.00"), wallet.obtenerSaldo(jugador));
        assertEquals(saldoAdminAntes.add(new BigDecimal("375.00")), wallet.obtenerSaldo(adminId));
        assertEquals(2, dao.queryOne("SELECT COUNT(*) AS total FROM leaderboard_cut_payments WHERE corte_id = ? "
                + "AND estado = 'pagado'", corteId).get("total").getAsInt());
        assertEquals(1, dao.queryOne("SELECT COUNT(*) AS total FROM leaderboard_history "
                + "WHERE user_id = ? AND corte_pago_id IN (SELECT id FROM leaderboard_cut_payments WHERE corte_id = ?)",
                jugador, corteId).get("total").getAsInt());

        leaderboard.premiarYResetear("dia", "victorias_dia", claveCorte);
        assertEquals(new BigDecimal("625.00"), wallet.obtenerSaldo(jugador));
        assertEquals(saldoAdminAntes.add(new BigDecimal("375.00")), wallet.obtenerSaldo(adminId));
        assertEquals(1, dao.queryOne("SELECT COUNT(*) AS total FROM leaderboard_cuts WHERE periodo = 'dia' "
                + "AND clave_corte = ?", claveCorte).get("total").getAsInt());
    }

    @Test
    void sorteoAceptaTicketsDentroDelLimiteYDevuelveLosTicketsAlEliminarlo() {
        dao.update("INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, 3, 0) "
                + "ON CONFLICT (user_id) DO UPDATE SET cantidad = 3, acumulado = 0", usuarioId);
        var sorteo = sorteos.crear("Sorteo QA " + UUID.randomUUID(), "gemas",
                JsonParser.parseString("2000"), JsonParser.parseString("10"));
        int sorteoId = sorteo.getAsJsonObject("sorteo").get("id").getAsInt();
        var participacion = sorteos.participar(usuarioId, sorteoId, 1);
        assertEquals(1, participacion.get("misTicketsEnSorteo").getAsInt());
        assertEquals(2, sorteos.ticketsDeUsuario(usuarioId).get("tickets").getAsInt());
        assertTrue(sorteos.eliminar(sorteoId).contains("tickets devueltos"));
        assertEquals(3, sorteos.ticketsDeUsuario(usuarioId).get("tickets").getAsInt());
    }

    @Test
    void sorteoVencidoNoAceptaParticipacion() {
        dao.update("INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, 2, 0) "
                + "ON CONFLICT (user_id) DO UPDATE SET cantidad = 2", otroUsuarioId);
        int id = sorteos.crear("Sorteo vencido " + UUID.randomUUID(), "pass",
                JsonParser.parseString("1000"), JsonParser.parseString("10"))
                .getAsJsonObject("sorteo").get("id").getAsInt();
        dao.update("UPDATE raffles SET fecha_limite = NOW() - INTERVAL '1 minute' WHERE id = ?", id);
        assertEquals(400, assertThrows(ServicioException.class,
                () -> sorteos.participar(otroUsuarioId, id, 1)).getStatus());
        assertEquals(2, sorteos.ticketsDeUsuario(otroUsuarioId).get("tickets").getAsInt());
    }

    @Test
    void inicioConcurrenteDeLaMismaSalaCobraUnaSolaVez() throws Exception {
        int p1 = usuarioConSaldo(new BigDecimal("10000"));
        int p2 = usuarioConSaldo(new BigDecimal("10000"));
        String nombre1 = dao.queryOne("SELECT username FROM users WHERE id = ?", p1).get("username").getAsString();
        String nombre2 = dao.queryOne("SELECT username FROM users WHERE id = ?", p2).get("username").getAsString();
        String sala = "qa-race-" + UUID.randomUUID();
        dao.update("INSERT INTO active_matches (sala_id, player1_id, player1_username, player2_id, player2_username, "
                + "last_modo, last_dinero) VALUES (?, ?, ?, ?, ?, 'friendly', '1000')",
                sala, p1, nombre1, p2, nombre2);

        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService dosSolicitudes = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<WalletService.InicioPartidaResult> tarea = () -> {
                salida.await();
                return wallet.iniciarPartidaTorneo(p1, nombre1, p2, nombre2, sala, "friendly",
                        new BigDecimal("1000"), "#PQL123", "#PQL124", "2026-10-22T00:00:00Z");
            };
            Future<WalletService.InicioPartidaResult> a = dosSolicitudes.submit(tarea);
            Future<WalletService.InicioPartidaResult> b = dosSolicitudes.submit(tarea);
            salida.countDown();
            var resultadoA = a.get(15, TimeUnit.SECONDS);
            var resultadoB = b.get(15, TimeUnit.SECONDS);
            assertTrue(resultadoA.success);
            assertTrue(resultadoB.success);
            assertEquals(resultadoA.matchId, resultadoB.matchId);
            assertEquals(1, (resultadoA.nuevoInicio ? 1 : 0) + (resultadoB.nuevoInicio ? 1 : 0));
            assertEquals(new BigDecimal("9000.00"), wallet.obtenerSaldo(p1));
            assertEquals(new BigDecimal("9000.00"), wallet.obtenerSaldo(p2));
            assertEquals(1, dao.queryOne("SELECT COUNT(*) AS total FROM matches WHERE sala_id = ?", sala)
                    .get("total").getAsInt());
        } finally {
            dosSolicitudes.shutdownNow();
        }
    }

    @Test
    void inicioDePartidaConSaldoInsuficienteRevierteAmbosSaldos() {
        int p1 = usuarioConSaldo(new BigDecimal("10000"));
        int p2 = usuarioConSaldo(new BigDecimal("500"));
        String nombre1 = dao.queryOne("SELECT username FROM users WHERE id = ?", p1).get("username").getAsString();
        String nombre2 = dao.queryOne("SELECT username FROM users WHERE id = ?", p2).get("username").getAsString();
        String sala = "qa-insufficient-" + UUID.randomUUID();
        dao.update("INSERT INTO active_matches (sala_id, player1_id, player1_username, player2_id, player2_username, "
                + "last_modo, last_dinero) VALUES (?, ?, ?, ?, ?, 'friendly', '1000')",
                sala, p1, nombre1, p2, nombre2);
        var resultado = wallet.iniciarPartidaTorneo(p1, nombre1, p2, nombre2, sala, "friendly",
                new BigDecimal("1000"), "#PQL123", "#PQL124", "2026-10-22T00:00:00Z");
        assertFalse(resultado.success);
        assertEquals(new BigDecimal("10000.00"), wallet.obtenerSaldo(p1));
        assertEquals(new BigDecimal("500.00"), wallet.obtenerSaldo(p2));
        assertEquals(0, dao.queryOne("SELECT COUNT(*) AS total FROM matches WHERE sala_id = ?", sala)
                .get("total").getAsInt());
    }

    @Test
    void inicioDePartidaLegadoSeReconciliaAlReiniciarSinVolverACobrar() {
        int p1 = usuarioConSaldo(new BigDecimal("9000"));
        int p2 = usuarioConSaldo(new BigDecimal("9000"));
        String nombre1 = dao.queryOne("SELECT username FROM users WHERE id = ?", p1).get("username").getAsString();
        String nombre2 = dao.queryOne("SELECT username FROM users WHERE id = ?", p2).get("username").getAsString();
        String sala = "qa-recovery-" + UUID.randomUUID();
        int matchId = dao.insertReturningId("INSERT INTO matches (jugador1, jugador2, modo, monto, estado, fecha) "
                + "VALUES (?, ?, 'friendly', 1000, 'en_curso', CURRENT_TIMESTAMP) RETURNING id", nombre1, nombre2);
        assertTrue(matchId > 0);
        String ref = "Torneo Match #" + matchId;
        dao.update("INSERT INTO wallet_ledger (user_id, tipo, monto, saldo_anterior, saldo_posterior, referencia) "
                + "VALUES (?, 'torneo', 1000, 10000, 9000, ?), (?, 'torneo', 1000, 10000, 9000, ?)",
                p1, ref, p2, ref);
        dao.update("INSERT INTO active_matches (sala_id, player1_id, player1_username, player2_id, player2_username, "
                + "iniciado, match_db_id, player_tag1, player_tag2, last_modo, last_dinero, actualizado_en) "
                + "VALUES (?, ?, ?, ?, ?, FALSE, 0, '#PQL123', '#PQL124', 'friendly', '1000', "
                + "CURRENT_TIMESTAMP - INTERVAL '1 minute')", sala, p1, nombre1, p2, nombre2);

        var reparadas = wallet.reconciliarPartidasTorneoAlReiniciar();
        assertTrue(reparadas.stream().anyMatch(partida -> partida.matchId() == matchId));
        assertEquals(sala, dao.queryOne("SELECT sala_id FROM matches WHERE id = ?", matchId).get("sala_id").getAsString());
        assertEquals(new BigDecimal("9000.00"), wallet.obtenerSaldo(p1));
        assertEquals(new BigDecimal("9000.00"), wallet.obtenerSaldo(p2));
    }

    @Test
    void evidenciaDePagoSoloLaPublicaAdminUnaVezYElUsuarioVeSuHistorial() {
        int transactionId = dao.insertReturningId("INSERT INTO transactions "
                + "(usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado) "
                + "VALUES (?, 'qa', 'retiro', 'nequi', 10000, ?, 'completado') RETURNING id",
                usuarioId, "qa-evidence-" + UUID.randomUUID());
        assertTrue(transactionId > 0);
        String imagen = "data:image/png;base64,AAAA";
        assertEquals(403, assertThrows(ServicioException.class,
                () -> evidencias.publicar(usuarioId, transactionId, imagen, "Comprobante")).getStatus());
        assertTrue(evidencias.publicar(adminId, transactionId, imagen, "<b>pagado</b>").get("success").getAsBoolean());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> evidencias.publicar(adminId, transactionId, imagen, "Duplicado")).getStatus());
        var historial = evidencias.historial(usuarioId);
        assertEquals(1, historial.size());
        assertEquals(EvidenciaPagoService.CANAL, historial.get(0).get("canal").getAsString());
        assertEquals(1, evidencias.historial(adminId).size());
    }

    @Test
    void panelDbOcultaCredencialesYProtegeLosCambiosDeSaldoYLedger() {
        DbAdminService panelDb = new DbAdminService(dao, conexion, config);
        Set<String> tablas = new HashSet<>();
        panelDb.listarTablas().forEach(tabla -> tablas.add(tabla.getAsJsonObject().get("name").getAsString()));
        assertTrue(tablas.contains("users"));
        assertFalse(tablas.contains("user_sessions"));
        assertFalse(tablas.contains("password_reset_tokens"));
        assertFalse(tablas.contains("flyway_schema_history"));

        int idTemporal = usuarioConSaldo(BigDecimal.ZERO);
        JsonArray filasUsuario = panelDb.datosTabla("users").getAsJsonArray("rows");
        JsonObject filaTemporal = null;
        for (var elemento : filasUsuario) {
            JsonObject fila = elemento.getAsJsonObject();
            if (fila.get("id").getAsInt() == idTemporal) filaTemporal = fila;
        }
        assertNotNull(filaTemporal);
        assertEquals("[HASH_PROTEGIDO]", filaTemporal.get("password").getAsString());

        String usernameTemporal = "qa_editado_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        panelDb.actualizarCelda("users", idTemporal, "username", usernameTemporal);
        assertEquals(usernameTemporal, dao.queryOne("SELECT username FROM users WHERE id = ?", idTemporal)
                .get("username").getAsString());
        assertThrows(ServicioException.class, () -> panelDb.actualizarCelda("users", idTemporal,
                "saldo", "999999"));
        assertThrows(ServicioException.class, () -> panelDb.actualizarCelda("wallet_ledger", 1,
                "monto", "1"));
        assertThrows(ServicioException.class, () -> panelDb.datosTabla("user_sessions"));
    }

    @Test
    void panelDbPuedeCrearBackupYRechazaArchivoCorruptoAntesDeRestaurar() throws Exception {
        DbAdminService panelDb = new DbAdminService(dao, conexion, config);
        Path archivoBackup;
        try (PostgresBackupService.ArchivoBackup backup = panelDb.crearBackupCompleto()) {
            archivoBackup = backup.getArchivo();
            assertTrue(backup.getBytes() > 0);
            assertTrue(Files.isRegularFile(archivoBackup));
            assertTrue(Files.size(archivoBackup) > 0);
        }
        assertFalse(Files.exists(archivoBackup), "El artefacto temporal debe borrarse cuando termina la descarga simulada.");

        byte[] corrupto = "archivo-no-es-un-dump-postgresql".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(ServicioException.class, () -> panelDb.restaurarBackupCompleto(
                new ByteArrayInputStream(corrupto), corrupto.length, "RESTAURAR BASE DE DATOS"));
        assertFalse(panelDb.isRestauracionActiva(), "Un archivo inválido debe rechazarse antes de activar mantenimiento.");
    }

    @Test
    void flywayDejaLaUltimaMigracionAplicadaEnElEsquemaTemporal() {
        var historial = dao.queryOne("SELECT COUNT(*) AS total FROM flyway_schema_history WHERE success = TRUE");
        assertNotNull(historial);
        assertTrue(historial.get("total").getAsInt() >= 17);
        assertNotNull(dao.queryOne("SELECT to_regclass(?) AS tabla", esquema + ".users").get("tabla"));
    }

    @Test
    void flywayDetieneUnaMigracionDeReferenciasDuplicadasSinBorrarFilasExistentes() throws Exception {
        String esquemaDuplicados = "tf_qa_mig_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(esquemaDuplicados.matches("tf_qa_mig_[a-f0-9]{32}"));
        String urlEsquema = conEsquema(urlBaseJdbc, esquemaDuplicados);
        try (HikariDataSource scoped = crearPool(urlEsquema, enlaceBase, "torneos-qa-flyway-duplicate")) {
            try (Connection c = scoped.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "CREATE SCHEMA \"" + esquemaDuplicados + "\"")) {
                ps.execute();
            }
            Flyway.configure().dataSource(scoped).schemas(esquemaDuplicados).defaultSchema(esquemaDuplicados)
                    .createSchemas(true).locations("classpath:db/migration").target("11")
                    .validateOnMigrate(true).cleanDisabled(true).load().migrate();
            try (Connection c = scoped.getConnection()) {
                try (PreparedStatement user = c.prepareStatement("INSERT INTO users (username, email) VALUES ('qa-mig', 'qa-mig@example.test')")) {
                    user.executeUpdate();
                }
                try (PreparedStatement dup = c.prepareStatement("INSERT INTO transactions "
                        + "(usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado) "
                        + "VALUES (1, 'qa-mig', 'deposito', 'wompi', 1000, 'TF-QA-DUPLICADA', 'pendiente'), "
                        + "(1, 'qa-mig', 'deposito', 'wompi', 1000, 'TF-QA-DUPLICADA', 'pendiente')")) {
                    dup.executeUpdate();
                }
            }
            FlywayException error = assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(scoped).schemas(esquemaDuplicados).defaultSchema(esquemaDuplicados)
                    .locations("classpath:db/migration").validateOnMigrate(true).cleanDisabled(true).load().migrate());
            assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("migrat")
                    || error.getCause() != null);
            try (Connection c = scoped.getConnection(); PreparedStatement count = c.prepareStatement(
                    "SELECT COUNT(*) FROM transactions WHERE referencia = 'TF-QA-DUPLICADA'")) {
                try (ResultSet rs = count.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "La migración debe conservar las filas para conciliación manual.");
                }
            }
        } finally {
            try (HikariDataSource adminDb = crearPool(urlBaseJdbc, enlaceBase, "torneos-qa-migration-cleanup");
                 Connection c = adminDb.getConnection(); PreparedStatement ps = c.prepareStatement(
                         "DROP SCHEMA IF EXISTS \"" + esquemaDuplicados + "\" CASCADE")) {
                ps.execute();
            }
        }
    }

    private static int crearUsuario(String username, String tipo, BigDecimal saldo) {
        int id = dao.insertReturningId("INSERT INTO users "
                + "(username, email, password, player_tag, telefono, tipo_suscripcion, saldo) "
                + "VALUES (?, ?, 'qa-hash-no-login', ?, '3000000000', ?, ?) RETURNING id",
                username, username + "@example.test", "#" + username.toUpperCase(Locale.ROOT), tipo, saldo);
        assertTrue(id > 0, "No se pudo crear el usuario de aceptación.");
        return id;
    }

    private static int usuarioConSaldo(BigDecimal saldo) {
        String sufijo = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return crearUsuario("qa_fin_" + sufijo, "user", saldo);
    }

    private static HttpResponse<String> enviar(HttpClient cliente, String base, String metodo, String ruta,
                                                String cookie, String cuerpo) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + ruta))
                .timeout(java.time.Duration.ofSeconds(5));
        if (cookie != null) request.header("Cookie", cookie);
        if (cuerpo != null) request.header("Content-Type", "application/json");
        request.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(cuerpo));
        return cliente.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> enviarConXff(HttpClient cliente, String base, String ruta, String xff)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + ruta))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("X-Forwarded-For", xff)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return cliente.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieSession(HttpResponse<?> respuesta) {
        return respuesta.headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith("session="))
                .map(cookie -> {
                    int fin = cookie.indexOf(';');
                    return cookie.substring("session=".length(), fin >= 0 ? fin : cookie.length());
                })
                .filter(token -> !token.isBlank())
                .findFirst().orElse(null);
    }

    private static HikariDataSource crearPool(String jdbcUrl, ConexionDB.EnlaceBD enlace, String nombre) {
        HikariConfig configPool = new HikariConfig();
        configPool.setPoolName(nombre);
        configPool.setJdbcUrl(jdbcUrl);
        if (enlace.usuario != null) configPool.setUsername(enlace.usuario);
        if (enlace.password != null) configPool.setPassword(enlace.password);
        configPool.setMaximumPoolSize(2);
        configPool.setMinimumIdle(0);
        configPool.setConnectionTimeout(10_000);
        return new HikariDataSource(configPool);
    }

    private static String conEsquema(String jdbcUrl, String schema) {
        String sinEsquema = jdbcUrl.replaceAll("(?i)([?&])currentschema=[^&]*", "");
        sinEsquema = sinEsquema.replaceAll("[?&]$", "");
        return sinEsquema + (sinEsquema.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }
}
