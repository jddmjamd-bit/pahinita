package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import io.javalin.Javalin;
import java.io.*;
import java.sql.*;
import java.util.*;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas del panel de administración de la base de datos.
 * Equivalente al bloque admin-db de index.js.
 * Incluye: visualizar tablas, editar celdas, importar/exportar.
 */
public class RutasDbAdmin {

    public static void register(Javalin app, GenericDAO db, AppConfig config) {

        String secret = config.getDbAdminSecret();

        // Middleware de verificación de secreto
        app.before("/admin-db/{secret}", ctx -> verificarSecreto(ctx, secret));
        app.before("/api/db-admin/{secret}/*", ctx -> verificarSecreto(ctx, secret));

        // GET /admin-db/:secret - Servir el panel HTML
        app.get("/admin-db/{secret}", ctx -> {
            // Buscar admin-db.html en varios lugares
            String[] paths = {"admin-db.html", "src/main/resources/admin-db.html", "../admin-db.html"};
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

        // GET /api/db-admin/:secret/tables
        app.get("/api/db-admin/{secret}/tables", ctx -> {
            try (Connection conn = db.getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name");
                ResultSet rs = ps.executeQuery();
                JsonArray tables = new JsonArray();
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) as count FROM " + tableName);
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

        // GET /api/db-admin/:secret/table/:name
        app.get("/api/db-admin/{secret}/table/{name}", ctx -> {
            String tableName = ctx.pathParam("name");
            try (Connection conn = db.getConnection()) {
                // Validar tabla
                PreparedStatement validPs = conn.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'");
                ResultSet validRs = validPs.executeQuery();
                boolean valid = false;
                while (validRs.next()) {
                    if (tableName.equals(validRs.getString("table_name"))) { valid = true; break; }
                }
                if (!valid) { ctx.status(400).json(errorJson("Tabla no válida")); return; }

                // Columnas
                PreparedStatement colPs = conn.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position");
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
                ResultSet dataRs = dataStmt.executeQuery("SELECT * FROM " + tableName + " ORDER BY id");
                ResultSetMetaData meta = dataRs.getMetaData();
                JsonArray rows = new JsonArray();
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

                JsonObject result = new JsonObject();
                result.add("columns", columns);
                result.add("rows", rows);
                ctx.json(result);
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // POST /api/db-admin/:secret/table/:name/update
        app.post("/api/db-admin/{secret}/table/{name}/update", ctx -> {
            JsonObject body = parseBody(ctx);
            String tableName = ctx.pathParam("name");
            int id = body.get("id").getAsInt();
            String column = body.get("column").getAsString();
            String value = body.has("value") && !body.get("value").isJsonNull() ? body.get("value").getAsString() : null;

            // Validar columna y obtener su tipo de dato
            try (Connection conn = db.getConnection()) {
                PreparedStatement colPs = conn.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?");
                colPs.setString(1, tableName);
                colPs.setString(2, column);
                ResultSet colRs = colPs.executeQuery();
                if (!colRs.next()) { ctx.status(400).json(errorJson("Columna no válida")); return; }
                String dataType = colRs.getString("data_type");

                String finalVal = (value != null && !value.isEmpty()) ? value : null;
                PreparedStatement updatePs = conn.prepareStatement(
                        "UPDATE " + tableName + " SET " + column + " = ? WHERE id = ?");
                setTypedParam(updatePs, 1, finalVal, dataType);
                updatePs.setInt(2, id);
                updatePs.executeUpdate();
                ctx.json(successJson("Actualizado", 0));
            } catch (Exception e) {
                ctx.status(500).json(errorJson(e.getMessage()));
            }
        });

        // POST /api/db-admin/:secret/table/:name/delete
        app.post("/api/db-admin/{secret}/table/{name}/delete", ctx -> {
            JsonObject body = parseBody(ctx);
            db.update("DELETE FROM " + ctx.pathParam("name") + " WHERE id = ?", body.get("id").getAsInt());
            ctx.json(successJson("Eliminado", 0));
        });

        // POST /api/db-admin/:secret/table/:name/insert
        app.post("/api/db-admin/{secret}/table/{name}/insert", ctx -> {
            JsonObject body = parseBody(ctx);
            String tableName = ctx.pathParam("name");
            Set<String> keys = body.keySet();
            if (keys.isEmpty()) { ctx.status(400).json(errorJson("Sin datos")); return; }

            try (Connection conn = db.getConnection()) {
                // Obtener tipos de columna de la tabla
                Map<String, String> columnTypes = getColumnTypes(conn, tableName);

                StringBuilder cols = new StringBuilder();
                StringBuilder placeholders = new StringBuilder();
                List<String> values = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (String key : keys) {
                    if (cols.length() > 0) { cols.append(", "); placeholders.append(", "); }
                    cols.append(key);
                    placeholders.append("?");
                    values.add(body.get(key).isJsonNull() ? null : body.get(key).getAsString());
                    types.add(columnTypes.getOrDefault(key, "text"));
                }

                try (PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO " + tableName + " (" + cols + ") VALUES (" + placeholders + ")")) {
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

        // GET /api/db-admin/:secret/export
        app.get("/api/db-admin/{secret}/export", ctx -> {
            try (Connection conn = db.getConnection()) {
                PreparedStatement tablesPs = conn.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name");
                ResultSet tablesRs = tablesPs.executeQuery();
                JsonObject backup = new JsonObject();

                while (tablesRs.next()) {
                    String tableName = tablesRs.getString("table_name");
                    Statement dataStmt = conn.createStatement();
                    ResultSet dataRs = dataStmt.executeQuery("SELECT * FROM " + tableName + " ORDER BY id");
                    ResultSetMetaData meta = dataRs.getMetaData();
                    JsonArray rows = new JsonArray();
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

        // POST /api/db-admin/:secret/import
        app.post("/api/db-admin/{secret}/import", ctx -> {
            try {
                JsonObject data = parseBody(ctx);
                String[] ordenTablas = {"users", "messages", "matches", "transactions", "admin_wallet",
                        "user_tokens", "user_tickets", "raffles", "raffle_entries"};

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
                        if (!data.has(tabla) || !data.get(tabla).isJsonArray()) continue;
                        JsonArray rows = data.getAsJsonArray(tabla);
                        for (JsonElement elem : rows) {
                            JsonObject row = elem.getAsJsonObject();
                            Set<String> keys = row.keySet();
                            StringBuilder cols = new StringBuilder();
                            StringBuilder ph = new StringBuilder();
                            List<String> vals = new ArrayList<>();
                            for (String key : keys) {
                                if (cols.length() > 0) { cols.append(", "); ph.append(", "); }
                                cols.append(key); ph.append("?");
                                vals.add(row.get(key).isJsonNull() ? null : row.get(key).getAsString());
                            }
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO " + tabla + " (" + cols + ") VALUES (" + ph + ")")) {
                                for (int j = 0; j < vals.size(); j++) {
                                    if (vals.get(j) == null) ps.setNull(j + 1, Types.VARCHAR);
                                    else ps.setString(j + 1, vals.get(j));
                                }
                                ps.executeUpdate();
                                totalInserted++;
                            } catch (Exception ignored) {}
                        }
                    }

                    // Resetear secuencias
                    for (String tabla : ordenTablas) {
                        if (data.has(tabla)) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute("SELECT setval(pg_get_serial_sequence('" + tabla +
                                        "', 'id'), COALESCE((SELECT MAX(id) FROM " + tabla + "), 0) + 1, false)");
                            } catch (Exception ignored) {}
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

    private static void verificarSecreto(io.javalin.http.Context ctx, String expectedSecret) {
        String secret = ctx.pathParam("secret");
        if (!expectedSecret.equals(secret)) {
            ctx.status(403).result("⛔ Acceso denegado");
            ctx.skipRemainingHandlers();
        }
    }

    /**
     * Obtiene un mapa de columna -> data_type para una tabla.
     */
    private static Map<String, String> getColumnTypes(Connection conn, String tableName) throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position");
        ps.setString(1, tableName);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            types.put(rs.getString("column_name"), rs.getString("data_type").toLowerCase());
        }
        ps.close();
        return types;
    }

    /**
     * Setea un parámetro del PreparedStatement según el tipo de dato real de la columna.
     */
    private static void setTypedParam(PreparedStatement ps, int index, String value, String dataType) throws SQLException {
        if (value == null || value.isEmpty()) {
            // Determinar el tipo SQL correcto para el null
            int sqlType;
            switch (dataType) {
                case "integer": case "smallint": sqlType = Types.INTEGER; break;
                case "bigint": sqlType = Types.BIGINT; break;
                case "numeric": case "decimal": case "real": case "double precision": sqlType = Types.NUMERIC; break;
                case "boolean": sqlType = Types.BOOLEAN; break;
                case "timestamp without time zone": case "timestamp with time zone": sqlType = Types.TIMESTAMP; break;
                case "date": sqlType = Types.DATE; break;
                default: sqlType = Types.VARCHAR; break;
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
                ps.setDate(index, Date.valueOf(value));
                break;
            default:
                ps.setString(index, value);
                break;
        }
    }
}
