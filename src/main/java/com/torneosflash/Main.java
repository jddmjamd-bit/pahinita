package com.torneosflash;

import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.servicio.ClashApiServicio;
import com.torneosflash.servicio.CorreoServicio;
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
 * CONCEPTO POO: Aquí se demuestra Polimorfismo con Collection<Entidad>,
 * uso de ArrayList y HashMap, y cómo todos los componentes se conectan.
 *
 * @author TorneosFlash
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("     🎮 TORNEOS FLASH - Backend Java         ");
        System.out.println("═══════════════════════════════════════════════");

        // ============================================
        // 1. CONFIGURACIÓN
        // ============================================
        AppConfig config = new AppConfig();
        System.out.println("📋 Puerto: " + config.getPort());

        // ============================================
        // 2. BASE DE DATOS (PostgreSQL)
        // ============================================
        ConexionDB conexion = ConexionDB.getInstancia(config.getDatabaseUrl());
        conexion.inicializarTablas();

        // DAOs
        UsuarioDAO usuarioDAO = new UsuarioDAO(conexion);
        GenericDAO db = new GenericDAO(conexion);

        // ============================================
        // 3. SERVICIOS
        // ============================================
        ClashApiServicio clashApi = new ClashApiServicio(config.getClashApiToken());
        if (clashApi.verificarConexionGlobal()) {
            System.out.println("✅ Conectado a la API de Clash Royale.");
        } else {
            System.out.println("❌ Fallo al conectar a la API de Clash Royale. Revisa el log de errores.");
        }

        CorreoServicio correo = new CorreoServicio(config.getGmailUser(), config.getGmailPass());

        // ============================================
        // 4. SOCKET.IO
        // ============================================
        SocketIOServer socketServer = new SocketIOServer();

        // ============================================
        // 5. SERVIDOR HTTP (Javalin)
        // ============================================
        Javalin app = Javalin.create(javalinConfig -> {
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
            String[] possiblePaths = {"public", "../public", "src/main/resources/public"};
            for (String path : possiblePaths) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    final String resolvedPath = dir.getAbsolutePath();
                    javalinConfig.staticFiles.add(staticConfig -> {
                        staticConfig.hostedPath = "/";
                        staticConfig.directory = resolvedPath;
                        staticConfig.location = Location.EXTERNAL;
                    });
                    System.out.println("📁 Sirviendo archivos estáticos desde: " + resolvedPath);
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
        RutasFinanzas.register(app, db, config, socketServer);
        RutasAdmin.register(app, usuarioDAO, db, socketServer);
        RutasSorteos.register(app, db, socketServer, correo);
        RutasLeaderboard.register(app, db, socketServer);
        RutasDbAdmin.register(app, db, config);

        // ============================================
        // 8. REGISTRAR SOCKET HANDLERS
        // ============================================
        SocketHandler socketHandler = new SocketHandler(db, socketServer, clashApi);
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
                    System.out.println("⏰ Sorteo #" + raffleId + " (" + nombre + ") expirado");

                    // Devolver tickets
                    ArrayList<com.google.gson.JsonObject> entries = db.query(
                            "SELECT * FROM raffle_entries WHERE raffle_id = ?", raffleId);
                    for (com.google.gson.JsonObject entry : entries) {
                        int ticketsAsignados = (int) entry.get("tickets_asignados").getAsLong();
                        int userId = (int) entry.get("user_id").getAsLong();
                        db.update("INSERT INTO user_tickets (user_id, cantidad) VALUES (?, ?) " +
                                "ON CONFLICT (user_id) DO UPDATE SET cantidad = user_tickets.cantidad + ?",
                                userId, ticketsAsignados, ticketsAsignados);
                    }

                    db.update("DELETE FROM raffle_entries WHERE raffle_id = ?", raffleId);
                    db.update("UPDATE raffles SET estado = 'expirado', tickets_actuales = 0 WHERE id = ?", raffleId);

                    com.google.gson.JsonObject expData = new com.google.gson.JsonObject();
                    expData.addProperty("raffleId", raffleId);
                    expData.addProperty("nombre", nombre);
                    socketServer.emit("sorteo_expirado", expData);
                }
            } catch (Exception e) {
                System.err.println("Error verificando sorteos expirados: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);

        // Leaderboard reset check cada minuto
        final String[] ultimoReset = {null, null, null, null}; // dia, semana, mes, año
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
        // 10. INICIAR SERVIDOR
        // ============================================
        app.start("0.0.0.0", config.getPort());
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  ✅ Servidor listo en puerto " + config.getPort());
        System.out.println("═══════════════════════════════════════════════");
    }
}
