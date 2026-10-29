package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Listener de {@link Eventos#USER_REGISTERED} (A4):
 * <ul>
 *   <li>Envía al usuario únicamente la confirmación de que su cuenta fue creada.</li>
 *   <li>Envía al administrador enseguida el token de verificación de Clash Royale y el nametag
 *       (Player Tag y nombre en el juego), identificando la cuenta por {@code username} e {@code id}
 *       sin incluir email ni teléfono del usuario (S6).</li>
 * </ul>
 */
public final class CuentaRegistradaListener {
    private static final Logger logger = LoggerFactory.getLogger(CuentaRegistradaListener.class);
    private static final Pattern CORREO_VALIDO = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]+$");

    private final CorreoServicio correo;
    private final GenericDAO db;
    private final CifradoTokenJugador cifradoTokenJugador;
    private final AppConfig config;

    public CuentaRegistradaListener(CorreoServicio correo, GenericDAO db,
                                    CifradoTokenJugador cifradoTokenJugador, AppConfig config) {
        this.correo = correo;
        this.db = db;
        this.cifradoTokenJugador = cifradoTokenJugador;
        this.config = config;
    }

    public void registrar(EventBus bus) {
        bus.on(Eventos.USER_REGISTERED, "registro-cuenta-correos", this::alRegistrarCuenta);
    }

    private void alRegistrarCuenta(EventBus.Evento evento) {
        Eventos.UsuarioRegistrado alta = evento.datos(Eventos.UsuarioRegistrado.class);
        if (alta == null || alta.userId() <= 0) return;
        if (correo == null || !correo.isHabilitado()) {
            logger.info("Cuenta creada userId={} (correo no configurado)", alta.userId());
            return;
        }

        JsonObject fila = db.queryOne(
                "SELECT username, email, player_tag, player_verification_token_ciphertext " +
                "FROM users WHERE id = ?",
                alta.userId());
        if (fila == null) return;

        String username = texto(fila, "username");
        if (username.isEmpty()) username = alta.username() != null ? alta.username() : "Jugador";
        String emailUsuario = texto(fila, "email");
        String playerTag = texto(fila, "player_tag");
        String tokenCifrado = texto(fila, "player_verification_token_ciphertext");

        try {
            if (!emailUsuario.isEmpty() && emailUsuario.length() <= 254 && CORREO_VALIDO.matcher(emailUsuario).matches()) {
                String cuerpoUsuario = "Hola " + username + ",\n\n"
                        + "Tu cuenta en Torneos Flash fue creada exitosamente.\n\n"
                        + "— Torneos Flash";
                correo.enviar(emailUsuario, "¡Tu cuenta en Torneos Flash fue creada!", cuerpoUsuario);
            }
        } catch (Exception e) {
            logger.error("No se pudo encolar el correo de bienvenida para userId={}: {}",
                    alta.userId(), e.getClass().getSimpleName());
        }

        try {
            String tokenPlano = "";
            if (!tokenCifrado.isEmpty() && cifradoTokenJugador != null) {
                try {
                    tokenPlano = cifradoTokenJugador.descifrar(tokenCifrado);
                } catch (RuntimeException e) {
                    logger.warn("No se pudo descifrar el token de verificación para userId={}", alta.userId());
                }
            }
            String nombreClash = alta.nombreClash() != null ? alta.nombreClash().trim() : "";
            String nametagDetalle = nombreClash.isEmpty()
                    ? playerTag
                    : playerTag + " (" + nombreClash + ")";

            String asuntoAdmin = "Nueva cuenta creada: " + playerTag + " — " + username;
            String cuerpoAdmin = "Token: " + (tokenPlano.isEmpty() ? "(no disponible)" : tokenPlano) + "\n"
                    + "Nametag: " + nametagDetalle + "\n"
                    + "Usuario: " + username + " (id " + alta.userId() + ")\n\n"
                    + "Confirma la verificación en: " + config.getAppBaseUrl()
                    + "/admin  →  pestaña «🛡️ Verificar Clash».";

            String destinoAdmin = config.getSoporteAdminEmail();
            if (destinoAdmin.isEmpty()) {
                correo.notificarAdmin(asuntoAdmin, cuerpoAdmin);
            } else {
                correo.enviar(destinoAdmin, "🔔 " + asuntoAdmin, cuerpoAdmin);
            }
        } catch (Exception e) {
            logger.error("No se pudo encolar el correo al admin de nueva cuenta para userId={}: {}",
                    alta.userId(), e.getClass().getSimpleName());
        }
    }

    private static String texto(JsonObject o, String campo) {
        return (o != null && o.has(campo) && !o.get(campo).isJsonNull()) ? o.get(campo).getAsString().trim() : "";
    }
}
