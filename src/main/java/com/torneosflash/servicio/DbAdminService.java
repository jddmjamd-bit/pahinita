package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;

import java.sql.*;
import java.util.*;

/**
 * Operaciones del panel de administración de la base de datos: ver tablas, editar celdas, insertar,
 * borrar, exportar e importar (A3). Antes todo este SQL estaba dentro de {@code RutasDbAdmin}.
 *
 * El acceso al panel (clave, bloqueo por IP, cabeceras) sigue en {@code RutasDbAdmin}: es protocolo HTTP,
 * no lógica de negocio. Aquí todo nombre de tabla/columna que venga del request se valida contra
 * {@code information_schema} antes de concatenarlo en un SQL (S10).
 */
public class DbAdminService {

    /** Orden de inserción al importar (respeta dependencias); al borrar se recorre al revés. */
    private static final String[] ORDEN_TABLAS_IMPORT = { "users", "messages", "matches", "transactions", "admin_wallet",
            "user_tokens", "user_tickets", "raffles", "raffle_entries" };

    private final GenericDAO db;

    /**
     * A5: tabla donde Flyway guarda el historial de migraciones. El panel no la muestra ni la deja tocar:
     * editarla desde la web podría hacer que Flyway rehaga o salte migraciones. Además no tiene columna `id`,
     * así que `ORDER BY id` (ver, exportar) fallaría con ella.
     */
    private static final String TABLA_FLYWAY = "flyway_schema_history";

    public DbAdminService(GenericDAO db) {
        this.db = db;
    }

    /** Operación de BD que puede lanzar excepciones SQL. */
    @FunctionalInterface
    private interface Operacion<T> {
        T ejecutar() throws Exception;
    }

