package com.torneosflash.dao;

import com.google.gson.JsonObject;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * CONCEPTO POO: Collection - ArrayList para listas de datos
 * DAO genérico para consultas directas a PostgreSQL.
 * Usado por las rutas que necesitan queries específicas.
 */
public class GenericDAO {

    private final ConexionDB db;

    public GenericDAO(ConexionDB db) {
        this.db = db;
    }

    /**
     * Ejecuta un SELECT y retorna lista de JsonObjects.
     * CONCEPTO POO: Collection - ArrayList<JsonObject>
     */
    public ArrayList<JsonObject> query(String sql, Object... params) {
        ArrayList<JsonObject> lista = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                lista.add(rowToJson(rs));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return lista;
    }

    /**
     * Ejecuta un SELECT y retorna el primer resultado o null.
     */
    public JsonObject queryOne(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rowToJson(rs);
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    /**
     * Ejecuta un INSERT/UPDATE/DELETE.
     */
    public int update(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            return ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); return 0; }
    }

    /**
     * Ejecuta un INSERT con RETURNING id.
     */
    public int insertReturningId(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) { e.printStackTrace(); }
        return -1;
    }

    /**
     * Ejecuta SQL arbitrario (para admin-db panel).
     */
    public void execute(String sql) {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * Obtiene la conexión directa (para operaciones complejas del admin panel).
     */
    public Connection getConnection() throws SQLException {
        return db.getConnection();
    }

    // --- Helpers ---
    private void setParams(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Integer) ps.setInt(i + 1, (Integer) p);
            else if (p instanceof Long) ps.setLong(i + 1, (Long) p);
            else if (p instanceof Double) ps.setDouble(i + 1, (Double) p);
            else if (p instanceof String) ps.setString(i + 1, (String) p);
            else if (p == null) ps.setNull(i + 1, Types.VARCHAR);
            else ps.setObject(i + 1, p);
        }
    }

    private JsonObject rowToJson(ResultSet rs) throws SQLException {
        JsonObject obj = new JsonObject();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String col = meta.getColumnName(i);
            int type = meta.getColumnType(i);
            Object val = rs.getObject(i);
            if (val == null) {
                obj.addProperty(col, (String) null);
            } else if (type == Types.INTEGER || type == Types.BIGINT || type == Types.SMALLINT) {
                obj.addProperty(col, rs.getLong(i));
            } else if (type == Types.NUMERIC || type == Types.DECIMAL || type == Types.DOUBLE || type == Types.FLOAT) {
                obj.addProperty(col, rs.getDouble(i));
            } else if (type == Types.BOOLEAN || type == Types.BIT) {
                obj.addProperty(col, rs.getBoolean(i));
            } else if (type == Types.TIMESTAMP) {
                Timestamp ts = rs.getTimestamp(i);
                if (ts != null) {
                    obj.addProperty(col, ts.toInstant().toString());
                } else {
                    obj.addProperty(col, (String) null);
                }
            } else {
                obj.addProperty(col, val.toString());
            }
        }
        return obj;
    }
}
