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
import com.torneosflash.servicio.AdminService;
import com.torneosflash.servicio.AuthService;
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
import io.javalin.json.JsonMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;

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
        logger.info("📋 Puerto: " + config.getPort());

        // Pools de hilos (A8): HTTP, IO, CPU y temporizadores. Se crean antes que todo lo que los usa.
        Ejecutores ejecutores = new Ejecutores(config);

        // ============================================
        // 2. BASE DE DATOS (PostgreSQL)
        // ============================================
        ConexionDB conexion = ConexionDB.getInstancia(config.getDatabaseUrl(), config.getDbSslMode(), config.getDbPoolMax()); // A8: DB_POOL_MAX_SIZE
        // A5: el esquema lo versiona Flyway (src/main/resources/db/migration). Si una migración falla NO se arranca:
        // un esquema a medias es peor que un deploy caído (Render conserva la versión anterior sirviendo).
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

        // WalletService — centraliza todo movimiento de dinero
        WalletService wallet = new WalletService(conexion, comisiones);

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
        // 4. SOCKET.IO
        // ============================================
        SocketIOServer socketServer = new SocketIOServer(ejecutores.timers());

        // ============================================
        // 5. SERVIDOR HTTP (Javalin)
        // ============================================
        Javalin app = Javalin.create(javalinConfig -> {
            // Pool de hilos de Jetty acotado y con nombre (A8; antes: el default de Javalin, hasta 250 hilos)
            javalinConfig.jetty.threadPool = ejecutores.crearPoolHttp();
            // Límite de tamaño de mensajes WebSocket (WS_MAX_MESSAGE_BYTES, default 50 MB) // A7
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

            // CORS
            javalinConfig.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                    rule.allowCredentials = true;
                });
            });

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

        // ============================================
        // 5.5. RATE LIMITING (S8)
        // Va antes de registrar rutas y sockets para que sea lo primero que corre en cada request.
        // ============================================
        if (config.isRateLimitEnabled()) {
            RateLimitMiddleware.register(app, config, new RateLimiter());
        } else {
            logger.warn("⚠️ Rate limiting DESACTIVADO (RATE_LIMIT_ENABLED=false). No usar así en producción.");
        }

        // ============================================
        // 6. REGISTRAR SOCKET.IO
        // ============================================
        socketServer.register(app);

        // ============================================
        // 7. REGISTRAR RUTAS HTTP
        // ============================================
        // Errores de negocio de la capa de servicio (A3): ServicioException -> {"error": "..."} con su código HTTP
        ManejadorErrores.registrar(app);

        // Capa de servicio (A3): las rutas solo parsean el request y llaman a estos servicios
        NotificadorUsuarios notificador = new NotificadorUsuarios(socketServer, db, pushService);
        AuthService authService = new AuthService(usuarioDAO, db, clashApi, ejecutores);
        FinanzasService finanzasService = new FinanzasService(db, config, wallet, validadorMonto, notificador);
        SorteoService sorteoService = new SorteoService(db, socketServer, correo, pushService, validadorMonto);

        // Bus de eventos (A4): quien termina una acción de negocio emite el evento y estos listeners reaccionan.
        // Se registran ANTES de aceptar tráfico; el orden de registro es el orden en que corren (tickets, luego resultado).
        EventBus eventBus = new EventBus();
        new TicketsPartidaListener(sorteoService, notificador).registrar(eventBus);
        new ResultadoPartidaListener(notificador).registrar(eventBus);

        AdminService adminService = new AdminService(db, usuarioDAO, wallet, eventBus, notificador, socketServer);
        LeaderboardService leaderboardService = new LeaderboardService(db, socketServer, wallet);
        MediaService mediaService = new MediaService(db);
        DbAdminService dbAdminService = new DbAdminService(db);

        RutasAuth.register(app, authService);
        RutasFinanzas.register(app, finanzasService);
        RutasAdmin.register(app, adminService);
        RutasSorteos.register(app, sorteoService);
        RutasLeaderboard.register(app, leaderboardService);
        RutasComisiones.register(app, comisiones, validadorMonto);
        RutasDbAdmin.register(app, dbAdminService, config);
        RutasMedia.register(app, mediaService);

        // ============================================
        // 8. REGISTRAR SOCKET HANDLERS
        // ============================================
        SocketHandler socketHandler = new SocketHandler(db, socketServer, clashApi, pushService, wallet, validadorMonto, eventBus, config, ejecutores);
        socketHandler.registrar();

        // ============================================
        // 9. TAREAS PROGRAMADAS
        // ============================================
        // Temporizadores cortos compartidos (A8). Nada de HTTP externo aquí: eso va al pool de IO.
        ScheduledExecutorService scheduler = ejecutores.timers();

        // Verificar sorteos expirados cada minuto
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Ejecuta el sorteo de los que ya vencieron (se completa con espacios vacíos si no se llenaron)
                sorteoService.ejecutarSorteosExpirados();
            } catch (Exception e) {
                logger.error("Error verificando sorteos expirados: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);

        // Leaderboard reset check cada minuto
        final String[] ultimoReset = { null, null, null, null }; // dia, semana, mes, año
        scheduler.scheduleAtFixedRate(() -> {
            Calendar cal = Calendar.getInstance();
            int hora = cal.get(Calendar.HOUR_OF_DAY);
            int minuto = cal.get(Calendar.MINUTE);
            int diaSemana = cal.get(Calendar.DAY_OF_WEEK);
            int diaDelMes = cal.get(Calendar.DAY_OF_MONTH);
            int mes = cal.get(Calendar.MONTH);
            String hoy = new java.text.SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());

            if (hora == 0 && minuto == 0 && !hoy.equals(ultimoReset[0])) {
                ultimoReset[0] = hoy;
                leaderboardService.premiarYResetear("dia", "victorias_dia");
            }
            if (hora == 0 && minuto == 0 && diaSemana == Calendar.MONDAY && !hoy.equals(ultimoReset[1])) {
                ultimoReset[1] = hoy;
                leaderboardService.premiarYResetear("semana", "victorias_semana");
            }
            if (hora == 0 && minuto == 0 && diaDelMes == 1 && !hoy.equals(ultimoReset[2])) {
                ultimoReset[2] = hoy;
                leaderboardService.premiarYResetear("mes", "victorias_mes");
            }
            if (hora == 0 && minuto == 0 && diaDelMes == 1 && mes == Calendar.JANUARY && !hoy.equals(ultimoReset[3])) {
                ultimoReset[3] = hoy;
                leaderboardService.premiarYResetear("ano", "victorias_ano");
            }
        }, 1, 1, TimeUnit.MINUTES);

        // ============================================
        // SPA Fallback (Para React Router)
        // ============================================
        app.error(404, ctx -> {
            if (!ctx.path().startsWith("/api/")) {
                try {
                    String[] possiblePaths = { "public/index.html", "../public/index.html", "src/main/resources/public/index.html" };
                    for (String path : possiblePaths) {
                        File indexFile = new File(path);
                        if (indexFile.exists()) {
                            ctx.html(new String(java.nio.file.Files.readAllBytes(indexFile.toPath())));
                            return;
                        }
                    }
                } catch (Exception e) {}
            }
        });

        // ============================================
        // 10. INICIAR SERVIDOR
        // ============================================
        app.start(config.getBindHost(), config.getPort()); // A7: BIND_HOST (default 0.0.0.0)
        logger.info("═══════════════════════════════════════════════");
        logger.info("  ✅ Servidor listo en puerto " + config.getPort());
        logger.info("═══════════════════════════════════════════════");

        // Apagado ordenado (A8; Render envía SIGTERM en cada deploy): primero se deja de aceptar tráfico,
        // luego se terminan los pools (push/correos pendientes) y al final se cierra la BD.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("🛑 Apagando servidor...");
            try { app.stop(); } catch (Exception e) { logger.warn("Error deteniendo Javalin: " + e.getMessage()); }
            ejecutores.cerrar();
            conexion.cerrar();
        }, "shutdown-hook"));
    }
}
