package com.torneosflash.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    /** A5: carpeta (en el classpath) con los scripts V1__..., V2__... de Flyway. */
    private static final String UBICACION_MIGRACIONES = "classpath:db/migration";
    private HikariDataSource dataSource;

    // --- Constructor privado (ENCAPSULAMIENTO) ---
    private ConexionDB(String databaseUrl, String sslMode, int maxPoolSize) {
        try {
            EnlaceBD enlace = EnlaceBD.desde(databaseUrl, sslMode);
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

        private EnlaceBD(String jdbcUrl, String usuario, String password, String host, String sslMode) {
            this.jdbcUrl = jdbcUrl;
            this.usuario = usuario;
            this.password = password;
            this.host = host;
            this.sslMode = sslMode;
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
            return new EnlaceBD(jdbcUrl, usuario, password, host, modo);
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
        return dataSource.getConnection();
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
        if (dataSource != null) dataSource.close();
    }
}
