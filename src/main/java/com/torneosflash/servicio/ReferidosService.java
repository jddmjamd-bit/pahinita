package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Gestión del sistema de referidos (Prioridad 6.5 del roadmap):
 * enlace/código propio, mensaje corto para compartir, validación pública de códigos,
 * estadísticas de referidos y configuración opcional de comisión compartida (0% a 100%).
 */
public class ReferidosService {

    public static final String MENSAJE_CORTO_DEFECTO =
            "¡Juega conmigo en UltimateClash y participa en torneos de Clash Royale con premios reales! Regístrate con mi código:";

    private static final int MAX_LONGITUD_MENSAJE = 240;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final Pattern CODIGO_VALIDO = Pattern.compile("^[A-Za-z0-9_-]{3,24}$");

    private final GenericDAO db;
    private final UsuarioDAO usuarios;

    public ReferidosService(GenericDAO db, UsuarioDAO usuarios) {
        this.db = db;
        this.usuarios = usuarios;
    }

    /**
     * Valida de forma pública si un código de referido existe y si el referidor comparte porcentaje.
     * Nunca expone correo, teléfono ni datos sensibles.
     */
    public JsonObject validarCodigoPublico(String codigoCrudo) {
        String codigo = codigoCrudo == null ? "" : codigoCrudo.trim().toUpperCase(Locale.ROOT);
        JsonObject respuesta = new JsonObject();
        respuesta.addProperty("codigo", codigo);
        if (codigo.isEmpty() || !CODIGO_VALIDO.matcher(codigo).matches()) {
            respuesta.addProperty("valido", false);
            return respuesta;
        }
        UsuarioDAO.ReferidorEncontrado encontrado = usuarios.buscarReferidorPorCodigo(codigo);
        if (encontrado == null) {
            respuesta.addProperty("valido", false);
            return respuesta;
        }
        respuesta.addProperty("valido", true);
        respuesta.addProperty("codigo", encontrado.codigoReferido());
        respuesta.addProperty("usernameReferidor", encontrado.username());
        respuesta.addProperty("porcentajeCompartido",
                normalizarPorcentaje(encontrado.porcentajeCompartido()));
        return respuesta;
    }

