package com.torneosflash.servidor;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive; // ← NUEVO
import io.javalin.http.Context;
import com.torneosflash.servicio.ServicioException;

/**
 * Helpers para leer el request en las rutas (A3). Es lo único que una ruta
 * debería hacer con los datos:
 * extraerlos y pasárselos al servicio. Un campo ausente o con tipo equivocado
 * responde 400
 * (antes terminaba en NullPointerException / 500).
 */
final class Peticion {

    private Peticion() {
    }

    /** Campo entero obligatorio del body JSON. */
    static int entero(JsonObject body, String campo) {
        if (body == null || !body.has(campo) || body.get(campo).isJsonNull()) {
            throw ServicioException.solicitudInvalida("Falta el campo '" + campo + "'");
        }
        try {
            return body.get(campo).getAsInt();
        } catch (Exception e) {
            throw ServicioException.solicitudInvalida("El campo '" + campo + "' no es válido");
        }
    }

    /** Campo de texto obligatorio del body JSON. */
    static String texto(JsonObject body, String campo) {
        if (body == null || !body.has(campo) || body.get(campo).isJsonNull() || !body.get(campo).isJsonPrimitive()) {
            throw ServicioException.solicitudInvalida("Falta el campo '" + campo + "'");
        }
        return body.get(campo).getAsString();
    }

    /**
     * Campo de texto opcional; si falta (o es null) devuelve {@code porDefecto}.
     */
    static String textoOpcional(JsonObject body, String campo, String porDefecto) {
        if (body == null || !body.has(campo) || body.get(campo).isJsonNull() || !body.get(campo).isJsonPrimitive()) {
            return porDefecto;
        }
        return body.get(campo).getAsString();
    }

    // ↓↓↓ NUEVO ↓↓↓
    /**
     * Campo booleano opcional (roadmap 1.6). Acepta el booleano JSON y también los
     * textos "true" / "on"
     * (lo que envía un checkbox HTML dentro de un formulario). Cualquier otro valor
     * cuenta como false.
     */
    static boolean booleanoOpcional(JsonObject body, String campo, boolean porDefecto) {
        if (body == null || !body.has(campo) || body.get(campo).isJsonNull() || !body.get(campo).isJsonPrimitive()) {
            return porDefecto;
        }
        JsonPrimitive valor = body.get(campo).getAsJsonPrimitive();
        if (valor.isBoolean())
            return valor.getAsBoolean();
        String texto = valor.getAsString().trim();
        return texto.equalsIgnoreCase("true") || texto.equalsIgnoreCase("on");
    }

    /** Parámetro de ruta entero (ej. {@code /api/admin/raffle/{id}}). */
    static int pathEntero(Context ctx, String nombre) {
        try {
            return Integer.parseInt(ctx.pathParam(nombre));
        } catch (NumberFormatException e) {
            throw ServicioException.solicitudInvalida("El parámetro '" + nombre + "' no es válido");
        }
    }

    /**
     * Id de usuario de la sesión JWT (S1), o 0 si no hay sesión válida.
     * Lo inyecta {@link AuthMiddleware} tras verificar firma y expiración; la
     * cookie antigua {@code userId}
     * en texto plano ya no se lee (cualquiera podía escribir ahí el id de otro
     * usuario).
     */
    static int userIdDeSesion(Context ctx) {
        Integer userId = AuthMiddleware.getUserId(ctx);
        return userId != null ? userId : 0;
    }
}
