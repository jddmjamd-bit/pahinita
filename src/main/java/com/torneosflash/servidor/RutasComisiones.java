package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.servicio.ComisionService;
import com.torneosflash.servicio.ComisionService.Categoria;
import com.torneosflash.servicio.ValidadorMonto;
import io.javalin.Javalin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static com.torneosflash.servidor.RutasAuth.errorJson;

/**
 * Rutas de solo lectura para que el frontend muestre las comisiones sin replicar la fórmula (D4).
 * Aquí solo se parsea el request y se llama a {@link ComisionService}; no hay lógica de negocio.
 *
 * No exponen datos de usuarios ni mueven dinero: son los mismos números que se muestran en la UI.
 */
public class RutasComisiones {

    public static void register(Javalin app, ComisionService comisiones, ValidadorMonto validador) {

        // GET /api/comisiones/simular?monto=5000
        // Cuánto recibe el ganador si cada jugador pone `monto`. El monto lo valida el servidor (D5).
        app.get("/api/comisiones/simular", ctx -> {
            String crudo = ctx.queryParam("monto");
            ValidadorMonto.Resultado montoValido = validador.torneo(crudo == null ? null : new JsonPrimitive(crudo));
            if (!montoValido.isValido()) {
                ctx.status(400).json(errorJson(montoValido.getError()));
                return;
            }

            ComisionService.Desglose d = comisiones.calcular(montoValido.getMonto()); // REVIEW-MONEY
            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.addProperty("monto", d.getMontoPorJugador());
            res.addProperty("pozo", d.getPozo());
            res.addProperty("porcentajeComision", d.getPorcentaje().setScale(4, RoundingMode.HALF_UP));
            res.addProperty("comision", d.getComisionTotal());
            res.addProperty("premio", d.getPremio());
            ctx.json(res);
        });

        // GET /api/comisiones/distribucion
        // Porcentaje (de la comisión) que recibe cada categoría: {"sorteos": 20, "misiones": 10, ...}
        app.get("/api/comisiones/distribucion", ctx -> {
            JsonObject distribucion = new JsonObject();
            JsonObject activas = new JsonObject();
            for (Map.Entry<Categoria, BigDecimal> e : comisiones.distribucion().entrySet()) {
                distribucion.addProperty(e.getKey().getClave(), aPorcentaje(e.getValue()));
                activas.addProperty(e.getKey().getClave(), e.getKey().isActiva());
            }
            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.add("distribucion", distribucion);
            res.add("activas", activas);
            res.addProperty("porcentajeInactivasAdmin", aPorcentaje(comisiones.fraccionInactivas()));
            res.addProperty("porcentajeGananciaEfectivaAdmin", aPorcentaje(comisiones.fraccionGananciaEfectivaAdmin()));
            ctx.json(res);
        });
    }

    private static BigDecimal aPorcentaje(BigDecimal fraccion) {
        BigDecimal porcentaje = fraccion.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
        if (porcentaje.scale() < 0) {
            porcentaje = porcentaje.setScale(0);
        }
        return porcentaje;
    }
}
