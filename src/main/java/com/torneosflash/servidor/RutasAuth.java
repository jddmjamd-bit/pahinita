package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.servicio.AuthService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Rutas de autenticación: registro, login, sesión, logout.
 *
 * A3: aquí solo se parsea el request, se maneja la cookie y se llama a
 * {@link AuthService}.
 * Las reglas (validaciones, duplicados, bcrypt, Clash API) viven en el
 * servicio.
 *
 * S1: la sesión es un JWT firmado en la cookie httpOnly {@code session} (ver
 * {@link AuthMiddleware}).
 * La cookie antigua {@code userId} en texto plano ya NO se acepta: solo se
 * borra.
 */
public class RutasAuth {

    /** Rol de respaldo si la sesión no contiene {@code users.tipo_suscripcion}. */
    private static final String ROL_POR_DEFECTO = "free";

    public static void register(Javalin app, AuthService auth) {

        // POST /api/register
        app.post("/api/register", ctx -> {
            JsonObject body = parseBody(ctx);
            auth.registrar(
                    Peticion.texto(body, "username"),
                    Peticion.texto(body, "email"),
                    Peticion.texto(body, "password"),
                    Peticion.textoOpcional(body, "playerTag", ""),
                    Peticion.textoOpcional(body, "telefono", ""),
                    Peticion.booleanoOpcional(body, "aceptaTerminos", false));

            // La respuesta es idéntica si se creó la cuenta o si algún dato ya está en uso.
            // El registro no inicia sesión; el usuario debe usar el formulario de login.
            ctx.status(200).json(successJson(
                    "Solicitud recibida. Si los datos permiten crear una cuenta, podrás iniciar sesión en unos instantes.", 0));
        });

        // POST /api/login
        app.post("/api/login", ctx -> {
            JsonObject body = parseBody(ctx);
            AuthService.SesionIniciada sesion = auth.login(
                    Peticion.texto(body, "email"),
                    Peticion.texto(body, "password"));

            AuthMiddleware.iniciarSesion(ctx, sesion.userId(), rolDe(sesion.user()));

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("user", sesion.user());
            ctx.status(200).contentType("application/json").result(response.toString());
        });

        // GET /api/session
        app.get("/api/session", ctx -> {
            // S1: el userId sale del JWT ya verificado por AuthMiddleware (firma +
            // expiración), nunca de una cookie en claro
            Integer userId = AuthMiddleware.getUserId(ctx);
            if (userId == null) {
                AuthMiddleware.cerrarSesion(ctx); // borra un token vencido/manipulado y la cookie legacy "userId"
                ctx.status(401).json(errorJson("No hay sesión"));
                return;
            }

            JsonObject user;
            try {
                user = auth.obtenerSesion(userId);
            } catch (ServicioException e) {
                if (e.getStatus() >= 500)
                    throw e; // fallo de BD: no se desloguea al usuario por un error temporal
                // Token válido pero la cuenta ya no existe: se cierra la sesión
                AuthMiddleware.cerrarSesion(ctx);
                ctx.status(401).json(errorJson("Sesión inválida"));
                return;
            }

            // Sesión deslizante: se re-emite el token con el rol ACTUAL de la BD (si lo
            // hicieron admin o le quitaron
            // el rol, el JWT se corrige aquí) y con la expiración renovada para quien sigue
            // usando la app.
            AuthMiddleware.iniciarSesion(ctx, userId, rolDe(user));

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("user", user);
            ctx.result(response.toString()).contentType("application/json");
        });

        // POST /api/logout
        app.post("/api/logout", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            JsonObject body = parseBody(ctx);
            if (userId > 0 && body.has("pushTokens") && body.get("pushTokens").isJsonArray()) {
                JsonArray tokens = body.getAsJsonArray("pushTokens");
                for (int i = 0; i < Math.min(tokens.size(), 10); i++) {
                    JsonElement token = tokens.get(i);
                    if (token != null && token.isJsonPrimitive()) {
                        auth.eliminarTokenPush(userId, token.getAsString());
                    }
                }
            }
            AuthMiddleware.cerrarSesion(ctx);
            ctx.json(successJson("Sesión cerrada", 0));
        });

        // GET /api/check-username/:username
        app.get("/api/check-username/{username}", ctx -> ctx.json(auth.usernameDisponible(ctx.pathParam("username"))));

        // GET /api/check-email/:email
        app.get("/api/check-email/{email}", ctx -> ctx.json(auth.emailDisponible(ctx.pathParam("email"))));

        // GET /api/verify-tag/:tag
        app.get("/api/verify-tag/{tag}", ctx -> ctx.json(auth.verificarTag(ctx.pathParam("tag"))));

        // POST /api/register-token (FCM push tokens) — S2: userId siempre desde sesión JWT
        app.post("/api/register-token", ctx -> {
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            JsonObject body = parseBody(ctx);
            if (!body.has("token")) {
                ctx.status(400).json(errorJson("Faltan datos"));
                return;
            }
            auth.registrarTokenPush(userId, Peticion.texto(body, "token"),
                    Peticion.textoOpcional(body, "previousToken", ""));
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
        if (id > 0)
            obj.addProperty("userId", id);
        return obj;
    }

    /**
     * Rol para el JWT: la columna {@code tipo_suscripcion} de la vista de sesión
     * ("free" si falta).
     */
    private static String rolDe(JsonObject user) {
        if (user == null || !user.has("tipo_suscripcion") || user.get("tipo_suscripcion").isJsonNull()) {
            return ROL_POR_DEFECTO;
        }
        String rol = user.get("tipo_suscripcion").getAsString().trim();
        return rol.isEmpty() ? ROL_POR_DEFECTO : rol;
    }
}
