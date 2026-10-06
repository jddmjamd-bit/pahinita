package com.torneosflash.servidor;

import com.torneosflash.config.AppConfig;
import com.torneosflash.servicio.JwtServicio;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Middleware de autenticación.
 * Lee la cookie httpOnly de sesión, verifica el JWT y, si es válido,
 * inyecta {@code ctx.attribute("userId")} y {@code ctx.attribute("rol")}.
 *
 * No rechaza requests: cada ruta decide si exige sesión
 * (usar {@link #getUserId(Context)} y responder 401 si es null).
 */
public class AuthMiddleware {

    public static final String COOKIE_SESION = "session";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_ROL = "rol";

    /** Cookie antigua en texto plano. Solo se usa para borrarla. */
    private static final String COOKIE_LEGACY = "userId";

    private static JwtServicio jwt;
    private static AppConfig config;

    public static void register(Javalin app, JwtServicio jwtServicio, AppConfig appConfig) {
        jwt = jwtServicio;
        config = appConfig;

        app.before(ctx -> {
            JwtServicio.Sesion sesion = jwt.verificar(leerCookie(ctx.header("Cookie"), COOKIE_SESION));
            if (sesion != null) {
                ctx.attribute(ATTR_USER_ID, sesion.userId());
                ctx.attribute(ATTR_ROL, sesion.rol());
            }
        });
    }

    /** userId de la sesión JWT verificada, o null si no hay sesión válida. */
    public static Integer getUserId(Context ctx) {
        return ctx.attribute(ATTR_USER_ID);
    }

    /** Rol de la sesión JWT verificada, o null si no hay sesión válida. */
    public static String getRol(Context ctx) {
        return ctx.attribute(ATTR_ROL);
    }

    /** Emite un JWT nuevo y lo guarda en la cookie httpOnly. Borra la cookie legacy. */
    public static void iniciarSesion(Context ctx, int userId, String rol) {
        String token = jwt.generarToken(userId, rol);
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_SESION, token, jwt.getExpiracionSegundos()));
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_LEGACY, "", 0));
    }

    /** Borra la cookie de sesión y la legacy. */
    public static void cerrarSesion(Context ctx) {
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_SESION, "", 0));
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_LEGACY, "", 0));
    }

    /**
     * Para el handshake del socket: recibe el header Cookie crudo y
     * devuelve el userId si el JWT es válido.
     */
    public static Integer userIdDesdeCookieHeader(String cookieHeader) {
        if (jwt == null) return null;
        JwtServicio.Sesion sesion = jwt.verificar(leerCookie(cookieHeader, COOKIE_SESION));
        return sesion != null ? sesion.userId() : null;
    }

    // --- Helpers ---

    /**
     * Se arma el header Set-Cookie a mano porque jakarta.servlet.http.Cookie
     * (Servlet 5 / Jetty 11) no soporta SameSite, y ctx.cookie() de Javalin 6
     * tiene un bug conocido con Java 17 en este proyecto.
     */
    private static String construirCookie(String nombre, String valor, long maxAgeSegundos) {
        StringBuilder sb = new StringBuilder();
        sb.append(nombre).append('=').append(valor)
          .append("; Path=/")
          .append("; Max-Age=").append(maxAgeSegundos)
          .append("; HttpOnly")
          .append("; SameSite=").append(config.getCookieSameSite());
        if (config.isCookieSecure()) sb.append("; Secure");
        return sb.toString();
    }

    static String leerCookie(String cookieHeader, String nombre) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        for (String parte : cookieHeader.split(";")) {
            int eq = parte.indexOf('=');
            if (eq <= 0) continue;
            if (parte.substring(0, eq).trim().equals(nombre)) {
                return parte.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
