package com.torneosflash.servicio;

import com.torneosflash.config.Ejecutores;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;

/**
 * Listeners del chat de voz (roadmap 84), suscritos al bus de eventos (A4):
 * <ul>
 *   <li>{@link Eventos#MATCH_STARTED}: prepara el canal de voz de Discord de la partida (solo si algún jugador vinculó
 *       su cuenta).</li>
 *   <li>{@link Eventos#MATCH_FINISHED}: borra el canal de voz de la partida.</li>
 * </ul>
 * El bus es síncrono y corre en el hilo que emite (el de la partida), y hablar con Discord es HTTP externo, así que
 * aquí solo se ENCOLA el trabajo en el pool de IO (A8). Si el pool está lleno se descarta y lo recoge después
 * {@link VozDiscordService#limpiarVencidos()} (al cerrar) o {@link VozDiscordService#miCanal(int)} (al abrir).
 */
public class VozListener {

    private final VozDiscordService voz;
    private final Ejecutores ejecutores;

    public VozListener(VozDiscordService voz, Ejecutores ejecutores) {
        this.voz = voz;
        this.ejecutores = ejecutores;
    }

    /** Se suscribe al bus. Llamar una sola vez al arrancar. */
    public void registrar(EventBus bus) {
        bus.on(Eventos.MATCH_STARTED, "voz-preparar-canal", this::alIniciarPartida);
        bus.on(Eventos.MATCH_FINISHED, "voz-cerrar-canal", this::alTerminarPartida);
    }

    private void alIniciarPartida(EventBus.Evento evento) {
        if (!voz.disponible()) return;
        Eventos.PartidaIniciada partida = evento.datos(Eventos.PartidaIniciada.class);
        ejecutores.ejecutarIO("canal de voz de la partida #" + partida.matchId(),
                () -> voz.prepararCanal(partida.matchId(), partida.participantes()));
    }

    private void alTerminarPartida(EventBus.Evento evento) {
        if (!voz.disponible()) return;
        Eventos.PartidaFinalizada partida = evento.datos(Eventos.PartidaFinalizada.class);
        ejecutores.ejecutarIO("cerrar canal de voz de la partida #" + partida.matchId(),
                () -> voz.cerrarCanal(partida.matchId()));
    }
}
