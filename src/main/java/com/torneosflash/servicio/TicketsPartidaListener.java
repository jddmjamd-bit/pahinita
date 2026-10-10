package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener de {@link Eventos#MATCH_FINISHED}: acumula los tickets de sorteo de cada jugador y le avisa por socket
 * ({@code tickets_ganados}) (A4). Antes este código estaba copiado en {@code SocketHandler} (resultado por la API de
 * Clash) y en {@code AdminService.resolverDisputa}; ahora ambos solo emiten el evento.
 *
 * Cada jugador recibe su mitad de la comisión de sorteos que trae el desglose de {@link ComisionService} (D4).
 */
public class TicketsPartidaListener {
    private static final Logger logger = LoggerFactory.getLogger(TicketsPartidaListener.class);

    private final SorteoService sorteos;
    private final NotificadorUsuarios notificador;

    public TicketsPartidaListener(SorteoService sorteos, NotificadorUsuarios notificador) {
        this.sorteos = sorteos;
        this.notificador = notificador;
    }

    /** Se suscribe al bus. Llamar una sola vez al arrancar (el bus ignora un segundo registro con el mismo id). */
    public void registrar(EventBus bus) {
        bus.on(Eventos.MATCH_FINISHED, "tickets-sorteo", this::alTerminarPartida);
    }

    private void alTerminarPartida(EventBus.Evento evento) {
        Eventos.PartidaFinalizada partida = evento.datos(Eventos.PartidaFinalizada.class);
        if (partida.desglose() == null) return;

        for (int jugadorId : partida.participantes()) {
            if (jugadorId <= 0) continue;
            // Un fallo con un jugador no debe dejar sin tickets al otro
            try {
                int[] resultado = sorteos.acumularTicketsPorPartida(jugadorId, partida.desglose());

                JsonObject ticketData = new JsonObject();
                ticketData.addProperty("cantidad", resultado[0]);
                ticketData.addProperty("acumulado", resultado[1]);
                notificador.emitirA(jugadorId, "tickets_ganados", ticketData);
            } catch (Exception e) {
                logger.error("Error acreditando tickets al jugador {} (match #{}): {}",
                        jugadorId, partida.matchId(), e.getMessage());
            }
        }
    }
}
