package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas del panel de administración de la base de datos.
 * Equivalente al bloque admin-db de index.js.
 * Incluye: visualizar tablas, editar celdas, importar/exportar.
 *
 * S10 — acceso por navegador más seguro:
 * <ul>
 *   <li>La URL del panel es {@code /admin-db} (sin la clave). Antes era {@code /admin-db/<clave>}, y la
 *       clave quedaba en el historial del navegador, en los logs del servidor y en el header Referer.
 *       Ahora la clave se escribe en una pantalla de acceso y viaja en el header {@code X-DB-Admin-Key}.</li>
 *   <li>Ya no existe clave por defecto: sin {@code DB_ADMIN_SECRET} (o con la antigua {@code torneos2024},
 *       que estaba publicada en el código) el panel queda DESHABILITADO.</li>
 *   <li>La clave se compara en tiempo constante y tras 5 intentos fallidos la IP se bloquea 15 minutos.</li>
 *   <li>Todas las rutas validan el nombre de tabla/columna contra {@code information_schema} (antes
 *       {@code delete} e {@code insert} concatenaban nombres sin validar).</li>
 *   <li>Cabeceras de seguridad: no cachear, no iframes, sin Referer, sin indexar.</li>
 * </ul>
 */
public class RutasDbAdmin {
    private static final Logger logger = LoggerFactory.getLogger(RutasDbAdmin.class);

    /** Header por el que viaja la clave del panel. */
    static final String HEADER_CLAVE = "X-DB-Admin-Key";

    /** Clave que venía por defecto en el código: ya no se acepta. */
    private static final String CLAVE_ANTIGUA_PUBLICA = "torneos2024";
    private static final int LARGO_RECOMENDADO = 16;

    private static final int MAX_FALLOS = 5;
    private static final long VENTANA_FALLOS_MS = 15 * 60_000L;
    private static final int MAX_IPS_RASTREADAS = 10_000;
    /** IP -> {fallos, inicio de la ventana en ms}. En memoria (se reinicia con el servidor). */
    private static final ConcurrentHashMap<String, long[]> FALLOS = new ConcurrentHashMap<>();

