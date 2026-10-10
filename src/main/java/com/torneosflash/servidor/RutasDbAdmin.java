package com.torneosflash.servidor;

import com.google.gson.*;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.DbAdminService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas del panel de administración de la base de datos.
 * Incluye: visualizar tablas, editar celdas, importar/exportar.
 *
 * A3: el SQL vive en {@link DbAdminService}. Aquí quedan solo el acceso al panel (clave, bloqueo por IP,
 * cabeceras de seguridad) y el parseo del request.
 *
 * S10 — acceso por navegador más seguro:
 * <ul>
 *   <li>La URL del panel es {@code /admin-db} (sin la clave). Antes era {@code /admin-db/<clave>}, y la
 *       clave quedaba en el historial del navegador, en los logs del servidor y en el header Referer.
 *       Ahora la clave se escribe en una pantalla de acceso y viaja en el header {@code X-DB-Admin-Key}.</li>
 *   <li>Ya no existe clave por defecto: sin {@code DB_ADMIN_SECRET} (o con la antigua {@code torneos2024},
 *       que estaba publicada en el código) el panel queda DESHABILITADO.</li>
 *   <li>La clave se compara en tiempo constante y tras 5 intentos fallidos la IP se bloquea 15 minutos.</li>
 *   <li>Todas las operaciones validan el nombre de tabla/columna contra {@code information_schema}.</li>
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

    public static void register(Javalin app, DbAdminService dbAdmin, AppConfig config) {

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
        app.get("/api/db-admin/tables", ctx -> ctx.json(dbAdmin.listarTablas()));

        // GET /api/db-admin/table/:name
        app.get("/api/db-admin/table/{name}", ctx -> ctx.json(dbAdmin.datosTabla(ctx.pathParam("name"))));

        // POST /api/db-admin/table/:name/update
        app.post("/api/db-admin/table/{name}/update", ctx -> {
            JsonObject body = parseBody(ctx);
            String value = body.has("value") && !body.get("value").isJsonNull() ? body.get("value").getAsString() : null;
            dbAdmin.actualizarCelda(ctx.pathParam("name"), Peticion.entero(body, "id"),
                    Peticion.texto(body, "column"), value);
            ctx.json(successJson("Actualizado", 0));
        });

        // POST /api/db-admin/table/:name/delete
        app.post("/api/db-admin/table/{name}/delete", ctx -> {
            JsonObject body = parseBody(ctx);
            dbAdmin.eliminarFila(ctx.pathParam("name"), Peticion.entero(body, "id"));
            ctx.json(successJson("Eliminado", 0));
        });

        // POST /api/db-admin/table/:name/insert
        app.post("/api/db-admin/table/{name}/insert", ctx -> {
            dbAdmin.insertarFila(ctx.pathParam("name"), parseBody(ctx));
            ctx.json(successJson("Insertado", 0));
        });

        // GET /api/db-admin/export
        app.get("/api/db-admin/export", ctx -> ctx.json(dbAdmin.exportar()));

        // POST /api/db-admin/import
        app.post("/api/db-admin/import", ctx -> {
            int filas = dbAdmin.importar(parseBody(ctx));
            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.addProperty("message", filas + " filas importadas");
            ctx.json(res);
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
}
