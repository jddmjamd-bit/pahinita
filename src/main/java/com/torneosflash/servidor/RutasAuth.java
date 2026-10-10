package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.servicio.AuthService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.servlet.http.Cookie;

/**
 * Rutas de autenticación: registro, login, sesión, logout.
 *
 * A3: aquí solo se parsea el request, se maneja la cookie y se llama a {@link AuthService}.
 * Las reglas (validaciones, duplicados, bcrypt, Clash API) viven en el servicio.
 */
public class RutasAuth {

    private static final int COOKIE_SEGUNDOS = 365 * 24 * 3600;

    public static void register(Javalin app, AuthService auth) {

        // POST /api/register
        app.post("/api/register", ctx -> {
            JsonObject body = parseBody(ctx);
            int newId = auth.registrar(
                    Peticion.texto(body, "username"),
                    Peticion.texto(body, "email"),
                    Peticion.texto(body, "password"),
                    Peticion.textoOpcional(body, "playerTag", ""),
                    Peticion.textoOpcional(body, "telefono", ""));

            // Cookie de sesión (directo por servlet, evita bug Javalin 6 + Java 17)
            setCookieDirect(ctx, "userId", String.valueOf(newId), COOKIE_SEGUNDOS);
            ctx.result(successJson("Registrado exitosamente", newId).toString()).contentType("application/json");
        });

        // POST /api/login
        app.post("/api/login", ctx -> {
            JsonObject body = parseBody(ctx);
            AuthService.SesionIniciada sesion = auth.login(
                    Peticion.texto(body, "email"),
                    Peticion.texto(body, "password"));

            setCookieDirect(ctx, "userId", String.valueOf(sesion.userId()), COOKIE_SEGUNDOS);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("user", sesion.user());
            ctx.status(200).contentType("application/json").result(response.toString());
        });

        // GET /api/session
        app.get("/api/session", ctx -> {
            String cookieVal = getCookieDirect(ctx, "userId");
            if (cookieVal == null || cookieVal.isEmpty()) {
                ctx.status(400).json(errorJson("No hay sesión"));
                return;
            }
            int userId;
            try {
                userId = Integer.parseInt(cookieVal);
            } catch (Exception e) {
                ctx.status(400).json(errorJson("Sesión inválida"));
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("user", auth.obtenerSesion(userId));
            ctx.result(response.toString()).contentType("application/json");
        });

        // POST /api/logout
        app.post("/api/logout", ctx -> {
            setCookieDirect(ctx, "userId", "", 0);
            ctx.json(successJson("Sesión cerrada", 0));
        });

        // GET /api/check-username/:username
        app.get("/api/check-username/{username}", ctx ->
                ctx.json(auth.usernameDisponible(ctx.pathParam("username"))));

        // GET /api/check-email/:email
        app.get("/api/check-email/{email}", ctx ->
                ctx.json(auth.emailDisponible(ctx.pathParam("email"))));

        // GET /api/verify-tag/:tag
        app.get("/api/verify-tag/{tag}", ctx ->
                ctx.json(auth.verificarTag(ctx.pathParam("tag"))));

        // POST /api/register-token (FCM push tokens)
        app.post("/api/register-token", ctx -> {
            JsonObject body = parseBody(ctx);
            if (!body.has("userId") || !body.has("token")) {
                ctx.status(400).json(errorJson("Faltan datos"));
                return;
            }
            auth.registrarTokenPush(Peticion.entero(body, "userId"), Peticion.texto(body, "token"));
            ctx.json(successJson("Token registrado", 0));
        });
    }

    // --- Helpers HTTP (los usan también las demás rutas) ---
    static JsonObject parseBody(Context ctx) {
        try {
            return JsonParser.parseString(ctx.body()).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    static JsonObject errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj;
    }

    static JsonObject successJson(String message, int id) {
        JsonObject obj = new JsonObject();
        obj.addProperty("success", true);
        obj.addProperty("message", message);
        if (id > 0) obj.addProperty("userId", id);
        return obj;
    }

    // --- Cookie helpers (bypass Javalin 6 bug con Java 17) ---
    private static void setCookieDirect(Context ctx, String name, String value, int maxAge) {
        Cookie c = new Cookie(name, value);
        c.setPath("/");
        c.setMaxAge(maxAge);
        ctx.res().addCookie(c);
    }

    private static String getCookieDirect(Context ctx, String name) {
        Cookie[] cookies = ctx.req().getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (name.equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }
}
