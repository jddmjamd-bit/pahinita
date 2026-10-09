package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * S6 — Qué columnas de {@code users} pueden salir hacia el cliente.
 *
 * Regla del proyecto: nunca {@code SELECT *} sobre {@code users} para datos que viajan
 * al frontend. Se hace SELECT de columnas explícitas (lista blanca), así una columna nueva
 * sensible (hash, tokens, etc.) NO se filtra por defecto.
 *
 * Hay dos vistas:
 *  - SESIÓN: lo que ve el propio usuario (login, /api/session, userData del socket).
 *    Nunca incluye {@code password}.
 *  - RIVAL: lo que ve el otro jugador. Solo identidad y estadísticas de reputación; nada de
 *    password, email, teléfono, player_tag, saldo ni estado interno.
 */
public final class UsuarioVista {

    private UsuarioVista() {}

    /** Columnas del propio usuario (sin {@code password}). */
    public static final List<String> CAMPOS_SESION = List.of(
            "id", "username", "email", "player_tag", "telefono", "saldo", "tipo_suscripcion",
            "estado", "sala_actual", "paso_juego", "ganancia_generada", "faltas",
            "total_victorias", "victorias_normales", "victorias_disputa",
            "total_derrotas", "derrotas_normales", "derrotas_disputa",
            "total_partidas", "salidas_chat", "salidas_desconexion", "salidas_x", "salidas_canal",
            "total_monto_torneos", "total_ganado");

    /** Columnas que puede ver el rival en una partida. */
    public static final List<String> CAMPOS_RIVAL = List.of(
            "id", "username", "total_partidas", "total_victorias", "total_derrotas",
            "faltas", "salidas_chat");

    /** Para usar en SQL: {@code "SELECT " + UsuarioVista.COLUMNAS_SESION + " FROM users ..."}. */
    public static final String COLUMNAS_SESION = String.join(", ", CAMPOS_SESION);

    /**
     * Copia solo los campos permitidos para el rival. Se usa justo antes de emitir por socket,
     * incluso si el objeto de origen trae más columnas (defensa en profundidad).
     */
    public static JsonObject paraRival(JsonObject origen) {
        JsonObject salida = new JsonObject();
        if (origen == null) return salida;
        for (String campo : CAMPOS_RIVAL) {
            JsonElement valor = origen.get(campo);
            if (valor != null) salida.add(campo, valor);
        }
        return salida;
    }
}
