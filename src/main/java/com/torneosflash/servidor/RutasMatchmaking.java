package com.torneosflash.servidor;

import com.torneosflash.servicio.MatchmakingService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;

/** API autenticada del sistema de matchmaking justo (Prioridad 15). */
public final class RutasMatchmaking {

    private RutasMatchmaking() {}

    public static void register(Javalin app, MatchmakingService matchmaking) {
        app.get("/api/matchmaking/estado", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(matchmaking.estadoMatchmakingUsuario(userId));
        });
    }
}