    public static void register(Javalin app, GenericDAO db, AppConfig config) {

        final String secret = config.getDbAdminSecret();
        final int saltosProxy = config.getRateLimitProxyHops();
        final boolean habilitado = secret != null && !secret.isBlank()
                && !CLAVE_ANTIGUA_PUBLICA.equalsIgnoreCase(secret.trim());

        if (!habilitado) {
            logger.error("⛔ Panel DB deshabilitado: define DB_ADMIN_SECRET con una clave propia "
                    + "(no vacía y distinta de la clave antigua publicada en el código).");
        } else if (secret.length() < LARGO_RECOMENDADO) {
            logger.warn("⚠️ DB_ADMIN_SECRET tiene menos de {} caracteres. Usa una clave larga y aleatoria.", LARGO_RECOMENDADO);
        }
        final byte[] huellaClave = habilitado ? sha256(secret) : null;

        // --- Panel HTML: /admin-db (sin clave en la URL) ---
        app.before("/admin-db", ctx -> {
            if (!habilitado) {
                ctx.status(404).result("Not found");
                ctx.skipRemainingHandlers();
                return;
            }
            cabecerasSeguras(ctx);
            // La pantalla de acceso no puede ir en un iframe ni filtrar la URL por Referer
            ctx.header("Content-Security-Policy",
                    "frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'");
        });

        app.get("/admin-db", ctx -> {
            // Buscar admin-db.html en varios lugares (fuente única: public/admin-db.html)
            String[] paths = { "admin-db.html", "public/admin-db.html", "../public/admin-db.html",
                    "src/main/resources/admin-db.html" };
            for (String p : paths) {
                File f = new File(p);
                if (f.exists()) {
                    ctx.contentType("text/html");
                    ctx.result(new FileInputStream(f));
                    return;
                }
            }
            // Intentar desde classpath
            InputStream is = RutasDbAdmin.class.getClassLoader().getResourceAsStream("admin-db.html");
            if (is != null) {
                ctx.contentType("text/html");
                ctx.result(is);
            } else {
                ctx.status(404).result("admin-db.html no encontrado");
            }
        });

        // URL antigua /admin-db/<clave>: ya no sirve nada. Se redirige a /admin-db SIN la clave
        // (esa clave ya pasó por la URL, así que conviene rotar DB_ADMIN_SECRET en Render).
        app.get("/admin-db/{secret}", ctx -> ctx.redirect("/admin-db", io.javalin.http.HttpStatus.FOUND));

        // --- API: la clave viaja en el header X-DB-Admin-Key ---
        app.before("/api/db-admin/*", ctx -> {
            if ("OPTIONS".equalsIgnoreCase(ctx.req().getMethod())) return; // preflight CORS
            if (!habilitado) {
                ctx.status(404).json(errorJson("No disponible"));
                ctx.skipRemainingHandlers();
                return;
            }
            cabecerasSeguras(ctx);
            verificarClave(ctx, huellaClave, saltosProxy);
        });

        // GET /api/db-admin/tables
        app.get("/api/db-admin/tables", ctx -> {
            try (Connection conn = db.getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name");
                ResultSet rs = ps.executeQuery();
                JsonArray tables = new JsonArray();
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) as count FROM \"" + tableName + "\"");
                    ResultSet countRs = countPs.executeQuery();
                    countRs.next();
                    JsonObject t = new JsonObject();
                    t.addProperty("name", tableName);
                    t.addProperty("count", countRs.getInt("count"));
                    tables.add(t);
                    countPs.close();
                }
                ctx.json(tables);
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // GET /api/db-admin/table/:name
        app.get("/api/db-admin/table/{name}", ctx -> {
            String tableName = ctx.pathParam("name");
            try (Connection conn = db.getConnection()) {
                if (!tablaValida(conn, tableName)) {
                    ctx.status(400).json(errorJson("Tabla no válida"));
                    return;
                }

                // Columnas
                PreparedStatement colPs = conn.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position");
                colPs.setString(1, tableName);
                ResultSet colRs = colPs.executeQuery();
                JsonArray columns = new JsonArray();
                while (colRs.next()) {
                    JsonObject col = new JsonObject();
                    col.addProperty("column_name", colRs.getString("column_name"));
                    col.addProperty("data_type", colRs.getString("data_type"));
                    columns.add(col);
                }

                // Datos
                Statement dataStmt = conn.createStatement();
                ResultSet dataRs = dataStmt.executeQuery("SELECT * FROM \"" + tableName + "\" ORDER BY id");
                ResultSetMetaData meta = dataRs.getMetaData();
                JsonArray rows = new JsonArray();
                while (dataRs.next()) {
                    JsonObject row = new JsonObject();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String colName = meta.getColumnName(i);
                        Object val = dataRs.getObject(i);
                        if (val == null)
                            row.addProperty(colName, (String) null);
                        else if (val instanceof Number)
                            row.addProperty(colName, ((Number) val).doubleValue());
                        else
                            row.addProperty(colName, val.toString());
                    }
                    rows.add(row);
                }

                JsonObject result = new JsonObject();
                result.add("columns", columns);
                result.add("rows", rows);
                ctx.json(result);
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // POST /api/db-admin/table/:name/update
        app.post("/api/db-admin/table/{name}/update", ctx -> {
            JsonObject body = parseBody(ctx);
            String tableName = ctx.pathParam("name");
            int id = body.get("id").getAsInt();
            String column = body.get("column").getAsString();
            String value = body.has("value") && !body.get("value").isJsonNull() ? body.get("value").getAsString()
                    : null;

            // Validar tabla y columna, y obtener el tipo de dato
            try (Connection conn = db.getConnection()) {
                if (!tablaValida(conn, tableName)) {
                    ctx.status(400).json(errorJson("Tabla no válida"));
                    return;
                }
                Map<String, String> tipos = getColumnTypes(conn, tableName);
                String dataType = tipos.get(column);
                if (dataType == null) {
                    ctx.status(400).json(errorJson("Columna no válida"));
                    return;
                }

                String finalVal = (value != null && !value.isEmpty()) ? value : null;
                PreparedStatement updatePs = conn.prepareStatement(
                        "UPDATE \"" + tableName + "\" SET \"" + column + "\" = ? WHERE id = ?");
                setTypedParam(updatePs, 1, finalVal, dataType);
                updatePs.setInt(2, id);
                updatePs.executeUpdate();
                ctx.json(successJson("Actualizado", 0));
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // POST /api/db-admin/table/:name/delete
        app.post("/api/db-admin/table/{name}/delete", ctx -> {
            JsonObject body = parseBody(ctx);
            String tableName = ctx.pathParam("name");
            try (Connection conn = db.getConnection()) {
                if (!tablaValida(conn, tableName)) {
                    ctx.status(400).json(errorJson("Tabla no válida"));
                    return;
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM \"" + tableName + "\" WHERE id = ?")) {
                    ps.setInt(1, body.get("id").getAsInt());
                    ps.executeUpdate();
                }
                ctx.json(successJson("Eliminado", 0));
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // POST /api/db-admin/table/:name/insert
        app.post("/api/db-admin/table/{name}/insert", ctx -> {
            JsonObject body = parseBody(ctx);
            String tableName = ctx.pathParam("name");
            Set<String> keys = body.keySet();
            if (keys.isEmpty()) {
                ctx.status(400).json(errorJson("Sin datos"));
                return;
            }

            try (Connection conn = db.getConnection()) {
                if (!tablaValida(conn, tableName)) {
                    ctx.status(400).json(errorJson("Tabla no válida"));
                    return;
                }
                // Obtener tipos de columna de la tabla
                Map<String, String> columnTypes = getColumnTypes(conn, tableName);

                StringBuilder cols = new StringBuilder();
                StringBuilder placeholders = new StringBuilder();
                List<String> values = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (String key : keys) {
                    if (!columnTypes.containsKey(key)) {
                        ctx.status(400).json(errorJson("Columna no válida"));
                        return;
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
                    ctx.json(successJson("Insertado", 0));
                }
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // GET /api/db-admin/export
        app.get("/api/db-admin/export", ctx -> {
            try (Connection conn = db.getConnection()) {
                PreparedStatement tablesPs = conn.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name");
                ResultSet tablesRs = tablesPs.executeQuery();
                JsonObject backup = new JsonObject();

                while (tablesRs.next()) {
                    String tableName = tablesRs.getString("table_name");
                    Statement dataStmt = conn.createStatement();
                    ResultSet dataRs = dataStmt.executeQuery("SELECT * FROM \"" + tableName + "\" ORDER BY id");
                    ResultSetMetaData meta = dataRs.getMetaData();
                    JsonArray rows = new JsonArray();
                    while (dataRs.next()) {
                        JsonObject row = new JsonObject();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            String colName = meta.getColumnName(i);
                            Object val = dataRs.getObject(i);
                            if (val == null)
                                row.addProperty(colName, (String) null);
                            else if (val instanceof Number)
                                row.addProperty(colName, ((Number) val).doubleValue());
                            else
                                row.addProperty(colName, val.toString());
                        }
                        rows.add(row);
                    }
                    backup.add(tableName, rows);
                    dataStmt.close();
                }

                JsonObject meta = new JsonObject();
                meta.addProperty("exportDate", new java.util.Date().toString());
                meta.addProperty("tables", backup.keySet().size());
                backup.add("_meta", meta);
                ctx.json(backup);
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // POST /api/db-admin/import
        app.post("/api/db-admin/import", ctx -> {
            try {
                JsonObject data = parseBody(ctx);
                String[] ordenTablas = { "users", "messages", "matches", "transactions", "admin_wallet",
                        "user_tokens", "user_tickets", "raffles", "raffle_entries" };

                // Borrar en orden inverso
                for (int i = ordenTablas.length - 1; i >= 0; i--) {
                    if (data.has(ordenTablas[i])) {
                        db.update("DELETE FROM " + ordenTablas[i]);
                    }
                }

                // Insertar
                int totalInserted = 0;
                try (Connection conn = db.getConnection()) {
                    for (String tabla : ordenTablas) {
                        if (!data.has(tabla) || !data.get(tabla).isJsonArray())
                            continue;
                        // Nombres de columna del archivo importado: solo los que existen en la tabla
                        Map<String, String> columnasValidas = getColumnTypes(conn, tabla);
                        JsonArray rows = data.getAsJsonArray(tabla);
                        for (JsonElement elem : rows) {
                            JsonObject row = elem.getAsJsonObject();
                            Set<String> keys = row.keySet();
                            StringBuilder cols = new StringBuilder();
                            StringBuilder ph = new StringBuilder();
                            List<String> vals = new ArrayList<>();
                            boolean columnasOk = true;
                            for (String key : keys) {
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
                            if (!columnasOk || vals.isEmpty())
                                continue;
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO " + tabla + " (" + cols + ") VALUES (" + ph + ")")) {
                                for (int j = 0; j < vals.size(); j++) {
                                    if (vals.get(j) == null)
                                        ps.setNull(j + 1, Types.VARCHAR);
                                    else
                                        ps.setString(j + 1, vals.get(j));
                                }
                                ps.executeUpdate();
                                totalInserted++;
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    // Resetear secuencias
                    for (String tabla : ordenTablas) {
                        if (data.has(tabla)) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute("SELECT setval(pg_get_serial_sequence('" + tabla +
                                        "', 'id'), COALESCE((SELECT MAX(id) FROM " + tabla + "), 0) + 1, false)");
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }

                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                res.addProperty("message", totalInserted + " filas importadas");
                ctx.json(res);
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------
    // Autenticación del panel (S10)
    // ------------------------------------------------------------------

    /**
     * Comprueba el header {@link #HEADER_CLAVE}. Sin header: 401 (no cuenta como intento fallido, es la
     * pantalla de acceso preguntando). Clave incorrecta: 403 y cuenta un fallo; a los
     * {@link #MAX_FALLOS} fallos la IP queda bloqueada {@code VENTANA_FALLOS_MS}.
     */
    private static void verificarClave(Context ctx, byte[] huellaEsperada, int saltosProxy) {
        String ip = RateLimitMiddleware.ipCliente(ctx, saltosProxy);
        long ahora = System.currentTimeMillis();

        long bloqueadoSeg = segundosDeBloqueo(ip, ahora);
        if (bloqueadoSeg > 0) {
            JsonObject cuerpo = errorJson("Demasiados intentos fallidos. Intenta de nuevo en " + bloqueadoSeg + " segundos.");
            cuerpo.addProperty("retryAfter", bloqueadoSeg);
            ctx.status(429).header("Retry-After", String.valueOf(bloqueadoSeg)).json(cuerpo);
            ctx.skipRemainingHandlers();
            return;
        }

        String recibida = ctx.header(HEADER_CLAVE);
        if (recibida == null || recibida.isEmpty()) {
            ctx.status(401).json(errorJson("Clave requerida"));
            ctx.skipRemainingHandlers();
            return;
        }

        // Se comparan las huellas SHA-256 en tiempo constante: no se filtra nada por el tiempo de respuesta
        if (!MessageDigest.isEqual(huellaEsperada, sha256(recibida))) {
            boolean quedoBloqueada = registrarFallo(ip, ahora);
            logger.warn("⛔ Panel DB: clave incorrecta desde {}{}", ip, quedoBloqueada ? " (IP bloqueada 15 min)" : "");
            ctx.status(403).json(errorJson("Clave incorrecta"));
            ctx.skipRemainingHandlers();
            return;
        }

        FALLOS.remove(ip); // acceso correcto: se reinicia el contador de la IP
    }

    private static long segundosDeBloqueo(String ip, long ahora) {
        long[] f = FALLOS.get(ip);
        if (f == null || f[0] < MAX_FALLOS) return 0;
        long restanteMs = f[1] + VENTANA_FALLOS_MS - ahora;
        return restanteMs > 0 ? (restanteMs + 999L) / 1000L : 0;
    }

    /** Suma un fallo a la IP. Devuelve true si con este fallo llegó al límite. */
    private static boolean registrarFallo(String ip, long ahora) {
        if (FALLOS.size() >= MAX_IPS_RASTREADAS) {
            FALLOS.values().removeIf(v -> ahora - v[1] >= VENTANA_FALLOS_MS);
            if (FALLOS.size() >= MAX_IPS_RASTREADAS) return false; // lleno de IPs activas: no se rastrea la nueva
        }
        final boolean[] limite = { false };
        FALLOS.compute(ip, (k, v) -> {
            if (v == null || ahora - v[1] >= VENTANA_FALLOS_MS) {
                limite[0] = MAX_FALLOS <= 1;
                return new long[] { 1L, ahora };
            }
            v[0]++;
            limite[0] = v[0] == MAX_FALLOS;
            return v;
        });
        return limite[0];
    }

    private static byte[] sha256(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private static void cabecerasSeguras(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Robots-Tag", "noindex, nofollow");
    }

    // ------------------------------------------------------------------
    // Utilidades de BD
    // ------------------------------------------------------------------

    /** true si la tabla existe en el esquema public. Todo nombre de tabla de la URL pasa por aquí. */
    private static boolean tablaValida(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Obtiene un mapa de columna -> data_type para una tabla.
     */
    private static Map<String, String> getColumnTypes(Connection conn, String tableName) throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position");
        ps.setString(1, tableName);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            types.put(rs.getString("column_name"), rs.getString("data_type").toLowerCase());
        }
        ps.close();
        return types;
    }

    /**
     * Setea un parámetro del PreparedStatement según el tipo de dato real de la
     * columna.
     */
    private static void setTypedParam(PreparedStatement ps, int index, String value, String dataType)
            throws SQLException {
        if (value == null || value.isEmpty()) {
            // Determinar el tipo SQL correcto para el null
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
