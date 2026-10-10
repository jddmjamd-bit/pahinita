package com.torneosflash.servidor;

import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.torneosflash.servidor.RutasAuth.errorJson;

/**
 * Handler único para los errores de negocio de la capa de servicio (A3).
 * Convierte {@link ServicioException} en {@code {"error": "..."}} con el código HTTP que trae la excepción,
 * así ninguna ruta tiene que armar sus respuestas de error a mano.
 */
public final class ManejadorErrores {
    private static final Logger logger = LoggerFactory.getLogger(ManejadorErrores.class);

    private ManejadorErrores() {}

    public static void registrar(Javalin app) {
        app.exception(ServicioException.class, (e, ctx) -> {
            if (e.getStatus() >= 500) {
                logger.error("Error interno en {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
            }
            ctx.status(e.getStatus()).json(errorJson(e.getMessage()));
        });
    }
}
