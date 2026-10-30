package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;

import java.util.Set;

/** Convierte los eventos confirmados del dominio en avisos persistentes y push para usuarios desconectados. */
public final class NotificacionesListener {
    private final NotificacionesService notificaciones;
    private final GenericDAO db;

    public NotificacionesListener(NotificacionesService notificaciones, GenericDAO db) {
        this.notificaciones = notificaciones;
        this.db = db;
    }

    public void registrar(EventBus bus) {
        bus.on(Eventos.CHAT_MESSAGE_PUBLISHED, "notificacion-chat-publico", this::mensajePublico);
        bus.on(Eventos.PRIVATE_MESSAGE_PUBLISHED, "notificacion-chat-privado", this::mensajePrivado);
        bus.on(Eventos.MATCH_SEARCH_STARTED, "notificacion-busqueda-iniciada", this::busquedaIniciada);
        bus.on(Eventos.MATCH_SEARCH_ENDED, "notificacion-busqueda-terminada", this::busquedaTerminada);
        bus.on(Eventos.MATCH_ROOM_CREATED, "limpiar-aviso-busqueda-al-emparejar", this::emparejada);
        bus.on(Eventos.LEADERBOARD_CUT_COMPLETED, "notificacion-corte-ranking", this::corteRanking);
        bus.on(Eventos.RAFFLE_CREATED, "notificacion-sorteo-nuevo", this::sorteoNuevo);
        bus.on(Eventos.RAFFLE_FINISHED, "notificacion-sorteo-resultado", this::sorteoFinalizado);
        bus.on(Eventos.RAFFLE_CANCELLED, "notificacion-sorteo-cancelado", this::sorteoCancelado);
        bus.on(Eventos.RAFFLE_POLL_RESET, "notificacion-encuesta-sorteos", this::encuestaReiniciada);
    }

    private void mensajePublico(EventBus.Evento evento) {
        Eventos.MensajeChatPublicado mensaje = evento.datos(Eventos.MensajeChatPublicado.class);
        if (mensaje == null || mensaje.messageId() <= 0 || mensaje.canal() == null
                || "clash_logs".equals(mensaje.canal())) return;
        String nombreCanal = switch (mensaje.canal()) {
            case "general" -> "General";
            case "anuncios" -> "Anuncios";
            case "clash" -> "Clash";
            case "pagos" -> "Pagos";
            default -> mensaje.canal();
        };
        JsonObject datos = new JsonObject();
        datos.addProperty("canal", mensaje.canal());
        datos.addProperty("messageId", mensaje.messageId());
        notificaciones.notificarATodos(Set.of(mensaje.emisorId()), "chat", "Mensaje en " + nombreCanal,
                mensaje.username() + ": " + mensaje.preview(), "/chat?canal=" + mensaje.canal(),
                "chat:" + mensaje.messageId(), datos, true);
    }

    private void mensajePrivado(EventBus.Evento evento) {
        Eventos.MensajePrivadoPublicado mensaje = evento.datos(Eventos.MensajePrivadoPublicado.class);
        if (mensaje == null || mensaje.destinatarioId() <= 0) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("salaId", mensaje.salaId());
        notificaciones.notificar(mensaje.destinatarioId(), "partida", "Mensaje de " + mensaje.username(),
                mensaje.preview(), "/match", "mensaje-privado:" + mensaje.messageId(), datos, true);
    }

    private void busquedaIniciada(EventBus.Evento evento) {
        Eventos.BusquedaMatch busqueda = evento.datos(Eventos.BusquedaMatch.class);
        if (busqueda == null || busqueda.userId() <= 0) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("userId", busqueda.userId());
        notificaciones.notificarATodos(Set.of(busqueda.userId()), "partida", "Alguien busca partida",
                busqueda.username() + " está buscando rival.", "/match", claveBusqueda(busqueda.userId()), datos, true);
    }

    private void busquedaTerminada(EventBus.Evento evento) {
        Eventos.BusquedaMatch busqueda = evento.datos(Eventos.BusquedaMatch.class);
        if (busqueda != null) notificaciones.eliminarPorEvento(claveBusqueda(busqueda.userId()));
    }

    private void emparejada(EventBus.Evento evento) {
        Eventos.SalaPartidaCreada sala = evento.datos(Eventos.SalaPartidaCreada.class);
        if (sala == null) return;
        for (int userId : sala.participantes()) notificaciones.eliminarPorEvento(claveBusqueda(userId));
    }

