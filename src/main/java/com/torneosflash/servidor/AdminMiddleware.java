package com.torneosflash.servidor;

import com.torneosflash.servicio.AdminService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware de autorización de administrador (S3).
 *
 * Corre ANTES de cada ruta que solo debe usar un admin y corta la petición si quien la hace no lo es:
 * <ul>
 *   <li><b>401</b> si no hay sesión JWT válida ({@link AuthMiddleware} no inyectó {@code userId}).</li>
 *   <li><b>403</b> si hay sesión pero el usuario no es admin.</li>
 * </ul>
 * La respuesta es el {@code {"error": "..."}} de siempre (lo arma {@link ManejadorErrores}).
 *
 * <h3>Por qué el rol se consulta en la BD y no se confía en el claim {@code rol} del JWT</h3>
 * El claim se escribe al iniciar sesión y puede quedar viejo hasta 30 días (ver sección 16 de ARCHITECTURE.md):
 * un admin al que se le quitó el rol seguiría pasando con su token. El JWT solo sirve para saber QUIÉN es el
 * usuario; si es admin lo dice {@code users.tipo_suscripcion} en el momento de la petición (misma regla que
 * {@code ChatbotService.exigirAdmin}). Si la BD falla, el usuario se trata como no-admin (se cierra, no se abre).
 *
 * Debe registrarse DESPUÉS de {@link AuthMiddleware#register} (los {@code before} corren en orden de registro)
 * y antes de registrar las rutas.
 */
public final class AdminMiddleware {
    private static final Logger logger = LoggerFactory.getLogger(AdminMiddleware.class);

    /**
     * Rutas que exige admin. {@code /api/admin/*} cubre todo lo que cuelga de ese prefijo (incluidas las rutas
     * de admin del chatbot y de sorteos). Las otras dos NO llevan el prefijo pero son acciones de admin:
     * acreditar saldo a mano y resetear el estado de un jugador.
     */
    private static final String[] RUTAS_ADMIN = {
            "/api/admin/*",
            "/api/deposit",
            "/admin-fix-status/*"
    };

    private AdminMiddleware() {
    }

    public static void register(Javalin app, AdminService admin) {
        for (String patron : RUTAS_ADMIN) {
            app.before(patron, ctx -> exigirAdmin(ctx, admin));
        }
    }

    private static void exigirAdmin(Context ctx, AdminService admin) {
        // El preflight CORS no lleva cookies: no se puede autenticar y no ejecuta ninguna ruta
        if ("OPTIONS".equalsIgnoreCase(ctx.req().getMethod())) return;

        Integer userId = AuthMiddleware.getUserId(ctx);
        if (userId == null) {
            throw ServicioException.noAutenticado("Inicia sesión para continuar");
        }
        if (!admin.esAdmin(userId)) {
            logger.warn("⛔ Acceso admin denegado: usuario {} intentó {} {}", userId, ctx.method(), ctx.path());
            throw ServicioException.prohibido("Solo los administradores pueden hacer esto");
        }
    }
}
