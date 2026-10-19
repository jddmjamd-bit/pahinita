package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Operaciones del panel de administración de la base de datos: ver tablas, editar celdas, insertar,
 * borrar, exportar e importar (A3). Antes todo este SQL estaba dentro de {@code RutasDbAdmin}.
 *
 * El acceso al panel (clave, bloqueo por IP, cabeceras) sigue en {@code RutasDbAdmin}: es protocolo HTTP,
 * no lógica de negocio. Aquí todo nombre de tabla/columna que venga del request se valida contra
 * {@code information_schema} y contra {@link #IDENTIFICADOR_SQL} antes de citarlo en un SQL, y todas las
 * consultas usan exclusivamente {@link PreparedStatement} (S4, S10).
 */
public class DbAdminService {

    /** Orden de inserción al importar (respeta dependencias); al borrar se recorre al revés. */
    private static final String[] ORDEN_TABLAS_IMPORT = {
            "users", "messages", "matches", "transactions", "admin_wallet",
            "user_tokens", "user_tickets", "raffles", "raffle_entries"
    };

    /** S4: patrón estricto para identificadores SQL (tablas y columnas). */
    private static final Pattern IDENTIFICADOR_SQL = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final GenericDAO db;
    private final PostgresBackupService backups;

    /**
     * A5: tabla donde Flyway guarda el historial de migraciones. El panel no la muestra ni la deja tocar:
     * editarla desde la web podría hacer que Flyway rehaga o salte migraciones. Además no tiene columna id,
     * así que ORDER BY id (ver, exportar) fallaría con ella.
     */
    private static final String TABLA_FLYWAY = "flyway_schema_history";
    /** JTI son credenciales de sesión; el editor genérico no debe exponerlas ni permitir revocarlas indirectamente. */
    private static final String TABLA_SESIONES = "user_sessions";
    /** D3 / M4: El ledger financiero es inmutable; solo se permite lectura en el panel DB. */
    private static final String TABLA_LEDGER = "wallet_ledger";
    private static final String MASCARA_PASSWORD = "[HASH_PROTEGIDO]";
    private static final Set<String> TABLAS_NO_IMPORTABLES =
            Set.of("users", "transactions", "admin_wallet", "wallet_ledger", "user_tokens");

    public DbAdminService(GenericDAO db, ConexionDB conexion, AppConfig config) {
        this.db = db;
        this.backups = new PostgresBackupService(conexion, config);
    }

    public PostgresBackupService.ArchivoBackup crearBackupCompleto() {
        return backups.crearBackupCompleto();
    }

    public JsonObject restaurarBackupCompleto(java.io.InputStream archivo, long bytes, String confirmacion) {
        return backups.restaurarBackupCompleto(archivo, bytes, confirmacion);
    }

    public boolean isRestauracionActiva() {
        return backups.isRestauracionActiva();
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
            if (TABLA_LEDGER.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla wallet_ledger es inmutable y no se puede editar");
            }
            if ("users".equalsIgnoreCase(tableName) && "saldo".equalsIgnoreCase(column)) {
                throw ServicioException.prohibido(
                        "El saldo no se puede editar directamente en la BD (rompe wallet_ledger). Usa /api/deposit");
            }
            if ("users".equalsIgnoreCase(tableName) && "password".equalsIgnoreCase(column)
                    && MASCARA_PASSWORD.equals(value)) {
                return null; // No sobrescribir el hash real con la máscara visual
            }
            try (Connection conn = db.getConnection()) {
                exigirTablaValida(conn, tableName);
                String dataType = tiposDeColumna(conn, tableName).get(column);
                if (dataType == null) {
                    throw ServicioException.solicitudInvalida("Columna no válida");
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
            if (TABLA_LEDGER.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla wallet_ledger es inmutable y no permite borrar registros");
            }
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
        if (TABLA_LEDGER.equalsIgnoreCase(tableName)) {
            throw ServicioException.prohibido("La tabla wallet_ledger solo admite registros generados por WalletService");
        }
        if ("users".equalsIgnoreCase(tableName) && body != null && body.has("saldo") && !body.get("saldo").isJsonNull()) {
            String rawSaldo = body.get("saldo").getAsString().trim();
            if (!rawSaldo.isEmpty() && new BigDecimal(rawSaldo).compareTo(BigDecimal.ZERO) != 0) {
                throw ServicioException.prohibido(
                        "No se puede crear un usuario con saldo inicial distinto de 0 sin pasar por WalletService");
            }
        }
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

    /**
     * Exporta el subconjunto de tablas que este panel puede restaurar de forma segura.
     * No es un backup completo: excluye usuarios, datos financieros, tokens y tablas no soportadas.
     */
    public JsonObject exportar() {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                JsonObject backup = new JsonObject();
                List<String> tablasExistentes = nombresDeTablas(conn);
                Set<String> tablasSoportadas = new HashSet<>(Arrays.asList(ORDEN_TABLAS_IMPORT));
                JsonArray tablasOmitidas = new JsonArray();

                for (String tableName : tablasExistentes) {
                    if (!tablasSoportadas.contains(tableName)
                            || TABLAS_NO_IMPORTABLES.contains(tableName)) {
                        tablasOmitidas.add(tableName);
                        continue;
                    }
                    backup.add(tableName, filasDeTabla(conn, tableName));
                }

                JsonObject meta = new JsonObject();
                meta.addProperty("exportDate", new java.util.Date().toString());
                meta.addProperty("backupType", "db-admin-partial-v1");
                meta.addProperty("partial", true);
                meta.addProperty("tables", backup.size());
                meta.add("omittedTables", tablasOmitidas);
                meta.addProperty("warning",
                        "Respaldo parcial. No contiene usuarios, tokens push ni datos financieros. "
                                + "Usa un backup nativo de PostgreSQL para una recuperación completa.");
                backup.add("_meta", meta);
                return backup;
            }
        });
    }

    /**
     * Importa un respaldo parcial: borra e inserta las tablas incluidas dentro de una transacción.
     * Cualquier tabla, columna o fila no válida cancela la operación completa.
     *
     * @return número de filas insertadas
     */
    public int importar(JsonObject data) {
        return conErrores(() -> {
            if (data == null) {
                throw ServicioException.solicitudInvalida("El archivo de importación está vacío");
            }
            Set<String> tablasPermitidas = new HashSet<>(Arrays.asList(ORDEN_TABLAS_IMPORT));
            for (String tabla : data.keySet()) {
                if ("_meta".equals(tabla)) continue;
                if (TABLAS_NO_IMPORTABLES.contains(tabla)) {
                    throw ServicioException.prohibido(
                            "El panel no puede restaurar usuarios, tokens push ni datos financieros. "
                                    + "Usa un backup nativo de PostgreSQL para una restauración completa");
                }
                if (!tablasPermitidas.contains(tabla)) {
                    throw ServicioException.solicitudInvalida(
                            "El respaldo contiene una tabla que este panel no puede importar: " + tabla);
                }
                if (!data.get(tabla).isJsonArray()) {
                    throw ServicioException.solicitudInvalida(
                            "El contenido de la tabla " + tabla + " debe ser una lista de filas");
                }
            }

            int totalInserted = 0;
            try (Connection conn = db.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // M4: Borrar e insertar dentro de UNA sola transacción SQL para no dejar tablas vacías si algo falla
                    for (int i = ORDEN_TABLAS_IMPORT.length - 1; i >= 0; i--) {
                        String tabla = ORDEN_TABLAS_IMPORT[i];
                        if (data.has(tabla)) {
                            try (PreparedStatement delPs = conn.prepareStatement("DELETE FROM " + citarIdentificador(tabla))) {
                                delPs.executeUpdate();
                            }
                        }
                    }

                    for (String tabla : ORDEN_TABLAS_IMPORT) {
                        if (!data.has(tabla)) continue;

                        String tablaCitada = citarIdentificador(tabla);
                        Map<String, String> columnasValidas = tiposDeColumna(conn, tabla);
                        JsonArray rows = data.getAsJsonArray(tabla);
                        for (JsonElement elem : rows) {
                            if (elem == null || !elem.isJsonObject()) {
                                throw ServicioException.solicitudInvalida(
                                        "El respaldo contiene una fila no válida en la tabla " + tabla);
                            }
                            JsonObject row = elem.getAsJsonObject();
                        StringBuilder cols = new StringBuilder();
                        StringBuilder ph = new StringBuilder();
                        List<String> vals = new ArrayList<>();
                        List<String> types = new ArrayList<>();
                        for (String key : row.keySet()) {
                            if (!columnasValidas.containsKey(key)) {
                                throw ServicioException.solicitudInvalida(
                                        "El respaldo contiene una columna no válida en la tabla " + tabla + ": " + key);
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
                        if (vals.isEmpty()) {
                            throw ServicioException.solicitudInvalida(
                                    "El respaldo contiene una fila vacía en la tabla " + tabla);
                        }

                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO " + tablaCitada + " (" + cols + ") VALUES (" + ph + ")")) {
                            for (int j = 0; j < vals.size(); j++) {
                                setTypedParam(ps, j + 1, vals.get(j), types.get(j));
                            }
                            ps.executeUpdate();
                            totalInserted++;
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
                            }
                        }
                    }
                    conn.commit();
                } catch (Exception e) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw e;
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                }
            }
            return totalInserted;
        });
    }

    // ------------------------------------------------------------------
    // Metadatos y validación (S4 / S10)
    // ------------------------------------------------------------------

    private static List<String> nombresDeTablas(Connection conn) throws SQLException {
        List<String> nombres = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name NOT IN (?, ?) ORDER BY table_name")) {
            ps.setString(1, TABLA_FLYWAY);
            ps.setString(2, TABLA_SESIONES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) nombres.add(rs.getString("table_name"));
            }
        }
        return nombres;
    }

    /**
     * S4 / D1 / M4: Todas las filas de una tabla usando {@link PreparedStatement}, conservando {@link BigDecimal},
     * ordenando por {@code id} si la tabla lo tiene (o por su primera columna en tablas como {@code raffle_votes}
     * o {@code discord_links}) y enmascarando el hash {@code password} de {@code users}.
     */
    private static JsonArray filasDeTabla(Connection conn, String tableName) throws SQLException {
        String tablaCitada = citarIdentificador(tableName);
        Map<String, String> columnas = tiposDeColumna(conn, tableName);
        String colOrden = columnas.containsKey("id")
                ? "\"id\""
                : (columnas.isEmpty() ? "1" : citarIdentificador(columnas.keySet().iterator().next()));
        boolean esTablaUsers = "users".equalsIgnoreCase(tableName);

        JsonArray rows = new JsonArray();
        try (PreparedStatement dataPs = conn.prepareStatement("SELECT * FROM " + tablaCitada + " ORDER BY " + colOrden);
             ResultSet dataRs = dataPs.executeQuery()) {
            ResultSetMetaData meta = dataRs.getMetaData();
            while (dataRs.next()) {
                JsonObject row = new JsonObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String colName = meta.getColumnName(i);
                    if (esTablaUsers && "password".equalsIgnoreCase(colName)) {
                        row.addProperty(colName, MASCARA_PASSWORD);
                        continue;
                    }
                    Object val = dataRs.getObject(i);
                    if (val == null) row.addProperty(colName, (String) null);
                    else if (val instanceof BigDecimal) row.addProperty(colName, (BigDecimal) val); // REVIEW-MONEY
                    else if (val instanceof Number) row.addProperty(colName, (Number) val);
                    else if (val instanceof byte[]) row.addProperty(colName, "[BYTEA " + ((byte[]) val).length + " bytes]");
                    else row.addProperty(colName, val.toString());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Lanza 400 si la tabla no existe en el esquema public. Todo nombre de tabla del request pasa por aquí. */
    private static void exigirTablaValida(Connection conn, String tableName) throws SQLException {
        if (tableName == null || !IDENTIFICADOR_SQL.matcher(tableName).matches()) {
            throw ServicioException.solicitudInvalida("Tabla no válida");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ? AND table_name NOT IN (?, ?)")) {
            ps.setString(1, tableName);
            ps.setString(2, TABLA_FLYWAY);
            ps.setString(3, TABLA_SESIONES);
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

    /**
     * Cita un identificador de tabla o columna tras verificar que cumple {@link #IDENTIFICADOR_SQL}.
     */
    private static String citarIdentificador(String identificador) {
        if (identificador == null || !IDENTIFICADOR_SQL.matcher(identificador).matches()) {
            throw ServicioException.solicitudInvalida("Identificador SQL no válido");
        }
        return "\"" + identificador + "\"";
    }

    /** Setea un parámetro del PreparedStatement según el tipo de dato real de la columna. */
    private static void setTypedParam(PreparedStatement ps, int index, String value, String dataType) throws SQLException {
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
            case "real":
            case "double precision":
                ps.setBigDecimal(index, new BigDecimal(value));
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
