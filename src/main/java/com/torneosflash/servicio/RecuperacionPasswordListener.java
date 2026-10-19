package com.torneosflash.servicio;

import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reacciones posteriores a recuperar una cuenta: correo Brevo y cierre de sockets ya revocados. */
public final class RecuperacionPasswordListener {
    private static final Logger logger = LoggerFactory.getLogger(RecuperacionPasswordListener.class);

    private final CorreoServicio correo;
    private final SocketIOServer sockets;

    public RecuperacionPasswordListener(CorreoServicio correo, SocketIOServer sockets) {
        this.correo = correo;
        this.sockets = sockets;
    }

    public void registrar(EventBus bus) {
        bus.on(Eventos.PASSWORD_RESET_REQUESTED, "recuperacion-password-correo", this::enviarEnlace);
        bus.on(Eventos.PASSWORD_RESET_COMPLETED, "recuperacion-password-sockets", this::cerrarSesiones);
    }

    private void enviarEnlace(EventBus.Evento evento) {
        Eventos.PasswordResetSolicitado solicitud = evento.datos(Eventos.PasswordResetSolicitado.class);
        if (!correo.isHabilitado()) {
            logger.warn("Se solicitó recuperar una contraseña, pero BREVO_API_KEY/BREVO_SENDER_EMAIL no están configurados");
            return;
        }
        String cuerpo = "Hola " + solicitud.username() + ",\n\n"
                + "Recibimos una solicitud para cambiar la contraseña de tu cuenta de Torneos Flash.\n\n"
                + "Usa este enlace una sola vez dentro de los próximos " + solicitud.minutosVigencia() + " minutos:\n"
                + solicitud.enlace() + "\n\n"
                + "Si no solicitaste el cambio, ignora este mensaje. Nunca compartas este enlace.\n\n"
                + "— Torneos Flash";
        correo.enviar(solicitud.email(), "Recupera tu contraseña de Torneos Flash", cuerpo);
    }

    private void cerrarSesiones(EventBus.Evento evento) {
        Eventos.PasswordResetCompletado cambio = evento.datos(Eventos.PasswordResetCompletado.class);
        sockets.cerrarSocketsDeUsuario(cambio.userId());
    }
}
