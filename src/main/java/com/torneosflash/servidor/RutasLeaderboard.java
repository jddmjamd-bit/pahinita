package com.torneosflash.servidor;

import com.torneosflash.servicio.LeaderboardService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;

/**
 * Rutas de leaderboard/rankings.
 *
 * A3: solo parsean el request y llaman a {@link LeaderboardService}.
 * S2: el historial de premios exige sesión JWT y devuelve solo el del usuario autenticado.
 */
public class RutasLeaderboard {

    public static void register(Javalin app, LeaderboardService leaderboard) {

        // GET /api/leaderboard/history (S2: desde sesión JWT)
        app.get("/api/leaderboard/history", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(leaderboard.historial(userId));
        });

        // GET /api/leaderboard/history/:userId (compatibilidad S2: exige sesión y usa el userId autenticado)
        app.get("/api/leaderboard/history/{userId}", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(leaderboard.historial(userId));
        });

        // GET /api/leaderboard/:periodo
        app.get("/api/leaderboard/{periodo}", ctx -> ctx.json(leaderboard.ranking(ctx.pathParam("periodo"))));
    }
}
