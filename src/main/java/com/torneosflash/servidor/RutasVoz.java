package com.torneosflash.servidor;

import com.torneosflash.servicio.VozDiscordService;
import io.javalin.Javalin;

/**
 * Rutas del chat de voz con Discord (roadmap 84).
 *
 * A3: solo parsean el request y llaman a {@link VozDiscordService}. El usuario sale SIEMPRE de la sesión JWT
 * ({@link Peticion#userIdDeSesion}), nunca del body ni de la URL; el servicio responde 401 si no hay sesión y 503 si el
 * chat de voz no está configurado en el servidor.
 *
 * <pre>
 *   GET    /api/voz/estado                 {disponible, vinculado, discordUsername?, servidorUrl?}
 *   POST   /api/voz/discord/vincular       {url}  dirección de Discord donde el usuario autoriza
 *   GET    /api/voz/discord/callback       Discord devuelve aquí al usuario (code + state) y se lo redirige a /match?discord=...
 *   DELETE /api/voz/discord                quita el vínculo
 *   GET    /api/voz/mi-canal               {estado, mensaje, url?}  crea (si falta) y da acceso al canal de voz de su partida
 * </pre>
 */
public class RutasVoz {

    public static void register(Javalin app, VozDiscordService voz) {

        app.get("/api/voz/estado", ctx -> ctx.json(voz.estado(Peticion.userIdDeSesion(ctx))));

        app.post("/api/voz/discord/vincular", ctx -> ctx.json(voz.iniciarVinculacion(Peticion.userIdDeSesion(ctx))));

        // Aquí vuelve el navegador desde Discord. No exige sesión: la identidad sale del `state` firmado por el servidor
        // (si hay sesión, el servicio comprueba que sea del mismo usuario). Siempre termina en una redirección al frontend.
        app.get("/api/voz/discord/callback", ctx -> {
            String resultado;
            if (ctx.queryParam("error") != null) {
                resultado = "cancelado"; // el usuario pulsó «Cancelar» en Discord
            } else {
                resultado = voz.completarVinculacion(ctx.queryParam("code"), ctx.queryParam("state"),
                        Peticion.userIdDeSesion(ctx));
            }
            ctx.redirect(voz.urlResultado(resultado));
        });

        app.delete("/api/voz/discord", ctx -> ctx.json(voz.desvincular(Peticion.userIdDeSesion(ctx))));

        app.get("/api/voz/mi-canal", ctx -> ctx.json(voz.miCanal(Peticion.userIdDeSesion(ctx))));
    }
}
