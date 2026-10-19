package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.ConexionDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Backup y restore lógico completo de la base de la aplicación usando pg_dump/pg_restore. */
public final class PostgresBackupService {
    private static final Logger logger = LoggerFactory.getLogger(PostgresBackupService.class);
    private static final String CONFIRMACION_RESTORE = "RESTAURAR BASE DE DATOS";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long MAX_BYTES_LISTADO = 16L * 1024 * 1024;
    private static final Duration TIEMPO_BACKUP = Duration.ofHours(2);
    private static final Duration TIEMPO_RESTORE = Duration.ofHours(3);
    private static final Duration TIEMPO_VALIDACION = Duration.ofMinutes(2);
    private static final long ESPERA_CONEXIONES_MS = 60_000L;
    private static final Set<String> TABLAS_FINANCIERAS_REQUERIDAS = Set.of(
            "users", "transactions", "wallet_ledger", "user_tokens", "flyway_schema_history");
    private static final Set<PosixFilePermission> PERMISOS_DIRECTORIO_TEMPORAL =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PERMISOS_ARCHIVO_TEMPORAL =
            PosixFilePermissions.fromString("rw-------");

    private final ConexionDB conexion;
    private final long maxBytes;
    private final AtomicBoolean operacionEnCurso = new AtomicBoolean(false);

    public PostgresBackupService(ConexionDB conexion, AppConfig config) {
        this.conexion = conexion;
        this.maxBytes = config.getDbAdminMaxBackupBytes();
    }

    /** Crea un backup custom de pg_dump completo; el caller debe cerrar el artefacto al terminar de transmitirlo. */
    public ArchivoBackup crearBackupCompleto() {
        exigirOperacionLibre();
        Path directorio = null;
        boolean transferirArchivo = false;
        ConexionDB.BloqueoOperacion bloqueoOperacion = null;
        try {
            bloqueoOperacion = conexion.intentarBloqueoOperacionCompleta();
            if (bloqueoOperacion == null) {
                throw new ServicioException(409, "Ya hay un backup o una restauración en curso en otra instancia");
            }
            directorio = crearDirectorioTemporal();
            Path archivo = directorio.resolve("torneosflash.dump");
            Path pgpass = crearPgpass(directorio);
            Map<String, String> entorno = entornoProceso(pgpass);
            Path log = directorio.resolve("pg_dump.log");

            // Mantiene el advisory lock compartido mientras pg_dump toma su snapshot; un restore espera a que termine.
            if (!bloqueoOperacion.intentarBloqueoCompartidoRestauracion()) {
                throw ServicioException.noDisponible("La base de datos está entrando en mantenimiento; reintenta el backup.");
            }
            ejecutar(List.of("pg_dump", "--no-password", "--format=custom", "--compress=6",
                            "--no-owner", "--no-acl", "--quote-all-identifiers", "--file", archivo.toString()),
                    entorno, log, null, maxBytes, archivo, maxBytes, TIEMPO_BACKUP,
                    "No se pudo crear el backup completo. Verifica permisos, espacio temporal y versión de PostgreSQL.");

            long bytes = Files.size(archivo);
            if (bytes <= 0 || bytes > maxBytes) {
                throw new ServicioException(413,
                        "El backup supera el límite configurado de " + maxBytes + " bytes o quedó vacío.");
            }
            String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            ArchivoBackup resultado = new ArchivoBackup(directorio, archivo, bytes,
                    "torneosflash-postgresql-" + fecha + ".dump", operacionEnCurso);
            transferirArchivo = true;
            return resultado;
        } catch (ServicioException e) {
            eliminarDirectorio(directorio);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            eliminarDirectorio(directorio);
            throw ServicioException.noDisponible("Se interrumpió la creación del backup PostgreSQL.");
        } catch (SQLException e) {
            eliminarDirectorio(directorio);
            throw ServicioException.noDisponible(
                    "No se pudo reservar acceso a PostgreSQL para crear el backup; verifica el estado del servicio.");
        } catch (IOException | RuntimeException e) {
            eliminarDirectorio(directorio);
            logger.error("No se pudo crear el backup PostgreSQL: {}", e.getClass().getSimpleName());
            throw ServicioException.noDisponible(
                    "No se pudo crear el backup completo. Verifica las herramientas y permisos de PostgreSQL.");
        } finally {
            if (bloqueoOperacion != null) bloqueoOperacion.close();
            if (!transferirArchivo) operacionEnCurso.set(false);
        }
    }

