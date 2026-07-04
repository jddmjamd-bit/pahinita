package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOServer;
import com.torneosflash.servicio.CorreoServicio;
import io.javalin.Javalin;
import java.util.*;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de sorteos: tickets, participación, admin CRUD.
 */
public class RutasSorteos {

    public static void register(Javalin app, GenericDAO db, SocketIOServer io, CorreoServicio correo) {

        // GET /api/raffle/tickets/:userId
        app.get("/api/raffle/tickets/{userId}", ctx -> {
            int userId = Integer.parseInt(ctx.pathParam("userId"));
            JsonObject result = db.queryOne("SELECT cantidad FROM user_tickets WHERE user_id = ?", userId);
            JsonObject res = new JsonObject();
            res.addProperty("tickets", result != null ? result.get("cantidad").getAsLong() : 0);
            ctx.json(res);
        });

        // GET /api/raffle/offers
        app.get("/api/raffle/offers", ctx -> {
            String cookieVal = ctx.cookie("userId");
            int userId = 0;
            try { userId = Integer.parseInt(cookieVal); } catch (Exception ignored) {}

            boolean esAdmin = false;
            if (userId > 0) {
                JsonObject user = db.queryOne("SELECT tipo_suscripcion FROM users WHERE id = ?", userId);
                esAdmin = user != null && "admin".equals(user.get("tipo_suscripcion").getAsString());
            }

            ArrayList<JsonObject> raffles;
            if (esAdmin) {
                raffles = db.query("SELECT r.*, COALESCE(e.tickets_asignados, 0) as mis_tickets " +
                        "FROM raffles r LEFT JOIN raffle_entries e ON r.id = e.raffle_id AND e.user_id = ? " +
                        "WHERE r.estado IN ('activo', 'completado') ORDER BY r.estado ASC, r.fecha_limite ASC", userId);
            } else {
                raffles = db.query("SELECT r.*, COALESCE(e.tickets_asignados, 0) as mis_tickets " +
                        "FROM raffles r LEFT JOIN raffle_entries e ON r.id = e.raffle_id AND e.user_id = ? " +
                        "WHERE r.estado = 'activo' OR (r.estado = 'completado' AND r.fecha_completado > NOW() - INTERVAL '1 hour') " +
                        "ORDER BY r.estado ASC, r.fecha_limite ASC", userId);
            }
            ctx.json(raffles);
        });

        // GET /api/raffle/all
        app.get("/api/raffle/all", ctx -> {
            String cookieVal = ctx.cookie("userId");
            int userId = 0;
            try { userId = Integer.parseInt(cookieVal); } catch (Exception ignored) {}
            ctx.json(db.query("SELECT r.*, COALESCE(e.tickets_asignados, 0) as mis_tickets " +
                    "FROM raffles r LEFT JOIN raffle_entries e ON r.id = e.raffle_id AND e.user_id = ? " +
                    "ORDER BY r.fecha_creacion DESC LIMIT 50", userId));
        });

        // POST /api/raffle/participate
        app.post("/api/raffle/participate", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = body.get("userId").getAsInt();
            int raffleId = body.get("raffleId").getAsInt();
            int delta = body.get("ticketsDelta").getAsInt();

            // Verificar sorteo activo
            JsonObject raffle = db.queryOne("SELECT * FROM raffles WHERE id = ? AND estado = 'activo'", raffleId);
            if (raffle == null) { ctx.status(400).json(errorJson("Sorteo no disponible")); return; }

            // Tickets disponibles
            JsonObject ticketsRes = db.queryOne("SELECT cantidad FROM user_tickets WHERE user_id = ?", userId);
            int ticketsDisponibles = ticketsRes != null ? (int) ticketsRes.get("cantidad").getAsLong() : 0;

            // Participación actual
            JsonObject entryRes = db.queryOne("SELECT tickets_asignados FROM raffle_entries WHERE raffle_id = ? AND user_id = ?", raffleId, userId);
            int ticketsActuales = entryRes != null ? (int) entryRes.get("tickets_asignados").getAsLong() : 0;

            int nuevoTotal = ticketsActuales + delta;

            if (nuevoTotal < 0) { ctx.status(400).json(errorJson("No puedes quitar más tickets de los que tienes asignados")); return; }
            if (delta > 0 && ticketsDisponibles < delta) { ctx.status(400).json(errorJson("No tienes suficientes tickets disponibles")); return; }

            int ticketsRestantes = (int) raffle.get("tickets_necesarios").getAsLong() - (int) raffle.get("tickets_actuales").getAsLong();
            if (delta > ticketsRestantes) { ctx.status(400).json(errorJson("Solo quedan " + ticketsRestantes + " espacios")); return; }

            // Actualizar tickets del usuario
            if (delta > 0) {
                db.update("UPDATE user_tickets SET cantidad = cantidad - ? WHERE user_id = ?", delta, userId);
            } else if (delta < 0) {
                db.update("INSERT INTO user_tickets (user_id, cantidad) VALUES (?, ?) " +
                        "ON CONFLICT (user_id) DO UPDATE SET cantidad = user_tickets.cantidad + ?",
                        userId, Math.abs(delta), Math.abs(delta));
            }

            // Actualizar participación
            if (nuevoTotal == 0) {
                db.update("DELETE FROM raffle_entries WHERE raffle_id = ? AND user_id = ?", raffleId, userId);
            } else {
                db.update("INSERT INTO raffle_entries (raffle_id, user_id, tickets_asignados) VALUES (?, ?, ?) " +
                        "ON CONFLICT (raffle_id, user_id) DO UPDATE SET tickets_asignados = ?",
                        raffleId, userId, nuevoTotal, nuevoTotal);
            }

            db.update("UPDATE raffles SET tickets_actuales = tickets_actuales + ? WHERE id = ?", delta, raffleId);

            // Verificar si se completó
            JsonObject updated = db.queryOne("SELECT tickets_actuales, tickets_necesarios FROM raffles WHERE id = ?", raffleId);
            int tActuales = (int) updated.get("tickets_actuales").getAsLong();
            int tNecesarios = (int) updated.get("tickets_necesarios").getAsLong();
            if (tActuales >= tNecesarios) {
                ejecutarSorteo(db, io, correo, raffleId);
            } else {
                JsonObject updateData = new JsonObject();
                updateData.addProperty("raffleId", raffleId);
                updateData.addProperty("tickets_actuales", tActuales);
                io.emit("sorteo_actualizado", updateData);
            }

            JsonObject newTickets = db.queryOne("SELECT cantidad FROM user_tickets WHERE user_id = ?", userId);
            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.addProperty("ticketsUsuario", newTickets != null ? newTickets.get("cantidad").getAsLong() : 0);
            res.addProperty("misTicketsEnSorteo", nuevoTotal);
            res.addProperty("ticketsEnSorteo", tActuales);
            ctx.json(res);
        });

