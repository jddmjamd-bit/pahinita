package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener de {@link Eventos#MATCH_FINISHED}: avisa del resultado a los dos jugadores (A4). Antes este código vivía
 * dentro del sondeo de la API de Clash en {@code SocketHandler}.
 *
 * Para {@link Eventos.Origen#API} envía al ganador su saldo nuevo ({@code actualizar_saldo}) y a ambos el
 * {@code resultado_api} por socket más el push. Para {@link Eventos.Origen#DISPUTA} no hace nada a propósito: ahí el
 * flujo del admin ya avisa con {@code actualizar_saldo} + {@code flujo_completado} (no se cambió su comportamiento).
 */
public class ResultadoPartidaListener {
    private static final Logger logger = LoggerFactory.getLogger(ResultadoPartidaListener.class);

    private final NotificadorUsuarios notificador;

    public ResultadoPartidaListener(NotificadorUsuarios notificador) {
        this.notificador = notificador;
    }

    /** Se suscribe al bus. Llamar una sola vez al arrancar (el bus ignora un segundo registro con el mismo id). */
    public void registrar(EventBus bus) {
        bus.on(Eventos.MATCH_FINISHED, "notificacion-resultado", this::alTerminarPartida);
    }

    private void alTerminarPartida(EventBus.Evento evento) {
        Eventos.PartidaFinalizada partida = evento.datos(Eventos.PartidaFinalizada.class);
        if (partida.origen() != Eventos.Origen.API) return;

        double premio = partida.premio().doubleValue(); // solo para mostrar al cliente (JSON) // REVIEW-MONEY

        // Saldo nuevo del ganador
        try {
            notificador.emitirA(partida.ganadorId(), "actualizar_saldo",
                    new JsonPrimitive(partida.nuevoSaldoGanador().doubleValue()));
        } catch (Exception e) {
            logger.error("Error enviando el saldo nuevo al ganador {} (match #{}): {}",
                    partida.ganadorId(), partida.matchId(), e.getMessage());
        }

        // Resultado para cada jugador: socket + push. Un fallo con uno no debe dejar sin aviso al otro.
        for (int jugadorId : partida.participantes()) {
            if (jugadorId <= 0) continue;
            try {
                boolean esGanador = jugadorId == partida.ganadorId();
                String mensaje = esGanador
                        ? "🏆 ¡GANASTE! Recibiste $" + (int) premio
                        : "💀 Perdiste. " + partida.ganadorNombre() + " ganó la partida.";

                JsonObject resultData = new JsonObject();
                resultData.addProperty("ganador", partida.ganadorNombre());
                resultData.addProperty("premio", premio);
                resultData.addProperty("esGanador", esGanador);
                resultData.addProperty("mensaje", mensaje);
                notificador.emitirA(jugadorId, "resultado_api", resultData);

                notificador.enviarPush(jugadorId, esGanador ? "🏆 ¡Ganaste!" : "💀 Resultado", mensaje);
            } catch (Exception e) {
                logger.error("Error avisando el resultado al jugador {} (match #{}): {}",
                        jugadorId, partida.matchId(), e.getMessage());
            }
        }
    }
}
