package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;

import java.math.BigDecimal;
import java.sql.*;
import java.text.Collator;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Operaciones del panel de administración de la base de datos: ver tablas,
 * editar celdas, insertar,
 * borrar, exportar e importar (A3). Antes todo este SQL estaba dentro de
 * {@code RutasDbAdmin}.
 *
 * El acceso al panel (clave, bloqueo por IP, cabeceras) sigue en
 * {@code RutasDbAdmin}: es protocolo HTTP,
 * no lógica de negocio. Aquí todo nombre de tabla/columna que venga del request
 * se valida contra
 * {@code information_schema} y contra {@link #IDENTIFICADOR_SQL} antes de
 * citarlo en un SQL, y todas las
 * consultas usan exclusivamente {@link PreparedStatement} (S4, S10).
 */
public class DbAdminService {

    /**
     * Orden de inserción al importar (respeta dependencias); al borrar se recorre
     * al revés.
     */
    private static final String[] ORDEN_TABLAS_IMPORT = {
            "users", "messages", "matches", "transactions", "admin_wallet",
            "user_tokens", "user_tickets", "raffles", "raffle_entries"
    };

    /** S4: patrón estricto para identificadores SQL (tablas y columnas). */
    private static final Pattern IDENTIFICADOR_SQL = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern PATRON_DIACRITICOS = Pattern.compile("\\p{M}+");
    private static final Pattern PATRON_SEPARADOR_PALABRAS = Pattern.compile("[^\\p{Alnum}]+");
    private static final Locale LOCALE_ES = Locale.forLanguageTag("es-CO");

    private final GenericDAO db;
    private final ConexionDB conexion;
    private final PostgresBackupService backups;

    /**
     * A5: tabla donde Flyway guarda el historial de migraciones. El panel no la
     * muestra ni la deja tocar:
     * editarla desde la web podría hacer que Flyway rehaga o salte migraciones.
     * Además no tiene columna id,
     * así que ORDER BY id (ver, exportar) fallaría con ella.
     */
    private static final String TABLA_FLYWAY = "flyway_schema_history";
    /**
     * JTI son credenciales de sesión; el editor genérico no debe exponerlas ni
     * permitir revocarlas indirectamente.
     */
    private static final String TABLA_SESIONES = "user_sessions";
    /**
     * Los hashes de los tokens de recuperación también son credenciales temporales.
     */
    private static final String TABLA_RECUPERACION_PASSWORD = "password_reset_tokens";
    /**
     * D3 / M4: El ledger financiero es inmutable; solo se permite lectura en el
     * panel DB.
     */
    private static final String TABLA_LEDGER = "wallet_ledger";
    private static final String TABLA_REFERRAL_PAYOUTS = "referral_payouts";
    private static final String TABLA_MODERATOR_WALLET = "moderator_wallet";
    private static final Set<String> COLUMNAS_PROTEGIDAS_REFERRALS = Set.of(
            "partidas_jugadas", "total_comision_referido", "total_ganado_referidor", "total_devuelto_referido");
    private static final String MASCARA_PASSWORD = "[HASH_PROTEGIDO]";
    private static final Set<String> TABLAS_NO_IMPORTABLES = Set.of("users", "transactions", "admin_wallet",
            "wallet_ledger", "user_tokens", "referral_payouts", "moderator_wallet");

    public DbAdminService(GenericDAO db, ConexionDB conexion, AppConfig config) {
        this.db = db;
        this.conexion = conexion;
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

    /**
     * Ejecuta {@code op}; cualquier fallo que no sea de negocio sale como 500 con
     * el mensaje (como antes).
     */
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

    /**
     * Lista las tablas del esquema activo de la conexión con su número de filas. En
     * producción es {@code public}.
     */
    public JsonArray listarTablas() {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                JsonArray tables = new JsonArray();
                for (String tableName : nombresDeTablas(conn)) {
                    String tablaCitada = citarIdentificador(tableName);
                    try (PreparedStatement countPs = conn
                            .prepareStatement("SELECT COUNT(*) as count FROM " + tablaCitada);
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
        return datosTabla(tableName, null, null, true, null, "asc", "auto");
    }

    /**
     * Columnas y filas de una tabla aplicando filtro por palabras (exactas o parecidas) y ordenamiento seguro
     * sobre los resultados devueltos por {@link PreparedStatement}.
     */
    public JsonObject datosTabla(String tableName, String busqueda, String columnaFiltro, boolean aproximada,
                                 String ordenarPor, String direccion, String modoOrden) {
        return conErrores(() -> {
            try (Connection conn = db.getConnection()) {
                exigirTablaValida(conn, tableName);

                JsonArray columns = new JsonArray();
                List<String> colNames = new ArrayList<>();
                Map<String, String> colTypes = new LinkedHashMap<>();
                try (PreparedStatement colPs = conn.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position")) {
                    colPs.setString(1, tableName);
                    try (ResultSet colRs = colPs.executeQuery()) {
                        while (colRs.next()) {
                            String colName = colRs.getString("column_name");
                            String dataType = colRs.getString("data_type");
                            JsonObject col = new JsonObject();
                            col.addProperty("column_name", colName);
                            col.addProperty("data_type", dataType);
                            columns.add(col);
                            colNames.add(colName);
                            colTypes.put(colName, dataType != null ? dataType.toLowerCase(Locale.ROOT) : "");
                        }
                    }
                }

                JsonArray allRows = filasDeTabla(conn, tableName);
                JsonArray rows = filtrarYOrdenarFilas(allRows, colNames, colTypes, busqueda, columnaFiltro,
                        aproximada, ordenarPor, direccion, modoOrden);

                JsonObject result = new JsonObject();
                result.add("columns", columns);
                result.add("rows", rows);
                result.addProperty("totalRows", allRows.size());
                result.addProperty("filteredCount", rows.size());
                return result;
            }
        });
    }

    private record FilaConRelevancia(JsonObject fila, int puntaje, int indiceOriginal) {}

    /**
     * Filtra filas por tokens de búsqueda (con coincidencia exacta o por palabras parecidas: sin tildes,
     * prefijos, distancia de Levenshtein y similitud de bigramas) y las ordena de forma segura en memoria.
     */
    public static JsonArray filtrarYOrdenarFilas(JsonArray rows, List<String> colNames, Map<String, String> colTypes,
                                                 String busqueda, String columnaFiltro, boolean aproximada,
                                                 String ordenarPor, String direccion, String modoOrden) {
        if (rows == null || rows.isEmpty()) {
            return new JsonArray();
        }
        List<String> columnasDisponibles = colNames != null ? colNames : List.of();
        Map<String, String> tipos = colTypes != null ? colTypes : Map.of();

        String colFiltroLimpia = columnaFiltro != null ? columnaFiltro.trim() : "";
        boolean filtrarPorColumna = !colFiltroLimpia.isEmpty() && !"*".equals(colFiltroLimpia)
                && (columnasDisponibles.contains(colFiltroLimpia) || tipos.containsKey(colFiltroLimpia));
        List<String> columnasBusqueda = filtrarPorColumna
                ? List.of(colFiltroLimpia)
                : columnasDisponibles;

        String busquedaNorm = normalizarTexto(busqueda);
        List<String> tokens = new ArrayList<>();
        if (!busquedaNorm.isEmpty()) {
            for (String tok : busquedaNorm.split("\\s+")) {
                if (!tok.isEmpty()) tokens.add(tok);
            }
        }

        List<FilaConRelevancia> filtradas = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JsonElement elem = rows.get(i);
            if (elem == null || !elem.isJsonObject()) continue;
            JsonObject fila = elem.getAsJsonObject();
            if (tokens.isEmpty()) {
                filtradas.add(new FilaConRelevancia(fila, 0, i));
                continue;
            }
            List<String> colsObjetivo = columnasBusqueda.isEmpty() ? new ArrayList<>(fila.keySet()) : columnasBusqueda;
            int puntaje = calcularPuntajeFila(fila, colsObjetivo, tokens, aproximada);
            if (puntaje >= 0) {
                filtradas.add(new FilaConRelevancia(fila, puntaje, i));
            }
        }

        String modo = modoOrden != null ? modoOrden.trim().toLowerCase(Locale.ROOT) : "auto";
        String sortCol = ordenarPor != null ? ordenarPor.trim() : "";
        boolean desc = "desc".equalsIgnoreCase(direccion);
        boolean ordenarPorRelevancia = "relevancia".equals(modo)
                || "_relevancia".equals(sortCol)
                || (sortCol.isEmpty() && !tokens.isEmpty());

        if (ordenarPorRelevancia && !tokens.isEmpty()) {
            filtradas.sort(Comparator.comparingInt(FilaConRelevancia::puntaje).reversed()
                    .thenComparingInt(FilaConRelevancia::indiceOriginal));
        } else if (!sortCol.isEmpty() && (columnasDisponibles.contains(sortCol) || tipos.containsKey(sortCol)
                || (!filtradas.isEmpty() && filtradas.get(0).fila().has(sortCol)))) {
            String tipoCol = tipos.getOrDefault(sortCol, "");
            Collator collatorPrimario = Collator.getInstance(LOCALE_ES);
            collatorPrimario.setStrength(Collator.PRIMARY);
            Collator collatorTerciario = Collator.getInstance(LOCALE_ES);
            collatorTerciario.setStrength(Collator.TERTIARY);

            filtradas.sort((a, b) -> {
                JsonElement e1 = a.fila().get(sortCol);
                JsonElement e2 = b.fila().get(sortCol);
                boolean vacio1 = esNuloOVacio(e1);
                boolean vacio2 = esNuloOVacio(e2);
                if (vacio1 && vacio2) return Integer.compare(a.indiceOriginal(), b.indiceOriginal());
                if (vacio1) return 1;
                if (vacio2) return -1;
                int cmp = compararValores(e1.getAsString(), e2.getAsString(), tipoCol, modo,
                        collatorPrimario, collatorTerciario);
                if (cmp == 0) return Integer.compare(a.indiceOriginal(), b.indiceOriginal());
                return desc ? -cmp : cmp;
            });
        }

        JsonArray salida = new JsonArray();
        for (FilaConRelevancia item : filtradas) {
            salida.add(item.fila());
        }
        return salida;
    }

    private static int calcularPuntajeFila(JsonObject fila, List<String> columnas, List<String> tokens, boolean aproximada) {
        int total = 0;
        for (String token : tokens) {
            int mejorToken = -1;
            for (String col : columnas) {
                JsonElement val = fila.get(col);
                if (esNuloOVacio(val)) continue;
                int score = puntajeTokenEnTexto(val.getAsString(), token, aproximada);
                if (score > mejorToken) mejorToken = score;
                if (mejorToken >= 100) break;
            }
            if (mejorToken < 0) return -1;
            total += mejorToken;
        }
        return total;
    }

    private static int puntajeTokenEnTexto(String crudo, String token, boolean aproximada) {
        String norm = normalizarTexto(crudo);
        if (norm.isEmpty()) return -1;
        if (norm.equals(token)) return 120;
        if (norm.contains(token)) return 100;
        if (!aproximada || token.length() < 2) return -1;

        String[] palabras = PATRON_SEPARADOR_PALABRAS.split(norm);
        int mejor = -1;
        for (String palabra : palabras) {
            if (palabra.isEmpty()) continue;
            if (palabra.startsWith(token) || (palabra.length() >= 3 && token.startsWith(palabra))) {
                mejor = Math.max(mejor, 85);
                continue;
            }
            int maxLen = Math.max(token.length(), palabra.length());
            int minLen = Math.min(token.length(), palabra.length());
            if (minLen < 2) continue;
            int maxDist = maxLen <= 4 ? 1 : (maxLen <= 8 ? 2 : 3);
            if (Math.abs(token.length() - palabra.length()) <= maxDist) {
                int dist = levenshteinAcotado(token, palabra, maxDist);
                if (dist <= maxDist) {
                    mejor = Math.max(mejor, Math.max(45, 80 - dist * 15));
                    continue;
                }
            }
            if (token.length() >= 3 && palabra.length() >= 3 && similitudBigramas(token, palabra) >= 0.55) {
                mejor = Math.max(mejor, 50);
            }
        }
        return mejor;
    }

    private static String normalizarTexto(String texto) {
        if (texto == null) return "";
        String limpio = texto.trim().toLowerCase(Locale.ROOT);
        if (limpio.isEmpty()) return "";
        String nfd = Normalizer.normalize(limpio, Normalizer.Form.NFD);
        return PATRON_DIACRITICOS.matcher(nfd).replaceAll("");
    }

    private static int levenshteinAcotado(String a, String b, int limite) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > limite) return limite + 1;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            int minFila = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int costo = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + costo);
                if (curr[j] < minFila) minFila = curr[j];
            }
            if (minFila > limite) return limite + 1;
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    private static double similitudBigramas(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return 0.0;
        Map<String, Integer> conteoA = new HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) {
            String bg = a.substring(i, i + 2);
            conteoA.merge(bg, 1, Integer::sum);
        }
        int interseccion = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String bg = b.substring(i, i + 2);
            Integer disponible = conteoA.get(bg);
            if (disponible != null && disponible > 0) {
                interseccion++;
                conteoA.put(bg, disponible - 1);
            }
        }
        return (2.0 * interseccion) / ((a.length() - 1) + (b.length() - 1));
    }

    private static boolean esNuloOVacio(JsonElement elem) {
        return elem == null || elem.isJsonNull() || elem.getAsString().trim().isEmpty();
    }

    private static int compararValores(String s1, String s2, String colType, String modo,
                                       Collator collatorPrimario, Collator collatorTerciario) {
        String modoEfectivo = modo;
        if (modoEfectivo == null || modoEfectivo.isEmpty() || "auto".equals(modoEfectivo)) {
            if (esTipoNumericoSql(colType)) {
                modoEfectivo = "numero";
            } else if (esTipoFechaSql(colType)) {
                modoEfectivo = "fecha";
            } else if (colType == null || colType.isEmpty()) {
                if (parsearNumero(s1) != null && parsearNumero(s2) != null) modoEfectivo = "numero";
                else if (parsearFechaMs(s1) != null && parsearFechaMs(s2) != null) modoEfectivo = "fecha";
                else modoEfectivo = "texto";
            } else {
                modoEfectivo = "texto";
            }
        }

        if ("numero".equals(modoEfectivo)) {
            BigDecimal n1 = parsearNumero(s1);
            BigDecimal n2 = parsearNumero(s2);
            if (n1 != null && n2 != null) return n1.compareTo(n2);
            if (n1 != null) return -1;
            if (n2 != null) return 1;
        } else if ("fecha".equals(modoEfectivo)) {
            Long f1 = parsearFechaMs(s1);
            Long f2 = parsearFechaMs(s2);
            if (f1 != null && f2 != null) return Long.compare(f1, f2);
            if (f1 != null) return -1;
            if (f2 != null) return 1;
        }

        int cmp = collatorPrimario.compare(s1, s2);
        if (cmp != 0) return cmp;
        cmp = collatorTerciario.compare(s1, s2);
        return cmp != 0 ? cmp : s1.compareToIgnoreCase(s2);
    }

    private static boolean esTipoNumericoSql(String colType) {
        return colType != null && (colType.contains("int") || colType.contains("numeric")
                || colType.contains("decimal") || colType.contains("real") || colType.contains("double"));
    }

    private static boolean esTipoFechaSql(String colType) {
        return colType != null && (colType.contains("timestamp") || "date".equals(colType));
    }

    private static BigDecimal parsearNumero(String valor) {
        try {
            return new BigDecimal(valor.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parsearFechaMs(String valor) {
        String s = valor.trim();
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return Timestamp.valueOf(s).getTime();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    public void actualizarCelda(String tableName, int id, String column, String value) {
        conErrores(() -> {
            if (TABLA_LEDGER.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla wallet_ledger es inmutable y no se puede editar");
            }
            if (TABLA_REFERRAL_PAYOUTS.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla referral_payouts es de auditoría financiera y no se puede editar");
            }
            if (TABLA_MODERATOR_WALLET.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla moderator_wallet es de auditoría financiera y no se puede editar");
            }
            if ("referrals".equalsIgnoreCase(tableName) && column != null
                    && COLUMNAS_PROTEGIDAS_REFERRALS.contains(column.toLowerCase())) {
                throw ServicioException.prohibido("Los acumulados financieros de referidos no pueden editarse manualmente");
            }
            if ("users".equalsIgnoreCase(tableName) && "saldo".equalsIgnoreCase(column)) {
                throw ServicioException.prohibido(
                        "El saldo no se puede editar directamente en la BD (rompe wallet_ledger). Usa /api/deposit");
            }
            if ("users".equalsIgnoreCase(tableName) && "tipo_suscripcion".equalsIgnoreCase(column)
                    && value != null && ConexionDB.SUSCRIPCION_PRUEBA.equalsIgnoreCase(value.trim())) {
                throw ServicioException.prohibido(
                        "El tipo de suscripción 'prueba' solo puede asignarse desde el panel de administración (/admin)");
            }
            if ("users".equalsIgnoreCase(tableName) && "zona_admin".equalsIgnoreCase(column)) {
                throw ServicioException.prohibido(
                        "La zona activa del administrador solo puede cambiarse desde el panel de administración (/admin)");
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
                if ("users".equalsIgnoreCase(tableName) && conexion != null) {
                    conexion.invalidarCacheUsuario(id);
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
            if (TABLA_REFERRAL_PAYOUTS.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla referral_payouts es de auditoría financiera y no permite borrar registros");
            }
            if (TABLA_MODERATOR_WALLET.equalsIgnoreCase(tableName)) {
                throw ServicioException.prohibido("La tabla moderator_wallet es de auditoría financiera y no permite borrar registros");
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
            throw ServicioException
                    .prohibido("La tabla wallet_ledger solo admite registros generados por WalletService");
        }
        if (TABLA_REFERRAL_PAYOUTS.equalsIgnoreCase(tableName)) {
            throw ServicioException
                    .prohibido("La tabla referral_payouts solo admite registros generados por WalletService");
        }
        if (TABLA_MODERATOR_WALLET.equalsIgnoreCase(tableName)) {
            throw ServicioException
                    .prohibido("La tabla moderator_wallet solo admite registros generados por WalletService");
        }
        if ("users".equalsIgnoreCase(tableName) && body != null && body.has("saldo")
                && !body.get("saldo").isJsonNull()) {
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
     * Exporta el subconjunto de tablas que este panel puede restaurar de forma
     * segura.
     * No es un backup completo: excluye usuarios, datos financieros, tokens y
     * tablas no soportadas.
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
     * Importa un respaldo parcial: borra e inserta las tablas incluidas dentro de
     * una transacción.
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
                if ("_meta".equals(tabla))
                    continue;
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
                    // M4: Borrar e insertar dentro de UNA sola transacción SQL para no dejar tablas
                    // vacías si algo falla
                    for (int i = ORDEN_TABLAS_IMPORT.length - 1; i >= 0; i--) {
                        String tabla = ORDEN_TABLAS_IMPORT[i];
                        if (data.has(tabla)) {
                            try (PreparedStatement delPs = conn
                                    .prepareStatement("DELETE FROM " + citarIdentificador(tabla))) {
                                delPs.executeUpdate();
                            }
                        }
                    }

                    for (String tabla : ORDEN_TABLAS_IMPORT) {
                        if (!data.has(tabla))
                            continue;

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
                                            "El respaldo contiene una columna no válida en la tabla " + tabla + ": "
                                                    + key);
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

                    // S4: Resetear secuencias usando PreparedStatement en lugar de
                    // Statement.execute()
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
                    try {
                        conn.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw e;
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                    }
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
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_name NOT IN (?, ?, ?) ORDER BY table_name")) {
            ps.setString(1, TABLA_FLYWAY);
            ps.setString(2, TABLA_SESIONES);
            ps.setString(3, TABLA_RECUPERACION_PASSWORD);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    nombres.add(rs.getString("table_name"));
            }
        }
        return nombres;
    }

    /**
     * S4 / D1 / M4: Todas las filas de una tabla usando {@link PreparedStatement},
     * conservando {@link BigDecimal},
     * ordenando por {@code id} si la tabla lo tiene (o por su primera columna en
     * tablas como {@code raffle_votes}
     * o {@code discord_links}) y enmascarando el hash {@code password} de
     * {@code users}.
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
                    if (val == null)
                        row.addProperty(colName, (String) null);
                    else if (val instanceof BigDecimal)
                        row.addProperty(colName, (BigDecimal) val); // REVIEW-MONEY
                    else if (val instanceof Number)
                        row.addProperty(colName, (Number) val);
                    else if (val instanceof byte[])
                        row.addProperty(colName, "[BYTEA " + ((byte[]) val).length + " bytes]");
                    else
                        row.addProperty(colName, val.toString());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Lanza 400 si la tabla no existe en el esquema activo. Todo nombre de tabla
     * del request pasa por aquí.
     */
    private static void exigirTablaValida(Connection conn, String tableName) throws SQLException {
        if (tableName == null || !IDENTIFICADOR_SQL.matcher(tableName).matches()) {
            throw ServicioException.solicitudInvalida("Tabla no válida");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ? AND table_name NOT IN (?, ?, ?)")) {
            ps.setString(1, tableName);
            ps.setString(2, TABLA_FLYWAY);
            ps.setString(3, TABLA_SESIONES);
            ps.setString(4, TABLA_RECUPERACION_PASSWORD);
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
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position")) {
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
     * Cita un identificador de tabla o columna tras verificar que cumple
     * {@link #IDENTIFICADOR_SQL}.
     */
    private static String citarIdentificador(String identificador) {
        if (identificador == null || !IDENTIFICADOR_SQL.matcher(identificador).matches()) {
            throw ServicioException.solicitudInvalida("Identificador SQL no válido");
        }
        return "\"" + identificador + "\"";
    }

    /**
     * Setea un parámetro del PreparedStatement según el tipo de dato real de la
     * columna.
     */
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
