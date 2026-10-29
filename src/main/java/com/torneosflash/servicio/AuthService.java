package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Lógica de autenticación y cuentas: registro, login, sesión, validación de
 * formato y tokens push (A3).
 * No conoce HTTP: las cookies y las respuestas las maneja {@code RutasAuth}.
 */
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private static final Pattern PLAYER_TAG_VALIDO = Pattern.compile("^#[0289PYLQGRJCUV]{3,15}$");
    private static final Pattern TELEFONO_FORMATO = Pattern.compile("^\\+?[0-9() .-]{7,30}$");
    private static final Pattern TOKEN_RECUPERACION_VALIDO = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final int PASSWORD_MAX_CARACTERES = 128;
    private static final int PASSWORD_MAX_BYTES_BCRYPT = 72;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Hash de trabajo para que un correo inexistente también ejecute una comparación BCrypt. */
    private static final String HASH_CUENTA_INEXISTENTE = BCrypt.hashpw("clave-sin-cuenta", BCrypt.gensalt(10));
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
    private final AppConfig config;
    private final EventBus eventos;
    private final CifradoTokenJugador cifradoTokenJugador;

    public AuthService(UsuarioDAO usuarioDAO, GenericDAO db, ClashApiServicio clashApi, Ejecutores ejecutores,
                       AppConfig config, EventBus eventos) {
        this(usuarioDAO, db, clashApi, ejecutores, config, eventos,
                new CifradoTokenJugador(config.getJwtSecret()));
    }

    public AuthService(UsuarioDAO usuarioDAO, GenericDAO db, ClashApiServicio clashApi, Ejecutores ejecutores,
                       AppConfig config, EventBus eventos, CifradoTokenJugador cifradoTokenJugador) {
        this.usuarioDAO = usuarioDAO;
        this.db = db;
        this.clashApi = clashApi;
        this.ejecutores = ejecutores;
        this.config = config;
        this.eventos = eventos;
        this.cifradoTokenJugador = cifradoTokenJugador;
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

    /** Registra una cuenta; un conflicto de identidad se oculta con el mismo resultado HTTP que el alta. */
    public void registrar(String username, String email, String password, String playerTag, String telefono,
            String playerApiToken,
            boolean aceptaTerminos) {
        // ↓↓↓ NUEVO (roadmap 1.6) ↓↓↓
        if (!aceptaTerminos) {
            throw ServicioException.solicitudInvalida(
                    "Debes aceptar los Términos de Servicio y la Política de Privacidad para crear tu cuenta");
        }
        // ↑↑↑ NUEVO ↑↑↑
        username = username == null ? "" : username.trim();
        email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        playerTag = playerTag == null ? "" : playerTag.trim().toUpperCase(Locale.ROOT);
        if (!playerTag.isEmpty() && !playerTag.startsWith("#")) {
            playerTag = "#" + playerTag;
        }
        telefono = normalizarTelefono(telefono);

        if (username.isEmpty() || email.isEmpty() || password == null || password.isEmpty()
                || playerTag.isEmpty() || telefono.isEmpty()) {
            throw ServicioException.solicitudInvalida("Campos obligatorios vacíos");
        }
        if (email.length() > 120 || !EMAIL_VALIDO.matcher(email).matches()) {
            throw ServicioException.solicitudInvalida("El formato del correo no es válido");
        }
        if (!PLAYER_TAG_VALIDO.matcher(playerTag).matches()) {
            throw ServicioException.solicitudInvalida("El Player Tag no tiene un formato válido de Clash Royale (#XXXXXXX)");
        }
        String tokenJugador = playerApiToken == null ? "" : playerApiToken.trim();
        if (tokenJugador.isEmpty() || tokenJugador.length() > 256 || tokenJugador.chars().anyMatch(Character::isISOControl)) {
            throw ServicioException.solicitudInvalida("Ingresa el token de API generado en los ajustes de Clash Royale");
        }
        validarPasswordNueva(password);
        // S5 (XSS): el username se muestra en chat, rankings y paneles de admin
        if (!ChatSanitizer.esNombreUsuarioSeguro(username)) {
            throw ServicioException.solicitudInvalida(
                    "El usuario debe tener entre 3 y 30 caracteres sin símbolos especiales (< > \" ' & `)");
        }

        String nombreClash = "";
        try {
            JsonObject perfilClash = clashApi.verificarTag(playerTag);
            if (perfilClash != null && perfilClash.has("name") && !perfilClash.get("name").isJsonNull()) {
                nombreClash = ChatSanitizer.limpiarTexto(perfilClash.get("name").getAsString(), 60);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("(Código 404)")) {
                throw ServicioException.solicitudInvalida("No se encontró ese Player Tag en Clash Royale");
            }
            logger.warn("No se pudo consultar el Player Tag durante el registro: {}", e.getClass().getSimpleName());
            throw ServicioException.noDisponible("No se pudo consultar el Player Tag en Clash Royale. Inténtalo de nuevo más tarde.");
        }

        String hash = hashear(password);
        // Hacer las mismas consultas y el mismo trabajo BCrypt tanto si hay conflicto como si no.
        // El resultado de registro nunca debe confirmar si una cuenta ya existe.
        boolean usernameExiste = usuarioDAO.buscarPorUsername(username) != null;
        boolean emailExiste = usuarioDAO.buscarPorEmailLower(email) != null;
        boolean tagExiste = usuarioDAO.buscarPorPlayerTag(playerTag) != null;
        boolean telefonoExiste = usuarioDAO.buscarPorTelefonoNormalizado(telefono) != null;
        if (usernameExiste || emailExiste || tagExiste || telefonoExiste) {
            return;
        }
        String tokenCifrado = cifradoTokenJugador.cifrar(tokenJugador);
        int newId = usuarioDAO.registrarConAceptacionLegal(username, email, hash, playerTag, telefono,
                DocumentosLegales.TERMINOS_VERSION, DocumentosLegales.PRIVACIDAD_VERSION, tokenCifrado);
        if (newId == -1) {
            // Conflicto de unicidad concurrente: se presenta igual que un registro aceptado.
            return;
        }
        if (newId <= 0) {
            throw ServicioException.interno("No se pudo completar el registro", null);
        }
        if (eventos != null) {
            eventos.emit(Eventos.USER_REGISTERED, new Eventos.UsuarioRegistrado(newId, username, nombreClash));
        }
    }

    /** Canonicaliza el teléfono para que formatos distintos del mismo número colisionen en la BD. */
    private static String normalizarTelefono(String telefono) {
        String crudo = telefono == null ? "" : telefono.trim();
        if (crudo.isEmpty()) return "";
        if (!TELEFONO_FORMATO.matcher(crudo).matches()) {
            throw ServicioException.solicitudInvalida("El formato del celular no es válido");
        }
        String digitos = crudo.replaceAll("[^0-9]", "");
        if (digitos.length() < 7 || digitos.length() > 15) {
            throw ServicioException.solicitudInvalida("El celular debe tener entre 7 y 15 dígitos");
        }
        if (digitos.startsWith("00")) digitos = digitos.substring(2);
        if (digitos.length() < 7 || digitos.length() > 15) {
            throw ServicioException.solicitudInvalida("El celular debe tener entre 7 y 15 dígitos");
        }
        // En este servicio, los celulares colombianos pueden escribirse con o sin +57.
        if (digitos.matches("^3[0-9]{9}$")) digitos = "57" + digitos;
        return digitos;
    }

    /**
     * Verifica credenciales. Los datos que salen hacia el cliente pasan por la
     * lista blanca de columnas (S6).
     */
    public SesionIniciada login(String emailCrudo, String password) {
        if (password == null || password.isEmpty() || bytesUtf8(password) > PASSWORD_MAX_BYTES_BCRYPT) {
            coincide("clave-con-longitud-invalida", HASH_CUENTA_INEXISTENTE);
            throw ServicioException.solicitudInvalida("Correo o contraseña incorrectos");
        }
        String email = emailCrudo.trim().toLowerCase();

        // S6: el hash vive solo en este objeto de credenciales (nunca se envía al
        // cliente)
        JsonObject credenciales = usuarioDAO.buscarCredencialesPorEmail(email);
        if (credenciales == null) {
            coincide(password, HASH_CUENTA_INEXISTENTE);
            throw ServicioException.solicitudInvalida("Correo o contraseña incorrectos");
        }
        if (!coincide(password, credenciales.get("password").getAsString())) {
            throw ServicioException.solicitudInvalida("Correo o contraseña incorrectos");
        }

        int userId = credenciales.get("id").getAsNumber().intValue();
        JsonObject user = usuarioDAO.buscarPorId(userId);
        if (user == null) {
            throw ServicioException.solicitudInvalida("Correo o contraseña incorrectos");
        }
        return new SesionIniciada(userId, user);
    }

    /** Responde igual para cuentas existentes, inexistentes y solicitudes repetidas. */
    public void solicitarRecuperacionPassword(String emailCrudo) {
        String email = emailCrudo == null ? "" : emailCrudo.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 120 || !EMAIL_VALIDO.matcher(email).matches()) return;

        UsuarioDAO.CuentaRecuperacion cuenta;
        try {
            cuenta = usuarioDAO.buscarCuentaRecuperacion(email);
        } catch (SQLException e) {
            logger.error("No se pudo consultar una cuenta para recuperación de contraseña", e);
            return;
        }
        if (cuenta == null) return;

        byte[] aleatorio = new byte[32];
        SECURE_RANDOM.nextBytes(aleatorio);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio);
        java.util.Arrays.fill(aleatorio, (byte) 0);
        String tokenHash = hashToken(token);
        int minutosVigencia = config.getPasswordResetTokenMinutes();
        try {
            boolean creado = usuarioDAO.crearTokenRecuperacion(cuenta.userId(), tokenHash,
                    Instant.now().plusSeconds(minutosVigencia * 60L), config.getPasswordResetCooldownSeconds());
            if (!creado) return;
        } catch (SQLException e) {
            logger.error("No se pudo crear un token de recuperación de contraseña", e);
            return;
        }

        String enlace = config.getAppBaseUrl() + "/auth#reset=" + token;
        eventos.emit(Eventos.PASSWORD_RESET_REQUESTED,
                new Eventos.PasswordResetSolicitado(cuenta.email(), cuenta.username(), enlace, minutosVigencia));
    }

    /** Valida el enlace de un solo uso, cambia el hash y revoca todas las sesiones anteriores. */
    public void restablecerPassword(String token, String passwordNueva) {
        if (token == null || !TOKEN_RECUPERACION_VALIDO.matcher(token).matches()) {
            throw ServicioException.solicitudInvalida("El enlace de recuperación no es válido o ya venció");
        }
        validarPasswordNueva(passwordNueva);
        String tokenHash = hashToken(token);
        try {
            if (!usuarioDAO.tokenRecuperacionValido(tokenHash)) {
                throw ServicioException.solicitudInvalida("El enlace de recuperación no es válido o ya venció");
            }
        } catch (SQLException e) {
            logger.error("No se pudo validar el token de recuperación de contraseña", e);
            throw ServicioException.noDisponible("No se pudo validar el enlace en este momento");
        }

        String passwordHash = hashear(passwordNueva);
        try {
            int userId = usuarioDAO.restablecerPassword(tokenHash, passwordHash);
            if (userId <= 0) {
                throw ServicioException.solicitudInvalida("El enlace de recuperación no es válido o ya venció");
            }
            eventos.emit(Eventos.PASSWORD_RESET_COMPLETED, new Eventos.PasswordResetCompletado(userId));
        } catch (SQLException e) {
            logger.error("No se pudo guardar la contraseña recuperada", e);
            throw ServicioException.noDisponible("No se pudo actualizar la contraseña en este momento");
        }
    }

    /** Valida un enlace al abrirlo sin consumirlo; el endpoint de cambio vuelve a validarlo atómicamente. */
    public boolean validarTokenRecuperacion(String token) {
        if (token == null || !TOKEN_RECUPERACION_VALIDO.matcher(token).matches()) return false;
        try {
            return usuarioDAO.tokenRecuperacionValido(hashToken(token));
        } catch (SQLException e) {
            logger.error("No se pudo validar el token de recuperación al abrir el enlace", e);
            throw ServicioException.noDisponible("No se pudo validar el enlace en este momento");
        }
    }

    private static void validarPasswordNueva(String password) {
        if (password == null || password.length() < 6 || password.length() > PASSWORD_MAX_CARACTERES) {
            throw ServicioException.solicitudInvalida(
                    "La contraseña debe tener al menos 6 caracteres");
        }
        if (bytesUtf8(password) > PASSWORD_MAX_BYTES_BCRYPT) {
            throw ServicioException.solicitudInvalida(
                    "La contraseña es demasiado larga. Usa una más corta");
        }
    }

    private static int bytesUtf8(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible", e);
        }
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
        String normalizado = username == null ? "" : username.trim();
        boolean formatoValido = ChatSanitizer.esNombreUsuarioSeguro(normalizado);
        return resultadoValidacion(formatoValido, "✓ Formato válido",
                "Usa entre 3 y 30 caracteres sin símbolos especiales");
    }

    public JsonObject emailDisponible(String email) {
        String normalizado = email == null ? "" : email.trim().toLowerCase();
        boolean formatoValido = normalizado.length() <= 120 && EMAIL_VALIDO.matcher(normalizado).matches();
        return resultadoValidacion(formatoValido, "✓ Correo válido",
                "Ingresa un correo válido");
    }

    private static JsonObject resultadoValidacion(boolean valido, String msgSi, String msgNo) {
        JsonObject res = new JsonObject();
        res.addProperty("valid", valido);
        res.addProperty("message", valido ? msgSi : msgNo);
        return res;
    }

    /**
     * Verifica formato y existencia pública en Clash Royale; no consulta si el tag está vinculado a una cuenta local.
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
     * 10 tokens por usuario para evitar acumular dispositivos obsoletos y saturar entregas push.
     */
    public void registrarTokenPush(int userId, String token) {
        registrarTokenPush(userId, token, "");
    }

    /** Guarda el token nuevo y reemplaza el anterior de este dispositivo dentro de una transacción. */
    public void registrarTokenPush(int userId, String token, String tokenAnterior) {
        if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
        if (token == null) {
            throw ServicioException.solicitudInvalida("Token inválido");
        }
        String limpio = token.trim();
        if (limpio.length() < 20 || limpio.length() > 512) {
            throw ServicioException.solicitudInvalida("Token push inválido");
        }

        String anterior = tokenAnterior == null ? "" : tokenAnterior.trim();
        if (anterior.length() < 20 || anterior.length() > 512 || anterior.equals(limpio)) anterior = "";

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!anterior.isEmpty()) {
                    try (PreparedStatement borrar = conn.prepareStatement(
                            "DELETE FROM user_tokens WHERE user_id = ? AND fcm_token = ?")) {
                        borrar.setInt(1, userId);
                        borrar.setString(2, anterior);
                        borrar.executeUpdate();
                    }
                }
                try (PreparedStatement insertar = conn.prepareStatement(
                        "INSERT INTO user_tokens (user_id, fcm_token) VALUES (?, ?) " +
                                "ON CONFLICT (user_id, fcm_token) DO NOTHING")) {
                    insertar.setInt(1, userId);
                    insertar.setString(2, limpio);
                    insertar.executeUpdate();
                }
                try (PreparedStatement limitar = conn.prepareStatement(
                        "DELETE FROM user_tokens WHERE user_id = ? AND id NOT IN (" +
                                "SELECT id FROM user_tokens WHERE user_id = ? ORDER BY id DESC LIMIT 10)")) {
                    limitar.setInt(1, userId);
                    limitar.setInt(2, userId);
                    limitar.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            throw ServicioException.interno("No se pudo registrar el token push", e);
        }
    }

    /** Desasocia el token de este usuario (logout); no afecta registros de otros usuarios/dispositivos. */
    public void eliminarTokenPush(int userId, String token) {
        if (userId <= 0 || token == null) return;
        String limpio = token.trim();
        if (limpio.length() < 20 || limpio.length() > 512) return;
        db.update("DELETE FROM user_tokens WHERE user_id = ? AND fcm_token = ?", userId, limpio);
    }
}
