package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbAdminServiceTest {

    private static JsonObject fila(int id, String username, String modo, String monto, String fecha) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (username == null) o.add("username", JsonNull.INSTANCE);
        else o.addProperty("username", username);
        if (modo == null) o.add("modo", JsonNull.INSTANCE);
        else o.addProperty("modo", modo);
        if (monto == null) o.add("monto", JsonNull.INSTANCE);
        else o.addProperty("monto", monto);
        if (fecha == null) o.add("fecha", JsonNull.INSTANCE);
        else o.addProperty("fecha", fecha);
        return o;
    }

    @Test
    void filtraPorPalabrasExactasSinTildesYPalabrasParecidas() {
        JsonArray filas = new JsonArray();
        filas.add(fila(1, "Valquiria_Pro", "Elección", "5000", "2026-11-10T10:00:00Z"));
        filas.add(fila(2, "Montapuercos", "Triple Elección", "10000", "2026-11-12T15:30:00Z"));
        filas.add(fila(3, "ArqueroMagico", "Mega Draft", "2000", "2026-11-08T08:00:00Z"));
        filas.add(fila(4, null, "Clásico", null, null));

        List<String> cols = List.of("id", "username", "modo", "monto", "fecha");
        Map<String, String> tipos = Map.of(
                "id", "integer",
                "username", "text",
                "modo", "text",
                "monto", "numeric",
                "fecha", "timestamp without time zone"
        );

        // Coincidencia aproximada con error tipográfico + otra palabra en otra columna
        JsonArray parecidos = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, "valkiria elecion", "*", true, null, "asc", "auto");
        assertEquals(1, parecidos.size());
        assertEquals(1, parecidos.get(0).getAsJsonObject().get("id").getAsInt());

        // Con aproximada=false una palabra mal escrita no debe coincidir
        JsonArray estricta = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, "valkiria", "*", false, null, "asc", "auto");
        assertEquals(0, estricta.size());

        // Filtro por columna específica
        JsonArray soloColumnaModo = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, "draft", "modo", true, null, "asc", "auto");
        assertEquals(1, soloColumnaModo.size());
        assertEquals(3, soloColumnaModo.get(0).getAsJsonObject().get("id").getAsInt());
    }

    @Test
    void ordenaAlfabeticamenteNumericamenteYPorFechasDejandoNulosAlFinal() {
        JsonArray filas = new JsonArray();
        filas.add(fila(1, "Carlos", "Elección", "10000", "2026-11-10T10:00:00Z"));
        filas.add(fila(2, "Ángela", "Clásico", "2000", "2026-11-12T15:30:00Z"));
        filas.add(fila(3, "beto", "Mega Draft", "5000", "2026-11-08T08:00:00Z"));
        filas.add(fila(4, null, null, null, null));

        List<String> cols = List.of("id", "username", "modo", "monto", "fecha");
        Map<String, String> tipos = Map.of(
                "id", "integer",
                "username", "text",
                "modo", "text",
                "monto", "numeric",
                "fecha", "timestamp without time zone"
        );

        // Alfabético ascendente (A -> Z, insensible a tildes y mayúsculas, null al final)
        JsonArray alfaAsc = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, null, "*", true, "username", "asc", "texto");
        assertEquals(2, alfaAsc.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(3, alfaAsc.get(1).getAsJsonObject().get("id").getAsInt());
        assertEquals(1, alfaAsc.get(2).getAsJsonObject().get("id").getAsInt());
        assertEquals(4, alfaAsc.get(3).getAsJsonObject().get("id").getAsInt());

        // Alfabético descendente (Z -> A, null también al final)
        JsonArray alfaDesc = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, null, "*", true, "username", "desc", "texto");
        assertEquals(1, alfaDesc.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(3, alfaDesc.get(1).getAsJsonObject().get("id").getAsInt());
        assertEquals(2, alfaDesc.get(2).getAsJsonObject().get("id").getAsInt());
        assertEquals(4, alfaDesc.get(3).getAsJsonObject().get("id").getAsInt());

        // Numérico ascendente (2000 < 5000 < 10000, no orden lexicográfico de string)
        JsonArray numAsc = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, null, "*", true, "monto", "asc", "numero");
        assertEquals(2, numAsc.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(3, numAsc.get(1).getAsJsonObject().get("id").getAsInt());
        assertEquals(1, numAsc.get(2).getAsJsonObject().get("id").getAsInt());
        assertEquals(4, numAsc.get(3).getAsJsonObject().get("id").getAsInt());

        // Fecha descendente (más reciente primero, null al final)
        JsonArray fechaDesc = DbAdminService.filtrarYOrdenarFilas(
                filas, cols, tipos, null, "*", true, "fecha", "desc", "fecha");
        assertEquals(2, fechaDesc.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(1, fechaDesc.get(1).getAsJsonObject().get("id").getAsInt());
        assertEquals(3, fechaDesc.get(2).getAsJsonObject().get("id").getAsInt());
        assertEquals(4, fechaDesc.get(3).getAsJsonObject().get("id").getAsInt());
    }
}