    /** Devuelve el resumen completo de referidos del usuario autenticado. */
    public JsonObject obtenerResumenUsuario(int userId) {
        if (userId <= 0) {
            throw new ServicioException(401, "Debes iniciar sesión");
        }
        JsonObject usuario = db.queryOne(
                "SELECT id, username, codigo_referido, " +
                "COALESCE(referido_porcentaje_compartido, 0) AS porcentaje_compartido, " +
                "COALESCE(referido_mensaje_corto, '') AS mensaje_corto " +
                "FROM users WHERE id = ?",
                userId);
        if (usuario == null) {
            throw ServicioException.noEncontrado("Usuario no encontrado");
        }

        String codigoReferido = str(usuario, "codigo_referido");
        if (codigoReferido.isBlank()) {
            codigoReferido = asignarCodigoFaltante(userId, str(usuario, "username"));
        }

        BigDecimal porcentajeGeneral = usuario.has("porcentaje_compartido") && !usuario.get("porcentaje_compartido").isJsonNull()
                ? normalizarPorcentaje(usuario.get("porcentaje_compartido").getAsBigDecimal())
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        String mensajeGuardado = str(usuario, "mensaje_corto");
        String mensajeCorto = mensajeGuardado.isBlank() ? MENSAJE_CORTO_DEFECTO : mensajeGuardado;

        List<JsonObject> filasReferidos = db.query(
                "SELECT r.id, r.referred_user_id, u.username, r.codigo_usado, r.porcentaje_compartido, " +
                "r.usa_porcentaje_personalizado, r.partidas_jugadas, r.total_comision_referido, " +
                "r.total_ganado_referidor, r.total_devuelto_referido, r.creado_en " +
                "FROM referrals r " +
                "JOIN users u ON u.id = r.referred_user_id " +
                "WHERE r.referrer_user_id = ? " +
                "ORDER BY r.creado_en DESC, r.id DESC",
                userId);

        int totalReferidos = filasReferidos.size();
        int referidosActivos = 0;
        int totalPartidas = 0;
        BigDecimal totalComisionGenerada = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalGanadoReferidor = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDevueltoReferidos = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        JsonArray listaReferidos = new JsonArray();
        for (JsonObject fila : filasReferidos) {
            int partidas = entero(fila, "partidas_jugadas");
            if (partidas > 0) referidosActivos++;
            totalPartidas += partidas;

            BigDecimal comBase = decimal(fila, "total_comision_referido");
            BigDecimal ganado = decimal(fila, "total_ganado_referidor");
            BigDecimal devuelto = decimal(fila, "total_devuelto_referido");
            totalComisionGenerada = totalComisionGenerada.add(comBase);
            totalGanadoReferidor = totalGanadoReferidor.add(ganado);
            totalDevueltoReferidos = totalDevueltoReferidos.add(devuelto);

            JsonObject item = new JsonObject();
            item.addProperty("referredUserId", entero(fila, "referred_user_id"));
            item.addProperty("username", str(fila, "username"));
            item.addProperty("codigoUsado", str(fila, "codigo_usado"));
            item.addProperty("porcentajeCompartido", normalizarPorcentaje(decimal(fila, "porcentaje_compartido")));
            item.addProperty("usaPorcentajePersonalizado",
                    fila.has("usa_porcentaje_personalizado") && fila.get("usa_porcentaje_personalizado").getAsBoolean());
            item.addProperty("partidasJugadas", partidas);
            item.addProperty("totalComisionReferido", comBase);
            item.addProperty("totalGanadoReferidor", ganado);
            item.addProperty("totalDevueltoReferido", devuelto);
            item.addProperty("creadoEn", str(fila, "creado_en"));
            listaReferidos.add(item);
        }

        JsonObject estadisticas = new JsonObject();
        estadisticas.addProperty("totalReferidos", totalReferidos);
        estadisticas.addProperty("referidosActivos", referidosActivos);
        estadisticas.addProperty("totalPartidasReferidos", totalPartidas);
        estadisticas.addProperty("totalComisionGenerada", totalComisionGenerada);
        estadisticas.addProperty("totalGanadoReferidor", totalGanadoReferidor);
        estadisticas.addProperty("totalDevueltoReferidos", totalDevueltoReferidos);

        JsonObject filaMiReferidor = db.queryOne(
                "SELECT u.username AS referrer_username, r.codigo_usado, r.porcentaje_compartido, " +
                "r.partidas_jugadas, r.total_devuelto_referido, r.creado_en " +
                "FROM referrals r " +
                "JOIN users u ON u.id = r.referrer_user_id " +
                "WHERE r.referred_user_id = ? LIMIT 1",
                userId);

        JsonObject miReferidor = null;
        if (filaMiReferidor != null) {
            miReferidor = new JsonObject();
            miReferidor.addProperty("username", str(filaMiReferidor, "referrer_username"));
            miReferidor.addProperty("codigoUsado", str(filaMiReferidor, "codigo_usado"));
            miReferidor.addProperty("porcentajeCompartido",
                    normalizarPorcentaje(decimal(filaMiReferidor, "porcentaje_compartido")));
            miReferidor.addProperty("partidasJugadas", entero(filaMiReferidor, "partidas_jugadas"));
            miReferidor.addProperty("totalRecibidoDevolucion", decimal(filaMiReferidor, "total_devuelto_referido"));
            miReferidor.addProperty("creadoEn", str(filaMiReferidor, "creado_en"));
        }

        List<JsonObject> filasPagos = db.query(
                "SELECT p.id, p.match_id, p.jugador_id, uj.username AS jugador_username, " +
                "p.referrer_user_id, ur.username AS referrer_username, " +
                "p.monto_base, p.porcentaje_compartido, p.monto_referidor, " +
                "p.monto_devuelto_referido, p.creado_en " +
                "FROM referral_payouts p " +
                "JOIN users uj ON uj.id = p.jugador_id " +
                "LEFT JOIN users ur ON ur.id = p.referrer_user_id " +
                "WHERE p.referrer_user_id = ? OR (p.jugador_id = ? AND p.monto_devuelto_referido > 0) " +
                "ORDER BY p.creado_en DESC, p.id DESC LIMIT 30",
                userId, userId);

        JsonArray historialPagos = new JsonArray();
        for (JsonObject fila : filasPagos) {
            int refId = entero(fila, "referrer_user_id");
            boolean esReferidor = refId == userId;
            BigDecimal montoReferidor = decimal(fila, "monto_referidor");
            BigDecimal montoDevuelto = decimal(fila, "monto_devuelto_referido");

            JsonObject pago = new JsonObject();
            pago.addProperty("id", fila.get("id").getAsLong());
            pago.addProperty("matchId", entero(fila, "match_id"));
            pago.addProperty("rolUsuario", esReferidor ? "referidor" : "referido");
            pago.addProperty("contraparte", esReferidor ? str(fila, "jugador_username") : str(fila, "referrer_username"));
            pago.addProperty("montoBase", decimal(fila, "monto_base"));
            pago.addProperty("porcentajeCompartido", normalizarPorcentaje(decimal(fila, "porcentaje_compartido")));
            pago.addProperty("montoReferidor", montoReferidor);
            pago.addProperty("montoDevuelto", montoDevuelto);
            pago.addProperty("montoParaUsuario", esReferidor ? montoReferidor : montoDevuelto);
            pago.addProperty("creadoEn", str(fila, "creado_en"));
            historialPagos.add(pago);
        }

        JsonObject respuesta = new JsonObject();
        respuesta.addProperty("success", true);
        respuesta.addProperty("codigoReferido", codigoReferido);
        respuesta.addProperty("porcentajeCompartidoGeneral", porcentajeGeneral);
        respuesta.addProperty("mensajeCorto", mensajeCorto);
        respuesta.addProperty("mensajeCortoDefecto", MENSAJE_CORTO_DEFECTO);
        respuesta.add("estadisticas", estadisticas);
        respuesta.add("miReferidor", miReferidor != null ? miReferidor : JsonNull.INSTANCE);
        respuesta.add("referidos", listaReferidos);
        respuesta.add("historialPagos", historialPagos);
        return respuesta;
    }