    /** Ejecuta {@code op}; cualquier fallo que no sea de negocio sale como 500 con el mensaje (como antes). */
    private static <T> T conErrores(Operacion<T> op) {
        try {
            return op.ejecutar();
        } catch (ServicioException e) {
            throw e;
        } catch (Exception e) {
            throw new ServicioException(500, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    /** Lista las tablas del esquema public con su número de filas. */
    public JsonArray listarTablas() {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                JsonArray tables = new JsonArray();
                for (String tableName : nombresDeTablas(conn)) {
                    try (PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) as count FROM \"" + tableName + "\"");
                         ResultSet countRs = countPs.executeQuery()) {
                        countRs.next();
                        JsonObject t = new JsonObject();
                        t.addProperty("name", tableName);
                        t.addProperty("count", countRs.getInt("count"));
                        tables.add(t);
                    }
                }
                return tables;
            }
        });
    }

    /** Columnas y filas de una tabla. */
    public JsonObject datosTabla(String tableName) {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                exigirTablaValida(conn, tableName);

                JsonArray columns = new JsonArray();
                try (PreparedStatement colPs = conn.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position")) {
                    colPs.setString(1, tableName);
                    try (ResultSet colRs = colPs.executeQuery()) {
                        while (colRs.next()) {
                            JsonObject col = new JsonObject();
                            col.addProperty("column_name", colRs.getString("column_name"));
                            col.addProperty("data_type", colRs.getString("data_type"));
                            columns.add(col);
                        }
                    }
                }

                JsonObject result = new JsonObject();
                result.add("columns", columns);
                result.add("rows", filasDeTabla(conn, tableName));
                return result;
            }
        });
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    public void actualizarCelda(String tableName, int id, String column, String value) {
        conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                exigirTablaValida(conn, tableName);
                String dataType = tiposDeColumna(conn, tableName).get(column);
                if (dataType == null) {
                    throw ServicioException.solicitudInvalida("Columna no válida");
                }

                String finalVal = (value != null && !value.isEmpty()) ? value : null;
                try (PreparedStatement updatePs = conn.prepareStatement(
                        "UPDATE \"" + tableName + "\" SET \"" + column + "\" = ? WHERE id = ?")) {
                    setTypedParam(updatePs, 1, finalVal, dataType);
                    updatePs.setInt(2, id);
                    updatePs.executeUpdate();
                }
                return null;
            }
        });
    }

    public void eliminarFila(String tableName, int id) {
        conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                exigirTablaValida(conn, tableName);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM \"" + tableName + "\" WHERE id = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    public void insertarFila(String tableName, JsonObject body) {
        Set<String> keys = body.keySet();
        if (keys.isEmpty()) {
            throw ServicioException.solicitudInvalida("Sin datos");
        }

        conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                exigirTablaValida(conn, tableName);
                Map<String, String> columnTypes = tiposDeColumna(conn, tableName);

                StringBuilder cols = new StringBuilder();
                StringBuilder placeholders = new StringBuilder();
                List<String> values = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (String key : keys) {
                    if (!columnTypes.containsKey(key)) {
                        throw ServicioException.solicitudInvalida("Columna no válida");
                    }
                    if (cols.length() > 0) {
                        cols.append(", ");
                        placeholders.append(", ");
                    }
                    cols.append('"').append(key).append('"');
                    placeholders.append("?");
                    values.add(body.get(key).isJsonNull() ? null : body.get(key).getAsString());
                    types.add(columnTypes.get(key));
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO \"" + tableName + "\" (" + cols + ") VALUES (" + placeholders + ")")) {
                    for (int i = 0; i < values.size(); i++) {
                        setTypedParam(ps, i + 1, values.get(i), types.get(i));
                    }
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------

    /** Exporta todas las tablas (más un bloque {@code _meta}). */
    public JsonObject exportar() {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                JsonObject backup = new JsonObject();
                for (String tableName : nombresDeTablas(conn)) {
                    backup.add(tableName, filasDeTabla(conn, tableName));
                }

                JsonObject meta = new JsonObject();
                meta.addProperty("exportDate", new java.util.Date().toString());
                meta.addProperty("tables", backup.keySet().size());
                backup.add("_meta", meta);
                return backup;
            }
        });
    }

    /**
     * Importa un backup: borra las tablas presentes en el archivo e inserta sus filas.
     * Solo se aceptan columnas que existan en la tabla; las filas inválidas se omiten.
     *
     * @return número de filas insertadas
     */
    public int importar(JsonObject data) {
        return conErrores(() -> {
            // Borrar en orden inverso
            for (int i = ORDEN_TABLAS_IMPORT.length - 1; i >= 0; i--) {
                if (data.has(ORDEN_TABLAS_IMPORT[i])) {
                    db.update("DELETE FROM " + ORDEN_TABLAS_IMPORT[i]);
                }
            }

            int totalInserted = 0;
            try (Connection conn = db.getConnection()) {
                for (String tabla : ORDEN_TABLAS_IMPORT) {
                    if (!data.has(tabla) || !data.get(tabla).isJsonArray()) continue;

                    Map<String, String> columnasValidas = tiposDeColumna(conn, tabla);
                    JsonArray rows = data.getAsJsonArray(tabla);
                    for (JsonElement elem : rows) {
                        JsonObject row = elem.getAsJsonObject();
                        StringBuilder cols = new StringBuilder();
                        StringBuilder ph = new StringBuilder();
                        List<String> vals = new ArrayList<>();
                        boolean columnasOk = true;
                        for (String key : row.keySet()) {
                            if (!columnasValidas.containsKey(key)) {
                                columnasOk = false;
                                break;
                            }
                            if (cols.length() > 0) {
                                cols.append(", ");
                                ph.append(", ");
                            }
                            cols.append('"').append(key).append('"');
                            ph.append("?");
                            vals.add(row.get(key).isJsonNull() ? null : row.get(key).getAsString());
                        }
                        if (!columnasOk || vals.isEmpty()) continue;

                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO " + tabla + " (" + cols + ") VALUES (" + ph + ")")) {
                            for (int j = 0; j < vals.size(); j++) {
                                if (vals.get(j) == null) ps.setNull(j + 1, Types.VARCHAR);
                                else ps.setString(j + 1, vals.get(j));
                            }
                            ps.executeUpdate();
                            totalInserted++;
                        } catch (Exception ignored) {
                            // fila inválida: se omite
                        }
                    }
                }

                // Resetear secuencias
                for (String tabla : ORDEN_TABLAS_IMPORT) {
                    if (data.has(tabla)) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("SELECT setval(pg_get_serial_sequence('" + tabla +
                                    "', 'id'), COALESCE((SELECT MAX(id) FROM " + tabla + "), 0) + 1, false)");
                        } catch (Exception ignored) {
                            // tabla sin secuencia
                        }
                    }
                }
            }
            return totalInserted;
        });
    }

    // ------------------------------------------------------------------
    // Utilidades de BD
    // ------------------------------------------------------------------

    private static List<String> nombresDeTablas(Connection conn) throws SQLException {
        List<String> nombres = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name <> '" + TABLA_FLYWAY + "' ORDER BY table_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) nombres.add(rs.getString("table_name"));
        }
        return nombres;
    }

    /** Todas las filas de una tabla (el nombre ya debe venir de {@code information_schema}). */
    private static JsonArray filasDeTabla(Connection conn, String tableName) throws SQLException {
        JsonArray rows = new JsonArray();
        try (Statement dataStmt = conn.createStatement();
             ResultSet dataRs = dataStmt.executeQuery("SELECT * FROM \"" + tableName + "\" ORDER BY id")) {
            ResultSetMetaData meta = dataRs.getMetaData();
            while (dataRs.next()) {
                JsonObject row = new JsonObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String colName = meta.getColumnName(i);
                    Object val = dataRs.getObject(i);
                    if (val == null) row.addProperty(colName, (String) null);
                    else if (val instanceof Number) row.addProperty(colName, ((Number) val).doubleValue());
                    else row.addProperty(colName, val.toString());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Lanza 400 si la tabla no existe en el esquema public. Todo nombre de tabla del request pasa por aquí. */
    private static void exigirTablaValida(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ? AND table_name <> '" + TABLA_FLYWAY + "'")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ServicioException.solicitudInvalida("Tabla no válida");
                }
            }
        }
    }

    /** Mapa columna -> data_type de una tabla. */
    private static Map<String, String> tiposDeColumna(Connection conn, String tableName) throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString("column_name"), rs.getString("data_type").toLowerCase());
                }
            }
        }
        return types;
    }

    /** Setea un parámetro del PreparedStatement según el tipo de dato real de la columna. */
    private static void setTypedParam(PreparedStatement ps, int index, String value, String dataType)
            throws SQLException {
        if (value == null || value.isEmpty()) {
            int sqlType;
            switch (dataType) {
                case "integer":
                case "smallint":
                    sqlType = Types.INTEGER;
                    break;
                case "bigint":
                    sqlType = Types.BIGINT;
                    break;
                case "numeric":
                case "decimal":
                case "real":
                case "double precision":
                    sqlType = Types.NUMERIC;
                    break;
                case "boolean":
                    sqlType = Types.BOOLEAN;
                    break;
                case "timestamp without time zone":
                case "timestamp with time zone":
                    sqlType = Types.TIMESTAMP;
                    break;
                case "date":
                    sqlType = Types.DATE;
                    break;
                default:
                    sqlType = Types.VARCHAR;
                    break;
            }
            ps.setNull(index, sqlType);
            return;
        }

        switch (dataType) {
            case "integer":
            case "smallint":
                ps.setInt(index, Integer.parseInt(value));
                break;
            case "bigint":
                ps.setLong(index, Long.parseLong(value));
                break;
            case "numeric":
            case "decimal":
                ps.setBigDecimal(index, new java.math.BigDecimal(value));
                break;
            case "real":
                ps.setFloat(index, Float.parseFloat(value));
                break;
            case "double precision":
                ps.setDouble(index, Double.parseDouble(value));
                break;
            case "boolean":
                ps.setBoolean(index, Boolean.parseBoolean(value) || "1".equals(value));
                break;
            case "timestamp without time zone":
            case "timestamp with time zone":
                ps.setTimestamp(index, Timestamp.valueOf(value));
                break;
            case "date":
                ps.setDate(index, java.sql.Date.valueOf(value));
                break;
            default:
                ps.setString(index, value);
                break;
        }
    }
}
