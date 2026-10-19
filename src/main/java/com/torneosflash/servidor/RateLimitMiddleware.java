package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.RateLimiter;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
 *   <li><b>MEDIA</b> (30/min por defecto): {@code GET /api/media/*}. Un video hace peticiones Range al
 *       buscar y el chat carga varias imágenes a la vez; por eso tiene su propio cubo, más amplio.</li>
 * </ul>
 *
 * Exentos: preflight CORS ({@code OPTIONS}), los archivos estáticos del frontend, el WebSocket y el
 * webhook de Wompi (llegan de los servidores de Wompi, no de un usuario: limitarlo podría perder
 * pagos reales; su defensa es la verificación de firma, tarea S7).
 *
 * La cabecera {@code X-Forwarded-For} solo se usa con una cantidad de saltos de proxy configurada
 * explícitamente. Se cuenta desde la derecha y nunca se corrige una cadena demasiado corta tomando
 * la entrada izquierda, que puede haber sido enviada por el cliente. Hasta confirmar la cadena del
 * despliegue, {@code RATE_LIMIT_PROXY_HOPS=0} usa la IP directa de la conexión.
 */
public class RateLimitMiddleware {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitMiddleware.class);

    enum Categoria { EXENTO, GENERAL, FINANCIERO, AUTH, MEDIA }

    /** Rutas que mueven dinero (en minúsculas, sin "/" final). */
    private static final Set<String> RUTAS_FINANCIERAS = Set.of(
            "/api/deposit",
            "/api/transaction/create",
            "/api/transaction/withdraw",
            "/api/wompi/init",
            "/api/admin/transaction/process",
            "/api/admin/resolve-dispute");

    /** Rutas de autenticación y validación pública sujetas a un límite más estricto contra fuerza bruta y sondeo. */
    private static final Set<String> RUTAS_AUTH = Set.of(
            "/api/login",
            "/api/register",
            "/api/password/forgot",
            "/api/password/reset");

    /** Solo se limita lo que es API, socket polling o panel admin; el frontend estático no. */
    private static final String[] PREFIJOS_PROTEGIDOS = {
            "/api/", "/socket.io", "/admin-db", "/secret-admin/", "/admin-fix-status/", "/check-ip"
    };

    private static final String RUTA_WEBHOOK_WOMPI = "/api/wompi/webhook";
    private static final String PREFIJO_MEDIA = "/api/media/";

    private static final int LARGO_MAX_IP = 64;
    private static final int LARGO_MAX_XFF = 1024;
    private static final int MAX_ENTRADAS_XFF = 20;
    private static final int MAX_SALTOS_PROXY = 10;
    private static final Pattern FORMATO_IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    private static final Pattern CARACTERES_IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    public static void register(Javalin app, AppConfig config, RateLimiter limiter) {
        final int limiteGeneral = config.getRateLimitGeneralPorMin();
        final int limiteFinanciero = config.getRateLimitFinancieroPorMin();
        final int limiteAuth = config.getRateLimitAuthPorMin();
        final int limiteMedia = config.getRateLimitMediaPorMin();
        final int saltosProxy = config.getRateLimitProxyHops();

        logger.info("🚦 Rate limiting activo: general {}/min, financiero {}/min, auth {}/min, media {}/min por IP (saltos de proxy: {})",
                limiteGeneral, limiteFinanciero, limiteAuth, limiteMedia, saltosProxy);
        if (saltosProxy == 0) {
            logger.warn("RATE_LIMIT_PROXY_HOPS=0: se ignora X-Forwarded-For; las solicitudes detrás del mismo proxy compartirán la IP directa hasta verificar y configurar la cadena confiable.");
        }

        app.before(ctx -> {
            String metodo = ctx.req().getMethod();
            String ruta = normalizar(ctx.path());
            Categoria categoria = clasificar(metodo, ruta);
            if (categoria == Categoria.EXENTO) return;

            String ip = ipCliente(ctx, saltosProxy);

            if (categoria == Categoria.MEDIA) {
                RateLimiter.Resultado media = limiter.intentar("media|" + ip, limiteMedia);
                if (!media.isPermitido()) {
                    rechazar(ctx, config, media, categoria, ip, metodo, ruta);
                    return;
                }
                encabezados(ctx, media);
                return;
            }

            // Todo lo demás cuenta en el cubo general (incluidas las rutas financieras y de auth)
            RateLimiter.Resultado general = limiter.intentar("general|" + ip, limiteGeneral);
            if (!general.isPermitido()) {
                rechazar(ctx, config, general, Categoria.GENERAL, ip, metodo, ruta);
                return;
            }

            if (categoria == Categoria.FINANCIERO) {
                RateLimiter.Resultado financiero = limiter.intentar("financiero|" + ip, limiteFinanciero);
                if (!financiero.isPermitido()) {
                    rechazar(ctx, config, financiero, Categoria.FINANCIERO, ip, metodo, ruta);
                    return;
                }
                encabezados(ctx, financiero);
                return;
            }

            if (categoria == Categoria.AUTH) {
                RateLimiter.Resultado auth = limiter.intentar("auth|" + ip, limiteAuth);
                if (!auth.isPermitido()) {
                    rechazar(ctx, config, auth, Categoria.AUTH, ip, metodo, ruta);
                    return;
                }
                encabezados(ctx, auth);
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
        if (RUTAS_AUTH.contains(ruta)
                || ruta.startsWith("/api/check-username/")
                || ruta.startsWith("/api/check-email/")
                || ruta.startsWith("/api/verify-tag/")) return Categoria.AUTH;
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
     * IP que se usará para el rate limit. Solo confía en X-Forwarded-For cuando se configuró el número
     * exacto de entradas confiables desde la derecha. Si faltan entradas o la dirección seleccionada no
     * es una IP literal válida, usa la dirección de conexión y nunca la primera entrada por defecto.
     */
    static String ipCliente(Context ctx, int saltosProxy) {
        return ipCliente(ctx.ip(), ctx.header("X-Forwarded-For"), saltosProxy);
    }

    /** Lógica pura para poder verificar las cadenas de proxy sin depender de un Context HTTP. */
    static String ipCliente(String ipConexion, String xff, int saltosProxy) {
        String directa = normalizarIpLiteral(ipConexion);
        if (directa == null) directa = "unknown";
        if (saltosProxy <= 0 || saltosProxy > MAX_SALTOS_PROXY
                || xff == null || xff.isBlank() || xff.length() > LARGO_MAX_XFF) {
            return directa;
        }

        String[] partes = xff.split(",", -1);
        if (partes.length < saltosProxy || partes.length > MAX_ENTRADAS_XFF) return directa;

        int indice = partes.length - saltosProxy;
        String candidata = null;
        for (int i = indice; i < partes.length; i++) {
            String ip = normalizarIpLiteral(partes[i]);
            if (ip == null) return directa;
            if (i == indice) candidata = ip;
        }
        return candidata == null ? directa : candidata;
    }

    /** Solo acepta literales IPv4/IPv6; evita nombres DNS, puertos y formatos ambiguos en XFF. */
    private static String normalizarIpLiteral(String valor) {
        if (valor == null) return null;
        String ip = valor.trim();
        if (ip.isEmpty() || ip.length() > LARGO_MAX_IP) return null;

        if (FORMATO_IPV4.matcher(ip).matches()) {
            String[] octetos = ip.split("\\.");
            for (String octeto : octetos) {
                // Evita representaciones con ceros iniciales, cuyo significado varía entre parsers.
                if (octeto.length() > 1 && octeto.charAt(0) == '0') return null;
                try {
                    if (Integer.parseInt(octeto) > 255) return null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return ip;
        }

        if (ip.indexOf(':') < 0 || !CARACTERES_IPV6.matcher(ip).matches()) return null;
        try {
            InetAddress direccion = InetAddress.getByName(ip);
            return direccion instanceof Inet6Address ? direccion.getHostAddress().toLowerCase(Locale.ROOT) : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static void encabezados(Context ctx, RateLimiter.Resultado r) {
        ctx.header("X-RateLimit-Limit", String.valueOf(r.getLimite()));
        ctx.header("X-RateLimit-Remaining", String.valueOf(r.getRestantes()));
    }

    /**
     * Cabeceras CORS para la respuesta 429. Este middleware se registra antes de que Javalin instale el
     * plugin CORS y, al rechazar, se saltan los handlers restantes: la 429 podía salir sin cabeceras CORS
     * y el navegador la bloqueaba en la app React/Capacitor (otro origen), mostrando "Error de conexión"
     * en vez del mensaje. Replica la regla de {@code Main} (anyHost + credenciales: se refleja el Origin)
     * y expone {@code Retry-After} para que el frontend pueda leerlo.
     */
    private static void cabecerasCors(Context ctx, AppConfig config) {
        String origen = ctx.header("Origin");
        if (origen != null && !origen.isBlank() && (config == null || config.esOrigenPermitido(origen))) {
            ctx.header("Access-Control-Allow-Origin", origen);
            ctx.header("Access-Control-Allow-Credentials", "true");
            ctx.header("Vary", "Origin");
        }
        ctx.header("Access-Control-Expose-Headers", "Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining");
    }

    private static void rechazar(Context ctx, AppConfig config, RateLimiter.Resultado r, Categoria categoria,
                                 String ip, String metodo, String ruta) {
        if (r.isPrimerBloqueo()) {
            logger.warn("🚦 Rate limit ({}): IP {} superó {}/min en {} {}",
                    categoria, ip, r.getLimite(), metodo, ruta);
        }
        long segundos = r.getReintentarEnSegundos();
        JsonObject cuerpo = errorJson("Demasiadas solicitudes. Intenta de nuevo en " + segundos + " segundos.");
        cuerpo.addProperty("retryAfter", segundos);

        ctx.status(429);
        cabecerasCors(ctx, config);
        ctx.header("Retry-After", String.valueOf(segundos));
        ctx.header("X-RateLimit-Limit", String.valueOf(r.getLimite()));
        ctx.header("X-RateLimit-Remaining", "0");
        ctx.json(cuerpo);
        ctx.skipRemainingHandlers();
    }
}