        // POST /api/admin/raffle/create
        app.post("/api/admin/raffle/create", ctx -> {
            JsonObject body = parseBody(ctx);
            String nombre = body.get("nombre").getAsString();
            String categoria = body.get("categoria").getAsString();
            int precio = body.get("precio").getAsInt();
            int duracionMinutos = body.get("duracionMinutos").getAsInt();
            int ticketsNecesarios = (int) Math.ceil(precio / 1000.0);

            int newId = db.insertReturningId("INSERT INTO raffles (nombre, categoria, precio, tickets_necesarios, fecha_limite) " +
                    "VALUES (?, ?, ?, ?, NOW() + INTERVAL '" + duracionMinutos + " minutes') RETURNING id",
                    nombre, categoria, precio, ticketsNecesarios);

            JsonObject nuevoSorteo = db.queryOne("SELECT * FROM raffles WHERE id = ?", newId);
            io.emit("nuevo_sorteo", nuevoSorteo);

            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.add("sorteo", nuevoSorteo);
            ctx.json(res);
        });

        // DELETE /api/admin/raffle/:id
        app.delete("/api/admin/raffle/{id}", ctx -> {
            int raffleId = Integer.parseInt(ctx.pathParam("id"));
            JsonObject sorteo = db.queryOne("SELECT estado FROM raffles WHERE id = ?", raffleId);
            if (sorteo == null) { ctx.status(404).json(errorJson("Sorteo no encontrado")); return; }

            String estado = sorteo.get("estado").getAsString();
            if (!"completado".equals(estado)) {
                // Devolver tickets
                ArrayList<JsonObject> entries = db.query("SELECT * FROM raffle_entries WHERE raffle_id = ?", raffleId);
                for (JsonObject entry : entries) {
                    db.update("INSERT INTO user_tickets (user_id, cantidad) VALUES (?, ?) " +
                            "ON CONFLICT (user_id) DO UPDATE SET cantidad = user_tickets.cantidad + ?",
                            (int) entry.get("user_id").getAsLong(),
                            (int) entry.get("tickets_asignados").getAsLong(),
                            (int) entry.get("tickets_asignados").getAsLong());
                }
            }
            db.update("DELETE FROM raffles WHERE id = ?", raffleId);

            JsonObject deleteData = new JsonObject();
            deleteData.addProperty("raffleId", raffleId);
            io.emit("sorteo_eliminado", deleteData);

            ctx.json(successJson("completado".equals(estado) ? "Sorteo completado eliminado" : "Sorteo eliminado, tickets devueltos", 0));
        });