    /**
     * Actualiza el porcentaje general de comisión compartida (0 a 100%, opcional sin mínimos ni máximos adicionales)
     * y el mensaje corto personal para invitar referidos.
     */
    public JsonObject actualizarConfiguracion(int userId, BigDecimal porcentajeCompartido,
                                            String mensajeCortoCrudo, boolean aplicarATodos) {
        if (userId <= 0) {
            throw new ServicioException(401, "Debes iniciar sesión");
        }
        BigDecimal porcentaje = validarPorcentaje(porcentajeCompartido);
        String mensajeLimpio = mensajeCortoCrudo == null ? ""
                : ChatSanitizer.limpiarTexto(mensajeCortoCrudo, MAX_LONGITUD_MENSAJE);

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET referido_porcentaje_compartido = ?, referido_mensaje_corto = ? WHERE id = ?")) {
                    ps.setBigDecimal(1, porcentaje);
                    ps.setString(2, mensajeLimpio);
                    ps.setInt(3, userId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        throw ServicioException.noEncontrado("Usuario no encontrado");
                    }
                }
                String sqlReferidos = aplicarATodos
                        ? "UPDATE referrals SET porcentaje_compartido = ?, usa_porcentaje_personalizado = FALSE WHERE referrer_user_id = ?"
                        : "UPDATE referrals SET porcentaje_compartido = ? WHERE referrer_user_id = ? AND usa_porcentaje_personalizado = FALSE";
                try (PreparedStatement psRef = conn.prepareStatement(sqlReferidos)) {
                    psRef.setBigDecimal(1, porcentaje);
                    psRef.setInt(2, userId);
                    psRef.executeUpdate();
                }
                conn.commit();
            } catch (ServicioException e) {
                rollbackSilencioso(conn);
                throw e;
            } catch (Exception e) {
                rollbackSilencioso(conn);
                throw ServicioException.interno("No se pudo guardar la configuración de referidos", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (ServicioException e) {
            throw e;
        } catch (SQLException e) {
            throw ServicioException.interno("No se pudo guardar la configuración de referidos", e);
        }

        return obtenerResumenUsuario(userId);
    }

    /** Ajusta el porcentaje de comisión compartida para un referido específico de la lista del referidor. */
    public JsonObject actualizarPorcentajeReferidoIndividual(int referrerUserId, int referredUserId,
                                                            BigDecimal porcentajeCompartido) {
        if (referrerUserId <= 0) {
            throw new ServicioException(401, "Debes iniciar sesión");
        }
        if (referredUserId <= 0) {
            throw ServicioException.solicitudInvalida("El referido indicado no es válido");
        }
        BigDecimal porcentaje = validarPorcentaje(porcentajeCompartido);

        int actualizados = db.update(
                "UPDATE referrals SET porcentaje_compartido = ?, usa_porcentaje_personalizado = TRUE " +
                "WHERE referrer_user_id = ? AND referred_user_id = ?",
                porcentaje, referrerUserId, referredUserId);
        if (actualizados == 0) {
            throw ServicioException.noEncontrado("Ese jugador no forma parte de tu lista de referidos");
        }
        return obtenerResumenUsuario(referrerUserId);
    }

    private String asignarCodigoFaltante(int userId, String username) {
        try (Connection conn = db.getConnection()) {
            String nuevo = usuarios.generarCodigoReferidoUnico(conn, username);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET codigo_referido = ? WHERE id = ?")) {
                ps.setString(1, nuevo);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
            return nuevo;
        } catch (SQLException e) {
            throw ServicioException.interno("No se pudo generar el código de referido", e);
        }
    }

    private static BigDecimal validarPorcentaje(BigDecimal valor) {
        if (valor == null) {
            throw ServicioException.solicitudInvalida("Indica el porcentaje compartido (de 0 a 100)");
        }
        if (valor.compareTo(BigDecimal.ZERO) < 0 || valor.compareTo(CIEN) > 0) {
            throw ServicioException.solicitudInvalida("El porcentaje compartido debe estar entre 0% y 100%");
        }
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal normalizarPorcentaje(BigDecimal valor) {
        if (valor == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal acotado = valor;
        if (acotado.compareTo(BigDecimal.ZERO) < 0) acotado = BigDecimal.ZERO;
        if (acotado.compareTo(CIEN) > 0) acotado = CIEN;
        return acotado.setScale(2, RoundingMode.HALF_UP);
    }

    private static String str(JsonObject obj, String campo) {
        if (obj == null || !obj.has(campo) || obj.get(campo).isJsonNull()) return "";
        return obj.get(campo).getAsString();
    }

    private static int entero(JsonObject obj, String campo) {
        if (obj == null || !obj.has(campo) || obj.get(campo).isJsonNull()) return 0;
        return obj.get(campo).getAsInt();
    }

    private static BigDecimal decimal(JsonObject obj, String campo) {
        if (obj == null || !obj.has(campo) || obj.get(campo).isJsonNull()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return obj.get(campo).getAsBigDecimal().setScale(2, RoundingMode.HALF_UP);
    }

    private static void rollbackSilencioso(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
        }
    }
}
