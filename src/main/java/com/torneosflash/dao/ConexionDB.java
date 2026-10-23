package com.torneosflash.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
    private HikariDataSource dataSource;
    private EnlaceBD enlaceBD;
    private final AtomicBoolean restauracionActiva = new AtomicBoolean(false);
    private BloqueoOperacion bloqueoRestauracionActual;
    private volatile boolean restauracionGlobalCache;
    private volatile long restauracionGlobalCacheHasta;

    // --- Constructor privado (ENCAPSULAMIENTO) ---
    private ConexionDB(String databaseUrl, String sslMode, int maxPoolSize) {
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

    // --- Obtener conexión del pool ---
    public Connection getConnection() throws SQLException {
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

    public void cerrar() {
        BloqueoOperacion bloqueo = bloqueoRestauracionActual;
        bloqueoRestauracionActual = null;
        if (bloqueo != null) bloqueo.close();
        if (dataSource != null) dataSource.close();
    }
}