        // GET /api/admin/raffles
        app.get("/api/admin/raffles", ctx -> {
            ctx.json(db.query("SELECT * FROM raffles ORDER BY fecha_creacion DESC"));
        });
    }

    /**
     * Ejecutar el sorteo cuando se completan los tickets.
     */
    public static void ejecutarSorteo(GenericDAO db, SocketIOServer io, CorreoServicio correo, int raffleId) {
        try {
            JsonObject raffle = db.queryOne("SELECT * FROM raffles WHERE id = ? AND estado = 'activo'", raffleId);
            if (raffle == null) return;

            ArrayList<JsonObject> entries = db.query("SELECT * FROM raffle_entries WHERE raffle_id = ?", raffleId);

            // Pool ponderado
            ArrayList<Integer> pool = new ArrayList<>();
            for (JsonObject entry : entries) {
                int uid = (int) entry.get("user_id").getAsLong();
                int tickets = (int) entry.get("tickets_asignados").getAsLong();
                for (int i = 0; i < tickets; i++) pool.add(uid);
            }

            if (pool.isEmpty()) return;

            // Elegir ganador
            int ganadorId = pool.get(new Random().nextInt(pool.size()));
            JsonObject ganador = db.queryOne("SELECT username, email, telefono, player_tag FROM users WHERE id = ?", ganadorId);
            String ganadorNombre = ganador.get("username").getAsString();

            // Actualizar sorteo
            db.update("UPDATE raffles SET estado = 'completado', ganador_id = ?, ganador_nombre = ?, fecha_completado = NOW() WHERE id = ?",
                    ganadorId, ganadorNombre, raffleId);

            // Notificar admin por correo
            String nombre = raffle.get("nombre").getAsString();
            correo.notificarAdmin("SORTEO COMPLETADO: " + nombre,
                    "Ganador: " + ganadorNombre + " | Email: " + ganador.get("email").getAsString());

            // Notificar por socket
            JsonObject ganadorData = new JsonObject();
            ganadorData.addProperty("raffleId", raffleId);
            ganadorData.addProperty("nombre", nombre);
            ganadorData.addProperty("ganadorNombre", ganadorNombre);
            ganadorData.addProperty("ganadorId", ganadorId);
            io.emit("sorteo_ganador", ganadorData);

            System.out.println("🏆 Sorteo #" + raffleId + " completado. Ganador: " + ganadorNombre);
        } catch (Exception e) {
            System.err.println("Error ejecutando sorteo: " + e.getMessage());
        }
    }

    /**
     * Acumular tickets cuando termina una partida.
     */
    public static int acumularTickets(GenericDAO db, int userId, double montoApostado) {
        try {
            JsonObject ticketRes = db.queryOne("SELECT * FROM user_tickets WHERE user_id = ?", userId);
            if (ticketRes == null) {
                db.update("INSERT INTO user_tickets (user_id, cantidad, acumulado) VALUES (?, 0, 0)", userId);
                ticketRes = db.queryOne("SELECT * FROM user_tickets WHERE user_id = ?", userId);
            }

            int acumuladoAnterior = (int) ticketRes.get("acumulado").getAsLong();
            int nuevoAcumulado = acumuladoAnterior + (int) montoApostado;
            int ticketsAnteriores = acumuladoAnterior / 6000;
            int ticketsGanados = nuevoAcumulado / 6000;
            int residuo = nuevoAcumulado % 6000;
            int ticketsNuevos = ticketsGanados - ticketsAnteriores;

            if (ticketsNuevos > 0) {
                db.update("UPDATE user_tickets SET cantidad = cantidad + ?, acumulado = ? WHERE user_id = ?",
                        ticketsNuevos, residuo, userId);
                return ticketsNuevos;
            } else {
                db.update("UPDATE user_tickets SET acumulado = ? WHERE user_id = ?", nuevoAcumulado, userId);
                return 0;
            }
        } catch (Exception e) {
            System.err.println("Error acumulando tickets: " + e.getMessage());
            return 0;
        }
    }
}
