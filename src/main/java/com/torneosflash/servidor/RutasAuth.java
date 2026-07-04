package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Rutas de autenticación: registro, login, sesión, logout.
 * Equivalente a las rutas /api/register, /api/login, etc. en index.js
 */
public class RutasAuth {

    public static void register(Javalin app, UsuarioDAO usuarioDAO, GenericDAO db, AppConfig config,
                                 com.torneosflash.servicio.ClashApiServicio clashApi) {

        // POST /api/register
        app.post("/api/register", ctx -> {
            JsonObject body = parseBody(ctx);
            String username = body.get("username").getAsString().trim();
            String email = body.get("email").getAsString().trim().toLowerCase();
            String password = body.get("password").getAsString();
            String playerTag = body.has("playerTag") ? body.get("playerTag").getAsString().trim().toUpperCase() : "";
            String telefono = body.has("telefono") ? body.get("telefono").getAsString().trim() : "";

            // Validaciones
            if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                ctx.json(errorJson("Campos obligatorios vacíos"));
                return;
            }
            if (password.length() < 6) {
                ctx.json(errorJson("La contraseña debe tener al menos 6 caracteres"));
                return;
            }

            // Verificar duplicados
            if (usuarioDAO.buscarPorUsername(username) != null) {
                ctx.json(errorJson("Este nombre de usuario ya está registrado"));
                return;
            }
            if (usuarioDAO.buscarPorEmailLower(email) != null) {
                ctx.json(errorJson("Este correo ya está registrado"));
                return;
            }
            if (!playerTag.isEmpty() && usuarioDAO.buscarPorPlayerTag(playerTag) != null) {
                ctx.json(errorJson("Este Player Tag ya está registrado"));
                return;
            }

            // Hash password
            String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));

            // Insertar
            int newId = usuarioDAO.registrar(username, email, hash, playerTag, telefono);
            if (newId < 0) {
                ctx.json(errorJson("Error al registrar"));
                return;
            }

            // Setear cookie de sesión
            ctx.cookie("userId", String.valueOf(newId), 365 * 24 * 3600);
            ctx.json(successJson("Registrado exitosamente", newId));
        });

        // POST /api/login
        app.post("/api/login", ctx -> {
            JsonObject body = parseBody(ctx);
            String email = body.get("email").getAsString().trim().toLowerCase();
            String password = body.get("password").getAsString();

            JsonObject user = usuarioDAO.buscarPorEmail(email);
            if (user == null) {
                ctx.json(errorJson("Usuario no encontrado"));
                return;
            }

            String storedHash = user.get("password").getAsString();
            if (!BCrypt.checkpw(password, storedHash)) {
                ctx.json(errorJson("Contraseña incorrecta"));
                return;
            }

            int userId = user.get("id").getAsNumber().intValue();
            ctx.cookie("userId", String.valueOf(userId), 365 * 24 * 3600);

            // No enviar password al frontend
            user.remove("password");
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("user", user);
            ctx.json(response);
        });

        // GET /api/session
        app.get("/api/session", ctx -> {
            String cookieVal = ctx.cookie("userId");
            if (cookieVal == null || cookieVal.isEmpty()) {
                ctx.json(errorJson("No hay sesión"));
                return;
            }
            int userId;
            try { userId = Integer.parseInt(cookieVal); } catch (Exception e) {
                ctx.json(errorJson("Sesión inválida"));
                return;
            }

            JsonObject user = usuarioDAO.buscarPorId(userId);
            if (user == null) {
                ctx.json(errorJson("Usuario no encontrado"));
                return;
            }
            user.remove("password");
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("user", user);
            ctx.json(response);
        });

        // POST /api/logout
        app.post("/api/logout", ctx -> {
            ctx.removeCookie("userId");
            ctx.json(successJson("Sesión cerrada", 0));
        });

        // GET /api/check-username/:username
        app.get("/api/check-username/{username}", ctx -> {
            String username = ctx.pathParam("username");
            JsonObject existing = usuarioDAO.buscarPorUsername(username);
            JsonObject res = new JsonObject();
            res.addProperty("exists", existing != null);
            ctx.json(res);
        });

        // GET /api/check-email/:email
        app.get("/api/check-email/{email}", ctx -> {
            String email = ctx.pathParam("email");
            JsonObject existing = usuarioDAO.buscarPorEmailLower(email);
            JsonObject res = new JsonObject();
            res.addProperty("exists", existing != null);
            ctx.json(res);
        });

        // GET /api/verify-tag/:tag
        app.get("/api/verify-tag/{tag}", ctx -> {
            String tag = ctx.pathParam("tag");

            // Verificar si ya está registrado
            JsonObject existing = usuarioDAO.buscarPorPlayerTag(tag);
            if (existing != null) {
                JsonObject res = new JsonObject();
                res.addProperty("found", false);
                res.addProperty("message", "Este Player Tag ya está registrado");
                ctx.json(res);
                return;
            }

            // Verificar en API de Clash
            System.out.println("🔍 Buscando Player Tag en la API: " + tag);
            try {
                JsonObject apiResult = clashApi.verificarTag(tag);
                JsonObject res = new JsonObject();
                res.addProperty("found", true);
                String name = apiResult.has("name") ? apiResult.get("name").getAsString() : "";
                int trophies = apiResult.has("trophies") ? apiResult.get("trophies").getAsInt() : 0;
                res.addProperty("name", name);
                res.addProperty("trophies", trophies);
                System.out.println("✅ Player Tag encontrado. Usuario: " + name + " (Trophies: " + trophies + ")");
                ctx.json(res);
            } catch (Exception e) {
                System.out.println("❌ Fallo al buscar Player Tag: " + e.getMessage());
                JsonObject res = new JsonObject();
                res.addProperty("found", false);
                res.addProperty("message", e.getMessage());
                ctx.json(res);
            }
        });

        // POST /api/register-token (FCM push tokens)
        app.post("/api/register-token", ctx -> {
            JsonObject body = parseBody(ctx);
            if (!body.has("userId") || !body.has("token")) {
                ctx.json(errorJson("Faltan datos"));
                return;
            }
            int userId = body.get("userId").getAsInt();
            String token = body.get("token").getAsString();

            db.update("INSERT INTO user_tokens (user_id, fcm_token) VALUES (?, ?) " +
                    "ON CONFLICT (user_id, fcm_token) DO NOTHING", userId, token);
            ctx.json(successJson("Token registrado", 0));
        });
    }

    // --- Helpers ---
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
}
