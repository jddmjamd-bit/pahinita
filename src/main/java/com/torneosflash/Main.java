package com.torneosflash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.servicio.WalletService;
import com.torneosflash.servicio.ClashApiServicio;
import com.torneosflash.servicio.CorreoServicio;
import com.torneosflash.servicio.NotificacionPushServicio;
import com.torneosflash.servicio.ValidadorMonto;
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

        // ============================================
        // 2. BASE DE DATOS (PostgreSQL)
        // ============================================
        ConexionDB conexion = ConexionDB.getInstancia(config.getDatabaseUrl());
        conexion.inicializarTablas();

        // DAOs
        UsuarioDAO usuarioDAO = new UsuarioDAO(conexion);
        GenericDAO db = new GenericDAO(conexion);

        // WalletService — centraliza todo movimiento de dinero
        WalletService wallet = new WalletService(conexion);

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

        CorreoServicio correo = new CorreoServicio(config.getBrevoApiKey(), config.getBrevoSenderEmail());

        // Servicio de Push Notifications (FCM)
        NotificacionPushServicio pushService = new NotificacionPushServicio(
                config.getFirebaseServiceAccount(),
                "https://torneos-beta.onrender.com");

        // ============================================
        // 4. SOCKET.IO
        // ============================================
        SocketIOServer socketServer = new SocketIOServer();

        // ============================================
        // 5. SERVIDOR HTTP (Javalin)
        // ============================================
        Javalin app = Javalin.create(javalinConfig -> {
            // Aumentar límites de WebSocket a 50MB
            javalinConfig.jetty.modifyWebSocketServletFactory(wsFactory -> {
                wsFactory.setMaxTextMessageSize(50_000_000);
                wsFactory.setMaxBinaryMessageSize(50_000_000);
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
        // 6. REGISTRAR SOCKET.IO
        // ============================================
        socketServer.register(app);

        // ============================================
        // 7. REGISTRAR RUTAS HTTP
        // ============================================
        RutasAuth.register(app, usuarioDAO, db, config, clashApi);
        RutasFinanzas.register(app, db, config, socketServer, pushService, wallet, validadorMonto);
        RutasAdmin.register(app, usuarioDAO, db, socketServer, pushService, wallet);
        RutasSorteos.register(app, db, socketServer, correo, pushService, validadorMonto);
        RutasLeaderboard.register(app, db, socketServer, wallet);
        RutasDbAdmin.register(app, db, config);
        RutasMedia.register(app, db);

        // ============================================
        // 8. REGISTRAR SOCKET HANDLERS
        // ============================================
        SocketHandler socketHandler = new SocketHandler(db, socketServer, clashApi, pushService, wallet, validadorMonto);
        socketHandler.registrar();

        // ============================================
        // 9. TAREAS PROGRAMADAS
        // ============================================
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // Verificar sorteos expirados cada minuto
        scheduler.scheduleAtFixedRate(() -> {
            try {
                ArrayList<com.google.gson.JsonObject> expirados = db.query(
                        "SELECT * FROM raffles WHERE estado = 'activo' AND fecha_limite < NOW()");

                for (com.google.gson.JsonObject raffle : expirados) {
                    int raffleId = raffle.get("id").getAsNumber().intValue();
                    String nombre = raffle.get("nombre").getAsString();
                    logger.info("⏰ Sorteo #" + raffleId + " (" + nombre + ") expirado");

                    // Ejecutar el sorteo (se completará con espacios vacíos según la nueva lógica)
                    RutasSorteos.ejecutarSorteo(db, socketServer, correo, raffleId);
                }
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
                RutasLeaderboard.premiarYResetear(db, socketServer, "dia", "victorias_dia");
            }
            if (hora == 0 && minuto == 0 && diaSemana == Calendar.MONDAY && !hoy.equals(ultimoReset[1])) {
                ultimoReset[1] = hoy;
                RutasLeaderboard.premiarYResetear(db, socketServer, "semana", "victorias_semana");
            }
            if (hora == 0 && minuto == 0 && diaDelMes == 1 && !hoy.equals(ultimoReset[2])) {
                ultimoReset[2] = hoy;
                RutasLeaderboard.premiarYResetear(db, socketServer, "mes", "victorias_mes");
            }
            if (hora == 0 && minuto == 0 && diaDelMes == 1 && mes == Calendar.JANUARY && !hoy.equals(ultimoReset[3])) {
                ultimoReset[3] = hoy;
                RutasLeaderboard.premiarYResetear(db, socketServer, "ano", "victorias_ano");
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
        app.start("0.0.0.0", config.getPort());
        logger.info("═══════════════════════════════════════════════");
        logger.info("  ✅ Servidor listo en puerto " + config.getPort());
        logger.info("═══════════════════════════════════════════════");
    }
}
