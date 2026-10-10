package com.torneosflash.eventos;

import com.torneosflash.servicio.ComisionService;

import java.math.BigDecimal;
import java.util.List;

/**
 * Catálogo de eventos del {@link EventBus} (A4): el nombre de cada evento y la clase de sus datos.
 * Para añadir un evento nuevo: una constante aquí, un {@code record} con sus datos y, en el emisor,
 * {@code eventBus.emit(Eventos.NOMBRE, new Datos(...))}. Los nombres siguen el formato {@code entidad.accion}
 * en pasado ({@code match.finished}): el evento cuenta algo que YA ocurrió.
 */
public final class Eventos {
    private Eventos() {}

    /**
     * Una partida terminó y ya se liquidó (el dinero ya se movió y el match quedó {@code finalizada} en la BD).
     * Datos: {@link PartidaFinalizada}. Lo emiten {@code SocketHandler} (resultado automático por la API de Clash)
     * y {@code AdminService.resolverDisputa} (resultado decidido por un admin).
     */
    public static final String MATCH_FINISHED = "match.finished";

    /** Cómo se decidió el resultado de la partida. */
    public enum Origen {
        /** El sondeo a la API de Clash Royale encontró la batalla. */
        API,
        /** Un admin resolvió la disputa a mano. */
        DISPUTA
    }

    /**
     * Datos de {@link #MATCH_FINISHED}. Todos los montos son los de la liquidación ya confirmada. // REVIEW-MONEY
     *
     * @param matchId           id de la partida en la tabla {@code matches}
     * @param origen            cómo se decidió el resultado
     * @param participantes     ids de los jugadores que jugaron (ganador y perdedor; un id <= 0 significa "no
     *                          encontrado" y los listeners lo saltan). Lista inmutable
     * @param ganadorId         id del ganador
     * @param ganadorNombre     username del ganador
     * @param monto             lo que puso cada jugador
     * @param premio            lo que recibió el ganador (pozo - comisión)
     * @param nuevoSaldoGanador saldo del ganador después de cobrar el premio
     * @param desglose          comisión de la partida y su reparto por categoría ({@link ComisionService})
     */
    public record PartidaFinalizada(
            int matchId,
            Origen origen,
            List<Integer> participantes,
            int ganadorId,
            String ganadorNombre,
            BigDecimal monto,
            BigDecimal premio,
            BigDecimal nuevoSaldoGanador,
            ComisionService.Desglose desglose) {

        public PartidaFinalizada {
            participantes = List.copyOf(participantes);
        }
    }
}
