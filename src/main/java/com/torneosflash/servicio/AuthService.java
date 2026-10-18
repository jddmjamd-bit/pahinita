package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Lógica de autenticación y cuentas: registro, login, sesión, disponibilidad de
 * datos y tokens push (A3).
 * No conoce HTTP: las cookies y las respuestas las maneja {@code RutasAuth}.
 */
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private static final Pattern PLAYER_TAG_VALIDO = Pattern.compile("^#[0289PYLQGRJCUV]{3,15}$");
    private static final Pattern EMAIL_VALIDO = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]+$");

    /**
     * Resultado de un login correcto: el id (para la cookie) y los datos públicos
     * del usuario (sin password).
     */
    public record SesionIniciada(int userId, JsonObject user) {
    }

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

    /**
     * A8: el hash BCrypt gasta CPU, así que corre en el pool de CPU (un hilo por
     * núcleo), no en el hilo HTTP.
     */
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
    public int registrar(String username, String email, String password, String playerTag, String telefono,
            boolean aceptaTerminos) { // ← CAMBIA: parámetro nuevo al final
        // ↓↓↓ NUEVO (roadmap 1.6) ↓↓↓
        if (!aceptaTerminos) {
            throw ServicioException.solicitudInvalida(
                    "Debes aceptar los Términos de Servicio y la Política de Privacidad para crear tu cuenta");
        }
        // ↑↑↑ NUEVO ↑↑↑
        username = username.trim();
        email = email.trim().toLowerCase();
        playerTag = playerTag == null ? "" : playerTag.trim().toUpperCase();
        if (!playerTag.isEmpty() && !playerTag.startsWith("#")) {
            playerTag = "#" + playerTag;
        }
        telefono = telefono == null ? "" : ChatSanitizer.limpiarTexto(telefono.trim(), 30);

        if (username.isEmpty() || email.isEmpty() || password.isEmpty() || playerTag.isEmpty()) {
            throw ServicioException.solicitudInvalida("Campos obligatorios vacíos");
        }
        if (email.length() > 120 || !EMAIL_VALIDO.matcher(email).matches()) {
            throw ServicioException.solicitudInvalida("El formato del correo no es válido");
        }
        if (!PLAYER_TAG_VALIDO.matcher(playerTag).matches()) {
            throw ServicioException.solicitudInvalida("El Player Tag no tiene un formato válido de Clash Royale (#XXXXXXX)");
        }
        if (password.length() < 6 || password.length() > 128) {
            throw ServicioException.solicitudInvalida("La contraseña debe tener entre 6 y 128 caracteres");
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
        if (usuarioDAO.buscarPorPlayerTag(playerTag) != null) {
            throw ServicioException.solicitudInvalida("Este Player Tag ya está registrado");
        }

        String hash = hashear(password);
        // ← CAMBIA la línea de abajo (antes: usuarioDAO.registrar(username, email,
        // hash, playerTag, telefono))
        int newId = usuarioDAO.registrarConAceptacionLegal(username, email, hash, playerTag, telefono,
                DocumentosLegales.TERMINOS_VERSION, DocumentosLegales.PRIVACIDAD_VERSION);
        if (newId < 0) {
            throw ServicioException.solicitudInvalida("Error al registrar");
        }
        return newId;
    }

    /**
     * Verifica credenciales. Los datos que salen hacia el cliente pasan por la
     * lista blanca de columnas (S6).
     */
    public SesionIniciada login(String emailCrudo, String password) {
        String email = emailCrudo.trim().toLowerCase();

        // S6: el hash vive solo en este objeto de credenciales (nunca se envía al
        // cliente)
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

    /**
     * Datos públicos del usuario de la sesión (lista blanca de columnas, sin
     * password).
     */
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

    /**
     * Verifica que el Player Tag tenga formato válido, no esté registrado y exista en la API de Clash Royale.
     * M5: nunca devuelve al cliente el mensaje crudo de error de Clash API (que puede incluir la IP del servidor).
     */
    public JsonObject verificarTag(String tag) {
        JsonObject res = new JsonObject();
        String normalizado = tag == null ? "" : tag.trim().toUpperCase();
        if (!normalizado.startsWith("#")) {
            normalizado = "#" + normalizado;
        }
        if (!PLAYER_TAG_VALIDO.matcher(normalizado).matches()) {
            res.addProperty("found", false);
            res.addProperty("message", "Formato de Player Tag inválido");
            return res;
        }

        if (usuarioDAO.buscarPorPlayerTag(normalizado) != null) {
            res.addProperty("found", false);
            res.addProperty("message", "Este Player Tag ya está registrado");
            return res;
        }

        logger.info("🔍 Buscando Player Tag en la API: {}", normalizado);
        try {
            JsonObject apiResult = clashApi.verificarTag(normalizado);
            String name = apiResult.has("name") ? apiResult.get("name").getAsString() : "";
            int trophies = apiResult.has("trophies") ? apiResult.get("trophies").getAsInt() : 0;
            res.addProperty("found", true);
            res.addProperty("name", name);
            res.addProperty("trophies", trophies);
            logger.info("✅ Player Tag encontrado. Usuario: {} (Trophies: {})", name, trophies);
        } catch (Exception e) {
            logger.warn("❌ Fallo al buscar Player Tag {}: {}", normalizado, e.getMessage());
            res.addProperty("found", false);
            res.addProperty("message", "No se encontró el Player Tag en Clash Royale");
        }
        return res;
    }

    /**
     * A3: Guarda el token FCM del dispositivo validando su longitud y manteniendo como máximo
     * 10 tokens por usuario para evitar saturar el pool de IO en envíos broadcast.
     */
    public void registrarTokenPush(int userId, String token) {
        if (token == null) {
            throw ServicioException.solicitudInvalida("Token inválido");
        }
        String limpio = token.trim();
        if (limpio.length() < 20 || limpio.length() > 512) {
            throw ServicioException.solicitudInvalida("Token push inválido");
        }
        db.update("INSERT INTO user_tokens (user_id, fcm_token) VALUES (?, ?) " +
                "ON CONFLICT (user_id, fcm_token) DO NOTHING", userId, limpio);
        db.update("DELETE FROM user_tokens WHERE user_id = ? AND id NOT IN (" +
                "SELECT id FROM user_tokens WHERE user_id = ? ORDER BY id DESC LIMIT 10)", userId, userId);
    }
}