    /**
     * Valida el archivo y restaura todos sus objetos y datos dentro de una transacción PostgreSQL.
     * // REVIEW-MONEY: users.saldo, transactions y wallet_ledger se restauran juntos desde el mismo snapshot.
     */
    public JsonObject restaurarBackupCompleto(InputStream entrada, long contentLength, String confirmacion) {
        if (!CONFIRMACION_RESTORE.equals(confirmacion)) {
            throw ServicioException.prohibido("Escribe la frase de confirmación requerida para reemplazar la base");
        }
        if (contentLength > maxBytes) {
            throw new ServicioException(413, "El backup supera el límite configurado de " + maxBytes + " bytes.");
        }
        exigirOperacionLibre();

        Path directorio = null;
        boolean modoRestauracion = false;
        boolean mantenerMantenimiento = false;
        ConexionDB.BloqueoOperacion bloqueoOperacion = null;
        try {
            bloqueoOperacion = conexion.intentarBloqueoOperacionCompleta();
            if (bloqueoOperacion == null) {
                throw new ServicioException(409, "Ya hay un backup o una restauración en curso en otra instancia");
            }
            directorio = crearDirectorioTemporal();
            Path archivo = Files.createTempFile(directorio, "restore-", ".dump");
            asegurarPermisos(archivo, PERMISOS_ARCHIVO_TEMPORAL);
            long bytes = copiarConLimite(entrada, archivo);
            if (bytes == 0) throw ServicioException.solicitudInvalida("El archivo de backup está vacío");

            Path pgpass = crearPgpass(directorio);
            Map<String, String> entorno = entornoProceso(pgpass);
            Path lista = directorio.resolve("contenido.txt");
            Path logValidacion = directorio.resolve("pg_restore-list.log");
            ejecutar(List.of("pg_restore", "--list", "--no-password", archivo.toString()),
                    entorno, logValidacion, lista, MAX_BYTES_LISTADO, null, 0, TIEMPO_VALIDACION,
                    "El archivo no es un backup PostgreSQL custom válido o está dañado.");
            validarContenidoCompleto(lista);

            conexion.iniciarModoRestauracion(ESPERA_CONEXIONES_MS, bloqueoOperacion);
            modoRestauracion = true;
            Path logRestore = directorio.resolve("pg_restore.log");
            ejecutar(List.of("pg_restore", "--no-password", "--clean", "--if-exists",
                            "--single-transaction", "--exit-on-error", "--no-owner", "--no-acl", archivo.toString()),
                    entorno, logRestore, null, 0, null, 0, TIEMPO_RESTORE,
                    "La restauración falló y PostgreSQL revirtió la transacción completa.");

            // Si el backup viene de una versión anterior, aplica las migraciones pendientes antes de reabrir tráfico.
            try {
                conexion.migrar();
            } catch (RuntimeException e) {
                mantenerMantenimiento = true;
                logger.error("El backup se restauró, pero Flyway falló; se mantiene el servicio en modo mantenimiento.", e);
                throw new ServicioException(503,
                        "El backup se restauró, pero fallaron las migraciones. La API quedó bloqueada; revisa el log y reinicia el servicio al corregirlo.", e);
            }
            try {
                int credencialesRevocadas = conexion.revocarSesionesTrasRestauracion();
                logger.info("Restore completo; se invalidaron {} sesiones y enlaces de recuperación restaurados desde el backup.", credencialesRevocadas);
            } catch (SQLException e) {
                mantenerMantenimiento = true;
                logger.error("El backup se restauró, pero no se pudieron invalidar las sesiones y tokens restaurados; se mantiene el servicio en mantenimiento.", e);
                throw new ServicioException(503,
                        "El backup se restauró, pero no se pudieron invalidar las sesiones y enlaces temporales. La API quedó bloqueada; revisa el log y reinicia el servicio al corregirlo.", e);
            }
            JsonObject resultado = new JsonObject();
            resultado.addProperty("success", true);
            resultado.addProperty("complete", true);
            resultado.addProperty("bytesRestored", bytes);
            resultado.addProperty("message", "Backup completo restaurado; Flyway terminó correctamente.");
            return resultado;
        } catch (ServicioException e) {
            throw e;
        } catch (SQLException e) {
            logger.error("No se pudo entrar o salir del modo de restauración: {}", e.getMessage());
            throw ServicioException.noDisponible(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ServicioException.noDisponible("Se interrumpió la restauración PostgreSQL.");
        } catch (IOException e) {
            logger.error("Error de lectura/escritura durante la restauración PostgreSQL: {}", e.getClass().getSimpleName());
            throw ServicioException.noDisponible(
                    "No se pudo leer o procesar el backup. Si la restauración ya había empezado, PostgreSQL revierte cualquier error.");
        } catch (RuntimeException e) {
            logger.error("Error inesperado en la restauración PostgreSQL: {}", e.getClass().getSimpleName());
            throw ServicioException.noDisponible("No se pudo completar la restauración PostgreSQL.");
        } finally {
            if (modoRestauracion && !mantenerMantenimiento) conexion.terminarModoRestauracion();
            eliminarDirectorio(directorio);
            // Si Flyway falló, el advisory lock queda abierto y la instancia permanece en mantenimiento hasta reiniciar.
            if (bloqueoOperacion != null && !mantenerMantenimiento) bloqueoOperacion.close();
            operacionEnCurso.set(false);
        }
    }

    public boolean isRestauracionActiva() {
        return conexion.isRestauracionActiva();
    }

    private void exigirOperacionLibre() {
        if (!operacionEnCurso.compareAndSet(false, true)) {
            throw new ServicioException(409, "Ya hay una operación de backup/restauración en curso");
        }
    }

    private Path crearDirectorioTemporal() throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        Path directorio = Files.createTempDirectory(base, "torneosflash-db-");
        asegurarPermisos(directorio, PERMISOS_DIRECTORIO_TEMPORAL);
        return directorio;
    }

