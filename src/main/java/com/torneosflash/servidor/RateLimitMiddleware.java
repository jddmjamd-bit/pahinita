package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.RateLimiter;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

import static com.torneosflash.servidor.RutasAuth.errorJson;

/**
 * Middleware de rate limiting por IP (S8).
 *
 * Aquí solo se decide a qué cubo pertenece cada request y se arma la respuesta 429; el conteo
 * lo hace {@link RateLimiter} (capa de servicio).
 *
 * Cubos (todos por IP, ventana deslizante de 1 minuto, configurables por variable de entorno):
 * <ul>
 *   <li><b>GENERAL</b> (60/min): cualquier ruta de la API y de los paneles admin.</li>
 *   <li><b>FINANCIERO</b> (10/min): rutas que mueven dinero. Cuentan en este cubo Y en el general.</li>
 *   <li><b>MEDIA</b> (300/min): {@code GET /api/media/*}. Un video hace muchas peticiones Range al
 *       buscar y el chat carga varias imágenes a la vez; por eso tiene su propio cubo, más amplio.</li>
 * </ul>
 *
 * Exentos: preflight CORS ({@code OPTIONS}), los archivos estáticos del frontend, el WebSocket y el
 * webhook de Wompi (llegan de los servidores de Wompi, no de un usuario: limitarlo podría perder
 * pagos reales; su defensa es la verificación de firma, tarea S7).
 *
 * Detrás del proxy de Render {@code ctx.ip()} es la IP del proxy, no la del cliente. Por eso se
 * lee {@code X-Forwarded-For} contando desde la DERECHA ({@code RATE_LIMIT_PROXY_HOPS}), que es
 * lo que añaden los proxies de confianza; la parte izquierda la puede escribir el cliente.
 */
