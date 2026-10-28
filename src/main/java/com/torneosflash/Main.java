package com.torneosflash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.config.AppConfig;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.servicio.WalletService;
import com.torneosflash.servicio.ComisionService;
import com.torneosflash.servicio.ClashApiServicio;
import com.torneosflash.servicio.CorreoServicio;
import com.torneosflash.servicio.NotificacionPushServicio;
import com.torneosflash.servicio.RateLimiter;
import com.torneosflash.servicio.ResultadoPartidaListener;
import com.torneosflash.servicio.TicketsPartidaListener;
import com.torneosflash.servicio.ValidadorMonto;
import com.torneosflash.servicio.JwtServicio;
import com.torneosflash.servicio.AdminService;
import com.torneosflash.servicio.AuthService;
import com.torneosflash.servicio.CifradoTokenJugador;
import com.torneosflash.servicio.DbAdminService;
import com.torneosflash.servicio.FinanzasService;
import com.torneosflash.servicio.LeaderboardService;
import com.torneosflash.servicio.MediaService;
import com.torneosflash.servicio.NotificadorUsuarios;
import com.torneosflash.servicio.SorteoService;
import com.torneosflash.servidor.*;
import com.torneosflash.socketio.SocketIOServer;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.io.File;
import java.net.URI;
import io.javalin.json.JsonMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Type;
import java.util.concurrent.*;
import com.torneosflash.servicio.ChatbotIaCliente;
import com.torneosflash.servicio.ChatbotListener;
import com.torneosflash.servicio.ChatbotService;
import com.torneosflash.servicio.SoporteListener;
import com.torneosflash.servicio.SoporteService;
import com.torneosflash.servicio.EvidenciaPagoListener;
import com.torneosflash.servicio.EvidenciaPagoService;
import com.torneosflash.servicio.DiscordApiCliente;
import com.torneosflash.servicio.VozDiscordService;
import com.torneosflash.servicio.VozListener;
import com.torneosflash.servicio.RecuperacionPasswordListener;