    private Path crearPgpass(Path directorio) throws IOException {
        String password = conexion.passwordProcesoPostgres();
        if (password == null || password.isEmpty()) return null;
        if (password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            throw ServicioException.noDisponible(
                    "La clave de PostgreSQL contiene un salto de línea y no se puede usar en el archivo temporal de conexión.");
        }
        Map<String, String> env = conexion.variablesProcesoPostgres();
        Path pgpass = Files.createTempFile(directorio, "pgpass-", ".conf");
        asegurarPermisos(pgpass, PERMISOS_ARCHIVO_TEMPORAL);
        String linea = escaparPgpass(env.get("PGHOST")) + ":" + escaparPgpass(env.get("PGPORT")) + ":"
                + escaparPgpass(env.get("PGDATABASE")) + ":" + escaparPgpass(env.getOrDefault("PGUSER", "*"))
                + ":" + escaparPgpass(password) + System.lineSeparator();
        Files.writeString(pgpass, linea);
        return pgpass;
    }

    private Map<String, String> entornoProceso(Path pgpass) {
        Map<String, String> env = conexion.variablesProcesoPostgres();
        if (pgpass != null) env.put("PGPASSFILE", pgpass.toString());
        return env;
    }

    private static String escaparPgpass(String campo) {
        return campo.replace("\\", "\\\\").replace(":", "\\:").replace("*", "\\*");
    }

    private long copiarConLimite(InputStream entrada, Path destino) throws IOException {
        long total = 0;
        try (var salida = Files.newOutputStream(destino)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int leidos;
            while ((leidos = entrada.read(buffer)) != -1) {
                total += leidos;
                if (total > maxBytes) {
                    throw new ServicioException(413,
                            "El backup supera el límite configurado de " + maxBytes + " bytes.");
                }
                salida.write(buffer, 0, leidos);
            }
        }
        return total;
    }

