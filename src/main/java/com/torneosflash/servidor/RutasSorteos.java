package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.servicio.SorteoService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.*;
import com.torneosflash.servicio.ServicioException;

/**
 * Rutas de sorteos: tickets, participación, admin CRUD.
 *
 * A3: solo parsean el request y llaman a {@link SorteoService}.
 */
public class RutasSorteos {

    public static void register(Javalin app, SorteoService sorteos) {

        // F1 / S2: GET /api/raffle/tickets (toma siempre el userId de la sesión JWT; usado por Sorteos.jsx)
        app.get("/api/raffle/tickets", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(sorteos.ticketsDeUsuario(userId));
        });

        // GET /api/raffle/tickets/:userId (compatibilidad: exige sesión y devuelve solo los tickets propios)
        app.get("/api/raffle/tickets/{userId}", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(sorteos.ticketsDeUsuario(userId));
        });

        // GET /api/raffle/pool
        app.get("/api/raffle/pool", ctx -> ctx.json(sorteos.pozo()));

        // GET /api/raffle/poll
        app.get("/api/raffle/poll", ctx -> ctx.json(sorteos.encuesta(Peticion.userIdDeSesion(ctx))));

        // POST /api/raffle/vote
        app.post("/api/raffle/vote", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0)
                throw ServicioException.noAutenticado("Inicia sesión");
            sorteos.votar(userId, Peticion.texto(body, "categoria"));
            ctx.json(successJson("Voto registrado", 0));
        });

        // DELETE /api/admin/raffle/poll
        app.delete("/api/admin/raffle/poll", ctx -> {
            sorteos.reiniciarEncuesta();
            ctx.json(successJson("Encuesta reiniciada", 0));
        });

        // GET /api/raffle/offers
        app.get("/api/raffle/offers", ctx -> ctx.json(sorteos.ofertas(Peticion.userIdDeSesion(ctx))));

        // GET /api/raffle/all
        app.get("/api/raffle/all", ctx -> ctx.json(sorteos.todos(Peticion.userIdDeSesion(ctx))));

        // POST /api/raffle/participate
        app.post("/api/raffle/participate", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0)
                throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(sorteos.participar(
                    userId,
                    Peticion.entero(body, "raffleId"),
                    Peticion.entero(body, "ticketsDelta")));
        });

        // POST /api/admin/raffle/create
        app.post("/api/admin/raffle/create", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(sorteos.crear(
                    Peticion.texto(body, "nombre"),
                    Peticion.texto(body, "categoria"),
                    body.get("precio"),
                    body.get("duracionMinutos")));
        });

        // DELETE /api/admin/raffle/:id
        app.delete("/api/admin/raffle/{id}", ctx -> {
            String mensaje = sorteos.eliminar(Peticion.pathEntero(ctx, "id"));
            ctx.json(successJson(mensaje, 0));
        });

        // GET /api/admin/raffles
        app.get("/api/admin/raffles", ctx -> ctx.json(sorteos.listarAdmin()));
    }
}
