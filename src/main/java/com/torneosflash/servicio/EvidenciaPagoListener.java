package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.RoundingMode;

/**
 * Listener de evidencias de pago (roadmap 7), suscrito al bus de eventos (A4):
 * <ul>
 *   <li>{@link Eventos#PAGOS_EVIDENCIA_PUBLICADA}: manda el comprobante a todos los conectados como un mensaje del
 *       canal «pagos» ({@code mensaje_chat}, igual que anuncios) y le avisa por push a quien recibió el pago.</li>
 * </ul>
 * El comprobante ya está guardado cuando esto corre; si un canal falla, el otro se intenta igual y la evidencia sigue
 * visible al abrir el canal (se carga por HTTP). No usa rooms (no depende de A1).
 */
public class EvidenciaPagoListener {
    private static final Logger logger = LoggerFactory.getLogger(EvidenciaPagoListener.class);

    private final NotificadorUsuarios notificador;

    public EvidenciaPagoListener(NotificadorUsuarios notificador) {
        this.notificador = notificador;
    }

    /** Se suscribe al bus. Llamar una sola vez al arrancar. */
    public void registrar(EventBus bus) {
        bus.on(Eventos.PAGOS_EVIDENCIA_PUBLICADA, "pagos-reparto", this::repartir);
    }

    private void repartir(EventBus.Evento evento) {
        Eventos.EvidenciaPublicada e = evento.datos(Eventos.EvidenciaPublicada.class);

        try {
            JsonObject mensaje = EvidenciaPagoService.mensajeChat(
                    e.evidenciaId(), e.transaccionId(), e.adminUsername(), e.username(),
                    e.monto(), e.metodo(), e.nota(), e.imagen(), e.fecha());
            // El comprobante solo se entrega al usuario dueño de la transacción.
            notificador.emitirA(e.userId(), "mensaje_chat", mensaje);
            JsonObject aviso = new JsonObject();
            aviso.addProperty("evidenciaId", e.evidenciaId());
            aviso.addProperty("transaccionId", e.transaccionId());
            notificador.notificar(e.userId(), "finanzas", "Comprobante de retiro publicado",
                    "Publicamos el comprobante de tu retiro de $" +
                            e.monto().setScale(0, RoundingMode.HALF_UP).toPlainString() +
                            ". Míralo en el canal Pagos.", "/chat?canal=pagos",
                    "evidencia-pago:" + e.evidenciaId(), aviso, true);
        } catch (Exception ex) {
            logger.error("No se pudo repartir en vivo la evidencia #{}: {}", e.evidenciaId(), ex.getMessage());
        }

    }
}