    private void validarContenidoCompleto(Path lista) throws IOException {
        String contenido = Files.readString(lista);
        for (String tabla : TABLAS_FINANCIERAS_REQUERIDAS) {
            String marcador = "TABLE DATA public " + tabla;
            if (contenido.lines().noneMatch(linea -> linea.contains(marcador))) {
                throw ServicioException.solicitudInvalida(
                        "El backup está incompleto: no contiene los datos de " + tabla + ". No se modificó la base actual.");
            }
        }
    }

    private void ejecutar(List<String> comando, Map<String, String> variables,
                          Path archivoError, Path archivoSalida, long maximoSalida,
                          Path archivoConLimite, long limiteArchivoConLimite,
                          Duration timeout, String mensajeError)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(comando);
        // Evita que variables heredadas cambien la BD destino o sustituyan la credencial del DATABASE_URL.
        for (String variable : List.of("PGPASSWORD", "PGPASSFILE", "PGHOST", "PGPORT", "PGDATABASE", "PGUSER",
                "PGSSLMODE", "PGOPTIONS", "PGSERVICE", "PGSERVICEFILE")) {
            builder.environment().remove(variable);
        }
        builder.environment().putAll(variables);
        builder.redirectError(archivoError.toFile());
        if (archivoSalida == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        else builder.redirectOutput(archivoSalida.toFile());

        Process proceso;
        try {
            proceso = builder.start();
        } catch (IOException e) {
            logger.error("No se pudo iniciar la herramienta PostgreSQL {}", comando.get(0));
            throw ServicioException.noDisponible(
                    "El contenedor no tiene instalada la herramienta " + comando.get(0) + ".");
        }

        long limiteNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (!proceso.waitFor(500, TimeUnit.MILLISECONDS)) {
                if ((archivoConLimite != null && Files.exists(archivoConLimite)
                        && Files.size(archivoConLimite) > limiteArchivoConLimite)
                        || (archivoSalida != null && Files.exists(archivoSalida)
                        && Files.size(archivoSalida) > maximoSalida)) {
                    proceso.destroyForcibly();
                    proceso.waitFor();
                    throw new ServicioException(413,
                            "El archivo supera el límite de tamaño permitido para esta operación.");
                }
                if (System.nanoTime() >= limiteNanos) {
                    proceso.destroyForcibly();
                    proceso.waitFor();
                    throw ServicioException.noDisponible("La operación PostgreSQL excedió el tiempo permitido.");
                }
            }
            if (proceso.exitValue() != 0) {
                logger.error("La herramienta PostgreSQL {} terminó con código {}", comando.get(0), proceso.exitValue());
                throw ServicioException.noDisponible(mensajeError);
            }
        } catch (InterruptedException | RuntimeException e) {
            proceso.destroyForcibly();
            throw e;
        }
    }

    private static void asegurarPermisos(Path path, Set<PosixFilePermission> permisos) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permisos);
        } catch (UnsupportedOperationException ignored) {
            // El contenedor de producción es Linux; otros sistemas pueden usar ACL en vez de POSIX.
        }
    }

    private static void eliminarDirectorio(Path directorio) {
        if (directorio == null || !Files.exists(directorio)) return;
        try (Stream<Path> paths = Files.walk(directorio)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.warn("No se pudo borrar un temporal de backup PostgreSQL");
                }
            });
        } catch (IOException e) {
            logger.warn("No se pudo limpiar el directorio temporal de backup PostgreSQL");
        }
    }

    public static final class ArchivoBackup implements AutoCloseable {
        private final Path directorio;
        private final Path archivo;
        private final long bytes;
        private final String nombre;
        private final AtomicBoolean operacionEnCurso;
        private final AtomicBoolean cerrado = new AtomicBoolean(false);

        private ArchivoBackup(Path directorio, Path archivo, long bytes, String nombre,
                              AtomicBoolean operacionEnCurso) {
            this.directorio = directorio;
            this.archivo = archivo;
            this.bytes = bytes;
            this.nombre = nombre;
            this.operacionEnCurso = operacionEnCurso;
        }

        public Path getArchivo() { return archivo; }
        public long getBytes() { return bytes; }
        public String getNombre() { return nombre; }

        @Override
        public void close() {
            if (cerrado.compareAndSet(false, true)) {
                eliminarDirectorio(directorio);
                operacionEnCurso.set(false);
            }
        }
    }
}
