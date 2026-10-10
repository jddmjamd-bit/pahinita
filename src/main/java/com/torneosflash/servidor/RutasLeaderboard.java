package com.torneosflash.servidor;

import com.torneosflash.servicio.LeaderboardService;
import io.javalin.Javalin;

/**
 * Rutas de leaderboard/rankings.
 *
 * A3: solo parsean el request y llaman a {@link LeaderboardService}. El cálculo de premios y el reset
 * periódico (que dispara el scheduler de {@code Main}) viven en el servicio.
 */
public class RutasLeaderboard {

    public static void register(Javalin app, LeaderboardService leaderboard) {

        // GET /api/leaderboard/:periodo
        app.get("/api/leaderboard/{periodo}", ctx -> ctx.json(leaderboard.ranking(ctx.pathParam("periodo"))));

        // GET /api/leaderboard/history/:userId
        app.get("/api/leaderboard/history/{userId}", ctx ->
                ctx.json(leaderboard.historial(Peticion.pathEntero(ctx, "userId"))));
    }
}
