package com.torneosflash.dao;

import com.google.gson.JsonObject;
import com.torneosflash.servicio.UsuarioVista;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;

/**
 * CONCEPTO POO: Collection - usa ArrayList para retornar listas de usuarios
 * CONCEPTO POO: Encapsulamiento - lógica de BD encapsulada en métodos
 *
 * DAO para operaciones CRUD sobre la tabla users.
 * S4: Todas las consultas son fijas y usan exclusivamente {@link PreparedStatement} con {@code ?}.
 * D1: El saldo y las columnas numéricas monetarias se leen y escriben con {@link BigDecimal}.
 */
public class UsuarioDAO {
    private static final Logger logger = LoggerFactory.getLogger(UsuarioDAO.class);

    private final ConexionDB db;

    /** Datos mínimos que necesita el correo de recuperación; nunca incluye el hash de contraseña. */
    public record CuentaRecuperacion(int userId, String email, String username) {}

    public UsuarioDAO(ConexionDB db) {
        this.db = db;
    }

    // --- COLLECTION: ArrayList<JsonObject> ---
    public ArrayList<JsonObject> listarTodos() {
        ArrayList<JsonObject> lista = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users ORDER BY ganancia_generada DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(rowToJson(rs));
            }
        } catch (Exception e) {
            logger.error("Error listando usuarios: {}", e.getMessage());
        }
        return lista;
    }

    /**
     * S6: devuelve solo las columnas de sesión (nunca el hash de la contraseña).
     */
    public JsonObject buscarPorId(int id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToJson(rs);
            }
        } catch (Exception e) {
            logger.error("Error buscando usuario por id={}: {}", id, e.getMessage());
        }
        return null;
    }

    /**
     * S6: SOLO para verificar el login. Devuelve {id, password(hash)}; ese objeto debe
     * quedarse en el servidor y nunca serializarse hacia el cliente. Para los datos del
     * usuario usar {@link #buscarPorId(int)}.
     */
    public JsonObject buscarCredencialesPorEmail(String email) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, password FROM users WHERE LOWER(email) = LOWER(?)")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToJson(rs);
            }
        } catch (Exception e) {
            logger.error("Error buscando credenciales por email: {}", e.getMessage());
        }
        return null;
    }

    /** Crea una sesión de servidor para el JTI de un JWT recién emitido. */
    public void crearSesion(String jti, int userId, Instant expiraEn) throws SQLException {
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement limpiar = conn.prepareStatement(
                    "DELETE FROM user_sessions WHERE expires_at <= CURRENT_TIMESTAMP")) {
                limpiar.executeUpdate();
            }
            try (PreparedStatement insertar = conn.prepareStatement(
                    "INSERT INTO user_sessions (jti, user_id, expires_at) VALUES (?, ?, ?)")) {
                insertar.setString(1, jti);
                insertar.setInt(2, userId);
                insertar.setTimestamp(3, Timestamp.from(expiraEn));
                insertar.executeUpdate();
            }
        }
    }

    /** Comprueba en cada request que el JTI siga activo y pertenezca al usuario del JWT. */
    public boolean sesionActiva(String jti, int userId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM user_sessions WHERE jti = ? AND user_id = ? AND expires_at > CURRENT_TIMESTAMP")) {
            ps.setString(1, jti);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Extiende la misma sesión durante la renovación deslizante, sin reactivar una sesión revocada. */
    public boolean renovarSesion(String jti, int userId, Instant expiraEn) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE user_sessions SET expires_at = GREATEST(expires_at, ?) " +
                             "WHERE jti = ? AND user_id = ? AND expires_at > CURRENT_TIMESTAMP")) {
            ps.setTimestamp(1, Timestamp.from(expiraEn));
            ps.setString(2, jti);
            ps.setInt(3, userId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Revoca solo la sesión asociada al JTI; otros dispositivos del usuario siguen conectados. */
    public boolean revocarSesion(String jti, int userId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM user_sessions WHERE jti = ? AND user_id = ?")) {
            ps.setString(1, jti);
            ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Busca solo los datos necesarios para enviar un enlace sin revelar la cuenta por la API. */
    public CuentaRecuperacion buscarCuentaRecuperacion(String email) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, email, username FROM users WHERE LOWER(email) = LOWER(?)")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new CuentaRecuperacion(rs.getInt("id"), rs.getString("email"), rs.getString("username"))
                        : null;
            }
        }
    }

    /** Crea o renueva un único enlace activo por cuenta y aplica una pausa entre correos. */
    public boolean crearTokenRecuperacion(int userId, String tokenHash, Instant expiraEn, int cooldownSegundos)
            throws SQLException {
        try (Connection conn = db.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement bloquearUsuario = conn.prepareStatement(
                        "SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                    bloquearUsuario.setInt(1, userId);
                    try (ResultSet rs = bloquearUsuario.executeQuery()) {
                        if (!rs.next()) {
                            conn.commit();
                            return false;
                        }
                    }
                }

                try (PreparedStatement limpiar = conn.prepareStatement(
                        "DELETE FROM password_reset_tokens WHERE expires_at <= CURRENT_TIMESTAMP OR used_at IS NOT NULL")) {
                    limpiar.executeUpdate();
                }

                try (PreparedStatement reciente = conn.prepareStatement(
                        "SELECT 1 FROM password_reset_tokens WHERE user_id = ? AND used_at IS NULL " +
                                "AND expires_at > CURRENT_TIMESTAMP " +
                                "AND created_at > CURRENT_TIMESTAMP - (? * INTERVAL '1 second') LIMIT 1")) {
                    reciente.setInt(1, userId);
                    reciente.setInt(2, cooldownSegundos);
                    try (ResultSet rs = reciente.executeQuery()) {
                        if (rs.next()) {
                            conn.commit();
                            return false;
                        }
                    }
                }

                try (PreparedStatement invalidar = conn.prepareStatement(
                        "UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP " +
                                "WHERE user_id = ? AND used_at IS NULL")) {
                    invalidar.setInt(1, userId);
                    invalidar.executeUpdate();
                }
                try (PreparedStatement insertar = conn.prepareStatement(
                        "INSERT INTO password_reset_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
                    insertar.setString(1, tokenHash);
                    insertar.setInt(2, userId);
                    insertar.setTimestamp(3, Timestamp.from(expiraEn));
                    insertar.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(autoCommit); } catch (SQLException ignored) {}
            }
        }
    }

    public boolean tokenRecuperacionValido(String tokenHash) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM password_reset_tokens WHERE token_hash = ? AND used_at IS NULL " +
                             "AND expires_at > CURRENT_TIMESTAMP")) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Consume el token y cambia la contraseña y revoca todas las sesiones en una transacción. */
    public int restablecerPassword(String tokenHash, String passwordHash) throws SQLException {
        try (Connection conn = db.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Integer userId = null;
                // Lee primero el propietario sin bloquear el token. Los flujos de emisión y consumo
                // adquieren los bloqueos en el mismo orden: usuario y luego token.
                try (PreparedStatement buscar = conn.prepareStatement(
                        "SELECT user_id FROM password_reset_tokens WHERE token_hash = ? AND used_at IS NULL " +
                                "AND expires_at > CURRENT_TIMESTAMP")) {
                    buscar.setString(1, tokenHash);
                    try (ResultSet rs = buscar.executeQuery()) {
                        if (rs.next()) userId = rs.getInt("user_id");
                    }
                }
                if (userId == null) {
                    conn.rollback();
                    return 0;
                }

                try (PreparedStatement bloquearUsuario = conn.prepareStatement(
                        "SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                    bloquearUsuario.setInt(1, userId);
                    try (ResultSet rs = bloquearUsuario.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return 0;
                        }
                    }
                }

                // Revalida bajo bloqueo después de adquirir el lock del usuario: otro request
                // pudo consumir el token mientras este esperaba.
                try (PreparedStatement bloquearToken = conn.prepareStatement(
                        "SELECT 1 FROM password_reset_tokens WHERE token_hash = ? AND user_id = ? " +
                                "AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP FOR UPDATE")) {
                    bloquearToken.setString(1, tokenHash);
                    bloquearToken.setInt(2, userId);
                    try (ResultSet rs = bloquearToken.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return 0;
                        }
                    }
                }

                try (PreparedStatement actualizar = conn.prepareStatement(
                        "UPDATE users SET password = ? WHERE id = ?")) {
                    actualizar.setString(1, passwordHash);
                    actualizar.setInt(2, userId);
                    if (actualizar.executeUpdate() != 1) {
                        conn.rollback();
                        return 0;
                    }
                }
                try (PreparedStatement consumir = conn.prepareStatement(
                        "UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP " +
                                "WHERE user_id = ? AND used_at IS NULL")) {
                    consumir.setInt(1, userId);
                    consumir.executeUpdate();
                }
                try (PreparedStatement revocarSesiones = conn.prepareStatement(
                        "DELETE FROM user_sessions WHERE user_id = ?")) {
                    revocarSesiones.setInt(1, userId);
                    revocarSesiones.executeUpdate();
                }
                conn.commit();
                return userId;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(autoCommit); } catch (SQLException ignored) {}
            }
        }
    }

    public JsonObject buscarPorUsername(String username) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE LOWER(BTRIM(username)) = LOWER(BTRIM(?))")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", rs.getInt("id"));
                    return obj;
                }
            }
        } catch (Exception e) {
            logger.error("Error buscando usuario por username: {}", e.getMessage());
        }
        return null;
    }

    public JsonObject buscarPorEmailLower(String email) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE LOWER(BTRIM(email)) = LOWER(BTRIM(?))")) {
            ps.setString(1, email.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", rs.getInt("id"));
                    return obj;
                }
            }
        } catch (Exception e) {
            logger.error("Error buscando usuario por email: {}", e.getMessage());
        }
        return null;
    }

    public JsonObject buscarPorPlayerTag(String tag) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE UPPER(REGEXP_REPLACE(BTRIM(player_tag), '^#+', '')) = ?")) {
            ps.setString(1, tag == null ? "" : tag.trim().replace("#", "").toUpperCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", rs.getInt("id"));
                    return obj;
                }
            }
        } catch (Exception e) {
            logger.error("Error buscando usuario por player_tag: {}", e.getMessage());
        }
        return null;
    }

    public JsonObject buscarPorTelefonoNormalizado(String telefonoNormalizado) {
        String digitos = "REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g')";
        String normalizado = "CASE WHEN " + digitos + " ~ '^003[0-9]{9}$' THEN '57' || SUBSTRING(" + digitos
                + " FROM 3) WHEN " + digitos + " ~ '^00[0-9]{7,15}$' THEN SUBSTRING(" + digitos
                + " FROM 3) WHEN " + digitos + " ~ '^3[0-9]{9}$' THEN '57' || " + digitos
                + " ELSE " + digitos + " END";
        String sql = "SELECT id FROM users WHERE telefono IS NOT NULL AND " + normalizado + " = ? LIMIT 1";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, telefonoNormalizado);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", rs.getInt("id"));
                    return obj;
                }
            }
        } catch (Exception e) {
            logger.error("Error buscando usuario por teléfono normalizado: {}", e.getMessage());
        }
        return null;
    }

    public int registrar(String username, String email, String passwordHash, String playerTag, String telefono) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (username, email, password, player_tag, telefono) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setString(4, normalizarPlayerTag(playerTag));
            ps.setString(5, telefono);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        } catch (Exception e) {
            logger.error("Error registrando usuario: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Roadmap 1.6: crea el usuario y deja constancia de que aceptó los Términos de Servicio y la Política de
     * Privacidad (tabla legal_acceptances) en UNA sola transacción: o quedan las dos cosas o ninguna.
     *
     * @return el id del usuario nuevo, -1 si chocó con una restricción única, -2 si falló la BD
     */
    public int registrarConAceptacionLegal(String username, String email, String passwordHash, String playerTag,
                                           String telefono, String versionTerminos, String versionPrivacidad) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int newId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users (username, email, password, player_tag, telefono) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
                    ps.setString(1, username);
                    ps.setString(2, email);
                    ps.setString(3, passwordHash);
                    ps.setString(4, normalizarPlayerTag(playerTag));
                    ps.setString(5, telefono);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return -2;
                        }
                        newId = rs.getInt("id");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO legal_acceptances (user_id, documento, version) VALUES (?, 'terminos', ?), (?, 'privacidad', ?)")) {
                    ps.setInt(1, newId);
                    ps.setString(2, versionTerminos);
                    ps.setInt(3, newId);
                    ps.setString(4, versionPrivacidad);
                    ps.executeUpdate();
                }
                conn.commit();
                return newId;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                if (esViolacionUnica(e)) {
                    logger.debug("Registro rechazado por una restricción única");
                    return -1;
                }
                logger.error("Error registrando usuario con aceptación legal: {}", e.getMessage());
                return -2;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (Exception e) {
            logger.error("Error obteniendo conexión para registrar usuario: {}", e.getMessage());
            return -2;
        }
    }

    private static boolean esViolacionUnica(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof SQLException sql) {
                for (SQLException actualSql = sql; actualSql != null; actualSql = actualSql.getNextException()) {
                    if ("23505".equals(actualSql.getSQLState())) return true;
                }
            }
        }
        return false;
    }

    private static String normalizarPlayerTag(String playerTag) {
        String normalizado = playerTag == null ? "" : playerTag.trim().toUpperCase(java.util.Locale.ROOT);
        return normalizado.startsWith("#") ? normalizado : "#" + normalizado;
    }

    public void actualizarEstado(int userId, String estado, String salaActual, int pasoJuego) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET estado = ?, sala_actual = ?, paso_juego = ? WHERE id = ?")) {
            ps.setString(1, estado);
            if (salaActual == null) ps.setNull(2, Types.VARCHAR);
            else ps.setString(2, salaActual);
            ps.setInt(3, pasoJuego);
            ps.setInt(4, userId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Error actualizando estado usuario id={}: {}", userId, e.getMessage());
        }
    }

    public void resetearEstado(String username) {
        ejecutarSQL("DELETE FROM match_queue WHERE LOWER(username) = LOWER(?)", username);
        ejecutarSQL("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE username = ?", username);
    }

    private void ejecutarSQL(String sql, String param) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Error ejecutando actualización en UsuarioDAO: {}", e.getMessage());
        }
    }

    /**
     * Convierte un ResultSet row a JsonObject conservando BigDecimal para columnas NUMERIC/DECIMAL (D1).
     */
    private JsonObject rowToJson(ResultSet rs) throws SQLException {
        JsonObject obj = new JsonObject();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String col = meta.getColumnName(i);
            Object val = rs.getObject(i);
            if (val == null) {
                obj.addProperty(col, (String) null);
            } else if (val instanceof BigDecimal) {
                obj.addProperty(col, (BigDecimal) val); // REVIEW-MONEY
            } else if (val instanceof Number) {
                obj.addProperty(col, (Number) val);
            } else {
                obj.addProperty(col, val.toString());
            }
        }
        // D1: asegurar que saldo quede como BigDecimal si vino como texto
        if (obj.has("saldo") && !obj.get("saldo").isJsonNull()) {
            try {
                obj.addProperty("saldo", new BigDecimal(obj.get("saldo").getAsString())); // REVIEW-MONEY
            } catch (Exception ignored) {
            }
        }
        return obj;
    }
}
