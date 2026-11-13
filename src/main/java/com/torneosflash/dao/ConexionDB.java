package com.torneosflash.dao;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * CONCEPTO POO: Encapsulamiento - Singleton pattern
 * Gestiona la conexión a PostgreSQL (misma BD de Render).
 * Usa HikariCP como pool de conexiones.
 *
 * S10: el "link de acceso" a la BD se construye en {@link EnlaceBD}: las credenciales
 * viajan por {@code setUsername/setPassword} (nunca dentro de la URL JDBC, así no salen en
 * logs ni mensajes de error) y el modo SSL lo decide el servidor, no la URL.
 *
 * A5: el esquema ya no se crea aquí. Lo versiona Flyway con los scripts de
 * {@code src/main/resources/db/migration} y se aplica con {@link #migrar()}.
 */
public class ConexionDB {
    private static final Logger logger = LoggerFactory.getLogger(ConexionDB.class);

    public static final String ZONA_NORMAL = "normal";
    public static final String ZONA_PRUEBA = "prueba";
    public static final String SUSCRIPCION_FREE = "free";
    public static final String SUSCRIPCION_PRUEBA = "prueba";
    public static final String SUSCRIPCION_ADMIN = "admin";
    private static final String ESQUEMA_FALLBACK_PRUEBAS = "tf_zona_pruebas";
    private static final long CACHE_ZONA_USUARIO_MS = 2_000L;

    /** Zona activa del hilo actual ("normal" por defecto, "prueba" cuando opera sobre la copia temporal de JUnit). */
    private static final ThreadLocal<String> ZONA_ACTUAL = ThreadLocal.withInitial(() -> ZONA_NORMAL);

    public record EstadoZonaUsuario(int userId, String tipoSuscripcion, String zonaAdmin,
                                    String zonaActiva, long expiraCacheMs) {}

    // --- Singleton (ENCAPSULAMIENTO) ---
    private static ConexionDB instancia;
    /** Tamano del pool si no se indica otro (A8: Main lo toma de DB_POOL_MAX_SIZE). */
    private static final int POOL_MAX_POR_DEFECTO = 20;
    /** Par de claves reservado a los bloqueos compartidos de la aplicación durante un restore. */
    private static final int LOCK_RESTAURACION_CLAVE_1 = 0x54464C53;
    private static final int LOCK_RESTAURACION_CLAVE_2 = 1;
    private static final int LOCK_OPERACION_COMPLETA_CLAVE_2 = 2;
    private static final long CACHE_ESTADO_RESTAURACION_MS = 500L;
    /** A5: carpeta (en el classpath) con los scripts V1__..., V2__... de Flyway. */
    private static final String UBICACION_MIGRACIONES = "classpath:db/migration";
    private final String urlOriginalPrincipal;
    private final String sslModePrincipal;
    private HikariDataSource dataSource;
    private EnlaceBD enlaceBD;
    private HikariDataSource dataSourcePruebas;
    private EnlaceBD enlaceBDPruebas;
    private String esquemaPruebas;
    private final ConcurrentHashMap<Integer, String> usuariosSincronizadosPruebas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, EstadoZonaUsuario> cacheZonaUsuario = new ConcurrentHashMap<>();
    private final AtomicBoolean adminsSincronizadosPruebas = new AtomicBoolean(false);
    private final AtomicBoolean restauracionActiva = new AtomicBoolean(false);
    private BloqueoOperacion bloqueoRestauracionActual;
    private volatile boolean restauracionGlobalCache;
    private volatile long restauracionGlobalCacheHasta;

    // --- Constructor privado (ENCAPSULAMIENTO) ---
    private ConexionDB(String databaseUrl, String sslMode, int maxPoolSize) {
        this.urlOriginalPrincipal = databaseUrl;
        this.sslModePrincipal = sslMode;
        try {
            EnlaceBD enlace = EnlaceBD.desde(databaseUrl, sslMode);
            this.enlaceBD = enlace;
            HikariConfig config = new HikariConfig();
            config.setPoolName("torneos-db"); // A8: los hilos internos de Hikari se ven con este nombre
            config.setJdbcUrl(enlace.jdbcUrl);
            if (enlace.usuario != null) config.setUsername(enlace.usuario);
            if (enlace.password != null) config.setPassword(enlace.password);
            config.setMaximumPoolSize(maxPoolSize); // A8: DB_POOL_MAX_SIZE
            config.setMinimumIdle(2);
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(5000);
            this.dataSource = new HikariDataSource(config);
            logger.info("✅ ¡Conexión exitosa a PostgreSQL! (host={}, sslmode={})", enlace.host, enlace.sslMode);
        } catch (Exception e) {
            logger.error("❌ Error conectando a PostgreSQL: " + e.getMessage());
            throw new IllegalStateException("No se pudo inicializar el pool de PostgreSQL: " + e.getMessage(), e);
        }
    }

    public static String normalizarZona(String zona) {
        return ZONA_PRUEBA.equalsIgnoreCase(zona != null ? zona.trim() : "") ? ZONA_PRUEBA : ZONA_NORMAL;
    }

    public static String zonaActual() {
        String z = ZONA_ACTUAL.get();
        return ZONA_PRUEBA.equals(z) ? ZONA_PRUEBA : ZONA_NORMAL;
    }

    public static boolean esZonaPrueba() {
        return ZONA_PRUEBA.equals(zonaActual());
    }

    public static void fijarZonaActual(String zona) {
        String normalizada = normalizarZona(zona);
        if (ZONA_NORMAL.equals(normalizada)) {
            ZONA_ACTUAL.remove();
        } else {
            ZONA_ACTUAL.set(ZONA_PRUEBA);
        }
    }

    public static void limpiarZonaActual() {
        ZONA_ACTUAL.remove();
    }

    public static void ejecutarEnZona(String zona, Runnable accion) {
        String previa = zonaActual();
        fijarZonaActual(zona);
        try {
            accion.run();
        } finally {
            fijarZonaActual(previa);
        }
    }

    public static <T> T llamarEnZona(String zona, Callable<T> accion) throws Exception {
        String previa = zonaActual();
        fijarZonaActual(zona);
        try {
            return accion.call();
        } finally {
            fijarZonaActual(previa);
        }
    }

    /**
     * S10 — Enlace de acceso a la BD ya "endurecido".
     * Convierte {@code DATABASE_URL} (formato Render {@code postgres://usuario:clave@host/db} o
     * {@code jdbc:postgresql://...}) en una URL JDBC SIN credenciales + usuario/clave por separado.
     * Ningún mensaje de error ni log de esta clase incluye la URL original ni la contraseña.
     */
    static final class EnlaceBD {
        private static final Set<String> MODOS_SSL = Set.of("require", "verify-ca", "verify-full");
        private static final String MODO_SSL_DEFECTO = "require";
        /** Parámetros de la URL que se descartan: el cifrado lo decide el servidor (no se puede bajar a `disable`). */
        private static final Set<String> PARAMS_IGNORADOS = Set.of("ssl", "sslmode", "sslfactory");

        final String jdbcUrl;
        final String usuario;
        final String password;
        /** host[:puerto], sin credenciales: es lo único que se puede mostrar en logs. */
        final String host;
        final String sslMode;
        final String nombreBase;
        final String hostProceso;
        final String puertoProceso;

        private EnlaceBD(String jdbcUrl, String usuario, String password, String host, String sslMode,
                         String nombreBase, String hostProceso, String puertoProceso) {
            this.jdbcUrl = jdbcUrl;
            this.usuario = usuario;
            this.password = password;
            this.host = host;
            this.sslMode = sslMode;
            this.nombreBase = nombreBase;
            this.hostProceso = hostProceso;
            this.puertoProceso = puertoProceso;
        }

        static EnlaceBD desde(String url, String sslModeSolicitado) {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("DATABASE_URL no está definida");
            }
            String modo = normalizarSslMode(sslModeSolicitado);
            String resto = url.trim();

            if (resto.startsWith("jdbc:postgresql://")) resto = resto.substring("jdbc:postgresql://".length());
            else if (resto.startsWith("postgresql://")) resto = resto.substring("postgresql://".length());
            else if (resto.startsWith("postgres://")) resto = resto.substring("postgres://".length());
            else if (resto.contains("://")) throw new IllegalArgumentException("Formato de DATABASE_URL no soportado");

            // Credenciales: todo lo que va antes de la ÚLTIMA '@' (la clave puede traer ':' o '@').
            String usuario = null;
            String password = null;
            int arroba = resto.lastIndexOf('@');
            if (arroba >= 0) {
                String credenciales = resto.substring(0, arroba);
                resto = resto.substring(arroba + 1);
                int dosPuntos = credenciales.indexOf(':');
                if (dosPuntos >= 0) {
                    usuario = decodificar(credenciales.substring(0, dosPuntos));
                    password = decodificar(credenciales.substring(dosPuntos + 1));
                } else {
                    usuario = decodificar(credenciales);
                }
            }

            // host[:puerto]/db[?parametros]
            String query = "";
            int interrogante = resto.indexOf('?');
            if (interrogante >= 0) {
                query = resto.substring(interrogante + 1);
                resto = resto.substring(0, interrogante);
            }
            int barra = resto.indexOf('/');
            if (barra <= 0 || barra == resto.length() - 1) {
                throw new IllegalArgumentException("DATABASE_URL debe incluir host y nombre de la base de datos");
            }
            String host = resto.substring(0, barra);
            String nombreBase = decodificar(resto.substring(barra + 1));
            URI autoridad;
            try {
                autoridad = URI.create("postgresql://" + host + "/");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Host de DATABASE_URL no válido", e);
            }
            String hostProceso = autoridad.getHost();
            if (hostProceso == null || hostProceso.isBlank() || nombreBase.isBlank()) {
                throw new IllegalArgumentException("DATABASE_URL no incluye un host y nombre de base válidos");
            }
            if (hostProceso.startsWith("[") && hostProceso.endsWith("]")) {
                hostProceso = hostProceso.substring(1, hostProceso.length() - 1);
            }
            String puertoProceso = String.valueOf(autoridad.getPort() > 0 ? autoridad.getPort() : 5432);

            // Parámetros extra: user/password (si venían en la query) salen de la URL; ssl* se descartan.
            List<String> params = new ArrayList<>();
            for (String par : query.split("&")) {
                if (par.isEmpty()) continue;
                int igual = par.indexOf('=');
                String clave = (igual >= 0 ? par.substring(0, igual) : par).toLowerCase();
                String valor = igual >= 0 ? par.substring(igual + 1) : "";
                if (clave.equals("user")) {
                    if (usuario == null) usuario = decodificar(valor);
                } else if (clave.equals("password")) {
                    if (password == null) password = decodificar(valor);
                } else if (!PARAMS_IGNORADOS.contains(clave)) {
                    params.add(par);
                }
            }
            params.add("sslmode=" + modo);

            String jdbcUrl = "jdbc:postgresql://" + resto + "?" + String.join("&", params);
            return new EnlaceBD(jdbcUrl, usuario, password, host, modo,
                    nombreBase, hostProceso, puertoProceso);
        }

        Map<String, String> variablesProcesoPostgres() {
            Map<String, String> env = new HashMap<>();
            env.put("PGHOST", hostProceso);
            env.put("PGPORT", puertoProceso);
            env.put("PGDATABASE", nombreBase);
            env.put("PGSSLMODE", sslMode);
            env.put("PGCONNECT_TIMEOUT", "15");
            if (usuario != null && !usuario.isBlank()) env.put("PGUSER", usuario);
            return env;
        }

        private static String normalizarSslMode(String solicitado) {
            String modo = solicitado == null ? "" : solicitado.trim().toLowerCase();
            if (modo.isEmpty()) return MODO_SSL_DEFECTO;
            if (MODOS_SSL.contains(modo)) return modo;
            logger.error("⚠️ DB_SSLMODE='{}' no es válido (require, verify-ca o verify-full). Se usa {}.", solicitado, MODO_SSL_DEFECTO);
            return MODO_SSL_DEFECTO;
        }

        /** Decodifica %XX (p. ej. una clave con '@' o ':' codificada). El '+' se conserva literal. */
        private static String decodificar(String valor) {
            if (valor == null || valor.indexOf('%') < 0) return valor;
            try {
                return URLDecoder.decode(valor.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return valor; // '%' suelto que no es una secuencia válida: se deja tal cual
            }
        }
    }

    // --- Singleton getter ---
    public static synchronized ConexionDB getInstancia(String databaseUrl, String sslMode) {
        return getInstancia(databaseUrl, sslMode, POOL_MAX_POR_DEFECTO);
    }

    public static synchronized ConexionDB getInstancia(String databaseUrl, String sslMode, int maxPoolSize) {
        if (instancia == null) {
            instancia = new ConexionDB(databaseUrl, sslMode, maxPoolSize);
        }
        return instancia;
    }

    public static synchronized ConexionDB getInstancia(String databaseUrl) {
        return getInstancia(databaseUrl, null);
    }

    public static ConexionDB getInstancia() {
        return instancia;
    }

    // --- Obtener conexión del pool (enruta según la zona activa del hilo) ---
    public Connection getConnection() throws SQLException {
        if (esZonaPrueba() && dataSourcePruebas != null) {
            return getConnectionPruebas();
        }
        return getConnectionPrincipal();
    }

    /** Conexión explícita a la base de datos principal (producción / zona normal). */
    public Connection getConnectionPrincipal() throws SQLException {
        if (restauracionActiva.get()) {
            throw new SQLException("La base de datos está en restauración; intenta de nuevo en unos minutos");
        }
        Connection conn = dataSource.getConnection();
        boolean bloqueoAdquirido = false;
        try {
            if (!intentarBloqueoCompartido(conn)) {
                conn.close();
                throw new SQLException("La base de datos está en restauración; intenta de nuevo en unos minutos");
            }
            bloqueoAdquirido = true;
            // Cubre la carrera entre la primera comprobación y la obtención del bloqueo global.
            if (restauracionActiva.get()) {
                liberarBloqueoCompartido(conn);
                bloqueoAdquirido = false;
                conn.close();
                throw new SQLException("La base de datos está en restauración; intenta de nuevo en unos minutos");
            }
            return envolverConexionConBloqueo(conn);
        } catch (SQLException | RuntimeException e) {
            if (bloqueoAdquirido) descartarConexion(conn);
            cerrarSinError(conn);
            throw e;
        }
    }

    /** Conexión explícita a la base de datos aislada de pruebas (copia temporal sobre TEST_DATABASE_URL). */
    public Connection getConnectionPruebas() throws SQLException {
        HikariDataSource poolPruebas = this.dataSourcePruebas;
        if (poolPruebas == null) {
            throw new SQLException("La base de datos de la zona de pruebas no está configurada");
        }
        return poolPruebas.getConnection();
    }

    public boolean tieneZonaPruebas() {
        return dataSourcePruebas != null;
    }

    public String descripcionBasePruebas() {
        if (enlaceBDPruebas == null) return "no configurada";
        String sufijoEsquema = (esquemaPruebas != null && !esquemaPruebas.isBlank()
                && !"public".equalsIgnoreCase(esquemaPruebas))
                ? " (esquema " + esquemaPruebas + ")"
                : "";
        return enlaceBDPruebas.host + "/" + enlaceBDPruebas.nombreBase + sufijoEsquema;
    }

    /**
     * Invalida sesiones y enlaces de recuperación restaurados desde el dump. Son credenciales temporales y no
     * deben reactivarse al volver a una copia anterior. Solo se permite durante restore.
     */
    public int revocarSesionesTrasRestauracion() throws SQLException {
        if (!restauracionActiva.get()) {
            throw new SQLException("La revocación posterior al restore requiere modo de restauración");
        }
        if (enlaceBD == null) throw new SQLException("No está configurado el enlace de PostgreSQL");

        Properties propiedades = new Properties();
        if (enlaceBD.usuario != null && !enlaceBD.usuario.isBlank()) {
            propiedades.setProperty("user", enlaceBD.usuario);
        }
        if (enlaceBD.password != null) propiedades.setProperty("password", enlaceBD.password);
        try (Connection conn = DriverManager.getConnection(enlaceBD.jdbcUrl, propiedades)) {
            conn.setAutoCommit(false);
            try {
                int sesiones;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_sessions")) {
                    sesiones = ps.executeUpdate();
                }
                int tokens;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM password_reset_tokens")) {
                    tokens = ps.executeUpdate();
                }
                conn.commit();
                return sesiones + tokens;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            }
        }
    }

    /** Evita nuevas consultas y espera a que terminen las conexiones activas antes de restaurar. */
    public void iniciarModoRestauracion(long esperaMaximaMs, BloqueoOperacion operacion) throws SQLException {
        if (operacion == null) throw new SQLException("No se reservó el bloqueo global de backup/restore");
        if (!restauracionActiva.compareAndSet(false, true)) {
            throw new SQLException("Ya hay una restauración de base de datos en curso");
        }

        long limite = System.currentTimeMillis() + esperaMaximaMs;
        try {
            while (dataSource.getHikariPoolMXBean().getActiveConnections() > 0) {
                if (System.currentTimeMillis() >= limite) {
                    throw new SQLException("Hay operaciones activas en la base de datos; no se inició la restauración");
                }
                Thread.sleep(100L);
            }
            while (!operacion.intentarBloqueoExclusivoRestauracion()) {
                if (System.currentTimeMillis() >= limite) {
                    throw new SQLException("Hay operaciones activas en otra instancia; no se inició la restauración");
                }
                Thread.sleep(100L);
            }
            bloqueoRestauracionActual = operacion;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            restauracionActiva.set(false);
            throw new SQLException("Se interrumpió la espera de operaciones de base de datos", e);
        } catch (SQLException | RuntimeException e) {
            restauracionActiva.set(false);
            throw e;
        }
    }

    /** Cierra conexiones JDBC inactivas (pueden conservar planes previos al restore) y reabre el acceso. */
    public void terminarModoRestauracion() {
        try {
            BloqueoOperacion bloqueo = bloqueoRestauracionActual;
            bloqueoRestauracionActual = null;
            if (bloqueo != null) {
                try {
                    bloqueo.liberarBloqueoExclusivoRestauracion();
                } catch (SQLException | RuntimeException e) {
                    logger.warn("No se pudo liberar explícitamente el bloqueo de restauración; se cerrará al liberar el bloqueo global");
                }
            }
            try {
                if (dataSource != null && dataSource.getHikariPoolMXBean() != null) {
                    dataSource.getHikariPoolMXBean().softEvictConnections();
                }
            } catch (RuntimeException e) {
                logger.warn("No se pudieron renovar las conexiones inactivas luego de la restauración");
            }
        } finally {
            restauracionActiva.set(false);
        }
    }

    public synchronized boolean isRestauracionActiva() {
        if (restauracionActiva.get()) return true;
        if (dataSource == null) return false;
        long ahora = System.currentTimeMillis();
        if (ahora < restauracionGlobalCacheHasta) return restauracionGlobalCache;
        Connection conn = null;
        boolean bloqueoAdquirido = false;
        try {
            conn = dataSource.getConnection();
            if (!intentarBloqueoCompartido(conn)) {
                restauracionGlobalCache = true;
                restauracionGlobalCacheHasta = ahora + CACHE_ESTADO_RESTAURACION_MS;
                return true;
            }
            bloqueoAdquirido = true;
            liberarBloqueoCompartido(conn);
            bloqueoAdquirido = false;
            restauracionGlobalCache = false;
            restauracionGlobalCacheHasta = ahora + CACHE_ESTADO_RESTAURACION_MS;
            return false;
        } catch (SQLException e) {
            if (bloqueoAdquirido && conn != null) descartarConexion(conn);
            // Una caída de BD sigue su manejo normal; no se presenta como una restauración activa.
            restauracionGlobalCache = false;
            restauracionGlobalCacheHasta = ahora + CACHE_ESTADO_RESTAURACION_MS;
            return false;
        } finally {
            cerrarSinError(conn);
        }
    }

    private boolean intentarBloqueoCompartido(Connection conn) throws SQLException {
        return intentarBloqueo(conn, "SELECT pg_try_advisory_lock_shared(?, ?)", LOCK_RESTAURACION_CLAVE_2);
    }

    private boolean intentarBloqueo(Connection conn, String sql, int clave2) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, LOCK_RESTAURACION_CLAVE_1);
            ps.setInt(2, clave2);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void liberarBloqueoCompartido(Connection conn) throws SQLException {
        ejecutarLiberacion(conn, "SELECT pg_advisory_unlock_shared(?, ?)", LOCK_RESTAURACION_CLAVE_2);
    }

    private void ejecutarLiberacion(Connection conn, String sql, int clave2) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, LOCK_RESTAURACION_CLAVE_1);
            ps.setInt(2, clave2);
            ps.execute();
        }
    }

    /** Reserva un único backup/restore en todas las instancias sin detener el tráfico durante la validación. */
    public BloqueoOperacion intentarBloqueoOperacionCompleta() throws SQLException {
        if (enlaceBD == null) throw new SQLException("No está configurado el enlace de PostgreSQL");
        Properties propiedades = new Properties();
        if (enlaceBD.usuario != null && !enlaceBD.usuario.isBlank()) propiedades.setProperty("user", enlaceBD.usuario);
        if (enlaceBD.password != null) propiedades.setProperty("password", enlaceBD.password);
        Connection conn = DriverManager.getConnection(enlaceBD.jdbcUrl, propiedades);
        try {
            if (!intentarBloqueo(conn, "SELECT pg_try_advisory_lock(?, ?)", LOCK_OPERACION_COMPLETA_CLAVE_2)) {
                conn.close();
                return null;
            }
            return new BloqueoOperacion(conn);
        } catch (SQLException | RuntimeException e) {
            cerrarSinError(conn);
            throw e;
        }
    }

    public final class BloqueoOperacion implements AutoCloseable {
        private final Connection conexion;
        private final AtomicBoolean cerrado = new AtomicBoolean(false);
        private boolean bloqueoCompartido;
        private boolean bloqueoExclusivoRestauracion;

        private BloqueoOperacion(Connection conexion) {
            this.conexion = conexion;
        }

        public synchronized boolean intentarBloqueoCompartidoRestauracion() throws SQLException {
            if (cerrado.get()) throw new SQLException("El bloqueo global de backup/restore ya está cerrado");
            if (bloqueoCompartido) return true;
            bloqueoCompartido = intentarBloqueoCompartido(conexion);
            return bloqueoCompartido;
        }

        private synchronized boolean intentarBloqueoExclusivoRestauracion() throws SQLException {
            if (cerrado.get()) throw new SQLException("El bloqueo global de backup/restore ya está cerrado");
            if (bloqueoExclusivoRestauracion) return true;
            bloqueoExclusivoRestauracion = intentarBloqueo(conexion,
                    "SELECT pg_try_advisory_lock(?, ?)", LOCK_RESTAURACION_CLAVE_2);
            return bloqueoExclusivoRestauracion;
        }

        private synchronized void liberarBloqueoExclusivoRestauracion() throws SQLException {
            if (!bloqueoExclusivoRestauracion) return;
            ejecutarLiberacion(conexion, "SELECT pg_advisory_unlock(?, ?)", LOCK_RESTAURACION_CLAVE_2);
            bloqueoExclusivoRestauracion = false;
        }

        @Override
        public void close() {
            if (!cerrado.compareAndSet(false, true)) return;
            try {
                if (bloqueoExclusivoRestauracion) {
                    ejecutarLiberacion(conexion, "SELECT pg_advisory_unlock(?, ?)", LOCK_RESTAURACION_CLAVE_2);
                    bloqueoExclusivoRestauracion = false;
                }
                if (bloqueoCompartido) {
                    liberarBloqueoCompartido(conexion);
                    bloqueoCompartido = false;
                }
                ejecutarLiberacion(conexion, "SELECT pg_advisory_unlock(?, ?)", LOCK_OPERACION_COMPLETA_CLAVE_2);
            } catch (SQLException | RuntimeException e) {
                logger.warn("No se pudieron liberar todos los advisory locks; la conexión de control se cerrará al finalizar el proceso");
            } finally {
                try {
                    conexion.close();
                } catch (SQLException | RuntimeException e) {
                    logger.warn("No se pudo cerrar la conexión de control de backup/restore");
                }
            }
        }
    }

    /** El lock compartido vive en la sesión PostgreSQL hasta que el caller cierre su conexión lógica. */
    private Connection envolverConexionConBloqueo(Connection conn) {
        AtomicBoolean cerrada = new AtomicBoolean(false);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                        if (!cerrada.compareAndSet(false, true)) return null;
                        SQLException error = null;
                        try {
                            // Hikari también revierte una transacción abierta al cerrar; hacerlo antes del unlock
                            // impide que un restore exclusivo adelante una transacción aún no finalizada.
                            if (!conn.getAutoCommit()) conn.rollback();
                            liberarBloqueoCompartido(conn);
                        } catch (SQLException e) {
                            error = e;
                            descartarConexion(conn);
                        }
                        try {
                            conn.close();
                        } catch (SQLException e) {
                            if (error == null) error = e;
                            else error.addSuppressed(e);
                        }
                        if (error != null) throw error;
                        return null;
                    }
                    try {
                        return method.invoke(conn, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void cerrarSinError(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // El error principal se propaga; el cierre es best-effort.
        }
    }

    private void descartarConexion(Connection conn) {
        if (conn == null || dataSource == null) return;
        try {
            dataSource.evictConnection(conn);
        } catch (Exception e) {
            logger.warn("No se pudo descartar una conexión PostgreSQL que conservaba un advisory lock");
        }
    }

    /** Variables libpq sin credenciales en argumentos de proceso; la clave se entrega con un archivo pgpass temporal. */
    public Map<String, String> variablesProcesoPostgres() {
        if (enlaceBD == null) throw new IllegalStateException("No está configurado el enlace de PostgreSQL");
        return enlaceBD.variablesProcesoPostgres();
    }

    public String passwordProcesoPostgres() {
        if (enlaceBD == null) throw new IllegalStateException("No está configurado el enlace de PostgreSQL");
        return enlaceBD.password;
    }

    /**
     * A5 — Aplica las migraciones de Flyway pendientes (reemplaza a {@code inicializarTablas()}).
     *
     * <ul>
     *   <li><b>BD vacía:</b> corre V1, V2... en orden y crea todo el esquema.</li>
     *   <li><b>BD que ya existía sin Flyway (la de Render):</b> {@code baselineOnMigrate} marca el esquema
     *       actual como versión 0 y aplica V1, V2... encima. V1 es idempotente, así que no cambia lo existente.</li>
     *   <li><b>BD ya migrada:</b> solo aplica los scripts nuevos.</li>
     * </ul>
     *
     * Si una migración falla lanza una excepción y la app NO debe arrancar: seguir con un esquema a medias
     * es peor que no arrancar (el deploy anterior de Render sigue sirviendo hasta que se corrija).
     * Cada script corre en su propia transacción: si falla se revierte entero.
     *
     * @throws IllegalStateException si no hay conexión con la BD
     * @throws org.flywaydb.core.api.FlywayException si una migración falla o un script ya aplicado fue modificado
     */
    public void migrar() {
        if (dataSource == null) {
            throw new IllegalStateException("No hay conexión con PostgreSQL: no se pueden aplicar las migraciones");
        }
        logger.info("🔄 Aplicando migraciones de Flyway ({})...", UBICACION_MIGRACIONES);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(UBICACION_MIGRACIONES)
                .baselineOnMigrate(true)   // BD con tablas pero sin historial de Flyway (la de Render antes de A5)
                .baselineVersion("0")      // el esquema previo cuenta como versión 0, así V1 sí se ejecuta
                .baselineDescription("Esquema previo a Flyway (lo creaba inicializarTablas)")
                .validateOnMigrate(true)   // falla si un script ya aplicado fue editado (checksum distinto)
                .cleanDisabled(true)       // `flyway clean` borraría TODA la BD: nunca desde la app
                .load();

        MigrateResult resultado = flyway.migrate();
        String version = resultado.targetSchemaVersion != null ? resultado.targetSchemaVersion : "ninguna";
        logger.info("👍 Esquema al día: {} migración(es) aplicada(s) ahora, versión actual = {}",
                resultado.migrationsExecuted, version);
    }

    /**
     * Configura y migra el pool aislado de la zona de pruebas usando {@code TEST_DATABASE_URL}
     * (la misma base de datos de las pruebas de JUnit). Si en desarrollo local no se definió
     * {@code TEST_DATABASE_URL}, usa un esquema aislado {@code tf_zona_pruebas} para no mezclar nunca
     * datos con el esquema de producción.
     */
    public synchronized void configurarZonaPruebas(String testDatabaseUrl, String sslMode, int maxPoolSize) {
        cerrarPoolPruebas();
        String modoSsl = (sslMode == null || sslMode.isBlank()) ? sslModePrincipal : sslMode;
        String urlCandidata = testDatabaseUrl != null ? testDatabaseUrl.trim() : "";
        if (urlCandidata.isEmpty()) {
            if (urlOriginalPrincipal == null || urlOriginalPrincipal.isBlank()) return;
            urlCandidata = agregarCurrentSchema(urlOriginalPrincipal, ESQUEMA_FALLBACK_PRUEBAS);
        }

        try {
            EnlaceBD candidato = EnlaceBD.desde(urlCandidata, modoSsl);
            if (mismaBaseYEsquema(this.enlaceBD, candidato)) {
                urlCandidata = agregarCurrentSchema(urlCandidata, ESQUEMA_FALLBACK_PRUEBAS);
                candidato = EnlaceBD.desde(urlCandidata, modoSsl);
            }

            String esquemaDestino = extraerCurrentSchema(candidato.jdbcUrl);
            if (esquemaDestino != null && !esquemaDestino.isBlank() && !"public".equalsIgnoreCase(esquemaDestino)) {
                asegurarEsquemaFisico(candidato, esquemaDestino);
            }

            HikariConfig config = new HikariConfig();
            config.setPoolName("torneos-db-pruebas");
            config.setJdbcUrl(candidato.jdbcUrl);
            if (candidato.usuario != null) config.setUsername(candidato.usuario);
            if (candidato.password != null) config.setPassword(candidato.password);
            config.setMaximumPoolSize(Math.max(2, Math.min(8, maxPoolSize)));
            config.setMinimumIdle(1);
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(5000);

            this.dataSourcePruebas = new HikariDataSource(config);
            this.enlaceBDPruebas = candidato;
            this.esquemaPruebas = (esquemaDestino == null || esquemaDestino.isBlank()) ? "public" : esquemaDestino;

            var flywayConfig = Flyway.configure()
                    .dataSource(this.dataSourcePruebas)
                    .locations(UBICACION_MIGRACIONES)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .baselineDescription("Esquema zona de pruebas")
                    .validateOnMigrate(true)
                    .cleanDisabled(true);
            if (!"public".equalsIgnoreCase(this.esquemaPruebas)) {
                flywayConfig.schemas(this.esquemaPruebas)
                        .defaultSchema(this.esquemaPruebas)
                        .createSchemas(true);
            }
            MigrateResult res = flywayConfig.load().migrate();
            sincronizarUsuariosPruebaYAdmin();
            logger.info("🧪 Zona de pruebas lista en {} (migraciones aplicadas ahora: {})",
                    descripcionBasePruebas(), res.migrationsExecuted);
        } catch (Exception e) {
            cerrarPoolPruebas();
            logger.error("⚠️ No se pudo inicializar la zona de pruebas: {}", e.getMessage());
        }
    }

    private static boolean mismaBaseYEsquema(EnlaceBD a, EnlaceBD b) {
        if (a == null || b == null) return false;
        boolean mismaBase = a.hostProceso.equalsIgnoreCase(b.hostProceso)
                && a.puertoProceso.equals(b.puertoProceso)
                && a.nombreBase.equalsIgnoreCase(b.nombreBase);
        if (!mismaBase) return false;
        String esqA = extraerCurrentSchema(a.jdbcUrl);
        String esqB = extraerCurrentSchema(b.jdbcUrl);
        String normA = (esqA == null || esqA.isBlank()) ? "public" : esqA.trim().toLowerCase(Locale.ROOT);
        String normB = (esqB == null || esqB.isBlank()) ? "public" : esqB.trim().toLowerCase(Locale.ROOT);
        return normA.equals(normB);
    }

    private static String extraerCurrentSchema(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        int q = jdbcUrl.indexOf('?');
        if (q < 0) return null;
        for (String par : jdbcUrl.substring(q + 1).split("&")) {
            int eq = par.indexOf('=');
            if (eq <= 0) continue;
            String k = par.substring(0, eq).trim();
            if ("currentSchema".equalsIgnoreCase(k)) {
                return par.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String agregarCurrentSchema(String url, String esquema) {
        String limpia = url.replaceAll("([?&])currentSchema=[^&]*&?", "$1")
                .replaceAll("[?&]$", "");
        return limpia + (limpia.contains("?") ? "&" : "?") + "currentSchema=" + esquema;
    }

    private static void asegurarEsquemaFisico(EnlaceBD enlace, String esquema) throws SQLException {
        if (!esquema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new SQLException("Nombre de esquema de pruebas no válido");
        }
        Properties props = new Properties();
        if (enlace.usuario != null && !enlace.usuario.isBlank()) props.setProperty("user", enlace.usuario);
        if (enlace.password != null) props.setProperty("password", enlace.password);
        try (Connection conn = DriverManager.getConnection(enlace.jdbcUrl, props);
             PreparedStatement ps = conn.prepareStatement("CREATE SCHEMA IF NOT EXISTS \"" + esquema + "\"")) {
            ps.execute();
        }
    }

    public void invalidarCacheUsuario(int userId) {
        if (userId > 0) {
            cacheZonaUsuario.remove(userId);
            usuariosSincronizadosPruebas.remove(userId);
        }
    }

    public EstadoZonaUsuario obtenerEstadoZonaUsuario(int userId) {
        if (userId <= 0) {
            return new EstadoZonaUsuario(0, SUSCRIPCION_FREE, ZONA_NORMAL, ZONA_NORMAL, 0L);
        }
        long ahora = System.currentTimeMillis();
        EstadoZonaUsuario enCache = cacheZonaUsuario.get(userId);
        if (enCache != null && ahora < enCache.expiraCacheMs()) {
            if (ZONA_PRUEBA.equals(enCache.zonaActiva())) {
                asegurarUsuarioEnPruebas(userId);
            }
            return enCache;
        }
        String tipo = SUSCRIPCION_FREE;
        String zonaAdmin = ZONA_NORMAL;
        try (Connection conn = getConnectionPrincipal();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(tipo_suscripcion, 'free') AS tipo_suscripcion, " +
                     "COALESCE(zona_admin, 'normal') AS zona_admin FROM users WHERE id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    tipo = rs.getString("tipo_suscripcion").trim().toLowerCase(Locale.ROOT);
                    zonaAdmin = normalizarZona(rs.getString("zona_admin"));
                }
            }
        } catch (SQLException e) {
            logger.warn("No se pudo consultar la zona del usuario {}: {}", userId, e.getMessage());
        }

        String zonaActiva = ZONA_NORMAL;
        if (tieneZonaPruebas()) {
            if (SUSCRIPCION_PRUEBA.equalsIgnoreCase(tipo)) {
                zonaActiva = ZONA_PRUEBA;
            } else if (SUSCRIPCION_ADMIN.equalsIgnoreCase(tipo) && ZONA_PRUEBA.equalsIgnoreCase(zonaAdmin)) {
                zonaActiva = ZONA_PRUEBA;
            }
        }
        if (ZONA_PRUEBA.equals(zonaActiva)) {
            asegurarUsuarioEnPruebas(userId);
        }
        EstadoZonaUsuario nuevo = new EstadoZonaUsuario(userId, tipo, zonaAdmin, zonaActiva,
                ahora + CACHE_ZONA_USUARIO_MS);
        cacheZonaUsuario.put(userId, nuevo);
        return nuevo;
    }

    public String resolverZonaUsuario(int userId) {
        return obtenerEstadoZonaUsuario(userId).zonaActiva();
    }

    public void asegurarUsuarioEnPruebas(int userId) {
        if (!tieneZonaPruebas() || userId <= 0) return;
        if (adminsSincronizadosPruebas.compareAndSet(false, true)) {
            sincronizarUsuariosPruebaYAdmin();
        }
        if (usuariosSincronizadosPruebas.containsKey(userId)) return;
        sincronizarUsuarioConPruebas(userId);
    }

    /** Copia o actualiza la identidad y rol de todos los usuarios 'prueba' y 'admin' hacia la BD de pruebas. */
    public void sincronizarUsuariosPruebaYAdmin() {
        if (!tieneZonaPruebas()) return;
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = getConnectionPrincipal();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE LOWER(COALESCE(tipo_suscripcion, '')) IN ('prueba', 'admin') ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
        } catch (SQLException e) {
            logger.warn("No se pudieron listar los usuarios para sincronizar en pruebas: {}", e.getMessage());
            return;
        }
        for (int id : ids) {
            sincronizarUsuarioConPruebas(id);
        }
        adminsSincronizadosPruebas.set(true);
    }

    /**
     * Sincroniza la cuenta desde la BD principal hacia la copia temporal de pruebas conservando su mismo {@code id}
     * y sin sobrescribir el saldo ni las estadísticas que ya tenga acumuladas en la zona de pruebas.
     */
    public void sincronizarUsuarioConPruebas(int userId) {
        if (!tieneZonaPruebas() || userId <= 0) return;
        try (Connection origen = getConnectionPrincipal();
             PreparedStatement psLeer = origen.prepareStatement(
                     "SELECT id, username, email, password, player_tag, telefono, " +
                     "COALESCE(tipo_suscripcion, 'free') AS tipo_suscripcion, " +
                     "COALESCE(player_tag_verificado, FALSE) AS player_tag_verificado, " +
                     "player_tag_verificado_en, player_tag_verificado_por, " +
                     "player_verification_token_ciphertext, " +
                     "COALESCE(zona_admin, 'normal') AS zona_admin " +
                     "FROM users WHERE id = ?")) {
            psLeer.setInt(1, userId);
            try (ResultSet rs = psLeer.executeQuery()) {
                if (!rs.next()) return;
                int id = rs.getInt("id");
                String username = rs.getString("username");
                String email = rs.getString("email");
                String password = rs.getString("password");
                String playerTag = rs.getString("player_tag");
                String telefono = rs.getString("telefono");
                String tipoSuscripcion = rs.getString("tipo_suscripcion");
                boolean tagVerificado = rs.getBoolean("player_tag_verificado");
                Timestamp tagVerificadoEn = rs.getTimestamp("player_tag_verificado_en");
                Integer tagVerificadoPor = rs.getObject("player_tag_verificado_por") != null
                        ? rs.getInt("player_tag_verificado_por") : null;
                String tokenCipher = rs.getString("player_verification_token_ciphertext");
                String zonaAdmin = normalizarZona(rs.getString("zona_admin"));

                try (Connection destino = getConnectionPruebas()) {
                    boolean auto = destino.getAutoCommit();
                    destino.setAutoCommit(false);
                    try {
                        // Despeja posibles colisiones con datos sintéticos previos de distinto ID en la base de pruebas.
                        try (PreparedStatement psDespejar = destino.prepareStatement(
                                "UPDATE users SET username = 'legacy_u_' || id, " +
                                "email = 'legacy_' || id || '@pruebas.invalid', " +
                                "player_tag = NULL, telefono = NULL " +
                                "WHERE id <> ? AND (" +
                                "LOWER(BTRIM(username)) = LOWER(BTRIM(?)) " +
                                "OR LOWER(BTRIM(email)) = LOWER(BTRIM(?)) " +
                                "OR (? IS NOT NULL AND BTRIM(?) <> '' AND player_tag IS NOT NULL " +
                                "    AND UPPER(REGEXP_REPLACE(BTRIM(player_tag), '^#+', '')) = UPPER(REGEXP_REPLACE(BTRIM(?), '^#+', ''))) " +
                                "OR (? IS NOT NULL AND REGEXP_REPLACE(BTRIM(?), '[^0-9]', '', 'g') <> '' AND telefono IS NOT NULL " +
                                "    AND REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') = REGEXP_REPLACE(BTRIM(?), '[^0-9]', '', 'g'))" +
                                ")")) {
                            psDespejar.setInt(1, id);
                            psDespejar.setString(2, username != null ? username : "");
                            psDespejar.setString(3, email != null ? email : "");
                            psDespejar.setString(4, playerTag);
                            psDespejar.setString(5, playerTag != null ? playerTag : "");
                            psDespejar.setString(6, playerTag != null ? playerTag : "");
                            psDespejar.setString(7, telefono);
                            psDespejar.setString(8, telefono != null ? telefono : "");
                            psDespejar.setString(9, telefono != null ? telefono : "");
                            psDespejar.executeUpdate();
                        }

                        // Si tagVerificadoPor apunta a un usuario que aún no está en la BD de pruebas, se deja NULL.
                        Integer verificadoPorSeguro = null;
                        if (tagVerificadoPor != null && tagVerificadoPor > 0) {
                            if (tagVerificadoPor == id) {
                                verificadoPorSeguro = id;
                            } else {
                                try (PreparedStatement psCheck = destino.prepareStatement(
                                        "SELECT 1 FROM users WHERE id = ?")) {
                                    psCheck.setInt(1, tagVerificadoPor);
                                    try (ResultSet rsCheck = psCheck.executeQuery()) {
                                        if (rsCheck.next()) verificadoPorSeguro = tagVerificadoPor;
                                    }
                                }
                            }
                        }

                        try (PreparedStatement psUpsert = destino.prepareStatement(
                                "INSERT INTO users (id, username, email, password, player_tag, telefono, saldo, " +
                                "tipo_suscripcion, estado, player_tag_verificado, player_tag_verificado_en, " +
                                "player_tag_verificado_por, player_verification_token_ciphertext, zona_admin) " +
                                "VALUES (?, ?, ?, ?, ?, ?, 0, ?, 'normal', ?, ?, ?, ?, ?) " +
                                "ON CONFLICT (id) DO UPDATE SET " +
                                "username = EXCLUDED.username, " +
                                "email = EXCLUDED.email, " +
                                "password = EXCLUDED.password, " +
                                "player_tag = EXCLUDED.player_tag, " +
                                "telefono = EXCLUDED.telefono, " +
                                "tipo_suscripcion = EXCLUDED.tipo_suscripcion, " +
                                "player_tag_verificado = EXCLUDED.player_tag_verificado, " +
                                "player_tag_verificado_en = EXCLUDED.player_tag_verificado_en, " +
                                "player_tag_verificado_por = EXCLUDED.player_tag_verificado_por, " +
                                "player_verification_token_ciphertext = EXCLUDED.player_verification_token_ciphertext, " +
                                "zona_admin = EXCLUDED.zona_admin")) {
                            psUpsert.setInt(1, id);
                            psUpsert.setString(2, username);
                            psUpsert.setString(3, email);
                            psUpsert.setString(4, password);
                            psUpsert.setString(5, playerTag);
                            psUpsert.setString(6, telefono);
                            psUpsert.setString(7, tipoSuscripcion);
                            psUpsert.setBoolean(8, tagVerificado);
                            psUpsert.setTimestamp(9, tagVerificadoEn);
                            if (verificadoPorSeguro != null) {
                                psUpsert.setInt(10, verificadoPorSeguro);
                            } else {
                                psUpsert.setNull(10, Types.INTEGER);
                            }
                            psUpsert.setString(11, tokenCipher);
                            psUpsert.setString(12, zonaAdmin);
                            psUpsert.executeUpdate();
                        }

                        try (PreparedStatement psSeq = destino.prepareStatement(
                                "SELECT setval(pg_get_serial_sequence('users', 'id'), " +
                                "GREATEST(COALESCE((SELECT MAX(id) FROM users), 1), ?), true)")) {
                            psSeq.setInt(1, id);
                            try (ResultSet ignored = psSeq.executeQuery()) {
                            }
                        }

                        destino.commit();
                        usuariosSincronizadosPruebas.put(id, tipoSuscripcion);
                        cacheZonaUsuario.remove(id);
                    } catch (SQLException e) {
                        try { destino.rollback(); } catch (SQLException ignored) {}
                        throw e;
                    } finally {
                        try { destino.setAutoCommit(auto); } catch (SQLException ignored) {}
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("No se pudo sincronizar el usuario {} en la zona de pruebas: {}", userId, e.getMessage());
        }
    }

    /**
     * Resumen de filas y saldos actuales en la base de datos de la zona de pruebas.
     */
    public JsonObject resumenZonaPruebas() {
        JsonObject res = new JsonObject();
        res.addProperty("disponible", tieneZonaPruebas());
        res.addProperty("baseDatos", descripcionBasePruebas());
        if (!tieneZonaPruebas()) {
            res.addProperty("usuarios", 0);
            res.addProperty("partidas", 0);
            res.addProperty("transacciones", 0);
            res.addProperty("mensajes", 0);
            res.addProperty("sorteos", 0);
            res.addProperty("ticketsSoporte", 0);
            res.addProperty("movimientosLedger", 0);
            res.addProperty("saldoTotalPruebas", BigDecimal.ZERO);
            return res;
        }
        try (Connection conn = getConnectionPruebas()) {
            res.addProperty("usuarios", contarFilas(conn, "SELECT COUNT(*) FROM users WHERE tipo_suscripcion = 'prueba'"));
            res.addProperty("usuariosTotalesCopia", contarFilas(conn, "SELECT COUNT(*) FROM users"));
            res.addProperty("partidas", contarFilas(conn, "SELECT COUNT(*) FROM matches"));
            res.addProperty("transacciones", contarFilas(conn, "SELECT COUNT(*) FROM transactions"));
            res.addProperty("mensajes", contarFilas(conn, "SELECT COUNT(*) FROM messages"));
            res.addProperty("sorteos", contarFilas(conn, "SELECT COUNT(*) FROM raffles"));
            res.addProperty("ticketsSoporte", contarFilas(conn, "SELECT COUNT(*) FROM support_tickets"));
            res.addProperty("movimientosLedger", contarFilas(conn, "SELECT COUNT(*) FROM wallet_ledger"));
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(saldo), 0) FROM users");
                 ResultSet rs = ps.executeQuery()) {
                BigDecimal total = rs.next() && rs.getBigDecimal(1) != null ? rs.getBigDecimal(1) : BigDecimal.ZERO;
                res.addProperty("saldoTotalPruebas", total);
            }
        } catch (SQLException e) {
            logger.warn("No se pudo obtener el resumen de la zona de pruebas: {}", e.getMessage());
        }
        return res;
    }

    private static long contarFilas(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * Borra todos los datos e historiales de la copia temporal de pruebas cuando lo decide el administrador
     * y vuelve a dejar sincronizadas las cuentas {@code prueba} y {@code admin} con saldo y estadísticas en cero.
     */
    public synchronized void reiniciarDatosZonaPruebas() throws SQLException {
        if (!tieneZonaPruebas() || enlaceBDPruebas == null) {
            throw new SQLException("La base de datos de pruebas no está configurada");
        }
        if (mismaBaseYEsquema(this.enlaceBD, this.enlaceBDPruebas)) {
            throw new SQLException("Operación bloqueada: la zona de pruebas no puede apuntar al esquema principal");
        }

        String[] tablasBorrar = {
                "leaderboard_cut_payments",
                "leaderboard_cut_snapshots",
                "leaderboard_cuts",
                "leaderboard_live_positions",
                "leaderboard_history",
                "raffle_entries",
                "raffle_votes",
                "raffle_pool_movements",
                "raffles",
                "user_tickets",
                "payment_evidences",
                "support_tickets",
                "chatbot_duda_usuarios",
                "chatbot_mensajes",
                "chatbot_preferencias",
                "chatbot_conocimiento",
                "voice_channels",
                "discord_links",
                "active_matches",
                "matchmaking_challenges",
                "matchmaking_search_notified",
                "match_queue",
                "matchmaking_profiles",
                "matches",
                "user_notifications",
                "messages",
                "media_files",
                "wallet_ledger",
                "transactions",
                "admin_wallet",
                "legal_acceptances",
                "user_tokens",
                "password_reset_tokens",
                "user_sessions",
                "users"
        };

        String[] tablasSecuenciaId = {
                "messages", "matches", "transactions", "admin_wallet", "user_tokens",
                "user_tickets", "raffles", "raffle_entries", "raffle_pool_movements",
                "leaderboard_history", "leaderboard_cuts", "leaderboard_cut_payments",
                "media_files", "chatbot_conocimiento", "chatbot_mensajes",
                "legal_acceptances", "wallet_ledger", "support_tickets",
                "payment_evidences", "voice_channels", "match_queue", "active_matches",
                "matchmaking_challenges", "user_notifications"
        };

        try (Connection conn = getConnectionPruebas()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DROP TRIGGER IF EXISTS trg_wallet_ledger_inmutable ON wallet_ledger")) {
                    ps.execute();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DROP TRIGGER IF EXISTS trg_wallet_ledger_no_truncate ON wallet_ledger")) {
                    ps.execute();
                }

                for (String tabla : tablasBorrar) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM \"" + tabla + "\"")) {
                        ps.executeUpdate();
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM leaderboard_pools")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO leaderboard_pools (id, dia, semana, mes, ano) " +
                        "VALUES (1, 0, 0, 0, 0)")) {
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM raffle_pool")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO raffle_pool (id, monto) VALUES (1, 0)")) {
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chatbot_config")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO chatbot_config (clave, valor) VALUES ('tono_defecto', 'adaptativo')")) {
                    ps.executeUpdate();
                }

                for (String tabla : tablasSecuenciaId) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT setval(pg_get_serial_sequence(?, 'id'), 1, false)")) {
                        ps.setString(1, tabla);
                        try (ResultSet ignored = ps.executeQuery()) {
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "CREATE TRIGGER trg_wallet_ledger_inmutable " +
                        "BEFORE UPDATE OR DELETE ON wallet_ledger " +
                        "FOR EACH ROW EXECUTE FUNCTION fn_wallet_ledger_inmutable()")) {
                    ps.execute();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "CREATE TRIGGER trg_wallet_ledger_no_truncate " +
                        "BEFORE TRUNCATE ON wallet_ledger " +
                        "EXECUTE FUNCTION fn_wallet_ledger_inmutable()")) {
                    ps.execute();
                }

                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(auto); } catch (SQLException ignored) {}
            }
        }

        usuariosSincronizadosPruebas.clear();
        cacheZonaUsuario.clear();
        adminsSincronizadosPruebas.set(false);
        sincronizarUsuariosPruebaYAdmin();
    }

    private synchronized void cerrarPoolPruebas() {
        usuariosSincronizadosPruebas.clear();
        cacheZonaUsuario.clear();
        adminsSincronizadosPruebas.set(false);
        if (dataSourcePruebas != null) {
            try {
                dataSourcePruebas.close();
            } catch (RuntimeException ignored) {
            }
            dataSourcePruebas = null;
        }
        enlaceBDPruebas = null;
        esquemaPruebas = null;
    }

    public void cerrar() {
        limpiarZonaActual();
        BloqueoOperacion bloqueo = bloqueoRestauracionActual;
        bloqueoRestauracionActual = null;
        if (bloqueo != null) bloqueo.close();
        cerrarPoolPruebas();
        if (dataSource != null) dataSource.close();
        synchronized (ConexionDB.class) {
            if (instancia == this) {
                instancia = null;
            }
        }
    }
}
