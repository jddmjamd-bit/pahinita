package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lógica de autenticación y cuentas: registro, login, sesión, disponibilidad de datos y tokens push (A3).
 * No conoce HTTP: las cookies y las respuestas las maneja {@code RutasAuth}.
 */
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    /** Resultado de un login correcto: el id (para la cookie) y los datos públicos del usuario (sin password). */
    public record SesionIniciada(int userId, JsonObject user) {}

    private final UsuarioDAO usuarioDAO;
    private final GenericDAO db;
    private final ClashApiServicio clashApi;
    private final Ejecutores ejecutores;

    public AuthService(UsuarioDAO usuarioDAO, GenericDAO db, ClashApiServicio clashApi, Ejecutores ejecutores) {
        this.usuarioDAO = usuarioDAO;
        this.db = db;
        this.clashApi = clashApi;
        this.ejecutores = ejecutores;
    }

    /** A8: el hash BCrypt gasta CPU, así que corre en el pool de CPU (un hilo por núcleo), no en el hilo HTTP. */
    private String hashear(String password) {
        try {
            return ejecutores.enCpu(() -> BCrypt.hashpw(password, BCrypt.gensalt(10)));
        } catch (Exception e) {
            throw ServicioException.interno("Error interno", e);
        }
    }

    private boolean coincide(String password, String hash) {
        try {
            return ejecutores.enCpu(() -> BCrypt.checkpw(password, hash));
        } catch (Exception e) {
            throw ServicioException.interno("Error interno", e);
        }
    }

    /** Registra un usuario nuevo y devuelve su id. */
    public int registrar(String username, String email, String password, String playerTag, String telefono) {
        username = username.trim();
        email = email.trim().toLowerCase();
        playerTag = playerTag == null ? "" : playerTag.trim().toUpperCase();
        telefono = telefono == null ? "" : telefono.trim();

        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            throw ServicioException.solicitudInvalida("Campos obligatorios vacíos");
        }
        if (password.length() < 6) {
            throw ServicioException.solicitudInvalida("La contraseña debe tener al menos 6 caracteres");
        }
        // S5 (XSS): el username se muestra en chat, rankings y paneles de admin
        if (!ChatSanitizer.esNombreUsuarioSeguro(username)) {
            throw ServicioException.solicitudInvalida(
                    "El nombre de usuario debe tener entre 3 y 30 caracteres y no puede incluir < > \" ' & ` ni caracteres de control");
        }

        // Duplicados
        if (usuarioDAO.buscarPorUsername(username) != null) {
            throw ServicioException.solicitudInvalida("Este nombre de usuario ya está registrado");
        }
        if (usuarioDAO.buscarPorEmailLower(email) != null) {
            throw ServicioException.solicitudInvalida("Este correo ya está registrado");
        }
        if (!playerTag.isEmpty() && usuarioDAO.buscarPorPlayerTag(playerTag) != null) {
            throw ServicioException.solicitudInvalida("Este Player Tag ya está registrado");
        }

        String hash = hashear(password);
        int newId = usuarioDAO.registrar(username, email, hash, playerTag, telefono);
        if (newId < 0) {
            throw ServicioException.solicitudInvalida("Error al registrar");
        }
        return newId;
    }

    /** Verifica credenciales. Los datos que salen hacia el cliente pasan por la lista blanca de columnas (S6). */
    public SesionIniciada login(String emailCrudo, String password) {
        String email = emailCrudo.trim().toLowerCase();

        // S6: el hash vive solo en este objeto de credenciales (nunca se envía al cliente)
        JsonObject credenciales = usuarioDAO.buscarCredencialesPorEmail(email);
        if (credenciales == null) {
            throw ServicioException.solicitudInvalida("Usuario no encontrado");
        }
        if (!coincide(password, credenciales.get("password").getAsString())) {
            throw ServicioException.solicitudInvalida("Contraseña incorrecta");
        }

        int userId = credenciales.get("id").getAsNumber().intValue();
        JsonObject user = usuarioDAO.buscarPorId(userId);
        if (user == null) {
            throw ServicioException.solicitudInvalida("Usuario no encontrado");
        }
        return new SesionIniciada(userId, user);
    }

    /** Datos públicos del usuario de la sesión (lista blanca de columnas, sin password). */
    public JsonObject obtenerSesion(int userId) {
        JsonObject user = usuarioDAO.buscarPorId(userId);
        if (user == null) {
            throw ServicioException.solicitudInvalida("Usuario no encontrado");
        }
        return user;
    }

    public JsonObject usernameDisponible(String username) {
        boolean disponible = usuarioDAO.buscarPorUsername(username) == null;
        return disponibilidad(disponible, "✅ Usuario disponible", "❌ El usuario ya está en uso");
    }

    public JsonObject emailDisponible(String email) {
        boolean disponible = usuarioDAO.buscarPorEmailLower(email) == null;
        return disponibilidad(disponible, "✅ Correo disponible", "❌ El correo ya está registrado");
    }

    private static JsonObject disponibilidad(boolean disponible, String msgSi, String msgNo) {
        JsonObject res = new JsonObject();
        res.addProperty("available", disponible);
        res.addProperty("message", disponible ? msgSi : msgNo);
        return res;
    }

    /** Verifica que el Player Tag no esté registrado y que exista en la API de Clash Royale. */
    public JsonObject verificarTag(String tag) {
        JsonObject res = new JsonObject();

        if (usuarioDAO.buscarPorPlayerTag(tag) != null) {
            res.addProperty("found", false);
            res.addProperty("message", "Este Player Tag ya está registrado");
            return res;
        }

        logger.info("🔍 Buscando Player Tag en la API: {}", tag);
        try {
            JsonObject apiResult = clashApi.verificarTag(tag);
            String name = apiResult.has("name") ? apiResult.get("name").getAsString() : "";
            int trophies = apiResult.has("trophies") ? apiResult.get("trophies").getAsInt() : 0;
            res.addProperty("found", true);
            res.addProperty("name", name);
            res.addProperty("trophies", trophies);
            logger.info("✅ Player Tag encontrado. Usuario: {} (Trophies: {})", name, trophies);
        } catch (Exception e) {
            logger.info("❌ Fallo al buscar Player Tag: {}", e.getMessage());
            res.addProperty("found", false);
            res.addProperty("message", e.getMessage());
        }
        return res;
    }

    /** Guarda el token FCM del dispositivo (idempotente). */
    public void registrarTokenPush(int userId, String token) {
        db.update("INSERT INTO user_tokens (user_id, fcm_token) VALUES (?, ?) " +
                "ON CONFLICT (user_id, fcm_token) DO NOTHING", userId, token);
    }
}