public class RateLimitMiddleware {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitMiddleware.class);

    enum Categoria { EXENTO, GENERAL, FINANCIERO, MEDIA }

    /** Rutas que mueven dinero (en minúsculas, sin "/" final). */
    private static final Set<String> RUTAS_FINANCIERAS = Set.of(
            "/api/deposit",
            "/api/transaction/create",
            "/api/transaction/withdraw",
            "/api/wompi/init",
            "/api/admin/transaction/process",
            "/api/admin/resolve-dispute");

    /** Solo se limita lo que es API o panel admin; el frontend estático no (cada carga pide decenas de archivos). */
    private static final String[] PREFIJOS_PROTEGIDOS = {
            "/api/", "/admin-db/", "/secret-admin/", "/admin-fix-status/", "/check-ip"
    };

    private static final String RUTA_WEBHOOK_WOMPI = "/api/wompi/webhook";
    private static final String PREFIJO_MEDIA = "/api/media/";

    /** Una IP válida (v4 o v6) mide menos de 46 caracteres; un valor más largo es basura del cliente. */
    private static final int LARGO_MAX_IP = 64;

    public static void register(Javalin app, AppConfig config, RateLimiter limiter) {
        final int limiteGeneral = config.getRateLimitGeneralPorMin();
        final int limiteFinanciero = config.getRateLimitFinancieroPorMin();
        final int limiteMedia = config.getRateLimitMediaPorMin();
        final int saltosProxy = config.getRateLimitProxyHops();

        logger.info("🚦 Rate limiting activo: general {}/min, financiero {}/min, media {}/min por IP (saltos de proxy: {})",
                limiteGeneral, limiteFinanciero, limiteMedia, saltosProxy);

        app.before(ctx -> {
            String metodo = ctx.req().getMethod();
            String ruta = normalizar(ctx.path());
            Categoria categoria = clasificar(metodo, ruta);
            if (categoria == Categoria.EXENTO) return;

            String ip = ipCliente(ctx, saltosProxy);

            if (categoria == Categoria.MEDIA) {
                RateLimiter.Resultado media = limiter.intentar("media|" + ip, limiteMedia);
                if (!media.isPermitido()) {
                    rechazar(ctx, media, categoria, ip, metodo, ruta);
                    return;
                }
                encabezados(ctx, media);
                return;
            }

            // Todo lo demás cuenta en el cubo general (incluidas las rutas financieras)
            RateLimiter.Resultado general = limiter.intentar("general|" + ip, limiteGeneral);
            if (!general.isPermitido()) {
                rechazar(ctx, general, Categoria.GENERAL, ip, metodo, ruta);
                return;
            }

            if (categoria == Categoria.FINANCIERO) {
                RateLimiter.Resultado financiero = limiter.intentar("financiero|" + ip, limiteFinanciero);
                if (!financiero.isPermitido()) {
                    rechazar(ctx, financiero, Categoria.FINANCIERO, ip, metodo, ruta);
                    return;
                }
                encabezados(ctx, financiero);
                return;
            }

            encabezados(ctx, general);
        });
    }

    /** Decide a qué cubo pertenece un request. {@code ruta} ya debe venir normalizada. */
    static Categoria clasificar(String metodo, String ruta) {
        if ("OPTIONS".equalsIgnoreCase(metodo)) return Categoria.EXENTO; // preflight CORS: no es una acción del usuario
        if (!esRutaProtegida(ruta)) return Categoria.EXENTO;
        if (ruta.equals(RUTA_WEBHOOK_WOMPI)) return Categoria.EXENTO;

        boolean lectura = "GET".equalsIgnoreCase(metodo) || "HEAD".equalsIgnoreCase(metodo);
        if (lectura && ruta.startsWith(PREFIJO_MEDIA)) return Categoria.MEDIA;

        if (RUTAS_FINANCIERAS.contains(ruta)) return Categoria.FINANCIERO;
        return Categoria.GENERAL;
    }

    private static boolean esRutaProtegida(String ruta) {
        for (String prefijo : PREFIJOS_PROTEGIDOS) {
            if (ruta.startsWith(prefijo)) return true;
        }
        return false;
    }

    /**
     * Minúsculas, "//" -> "/" y sin "/" final. Javalin ignora el "/" final al buscar la ruta, así que
     * "/api/deposit/" llega al mismo handler que "/api/deposit" y debe contar igual.
     */
    static String normalizar(String ruta) {
        if (ruta == null || ruta.isEmpty()) return "/";
        String r = ruta.toLowerCase(Locale.ROOT).replaceAll("/{2,}", "/");
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /**
     * IP del cliente. Con {@code saltosProxy = N} se toma la N-ésima entrada de {@code X-Forwarded-For}
     * contando desde la derecha (la que añadió nuestro proxy). Con 0 se ignora el header y se usa la
     * IP de la conexión.
     */
    static String ipCliente(Context ctx, int saltosProxy) {
        String directa = ctx.ip();
        if (saltosProxy <= 0) return directa;

        String xff = ctx.header("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return directa;

        String[] partes = xff.split(",");
        int indice = partes.length - saltosProxy;
        if (indice < 0) indice = 0;
        String ip = partes[indice].trim();
        if (ip.isEmpty() || ip.length() > LARGO_MAX_IP) return directa;
        return ip;
    }

    private static void encabezados(Context ctx, RateLimiter.Resultado r) {
        ctx.header("X-RateLimit-Limit", String.valueOf(r.getLimite()));
        ctx.header("X-RateLimit-Remaining", String.valueOf(r.getRestantes()));
    }

    private static void rechazar(Context ctx, RateLimiter.Resultado r, Categoria categoria,
                                 String ip, String metodo, String ruta) {
        if (r.isPrimerBloqueo()) {
            logger.warn("🚦 Rate limit ({}): IP {} superó {}/min en {} {}",
                    categoria, ip, r.getLimite(), metodo, ruta);
        }
        long segundos = r.getReintentarEnSegundos();
        JsonObject cuerpo = errorJson("Demasiadas solicitudes. Intenta de nuevo en " + segundos + " segundos.");
        cuerpo.addProperty("retryAfter", segundos);

        ctx.status(429);
        ctx.header("Retry-After", String.valueOf(segundos));
        ctx.header("X-RateLimit-Limit", String.valueOf(r.getLimite()));
        ctx.header("X-RateLimit-Remaining", "0");
        ctx.json(cuerpo);
        ctx.skipRemainingHandlers();
    }
}
