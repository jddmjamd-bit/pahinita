package com.torneosflash.dao;

import com.google.gson.JsonObject;
import com.torneosflash.servicio.UsuarioVista;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
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

    public JsonObject buscarPorUsername(String username) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE LOWER(username) = LOWER(?)")) {
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
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE LOWER(email) = ?")) {
            ps.setString(1, email.toLowerCase());
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
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE player_tag = ?")) {
            ps.setString(1, tag.toUpperCase());
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

    public int registrar(String username, String email, String passwordHash, String playerTag, String telefono) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (username, email, password, player_tag, telefono) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setString(4, playerTag.toUpperCase());
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
                    ps.setString(4, playerTag.toUpperCase());
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
