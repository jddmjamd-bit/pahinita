package com.torneosflash.servidor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.*;
import com.torneosflash.servicio.FinanzasService;
import io.javalin.Javalin;

import static com.torneosflash.servidor.RutasAuth.*;

/**
 * Rutas de finanzas: depósitos, retiros, Wompi webhooks.
 *
 * A3: solo parsean el request y llaman a {@link FinanzasService}. La validación de montos, los
 * movimientos de dinero, las notificaciones y la firma de Wompi viven en el servicio.
 */
public class RutasFinanzas {
    private static final Logger logger = LoggerFactory.getLogger(RutasFinanzas.class);

    public static void register(Javalin app, FinanzasService finanzas) {

        // POST /api/deposit (depósito manual por admin)
        app.post("/api/deposit", ctx -> {
            JsonObject body = parseBody(ctx);
            finanzas.depositoManual(Peticion.entero(body, "userId"), body.get("monto"));
            ctx.json(successJson("Depósito realizado", 0));
        });

        // POST /api/transaction/create (crear solicitud de recarga)
        app.post("/api/transaction/create", ctx -> {
            JsonObject body = parseBody(ctx);
            finanzas.crearSolicitudRecarga(
                    Peticion.entero(body, "userId"),
                    Peticion.texto(body, "username"),
                    Peticion.texto(body, "metodo"),
                    Peticion.textoOpcional(body, "referencia", ""),
                    body.get("monto"));
            ctx.json(successJson("Solicitud creada", 0));
        });

        // POST /api/transaction/withdraw (solicitar retiro)
        app.post("/api/transaction/withdraw", ctx -> {
            JsonObject body = parseBody(ctx);
            double nuevoSaldo = finanzas.solicitarRetiro(
                    Peticion.entero(body, "userId"),
                    Peticion.texto(body, "username"),
                    Peticion.textoOpcional(body, "metodo", "nequi_retiro"),
                    Peticion.textoOpcional(body, "referencia", ""),
                    body.get("monto"));

            // newBalance en la respuesta para que el frontend actualice el saldo de inmediato
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Retiro solicitado");
            response.addProperty("newBalance", nuevoSaldo);
            ctx.json(response);
        });

        // POST /api/wompi/init (iniciar pago Wompi)
        app.post("/api/wompi/init", ctx -> {
            JsonObject body = parseBody(ctx);
            ctx.json(finanzas.iniciarWompi(
                    Peticion.entero(body, "userId"),
                    Peticion.texto(body, "username"),
                    body.get("monto")));
        });

        // POST /api/wompi/webhook (lo llama Wompi: respuesta en texto plano)
        app.post("/api/wompi/webhook", ctx -> {
            try {
                finanzas.procesarWebhookWompi(parseBody(ctx));
                ctx.result("OK");
            } catch (Exception e) {
                logger.error("Error webhook Wompi: " + e.getMessage());
                ctx.status(500).result("Error");
            }
        });
    }
}
