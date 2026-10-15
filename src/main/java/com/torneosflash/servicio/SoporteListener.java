package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Listeners de soporte (roadmap 24.5, "Hablar con el admin"), suscritos al bus de eventos (A4):
 * <ul>
 *   <li>{@link Eventos#SOPORTE_TICKET_CREADO}: avisa al admin por correo (Brevo) de la consulta nueva, con tope de
 *       correos por hora ({@code SOPORTE_MAX_CORREOS_HORA}) para que nadie pueda inundar el buzón; lo que pase del tope
 *       sigue visible en el panel admin. El correo lleva el id y el nombre de usuario, nunca email ni teléfono.</li>
 *   <li>{@link Eventos#SOPORTE_TICKET_RESPONDIDO}: avisa al usuario de que su consulta fue resuelta (socket
 *       {@code soporte_respuesta} + push) y le envía la respuesta a SU correo.</li>
 * </ul>
 * Son síncronos (A4): el correo y el push se encolan en el pool de IO por dentro, no bloquean.
 */
public class SoporteListener {
    private static final Logger logger = LoggerFactory.getLogger(SoporteListener.class);

    private static final long HORA_MS = 3_600_000L;
    private static final int MAX_MENSAJE_CORREO = 1500;
    private static final int MAX_ASUNTO_RESUMEN = 80;
    private static final Pattern CORREO_VALIDO = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]+$");

    private final CorreoServicio correo;
    private final NotificadorUsuarios notificador;
    private final SoporteService soporte;
    private final AppConfig config;

    /** Instantes de los correos al admin enviados en la última hora (para el tope por hora). */
    private final ArrayDeque<Long> enviados = new ArrayDeque<>();

    public SoporteListener(CorreoServicio correo, NotificadorUsuarios notificador, SoporteService soporte, AppConfig config) {
        this.correo = correo;
        this.notificador = notificador;
        this.soporte = soporte;
        this.config = config;
    }

    /** Se suscribe al bus. Llamar una sola vez al arrancar. */
    public void registrar(EventBus bus) {
        bus.on(Eventos.SOPORTE_TICKET_CREADO, "soporte-correo-admin", this::avisarAlAdmin);
        bus.on(Eventos.SOPORTE_TICKET_RESPONDIDO, "soporte-aviso-usuario", this::avisarAlUsuario);
    }

    private void avisarAlAdmin(EventBus.Evento evento) {
        Eventos.TicketCreado ticket = evento.datos(Eventos.TicketCreado.class);
        if (!correo.isHabilitado()) {
            logger.info("Ticket de soporte #{} (correo no configurado: se ve en el panel admin)", ticket.ticketId());
            return;
        }
        if (!hayCupoDeCorreo()) {
            logger.warn("Ticket de soporte #{}: tope de {} correos/hora alcanzado, se ve solo en el panel admin",
                    ticket.ticketId(), config.getSoporteMaxCorreosHora());
            return;
        }

        String asunto = "Soporte #" + ticket.ticketId() + " [" + ticket.categoria() + "]: "
                + recortar(ticket.asunto(), MAX_ASUNTO_RESUMEN);
        String detalle = "Un usuario te escribió desde «Hablar con el admin».\n\n"
                + "Usuario: " + ticket.username() + " (id " + ticket.userId() + ")\n"
                + "Categoría: " + ticket.categoria() + "\n"
                + "Asunto: " + ticket.asunto() + "\n\n"
                + recortar(ticket.mensaje(), MAX_MENSAJE_CORREO) + "\n\n"
                + "Respóndele en: " + config.getAppBaseUrl() + "/admin  →  pestaña «🎫 Soporte».\n"
                + "Tu respuesta le llega por correo y la ve en su panel de soporte.";

        String destino = config.getSoporteAdminEmail();
        if (destino.isEmpty()) {
            correo.notificarAdmin(asunto, detalle); // al remitente configurado en Brevo
        } else {
            correo.enviar(destino, "🔔 " + asunto, detalle);
        }
    }

    private void avisarAlUsuario(EventBus.Evento evento) {
        Eventos.TicketRespondido ticket = evento.datos(Eventos.TicketRespondido.class);
        int userId = ticket.userId();

        // Un fallo en un canal (socket, push o correo) no debe dejar al usuario sin los otros
        try {
            JsonObject datos = new JsonObject();
            datos.addProperty("ticketId", ticket.ticketId());
            datos.addProperty("asunto", ticket.asunto());
            datos.addProperty("respuesta", ticket.respuesta());
            notificador.emitirA(userId, "soporte_respuesta", datos);
            notificador.enviarPush(userId, "Torneos Flash",
                    "El administrador respondió tu consulta «" + recortar(ticket.asunto(), MAX_ASUNTO_RESUMEN) + "».");
        } catch (Exception e) {
            logger.error("No se pudo avisar al usuario {} de la respuesta del ticket #{}: {}",
                    userId, ticket.ticketId(), e.getMessage());
        }

        try {
            enviarRespuestaPorCorreo(ticket);
        } catch (Exception e) {
            logger.error("No se pudo enviar por correo la respuesta del ticket #{}: {}", ticket.ticketId(), e.getMessage());
        }
    }

    private void enviarRespuestaPorCorreo(Eventos.TicketRespondido ticket) {
        if (!correo.isHabilitado()) {
            logger.info("Ticket #{} respondido (correo no configurado: el usuario la ve en su panel de soporte)",
                    ticket.ticketId());
            return;
        }
        String destino = soporte.correoDeUsuario(ticket.userId());
        if (destino.isEmpty() || destino.length() > 254 || !CORREO_VALIDO.matcher(destino).matches()) {
            logger.info("Ticket #{} respondido: el usuario {} no tiene un correo válido, la ve en su panel de soporte",
                    ticket.ticketId(), ticket.userId());
            return;
        }

        String asunto = "Respondimos tu consulta: " + recortar(ticket.asunto(), MAX_ASUNTO_RESUMEN);
        String cuerpo = "Hola " + ticket.username() + ",\n\n"
                + "El administrador respondió tu consulta «" + ticket.asunto() + "»:\n\n"
                + ticket.respuesta() + "\n\n"
                + "Puedes ver todas tus consultas en: " + config.getAppBaseUrl() + "/soporte\n\n"
                + "— UltimateClash";
        correo.enviar(destino, asunto, cuerpo);
    }

    /** Ventana deslizante de 1 hora: true (y registra el envío) si aún no se llegó al tope. */
    private synchronized boolean hayCupoDeCorreo() {
        long ahora = System.currentTimeMillis();
        while (!enviados.isEmpty() && enviados.peekFirst() <= ahora - HORA_MS) {
            enviados.pollFirst();
        }
        if (enviados.size() >= config.getSoporteMaxCorreosHora()) return false;
        enviados.addLast(ahora);
        return true;
    }

    private static String recortar(String texto, int max) {
        if (texto == null) return "";
        String t = texto.trim();
        // El asunto del correo es de una sola línea; en el cuerpo los saltos del mensaje se conservan por separado
        t = max <= MAX_ASUNTO_RESUMEN ? t.replaceAll("\\s+", " ") : t;
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