    private void corteRanking(EventBus.Evento evento) {
        Eventos.CorteLeaderboard corte = evento.datos(Eventos.CorteLeaderboard.class);
        if (corte == null || corte.corteId() <= 0) return;
        String periodo = nombrePeriodo(corte.periodo());
        JsonObject datosCorte = new JsonObject();
        datosCorte.addProperty("corteId", corte.corteId());
        datosCorte.addProperty("periodo", corte.periodo());
        notificaciones.notificarATodos(Set.of(), "ranking", "Nuevo corte del ranking",
                "Se completó el corte " + periodo + ". Revisa los resultados y el historial de ganadores.",
                "/leaderboard", "corte-ranking:" + corte.corteId(), datosCorte, true);

        for (JsonObject ganador : db.query(
                "SELECT user_id, username, posicion, victorias, premio FROM leaderboard_cut_snapshots " +
                        "WHERE corte_id = ? AND posicion <= 10 ORDER BY posicion", corte.corteId())) {
            int userId = ganador.get("user_id").getAsInt();
            int posicion = ganador.get("posicion").getAsInt();
            JsonObject datos = new JsonObject();
            datos.addProperty("corteId", corte.corteId());
            datos.addProperty("periodo", periodo);
            datos.addProperty("posicion", posicion);
            datos.add("premio", ganador.get("premio"));
            notificaciones.notificar(userId, "ranking", "Ganaste el ranking " + periodo,
                    "Quedaste #" + posicion + " en " + periodo + " y ganaste $" +
                            ganador.get("premio").getAsBigDecimal().toPlainString() + ".",
                    "/leaderboard", "ranking-ganador:" + corte.corteId(), datos, true);
        }
    }

    private void sorteoNuevo(EventBus.Evento evento) {
        Eventos.SorteoCreado sorteo = evento.datos(Eventos.SorteoCreado.class);
        if (sorteo == null) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("raffleId", sorteo.raffleId());
        notificaciones.notificarATodos(Set.of(), "sorteos", "Nuevo sorteo", "Ya está disponible «" +
                sorteo.nombre() + "».", "/sorteos", "sorteo-nuevo:" + sorteo.raffleId(), datos, true);
    }

    private void sorteoFinalizado(EventBus.Evento evento) {
        Eventos.SorteoFinalizado sorteo = evento.datos(Eventos.SorteoFinalizado.class);
        if (sorteo == null) return;
        String causa = sorteo.motivo() == null || sorteo.motivo().isBlank()
                ? "se completó" : sorteo.motivo();
        Set<Integer> participantes = Set.copyOf(sorteo.participantes());
        JsonObject resultadoGeneral = new JsonObject();
        resultadoGeneral.addProperty("raffleId", sorteo.raffleId());
        resultadoGeneral.addProperty("motivo", causa);
        resultadoGeneral.addProperty("ganadorNombre", sorteo.ganadorNombre());
        notificaciones.notificarATodos(participantes, "sorteos", "Sorteo finalizado",
                "«" + sorteo.nombre() + "» terminó: " + causa + ". Ganó " + sorteo.ganadorNombre() + ".",
                "/sorteos", "sorteo-fin:" + sorteo.raffleId(), resultadoGeneral, false);
        for (int userId : sorteo.participantes()) {
            boolean ganador = userId == sorteo.ganadorId();
            JsonObject datos = new JsonObject();
            datos.addProperty("raffleId", sorteo.raffleId());
            datos.addProperty("ganador", ganador);
            datos.addProperty("ganadorNombre", sorteo.ganadorNombre());
            datos.addProperty("motivo", causa);
            String texto = ganador
                    ? "¡Ganaste «" + sorteo.nombre() + "»! Terminó porque " + causa + ". El administrador coordinará la entrega."
                    : "«" + sorteo.nombre() + "» terminó porque " + causa + ". Ganó " + sorteo.ganadorNombre() + ".";
            notificaciones.notificar(userId, "sorteos", ganador ? "Ganaste un sorteo" : "Sorteo finalizado",
                    texto, "/sorteos", "sorteo-resultado:" + sorteo.raffleId(), datos, true);
        }
    }

    private void sorteoCancelado(EventBus.Evento evento) {
        Eventos.SorteoCancelado sorteo = evento.datos(Eventos.SorteoCancelado.class);
        if (sorteo == null) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("raffleId", sorteo.raffleId());
        for (int userId : sorteo.participantes()) {
            notificaciones.notificar(userId, "sorteos", "Sorteo cancelado",
                    "Se canceló «" + sorteo.nombre() + "» y tus tickets fueron devueltos.",
                    "/sorteos", "sorteo-cancelado:" + sorteo.raffleId(), datos, true);
        }
    }

    private void encuestaReiniciada(EventBus.Evento evento) {
        JsonObject datos = new JsonObject();
        notificaciones.notificarATodos(Set.of(), "sorteos", "Nueva encuesta de sorteos",
                "Se reinició la encuesta. Entra a elegir tu premio favorito.", "/sorteos",
                "encuesta-sorteos:" + System.currentTimeMillis(), datos, true);
    }

    private static String claveBusqueda(int userId) { return "busqueda:" + userId; }

    private static String nombrePeriodo(String periodo) {
        return switch (periodo == null ? "" : periodo) {
            case "dia" -> "diario";
            case "semana" -> "semanal";
            case "mes" -> "mensual";
            case "ano" -> "anual";
            default -> periodo;
        };
    }
}
