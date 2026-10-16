package com.torneosflash.dao;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;

/**
 * CONCEPTO POO: Collection - ArrayList para listas de datos
 * DAO gen�rico para consultas parametrizadas a PostgreSQL.
 *
 * S4: Todas las consultas usan exclusivamente {@link PreparedStatement} con par�metros {@code ?}.
 * Se elimin� {@code execute(String sql)} para impedir la ejecuci�n de SQL arbitrario con {@link Statement}.
 */
public class GenericDAO {
    private static final Logger logger = LoggerFactory.getLogger(GenericDAO.class);

    private final ConexionDB db;

    public GenericDAO(ConexionDB db) {
        this.db = db;
    }

    /**
     * Ejecuta un SELECT parametrizado y retorna lista de JsonObjects.
     * CONCEPTO POO: Collection - ArrayList<JsonObject>
     */
    public ArrayList<JsonObject> query(String sql, Object... params) {
        ArrayList<JsonObject> lista = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(rowToJson(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Error en query SQL: {}", e.getMessage());
        }
        return lista;
    }

    /**
     * Ejecuta un SELECT parametrizado y retorna el primer resultado o null.
     */
    public JsonObject queryOne(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToJson(rs);
            }
        } catch (Exception e) {
            logger.error("Error en queryOne SQL: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Ejecuta un INSERT/UPDATE/DELETE parametrizado.
     */
    public int update(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            return ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Error en update SQL: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Ejecuta un INSERT parametrizado con RETURNING id.
     */
    public int insertReturningId(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            logger.error("Error en insertReturningId SQL: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Obtiene la conexi�n directa (para operaciones transaccionales o de metadatos con PreparedStatement).
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
            else if (p instanceof BigDecimal) ps.setBigDecimal(i + 1, (BigDecimal) p);
            else if (p instanceof Boolean) ps.setBoolean(i + 1, (Boolean) p);
            else if (p instanceof String) ps.setString(i + 1, (String) p);
            else if (p instanceof byte[]) ps.setBytes(i + 1, (byte[]) p);
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
