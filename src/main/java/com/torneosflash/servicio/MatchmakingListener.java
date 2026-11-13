package com.torneosflash.servicio;

import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;

/**
 * Conecta el ciclo de vida de usuarios y partidas (EventBus A4) con {@link MatchmakingService}:
 * actualiza el puntaje interno al registrarse o terminar partidas y limpia oleadas/retos al cerrar búsquedas.
 */
public final class MatchmakingListener {

    private final MatchmakingService matchmaking;

    public MatchmakingListener(MatchmakingService matchmaking) {
        this.matchmaking = matchmaking;
    }

    public void registrarEn(EventBus bus) {
        bus.on(Eventos.USER_REGISTERED, "matchmaking-alta-usuario", this::usuarioRegistrado);
        bus.on(Eventos.MATCH_FINISHED, "matchmaking-recalcular-post-partida", this::partidaFinalizada);
        bus.on(Eventos.MATCH_SEARCH_ENDED, "matchmaking-limpiar-busqueda", this::busquedaTerminada);
        bus.on(Eventos.MATCH_ROOM_CREATED, "matchmaking-limpiar-al-emparejar", this::salaCreada);
    }

    private void usuarioRegistrado(EventBus.Evento evento) {
        Eventos.UsuarioRegistrado reg = evento.datos(Eventos.UsuarioRegistrado.class);
        if (reg == null || reg.userId() <= 0) return;
        if (reg.datosClash() != null) {
            matchmaking.guardarDatosClashYRecalcular(reg.userId(), reg.datosClash());
        } else {
            matchmaking.asegurarYRecalcularPerfil(reg.userId());
        }
    }

    private void partidaFinalizada(EventBus.Evento evento) {
        Eventos.PartidaFinalizada fin = evento.datos(Eventos.PartidaFinalizada.class);
        if (fin == null || fin.participantes() == null) return;
        for (int userId : fin.participantes()) {
            if (userId > 0) {
                matchmaking.asegurarYRecalcularPerfil(userId);
            }
        }
    }

    private void busquedaTerminada(EventBus.Evento evento) {
        Eventos.BusquedaMatch busqueda = evento.datos(Eventos.BusquedaMatch.class);
        if (busqueda != null && busqueda.userId() > 0) {
            matchmaking.limpiarBusqueda(busqueda.userId());
        }
    }

    private void salaCreada(EventBus.Evento evento) {
        Eventos.SalaPartidaCreada sala = evento.datos(Eventos.SalaPartidaCreada.class);
        if (sala == null || sala.participantes() == null) return;
        for (int userId : sala.participantes()) {
            if (userId > 0) {
                matchmaking.limpiarBusqueda(userId);
            }
        }
    }
}
