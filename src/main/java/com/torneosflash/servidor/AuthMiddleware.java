package com.torneosflash.servidor;

import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.servicio.JwtServicio;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * Middleware de autenticación.
 * Lee la cookie httpOnly, verifica el JWT y el JTI persistido y, si la sesión sigue activa,
 * inyecta {@code ctx.attribute("userId")} y {@code ctx.attribute("rol")}.
 */
public class AuthMiddleware {
    private static final Logger logger = LoggerFactory.getLogger(AuthMiddleware.class);

    public static final String COOKIE_SESION = "session";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_ROL = "rol";
    private static final String ATTR_JTI = "sessionJti";

    private static final String COOKIE_LEGACY = "userId";

    private static JwtServicio jwt;
    private static AppConfig config;
    private static UsuarioDAO usuarioDAO;
    private static volatile Consumer<String> alRevocarSesion = jti -> {};

    public static void register(Javalin app, JwtServicio jwtServicio, AppConfig appConfig, UsuarioDAO usuarios) {
        jwt = jwtServicio;
        config = appConfig;
        usuarioDAO = usuarios;

        app.before(ctx -> {
            if (!ctx.path().startsWith("/api/")) return;
            String token = leerCookie(ctx.header("Cookie"), COOKIE_SESION);
            if (token == null) return;

            JwtServicio.Sesion sesion = jwt.verificar(token);
            if (sesion == null) {
                cerrarSesion(ctx);
                return;
            }
            try {
                if (!usuarioDAO.sesionActiva(sesion.jti(), sesion.userId())) {
                    cerrarSesion(ctx);
                    return;
                }
            } catch (SQLException e) {
                logger.error("No se pudo validar la sesión JWT", e);
                throw ServicioException.noDisponible("No se pudo validar la sesión en este momento");
            }
            ctx.attribute(ATTR_USER_ID, sesion.userId());
            ctx.attribute(ATTR_ROL, sesion.rol());
            ctx.attribute(ATTR_JTI, sesion.jti());
        });
    }

    /** Conecta la revocación HTTP con los sockets que siguen abiertos en esa sesión. */
    public static void setSessionRevocationHandler(Consumer<String> handler) {
        alRevocarSesion = handler != null ? handler : jti -> {};
    }

    public static Integer getUserId(Context ctx) {
        return ctx.attribute(ATTR_USER_ID);
    }

    public static String getRol(Context ctx) {
        return ctx.attribute(ATTR_ROL);
    }

    public static String getJti(Context ctx) {
        return ctx.attribute(ATTR_JTI);
    }

    public static void iniciarSesion(Context ctx, int userId, String rol) {
        JwtServicio.TokenEmitido token = jwt.generarToken(userId, rol);
        try {
            usuarioDAO.crearSesion(token.jti(), userId, token.expiraEn());
        } catch (SQLException e) {
            logger.error("No se pudo persistir la sesión JWT", e);
            throw ServicioException.noDisponible("No se pudo iniciar la sesión en este momento");
        }
        emitirCookieSesion(ctx, token);
    }

    /** Renueva la expiración de una sesión activa conservando su JTI. */
    public static void renovarSesion(Context ctx, int userId, String rol) {
        String jti = getJti(ctx);
        if (jti == null || jti.isBlank()) {
            cerrarSesion(ctx);
            throw ServicioException.noAutenticado("La sesión ya no está activa");
        }
        JwtServicio.TokenEmitido token = jwt.generarToken(userId, rol, jti);
        try {
            if (!usuarioDAO.renovarSesion(jti, userId, token.expiraEn())) {
                cerrarSesion(ctx);
                throw ServicioException.noAutenticado("La sesión ya no está activa");
            }
        } catch (SQLException e) {
            logger.error("No se pudo renovar la sesión JWT", e);
            throw ServicioException.noDisponible("No se pudo renovar la sesión en este momento");
        }
        emitirCookieSesion(ctx, token);
    }

    /** Revoca la sesión autenticada en el servidor antes de completar el logout. */
    public static void revocarSesion(Context ctx) {
        Integer userId = getUserId(ctx);
        String jti = getJti(ctx);
        if (userId == null || userId <= 0 || jti == null || jti.isBlank()) return;
        try {
            if (!usuarioDAO.revocarSesion(jti, userId)) {
                throw ServicioException.noAutenticado("La sesión ya no está activa");
            }
            try {
                alRevocarSesion.accept(jti);
            } catch (RuntimeException e) {
                // La fila ya quedó revocada; un fallo al cerrar un socket no debe cancelar el logout.
                logger.warn("La sesión {} fue revocada, pero no se pudieron cerrar todos sus sockets", jti, e);
            }
        } catch (SQLException e) {
            logger.error("No se pudo revocar la sesión JWT", e);
            throw ServicioException.noDisponible("No se pudo cerrar la sesión en este momento");
        }
    }

    private static void emitirCookieSesion(Context ctx, JwtServicio.TokenEmitido token) {
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_SESION, token.valor(), jwt.getExpiracionSegundos()));
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_LEGACY, "", 0));
    }

    public static void cerrarSesion(Context ctx) {
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_SESION, "", 0));
        ctx.res().addHeader("Set-Cookie", construirCookie(COOKIE_LEGACY, "", 0));
    }

    public static Integer userIdDesdeCookieHeader(String cookieHeader) {
        JwtServicio.Sesion sesion = sesionDesdeCookieHeader(cookieHeader);
        return sesion != null ? sesion.userId() : null;
    }

    /** Verifica también el estado persistido de la sesión, no solo la firma del token. */
    public static JwtServicio.Sesion sesionDesdeCookieHeader(String cookieHeader) {
        if (jwt == null) return null;
        JwtServicio.Sesion sesion = jwt.verificar(leerCookie(cookieHeader, COOKIE_SESION));
        if (sesion == null || usuarioDAO == null) return null;
        try {
            return usuarioDAO.sesionActiva(sesion.jti(), sesion.userId()) ? sesion : null;
        } catch (SQLException e) {
            logger.warn("No se pudo validar la sesión JWT durante el handshake de Socket.IO", e);
            return null;
        }
    }

    /** Comprueba una sesión ya asociada a un socket (también cubre carreras con el handshake). */
    public static boolean sesionVigente(int userId, String jti) {
        if (userId <= 0 || jti == null || jti.isBlank() || usuarioDAO == null) return false;
        try {
            return usuarioDAO.sesionActiva(jti, userId);
        } catch (SQLException e) {
            logger.warn("No se pudo revalidar la sesión JWT del socket", e);
            return false;
        }
    }

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
