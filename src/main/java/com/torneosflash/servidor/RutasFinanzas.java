package com.torneosflash.servidor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.*;
import com.torneosflash.servicio.FinanzasService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de finanzas: depósitos, retiros, Wompi webhooks.
 *
 * A3: solo parsean el request y llaman a {@link FinanzasService}. La validación de montos, los
 * movimientos de dinero, las notificaciones y la firma de Wompi viven en el servicio.
 * S2: el userId y el username salen siempre de la sesión JWT (nunca del body).
 */
public class RutasFinanzas {
    private static final Logger logger = LoggerFactory.getLogger(RutasFinanzas.class);

    public static void register(Javalin app, FinanzasService finanzas) {

        // POST /api/deposit (depósito manual por admin, protegido por AdminMiddleware)
        app.post("/api/deposit", ctx -> {
            JsonObject body = parseBody(ctx);
            finanzas.depositoManual(Peticion.entero(body, "userId"), body.get("monto"));
            ctx.json(successJson("Depósito realizado", 0));
        });

        // POST /api/transaction/create (crear solicitud de recarga) — S2: userId y username de sesión
        app.post("/api/transaction/create", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            finanzas.crearSolicitudRecarga(
                    userId,
                    Peticion.texto(body, "metodo"),
                    Peticion.textoOpcional(body, "referencia", ""),
                    body.get("monto"));
            ctx.json(successJson("Solicitud creada", 0));
        });

        // POST /api/transaction/withdraw (solicitar retiro) — S2: userId y username de sesión
        app.post("/api/transaction/withdraw", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            String refCuenta = Peticion.textoOpcional(body, "datosCuenta",
                    Peticion.textoOpcional(body, "referencia", ""));
            double nuevoSaldo = finanzas.solicitarRetiro(
                    userId,
                    Peticion.textoOpcional(body, "metodo", "nequi_retiro"),
                    refCuenta,
                    body.get("monto"));

            // newBalance en la respuesta para que el frontend actualice el saldo de inmediato
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Retiro solicitado");
            response.addProperty("newBalance", nuevoSaldo);
            ctx.json(response);
        });

        // POST /api/wompi/init (iniciar pago Wompi) — S2: userId y username de sesión
        app.post("/api/wompi/init", ctx -> {
            JsonObject body = parseBody(ctx);
            int userId = Peticion.userIdDeSesion(ctx);
            if (userId <= 0) throw ServicioException.noAutenticado("Inicia sesión");
            ctx.json(finanzas.iniciarWompi(userId, body.get("monto")));
        });

        // POST /api/wompi/webhook (lo llama Wompi: verifica firma SHA-256 S7; respuesta en texto plano)
        app.post("/api/wompi/webhook", ctx -> {
            try {
                finanzas.procesarWebhookWompi(parseBody(ctx), ctx.header("X-Event-Checksum"));
                ctx.status(200).result("OK");
            } catch (ServicioException e) {
                ctx.status(e.getStatus()).result(e.getMessage());
            } catch (Exception e) {
                logger.error("Error webhook Wompi: " + e.getMessage());
                ctx.status(500).result("Error");
            }
        });
    }
}
