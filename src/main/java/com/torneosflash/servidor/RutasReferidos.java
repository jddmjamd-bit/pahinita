package com.torneosflash.servidor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.servicio.ReferidosService;
import com.torneosflash.servicio.ServicioException;
import io.javalin.Javalin;

import java.math.BigDecimal;

import static com.torneosflash.servidor.RutasAuth.parseBody;

/**
 * Rutas HTTP del sistema de referidos (Prioridad 6.5).
 *
 * <pre>
 * Público:
 *   GET  /api/referidos/validar/{codigo}       verifica un código en registro y muestra si comparte comisión
 * Autenticado:
 *   GET  /api/referidos/mio                    resumen, código, mensaje corto, referidos e historial
 *   POST /api/referidos/configurar             {porcentajeCompartido, mensajeCorto, aplicarATodos}
 *   POST /api/referidos/referido-porcentaje    {referredUserId, porcentajeCompartido}
 * </pre>
 */
public class RutasReferidos {

    public static void register(Javalin app, ReferidosService referidos) {

        app.get("/api/referidos/validar/{codigo}", ctx ->
                ctx.json(referidos.validarCodigoPublico(ctx.pathParam("codigo"))));

        app.get("/api/referidos/mio", ctx ->
                ctx.json(referidos.obtenerResumenUsuario(Peticion.userIdDeSesion(ctx))));

        app.post("/api/referidos/configurar", ctx -> {
            JsonObject body = parseBody(ctx);
            BigDecimal porcentaje = leerDecimal(body, "porcentajeCompartido");
            String mensajeCorto = Peticion.textoOpcional(body, "mensajeCorto", "");
            boolean aplicarATodos = Peticion.booleanoOpcional(body, "aplicarATodos", false);
            ctx.json(referidos.actualizarConfiguracion(
                    Peticion.userIdDeSesion(ctx), porcentaje, mensajeCorto, aplicarATodos));
        });

        app.post("/api/referidos/referido-porcentaje", ctx -> {
            JsonObject body = parseBody(ctx);
            int referredUserId = Peticion.entero(body, "referredUserId");
            BigDecimal porcentaje = leerDecimal(body, "porcentajeCompartido");
            ctx.json(referidos.actualizarPorcentajeReferidoIndividual(
                    Peticion.userIdDeSesion(ctx), referredUserId, porcentaje));
        });
    }

    private static BigDecimal leerDecimal(JsonObject body, String campo) {
        if (body == null || !body.has(campo) || body.get(campo).isJsonNull() || !body.get(campo).isJsonPrimitive()) {
            throw ServicioException.solicitudInvalida("Falta el campo '" + campo + "'");
        }
        JsonElement elem = body.get(campo);
        try {
            String texto = elem.getAsString().trim().replace(',', '.');
            if (texto.isEmpty()) {
                throw ServicioException.solicitudInvalida("El campo '" + campo + "' no puede estar vacío");
            }
            return new BigDecimal(texto);
        } catch (ServicioException e) {
            throw e;
        } catch (Exception e) {
            throw ServicioException.solicitudInvalida("El campo '" + campo + "' debe ser un número válido");
        }
    }
}

