package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Operaciones del panel de administraci�n de la base de datos: ver tablas, editar celdas, insertar,
 * borrar, exportar e importar (A3). Antes todo este SQL estaba dentro de {@code RutasDbAdmin}.
 *
 * El acceso al panel (clave, bloqueo por IP, cabeceras) sigue en {@code RutasDbAdmin}: es protocolo HTTP,
 * no l�gica de negocio. Aqu� todo nombre de tabla/columna que venga del request se valida contra
 * {@code information_schema} y contra {@link #IDENTIFICADOR_SQL} antes de citarlo en un SQL, y todas las
 * consultas usan exclusivamente {@link PreparedStatement} (S4, S10).
 */
public class DbAdminService {

    /** Orden de inserci�n al importar (respeta dependencias); al borrar se recorre al rev�s. */
    private static final String[] ORDEN_TABLAS_IMPORT = { "users", "messages", "matches", "transactions", "admin_wallet",
            "user_tokens", "user_tickets", "raffles", "raffle_entries" };

    /** S4: patr�n estricto para identificadores SQL (tablas y columnas). */
    private static final Pattern IDENTIFICADOR_SQL = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final GenericDAO db;

    /**
     * A5: tabla donde Flyway guarda el historial de migraciones. El panel no la muestra ni la deja tocar:
     * editarla desde la web podr�a hacer que Flyway rehaga o salte migraciones. Adem�s no tiene columna `id`,
     * as� que `ORDER BY id` (ver, exportar) fallar�a con ella.
     */
    private static final String TABLA_FLYWAY = "flyway_schema_history";

    public DbAdminService(GenericDAO db) {
        this.db = db;
    }

    /** Operaci�n de BD que puede lanzar excepciones SQL. */
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

    /** Lista las tablas del esquema public con su n�mero de filas. */
    public JsonArray listarTablas() {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                JsonArray tables = new JsonArray();
                for (String tableName : nombresDeTablas(conn)) {
                    String tablaCitada = citarIdentificador(tableName);
                    try (PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) as count FROM " + tablaCitada);
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
                    throw ServicioException.solicitudInvalida("Columna no v�lida");
                }

                String tablaCitada = citarIdentificador(tableName);
                String columnaCitada = citarIdentificador(column);
                String finalVal = (value != null && !value.isEmpty()) ? value : null;
                try (PreparedStatement updatePs = conn.prepareStatement(
                        "UPDATE " + tablaCitada + " SET " + columnaCitada + " = ? WHERE id = ?")) {
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
                String tablaCitada = citarIdentificador(tableName);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tablaCitada + " WHERE id = ?")) {
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
                        throw ServicioException.solicitudInvalida("Columna no v�lida");
                    }
                    if (cols.length() > 0) {
                        cols.append(", ");
                        placeholders.append(", ");
                    }
                    cols.append(citarIdentificador(key));
                    placeholders.append("?");
                    values.add(body.get(key).isJsonNull() ? null : body.get(key).getAsString());
                    types.add(columnTypes.get(key));
                }

                String tablaCitada = citarIdentificador(tableName);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + tablaCitada + " (" + cols + ") VALUES (" + placeholders + ")")) {
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

    /** Exporta todas las tablas (m�s un bloque {@code _meta}). */
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
     * Solo se aceptan columnas que existan en la tabla; las filas inv�lidas se omiten.
     *
     * @return n�mero de filas insertadas
     */
    public int importar(JsonObject data) {
        return conErrores(() -> {
            // Borrar en orden inverso (tablas fijas de lista blanca)
            for (int i = ORDEN_TABLAS_IMPORT.length - 1; i >= 0; i--) {
                String tabla = ORDEN_TABLAS_IMPORT[i];
                if (data.has(tabla)) {
                    db.update("DELETE FROM " + citarIdentificador(tabla));
                }
            }

            int totalInserted = 0;
            try (Connection conn = db.getConnection()) {
                for (String tabla : ORDEN_TABLAS_IMPORT) {
                    if (!data.has(tabla) || !data.get(tabla).isJsonArray()) continue;

                    String tablaCitada = citarIdentificador(tabla);
                    Map<String, String> columnasValidas = tiposDeColumna(conn, tabla);
                    JsonArray rows = data.getAsJsonArray(tabla);
                    for (JsonElement elem : rows) {
                        JsonObject row = elem.getAsJsonObject();
                        StringBuilder cols = new StringBuilder();
                        StringBuilder ph = new StringBuilder();
                        List<String> vals = new ArrayList<>();
                        List<String> types = new ArrayList<>();
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
                            cols.append(citarIdentificador(key));
                            ph.append("?");
                            vals.add(row.get(key).isJsonNull() ? null : row.get(key).getAsString());
                            types.add(columnasValidas.get(key));
                        }
                        if (!columnasOk || vals.isEmpty()) continue;

                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO " + tablaCitada + " (" + cols + ") VALUES (" + ph + ")")) {
                            for (int j = 0; j < vals.size(); j++) {
                                setTypedParam(ps, j + 1, vals.get(j), types.get(j));
                            }
                            ps.executeUpdate();
                            totalInserted++;
                        } catch (Exception ignored) {
                            // fila inv�lida: se omite
                        }
                    }
                }

                // S4: Resetear secuencias usando PreparedStatement en lugar de Statement.execute()
                for (String tabla : ORDEN_TABLAS_IMPORT) {
                    if (data.has(tabla)) {
                        String tablaCitada = citarIdentificador(tabla);
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT setval(pg_get_serial_sequence(?, 'id'), COALESCE((SELECT MAX(id) FROM " +
                                        tablaCitada + "), 0) + 1, false)")) {
                            ps.setString(1, tabla);
                            try (ResultSet ignored = ps.executeQuery()) {
                                // secuencia actualizada
                            }
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

    /**
     * S4: Valida que un identificador SQL cumpla el formato permitido y lo devuelve entre comillas dobles.
     */
    private static String citarIdentificador(String identificador) {
        if (identificador == null || !IDENTIFICADOR_SQL.matcher(identificador).matches()) {
            throw ServicioException.solicitudInvalida("Identificador SQL no v�lido");
        }
        return "\"" + identificador + "\"";
    }

    private static List<String> nombresDeTablas(Connection conn) throws SQLException {
        List<String> nombres = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name <> ? ORDER BY table_name")) {
            ps.setString(1, TABLA_FLYWAY);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) nombres.add(rs.getString("table_name"));
            }
        }
        return nombres;
    }

    /**
     * S4: Todas las filas de una tabla usando {@link PreparedStatement} (el nombre ya fue validado contra
     * {@code information_schema} y pasa por {@link #citarIdentificador}).
     */
    private static JsonArray filasDeTabla(Connection conn, String tableName) throws SQLException {
        String tablaCitada = citarIdentificador(tableName);
        JsonArray rows = new JsonArray();
        try (PreparedStatement dataPs = conn.prepareStatement("SELECT * FROM " + tablaCitada + " ORDER BY id");
             ResultSet dataRs = dataPs.executeQuery()) {
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

    /** Lanza 400 si la tabla no existe en el esquema public. Todo nombre de tabla del request pasa por aqu�. */
    private static void exigirTablaValida(Connection conn, String tableName) throws SQLException {
        if (tableName == null || !IDENTIFICADOR_SQL.matcher(tableName).matches()) {
            throw ServicioException.solicitudInvalida("Tabla no v�lida");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ? AND table_name <> ?")) {
            ps.setString(1, tableName);
            ps.setString(2, TABLA_FLYWAY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ServicioException.solicitudInvalida("Tabla no v�lida");
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

    /** Setea un par�metro del PreparedStatement seg�n el tipo de dato real de la columna. */
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
