package com.torneosflash.dao;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.ArrayList;

/**
 * CONCEPTO POO: Collection - usa ArrayList para retornar listas de usuarios
 * CONCEPTO POO: Encapsulamiento - lógica de BD encapsulada en métodos
 * 
 * DAO para operaciones CRUD sobre la tabla users.
 * Retorna datos como JsonObject para compatibilidad con el frontend.
 */
public class UsuarioDAO {

    private final ConexionDB db;

    public UsuarioDAO(ConexionDB db) {
        this.db = db;
    }

    // --- COLLECTION: ArrayList<JsonObject> ---
    public ArrayList<JsonObject> listarTodos() {
        ArrayList<JsonObject> lista = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM users ORDER BY ganancia_generada DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(rowToJson(rs));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return lista;
    }

    public JsonObject buscarPorId(int id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rowToJson(rs);
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public JsonObject buscarPorEmail(String email) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE email = ?")) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rowToJson(rs);
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public JsonObject buscarPorUsername(String username) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE LOWER(username) = LOWER(?)")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) { JsonObject obj = new JsonObject(); obj.addProperty("id", rs.getInt("id")); return obj; }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public JsonObject buscarPorEmailLower(String email) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE LOWER(email) = ?")) {
            ps.setString(1, email.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) { JsonObject obj = new JsonObject(); obj.addProperty("id", rs.getInt("id")); return obj; }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public JsonObject buscarPorPlayerTag(String tag) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE player_tag = ?")) {
            ps.setString(1, tag.toUpperCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) { JsonObject obj = new JsonObject(); obj.addProperty("id", rs.getInt("id")); return obj; }
        } catch (Exception e) { e.printStackTrace(); }
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
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("id");
        } catch (Exception e) { e.printStackTrace(); }
        return -1;
    }

    public void actualizarSaldo(int userId, double monto) {
        ejecutar("UPDATE users SET saldo = saldo + ? WHERE id = ?", monto, userId);
    }

    public double obtenerSaldo(int userId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT saldo FROM users WHERE id = ?")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("saldo");
        } catch (Exception e) { e.printStackTrace(); }
        return 0;
    }

    public void actualizarEstado(int userId, String estado, String salaActual, int pasoJuego) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET estado = ?, sala_actual = ?, paso_juego = ? WHERE id = ?")) {
            ps.setString(1, estado);
            if (salaActual == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, salaActual);
            ps.setInt(3, pasoJuego);
            ps.setInt(4, userId);
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void resetearEstado(String username) {
        ejecutarSQL("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE username = ?", username);
    }

    public void hacerAdmin(String username) {
        ejecutarSQL("UPDATE users SET tipo_suscripcion = 'admin' WHERE username = ?", username);
    }

    // Métodos auxiliares
    private void ejecutar(String sql, double param1, int param2) {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, param1); ps.setInt(2, param2); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void ejecutarSQL(String sql, String param) {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void ejecutarUpdate(String sql, Object... params) {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof Integer) ps.setInt(i + 1, (Integer) params[i]);
                else if (params[i] instanceof Double) ps.setDouble(i + 1, (Double) params[i]);
                else if (params[i] instanceof String) ps.setString(i + 1, (String) params[i]);
                else if (params[i] == null) ps.setNull(i + 1, Types.VARCHAR);
            }
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * Convierte un ResultSet row a JsonObject (compatible con el frontend JSON)
     */
    private JsonObject rowToJson(ResultSet rs) throws SQLException {
        JsonObject obj = new JsonObject();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String col = meta.getColumnName(i);
            Object val = rs.getObject(i);
            if (val == null) obj.addProperty(col, (String) null);
            else if (val instanceof Number) obj.addProperty(col, ((Number) val).doubleValue());
            else obj.addProperty(col, val.toString());
        }
        // Convertir saldo de string a number si existe
        if (obj.has("saldo")) {
            try { obj.addProperty("saldo", Double.parseDouble(obj.get("saldo").getAsString())); } catch (Exception ignored) {}
        }
        return obj;
    }
}
