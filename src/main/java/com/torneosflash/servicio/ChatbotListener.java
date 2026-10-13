package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;

/**
 * Listeners del chatbot (roadmap parte 20), suscritos al bus de eventos (A4):
 * <ul>
 *   <li>{@link Eventos#CHATBOT_DUDA_SIN_RESPUESTA}: avisa al admin por correo (Brevo) de una duda nueva que la IA no
 *       supo resolver. Solo se avisa de dudas NUEVAS (si la misma duda se repite solo sube su contador en el panel) y
 *       con un tope de correos por hora ({@code CHATBOT_MAX_CORREOS_HORA}) para que nadie pueda inundar el buzón; lo
 *       que pase del tope sigue visible en el panel admin. El correo lleva el nombre de usuario, nunca email ni teléfono.</li>
 *   <li>{@link Eventos#CHATBOT_DUDA_RESPONDIDA}: entrega la respuesta del admin a cada usuario que hizo la duda: la deja
 *       en su historial del chat (así la ve aunque esté desconectado), la envía por socket ({@code chatbot_respuesta}) y
 *       manda un push.</li>
 * </ul>
 * Son síncronos (A4): el correo y el push se encolan en el pool de IO por dentro, no bloquean.
 */
public class ChatbotListener {
    private static final Logger logger = LoggerFactory.getLogger(ChatbotListener.class);

    private static final long HORA_MS = 3_600_000L;
    private static final int MAX_PREGUNTA_CORREO = 500;
    private static final int MAX_PREGUNTA_RESUMEN = 80;

    private final CorreoServicio correo;
    private final NotificadorUsuarios notificador;
    private final ChatbotService chatbot;
    private final AppConfig config;

    /** Instantes de los correos enviados en la última hora (para el tope por hora). */
    private final ArrayDeque<Long> enviados = new ArrayDeque<>();

    public ChatbotListener(CorreoServicio correo, NotificadorUsuarios notificador, ChatbotService chatbot, AppConfig config) {
        this.correo = correo;
        this.notificador = notificador;
        this.chatbot = chatbot;
        this.config = config;
    }

    /** Se suscribe al bus. Llamar una sola vez al arrancar. */
    public void registrar(EventBus bus) {
        bus.on(Eventos.CHATBOT_DUDA_SIN_RESPUESTA, "chatbot-correo-admin", this::avisarAlAdmin);
        bus.on(Eventos.CHATBOT_DUDA_RESPONDIDA, "chatbot-aviso-usuarios", this::entregarRespuesta);
    }

    private void avisarAlAdmin(EventBus.Evento evento) {
        Eventos.DudaSinRespuesta duda = evento.datos(Eventos.DudaSinRespuesta.class);
        if (!duda.nueva()) return;
        if (!correo.isHabilitado()) {
            logger.info("Duda #{} sin respuesta (correo no configurado: se ve en el panel admin)", duda.dudaId());
            return;
        }
        if (!hayCupoDeCorreo()) {
            logger.warn("Duda #{} sin respuesta: tope de {} correos/hora alcanzado, se ve solo en el panel admin",
                    duda.dudaId(), config.getChatbotMaxCorreosHora());
            return;
        }

        String pregunta = recortar(duda.pregunta(), MAX_PREGUNTA_CORREO);
        String asunto = "El chatbot no supo responder una duda";
        String detalle = "Un usuario hizo una pregunta que el chatbot no pudo resolver con lo que le has enseñado.\n\n"
                + "Usuario: " + duda.username() + " (id " + duda.userId() + ")\n"
                + "Pregunta: " + pregunta + "\n\n"
                + "Respóndela en: " + config.getAppBaseUrl() + "/admin  →  pestaña «🤖 Chatbot»  →  «Dudas de usuarios».\n"
                + "Tu respuesta se guarda para entrenar al bot y se le entrega al usuario en su chat.";

        String destino = config.getChatbotAdminEmail();
        if (destino.isEmpty()) {
            correo.notificarAdmin(asunto, detalle); // al remitente configurado en Brevo
        } else {
            correo.enviar(destino, "🔔 " + asunto, detalle);
        }
    }

    private void entregarRespuesta(EventBus.Evento evento) {
        Eventos.DudaRespondida duda = evento.datos(Eventos.DudaRespondida.class);
        String texto = "💬 El administrador respondió tu duda «" + recortar(duda.pregunta(), MAX_PREGUNTA_RESUMEN)
                + "»:\n" + duda.respuesta();

        JsonObject datos = new JsonObject();
        datos.addProperty("pregunta", duda.pregunta());
        datos.addProperty("respuesta", duda.respuesta());
        datos.addProperty("texto", texto);

        for (int userId : duda.usuarios()) {
            // Un fallo con un usuario no debe dejar sin respuesta a los demás
            try {
                chatbot.guardarMensajeBot(userId, texto);
                notificador.emitirA(userId, "chatbot_respuesta", datos);
                notificador.enviarPush(userId, "Torneos Flash", "El administrador respondió tu duda del asistente.");
            } catch (Exception e) {
                logger.error("No se pudo entregar la respuesta de la duda #{} al usuario {}: {}",
                        duda.dudaId(), userId, e.getMessage());
            }
        }
    }

    /** Ventana deslizante de 1 hora: true (y registra el envío) si aún no se llegó al tope. */
    private synchronized boolean hayCupoDeCorreo() {
        long ahora = System.currentTimeMillis();
        while (!enviados.isEmpty() && enviados.peekFirst() <= ahora - HORA_MS) {
            enviados.pollFirst();
        }
        if (enviados.size() >= config.getChatbotMaxCorreosHora()) return false;
        enviados.addLast(ahora);
        return true;
    }

    private static String recortar(String texto, int max) {
        if (texto == null) return "";
        String t = texto.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