/**
 * Punto de entrada principal de Torneos Flash.
 * Configura el servidor HTTP (Javalin), Socket.IO, base de datos,
 * y registra todas las rutas.
 *
 * Aquí se conectan todos los componentes (DAOs, servicios, rutas y sockets).
 *
 * @author TorneosFlash
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("═══════════════════════════════════════════════");
        logger.info("     🎮 TORNEOS FLASH - Backend Java         ");
        logger.info("═══════════════════════════════════════════════");

        // ============================================
        // 1. CONFIGURACIÓN
        // ============================================
        AppConfig config = new AppConfig();
        // Render define RENDER=true. Las variables obligatorias se validan antes de crear hilos,
        // conexiones o aceptar tráfico; un despliegue incompleto termina con código distinto de cero.
        config.validarConfiguracionProduccion();
        logger.info("📋 Puerto: " + config.getPort());

        // Pools de hilos (A8): HTTP, IO, push, CPU y temporizadores. Se crean antes que todo
        // lo que los usa.
        Ejecutores ejecutores = new Ejecutores(config);

        // ============================================
        // 2. BASE DE DATOS (PostgreSQL)
        // ============================================
        ConexionDB conexion = ConexionDB.getInstancia(config.getDatabaseUrl(), config.getDbSslMode(),
                config.getDbPoolMax()); // A8: DB_POOL_MAX_SIZE
        // A5: el esquema lo versiona Flyway (src/main/resources/db/migration). Si una
        // migración falla NO se arranca:
        // un esquema a medias es peor que un deploy caído (Render conserva la versión
        // anterior sirviendo).
        try {
            conexion.migrar();
        } catch (Exception e) {
            logger.error("❌ Fallaron las migraciones de la BD. El servidor NO arranca: {}", e.getMessage());
            ejecutores.cerrar();
            conexion.cerrar();
            System.exit(1);
        }

        // DAOs
        UsuarioDAO usuarioDAO = new UsuarioDAO(conexion);
        GenericDAO db = new GenericDAO(conexion);

        // ComisionService — única fuente de verdad de la política de comisiones (D4)
        ComisionService comisiones = new ComisionService();

        // Bus de eventos (A4): se crea temprano porque WalletService lo necesita para
        // emitir wallet.deposited, wallet.withdrawn, etc. (D2)
        EventBus eventBus = new EventBus();

        // WalletService — centraliza todo movimiento de dinero (D2)
        WalletService wallet = new WalletService(conexion, comisiones, eventBus);

        // ValidadorMonto — valida en el servidor todo monto que llega del cliente (D5)
        ValidadorMonto validadorMonto = new ValidadorMonto(config);

        // ============================================
        // 3. SERVICIOS
        // ============================================
        ClashApiServicio clashApi = new ClashApiServicio(config.getClashApiToken());
        if (clashApi.verificarConexionGlobal()) {
            logger.info("✅ Conectado a la API de Clash Royale.");
        } else {
            logger.info("❌ Fallo al conectar a la API de Clash Royale. Revisa el log de errores.");
        }

        CorreoServicio correo = new CorreoServicio(config.getBrevoApiKey(), config.getBrevoSenderEmail(), ejecutores);

        // Servicio de Push Notifications (FCM)
        NotificacionPushServicio pushService = new NotificacionPushServicio(
                config.getFirebaseServiceAccount(),
                config.getAppBaseUrl(), // A7: antes la URL estaba escrita aquí
                ejecutores);

        // ============================================
        // 4. SOCKET.IO (A1: netty-socketio)
        // ============================================
        SocketIOServer socketServer = new SocketIOServer(config, ejecutores.timers());

        // ============================================
        // 5. SERVIDOR HTTP (Javalin)
        // ============================================
        Javalin app = Javalin.create(javalinConfig -> {
            // Pool de hilos de Jetty acotado y con nombre (A8; antes: el default de
            // Javalin, hasta 250 hilos)
            javalinConfig.jetty.threadPool = ejecutores.crearPoolHttp();
            // Límite de tamaño de mensajes WebSocket (WS_MAX_MESSAGE_BYTES, default 50 MB)
            // // A7
            javalinConfig.jetty.modifyWebSocketServletFactory(wsFactory -> {
                wsFactory.setMaxTextMessageSize(config.getWsMaxMessageBytes());
                wsFactory.setMaxBinaryMessageSize(config.getWsMaxMessageBytes());
            });

            // Configurar Gson como el Object Mapper oficial
            Gson gson = new GsonBuilder().create();
            javalinConfig.jsonMapper(new JsonMapper() {
                @Override
                public String toJsonString(Object obj, Type type) {
                    if (obj instanceof com.google.gson.JsonElement) {
                        return gson.toJson((com.google.gson.JsonElement) obj);
                    }
                    return gson.toJson(obj);
                }

                @Override
                public <T> T fromJsonString(String json, Type targetType) {
                    return gson.fromJson(json, targetType);
                }
            });

            // A4: el CORS con lista blanca se aplica más abajo en un app.before propio (ver "CORS").
            // El plugin de Javalin devolvía 500 en los archivos estáticos (/assets/*) porque esas peticiones traen
            // Origin/Referer, y dejaba la página en blanco.

            // Servir archivos estáticos (frontend)
            // Buscar carpeta public/ en varios lugares
            String[] possiblePaths = { "public", "../public", "src/main/resources/public" };
            for (String path : possiblePaths) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    final String resolvedPath = dir.getAbsolutePath();
                    javalinConfig.staticFiles.add(staticConfig -> {
                        staticConfig.hostedPath = "/";
                        staticConfig.directory = resolvedPath;
                        staticConfig.location = Location.EXTERNAL;
                    });
                    logger.info("📁 Sirviendo archivos estáticos desde: " + resolvedPath);
                    break;
                }
            }
        });

        // CORS con lista blanca (AppConfig.esOrigenPermitido) + defensa CSRF para peticiones cross-site.
        // Si una petición que modifica estado (POST/PUT/PATCH/DELETE, salvo el webhook servidor-a-servidor de Wompi)
        // viene de un Origin no permitido o con Sec-Fetch-Site: cross-site sin Origin permitido, se bloquea con 403
        // incluso si el navegador no hizo preflight OPTIONS (protege cuando COOKIE_SAMESITE=None en Capacitor).
        app.before(ctx -> {
            String metodo = ctx.req().getMethod();
            String origen = ctx.header("Origin");
            boolean permitido = origen != null && config.esOrigenPermitido(origen);
            if (permitido) {
                ctx.header("Access-Control-Allow-Origin", origen);
                ctx.header("Access-Control-Allow-Credentials", "true");
                ctx.header("Vary", "Origin");
                ctx.header("Access-Control-Expose-Headers", "Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining");
            }
            // Preflight: solo se responde si es un preflight real (trae Access-Control-Request-Method)
            if ("OPTIONS".equalsIgnoreCase(metodo) && ctx.header("Access-Control-Request-Method") != null) {
                if (permitido) {
                    String pedidas = ctx.header("Access-Control-Request-Headers");
                    ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
                    ctx.header("Access-Control-Allow-Headers",
                            pedidas != null && !pedidas.isBlank() ? pedidas : "Content-Type,Authorization,X-DB-Admin-Key");
                    ctx.header("Access-Control-Max-Age", "600");
                    ctx.status(204);
                } else {
                    ctx.status(403);
                }
                ctx.skipRemainingHandlers();
                return;
            }
            boolean modificaEstado = "POST".equalsIgnoreCase(metodo)
                    || "PUT".equalsIgnoreCase(metodo)
                    || "PATCH".equalsIgnoreCase(metodo)
                    || "DELETE".equalsIgnoreCase(metodo);
            if (modificaEstado && !"/api/wompi/webhook".equalsIgnoreCase(ctx.path())) {
                if (origen != null && !permitido) {
                    ctx.status(403).result("{\"error\":\"Origen no permitido\"}");
                    ctx.skipRemainingHandlers();
                    return;
                }
                String fetchSite = ctx.header("Sec-Fetch-Site");
                if (origen == null && "cross-site".equalsIgnoreCase(fetchSite)) {
                    ctx.status(403).result("{\"error\":\"Petición cruzada no permitida\"}");
                    ctx.skipRemainingHandlers();
                }
            }
        });

        // Cabeceras de seguridad globales para toda respuesta HTTP. APP_BASE_URL define
        // el origen público de la API/Socket.IO detrás del proxy TLS.
        String politicaCsp = construirPoliticaCsp(config.getAppBaseUrl());
        boolean sitioHttps = config.getAppBaseUrl().regionMatches(true, 0, "https://", 0, 8);
        app.before(ctx -> {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
            ctx.header("Content-Security-Policy", politicaCsp);
            // HSTS solo se anuncia cuando el origen canónico está configurado con HTTPS.
            // No incluye subdominios: cada uno debe habilitarse cuando también use HTTPS.
            if (sitioHttps) ctx.header("Strict-Transport-Security", "max-age=31536000");
        });

        // ============================================
        // 5.5. RATE LIMITING (S8)
        // Va antes de registrar rutas y sockets para que sea lo primero que corre en
        // cada request.
        // ============================================
        if (config.isRateLimitEnabled()) {
            RateLimitMiddleware.register(app, config, new RateLimiter());
        } else {
            logger.warn("⚠️ Rate limiting DESACTIVADO (RATE_LIMIT_ENABLED=false). No usar así en producción.");
        }

        // ============================================
        // 5.6. SESIÓN JWT (S1)
        // Verifica la cookie httpOnly "session" y su JTI persistido en cada request;
        // inyecta ctx.attribute("userId") y ("rol") solo si no fue revocada.
        // No rechaza nada: cada ruta decide si exige sesión. La cookie antigua "userId"
        // en texto plano ya no se acepta.
        // ============================================
        JwtServicio jwtServicio = new JwtServicio(config.getJwtSecret(), config.getJwtExpirationSeconds());
        AuthMiddleware.register(app, jwtServicio, config, usuarioDAO);
        logger.info("🔐 Sesión JWT activa (cookie httpOnly, SameSite={}, Secure={}, expira en {} días)",
                config.getCookieSameSite(), config.isCookieSecure(), config.getJwtExpirationSeconds() / 86400);

        // ============================================
        // 6. REGISTRAR SOCKET.IO
        // ============================================
        socketServer.register(app);

        // ============================================
        // 7. REGISTRAR RUTAS HTTP
        // ============================================
        // Errores de negocio de la capa de servicio (A3): ServicioException ->
        // {"error": "..."} con su código HTTP
        ManejadorErrores.registrar(app);

        // Capa de servicio (A3): las rutas solo parsean el request y llaman a estos
        // servicios
        NotificadorUsuarios notificador = new NotificadorUsuarios(socketServer, db, pushService);
        CifradoTokenJugador cifradoTokenJugador = new CifradoTokenJugador(config.getJwtSecret());
        AuthService authService = new AuthService(usuarioDAO, db, clashApi, ejecutores, config, eventBus,
                cifradoTokenJugador);
        new RecuperacionPasswordListener(correo, socketServer).registrar(eventBus);
        FinanzasService finanzasService = new FinanzasService(db, config, wallet, validadorMonto, notificador);
        SorteoService sorteoService = new SorteoService(db, socketServer, correo, pushService, validadorMonto);

        // Listeners del bus de eventos (A4): se registran ANTES de aceptar tráfico;
        // el orden de registro es el orden en que corren (tickets, luego resultado).
        new TicketsPartidaListener(sorteoService, notificador).registrar(eventBus);
        new ResultadoPartidaListener(notificador).registrar(eventBus);
        ChatbotIaCliente chatbotIa = new ChatbotIaCliente(config);
        RateLimiter limitadorChatbot = new RateLimiter();
        ChatbotService chatbotService = new ChatbotService(db, chatbotIa, eventBus, config, limitadorChatbot);
        new ChatbotListener(correo, notificador, chatbotService, config).registrar(eventBus);
        // Soporte "Hablar con el admin" (roadmap 24.5): correo al admin al crear un ticket y aviso/correo al usuario al responderlo
        SoporteService soporteService = new SoporteService(db, eventBus, config);
        new SoporteListener(correo, notificador, soporteService, config).registrar(eventBus);
        // Evidencias de pago (roadmap 7): el admin publica el comprobante de un retiro y el listener lo reparte al canal «pagos»
        EvidenciaPagoService evidenciaPagoService = new EvidenciaPagoService(db, eventBus, config);
        new EvidenciaPagoListener(notificador).registrar(eventBus);
        // Chat de voz con Discord (roadmap 84): al empezar una partida se prepara el canal de voz y al terminar se borra.
        // Sin las variables DISCORD_* queda desactivado y no hace nada.
        DiscordApiCliente discordApi = new DiscordApiCliente(config);
        VozDiscordService vozService = new VozDiscordService(db, discordApi, config);
        new VozListener(vozService, ejecutores).registrar(eventBus);
        AdminService adminService = new AdminService(db, usuarioDAO, wallet, eventBus, notificador, socketServer,
                cifradoTokenJugador);
        LeaderboardService leaderboardService = new LeaderboardService(db, socketServer, wallet);
        MediaService mediaService = new MediaService(db);
        DbAdminService dbAdminService = new DbAdminService(db, conexion, config);

        // La guardia global del panel de DB se registra antes de AdminMiddleware para cubrir también
        // las peticiones administrativas de otras instancias durante el mantenimiento.
        RutasDbAdmin.register(app, dbAdminService, config);
        // S3: exige admin (sesión JWT + rol leído de la BD) en /api/admin/*, /api/deposit y /admin-fix-status/*.
        // Va DESPUÉS de AuthMiddleware (necesita el userId de la sesión) y ANTES de registrar el resto de rutas.
        AdminMiddleware.register(app, adminService);

        RutasAuth.register(app, authService);
        RutasFinanzas.register(app, finanzasService);
        RutasAdmin.register(app, adminService, config);
        RutasSorteos.register(app, sorteoService);
        RutasLeaderboard.register(app, leaderboardService);
        RutasComisiones.register(app, comisiones, validadorMonto);
        RutasMedia.register(app, mediaService);
        RutasChatbot.register(app, chatbotService, config, limitadorChatbot);
        RutasSoporte.register(app, soporteService);
        RutasEvidenciaPago.register(app, evidenciaPagoService);
        RutasVoz.register(app, vozService);

        // ============================================
        // 8. REGISTRAR SOCKET HANDLERS
        // ============================================
        SocketHandler socketHandler = new SocketHandler(db, socketServer, clashApi, pushService, wallet, validadorMonto,
                eventBus, config, ejecutores);
        socketHandler.registrar();

        // ============================================
        // 9. TAREAS PROGRAMADAS
        // ============================================
        // Temporizadores cortos compartidos (A8). Nada de HTTP externo aquí: eso va al
        // pool de IO.
        ScheduledExecutorService scheduler = ejecutores.timers();

        // Chat de voz (roadmap 84): cada minuto se borran los canales de voz de partidas ya terminadas o demasiado largas.
        // El borrado habla con Discord (HTTP externo), así que se encola en el pool de IO, nunca corre en el temporizador.
        if (config.hasDiscord()) {
            scheduler.scheduleAtFixedRate(
                    () -> ejecutores.ejecutarIO("limpieza de canales de voz", vozService::limpiarVencidos),
                    1, 1, TimeUnit.MINUTES);
        }

        // Verificar sorteos expirados cada minuto
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Ejecuta el sorteo de los que ya vencieron (se completa con espacios vacíos si
                // no se llenaron)
                sorteoService.ejecutarSorteosExpirados();
            } catch (Exception e) {
                logger.error("Error verificando sorteos expirados: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);

        // C7: Verificar cierres del leaderboard cada minuto (hora Colombia America/Bogota, persistido en BD)
        scheduler.scheduleAtFixedRate(leaderboardService::verificarYCerrarPeriodos, 1, 1, TimeUnit.MINUTES);

        // ============================================
        // SPA Fallback (Para React Router)
        // ============================================
        app.error(404, ctx -> {
            if (!ctx.path().startsWith("/api/")) {
                try {
                    String[] possiblePaths = { "public/index.html", "../public/index.html",
                            "src/main/resources/public/index.html" };
                    for (String path : possiblePaths) {
                        File indexFile = new File(path);
                        if (indexFile.exists()) {
                            ctx.html(new String(java.nio.file.Files.readAllBytes(indexFile.toPath())));
                            return;
                        }
                    }
                } catch (Exception e) {
                }
            }
        });

        // ============================================
        // 10. INICIAR SERVIDOR
        // ============================================
        app.start(config.getBindHost(), config.getPort()); // A7: BIND_HOST (default 0.0.0.0)
        logger.info("═══════════════════════════════════════════════");
        logger.info("  ✅ Servidor listo en puerto " + config.getPort());
        logger.info("═══════════════════════════════════════════════");

        // Apagado ordenado (A8; Render envía SIGTERM en cada deploy): primero se deja
        // de aceptar tráfico,
        // luego se terminan los pools (push/correos pendientes) y al final se cierra la
        // BD.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("🛑 Apagando servidor...");
            try {
                socketServer.stop();
            } catch (Exception e) {
                logger.warn("Error deteniendo Socket.IO: " + e.getMessage());
            }
            try {
                app.stop();
            } catch (Exception e) {
                logger.warn("Error deteniendo Javalin: " + e.getMessage());
            }
            ejecutores.cerrar();
            conexion.cerrar();
        }, "shutdown-hook"));
    }

    private static String construirPoliticaCsp(String appBaseUrl) {
        URI appUri = URI.create(appBaseUrl);
        String appOrigen = appUri.getScheme() + "://" + appUri.getRawAuthority();
        String socketOrigen = ("https".equalsIgnoreCase(appUri.getScheme()) ? "wss" : "ws")
                + "://" + appUri.getRawAuthority();
        return "default-src 'self'; "
                + "base-uri 'self'; object-src 'none'; frame-ancestors 'none'; "
                + "form-action 'self' https://*.wompi.co; "
                + "script-src 'self' https://checkout.wompi.co; "
                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                + "font-src 'self' data: https://fonts.gstatic.com; "
                + "img-src 'self' data: blob:; media-src 'self' blob:; "
                + "connect-src 'self' " + appOrigen + " " + socketOrigen
                + " https://*.wompi.co; "
                + "frame-src 'self' https://www.youtube-nocookie.com https://*.wompi.co; "
                + "worker-src 'self' blob:";
    }
}
