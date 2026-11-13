package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchmakingPuntajeTest {

    @Test
    void alFaltarVariablesNoPenalizaYRenormalizaPesosDinamicamente() {
        var soloClashSoloCopas = new MatchmakingPuntaje.DatosJugador(
                0, 0, 0,
                new MatchmakingPuntaje.DatosClash(9000, 9000, null, null, null, null, null, null),
                MatchmakingPuntaje.DatosLiga.sinLiga()
        );
        var resSoloCopas = MatchmakingPuntaje.calcular(soloClashSoloCopas);
        assertFalse(resSoloCopas.tieneWinRate());
        assertFalse(resSoloCopas.tieneLiga());
        assertTrue(resSoloCopas.tieneClash());
        assertEquals(1, resSoloCopas.variablesActivas());
        // 9000 copas sin PoL ni medallas mundiales => 900.0 exacto sin arrastrar por las variables faltantes
        assertEquals(900.0, resSoloCopas.puntajeInterno(), 0.01);

        // Un jugador sin ninguna métrica disponible recibe puntaje neutro de calibración
        var sinDatos = MatchmakingPuntaje.calcular(new MatchmakingPuntaje.DatosJugador(0, 0, 0, null, null));
        assertEquals(MatchmakingPuntaje.PUNTAJE_NEUTRO_DEFECTO, sinDatos.puntajeInterno(), 0.01);
        assertEquals(0, sinDatos.variablesActivas());
        assertEquals("En calibración", sinDatos.perfilCualitativo());
    }

    @Test
    void ligaYWinRateYClashSeCombinanCuandoEstanDisponibles() {
        var clash = new MatchmakingPuntaje.DatosClash(9000, 9000, 10, 2400, 10, 2600, 450, 300);
        var sinLiga = MatchmakingPuntaje.calcular(new MatchmakingPuntaje.DatosJugador(18, 2, 20, clash, null));
        var conLigaTop = MatchmakingPuntaje.calcular(new MatchmakingPuntaje.DatosJugador(
                18, 2, 20, clash, new MatchmakingPuntaje.DatosLiga("leyenda", 8, MatchmakingPuntaje.LIGAS_RECOMENDADAS_TOTAL)));
        var conLigaBronce = MatchmakingPuntaje.calcular(new MatchmakingPuntaje.DatosJugador(
                18, 2, 20, clash, new MatchmakingPuntaje.DatosLiga("bronce", 1, MatchmakingPuntaje.LIGAS_RECOMENDADAS_TOTAL)));

        assertFalse(sinLiga.tieneLiga());
        assertTrue(conLigaTop.tieneLiga());
        assertTrue(conLigaTop.tieneWinRate());
        assertTrue(conLigaTop.tieneClash());
        assertEquals(3, conLigaTop.variablesActivas());
        assertTrue(conLigaTop.puntajeInterno() > sinLiga.puntajeInterno());
        assertTrue(sinLiga.puntajeInterno() > conLigaBronce.puntajeInterno());

        // Suavizado bayesiano: 1 victoria de 1 partida no dispara inmediatamente a 1000
        double wr1de1 = MatchmakingPuntaje.calcularWinRateSubscore(1, 0, 1).orElseThrow();
        double wr45de50 = MatchmakingPuntaje.calcularWinRateSubscore(45, 5, 50).orElseThrow();
        assertTrue(wr1de1 < 650.0);
        assertTrue(wr45de50 > wr1de1);
    }

    @Test
    void comparacionRelativaYGruposNoExponenValorNumerico() {
        // Candidato igual o menor => grupo 0, sin confirmación extra
        assertEquals(0, MatchmakingPuntaje.calcularGrupoRango(450.0, 500.0));
        assertEquals(0, MatchmakingPuntaje.calcularGrupoRango(515.0, 500.0));

        // Candidatos más fuertes caen progresivamente en grupos 1..5
        assertEquals(1, MatchmakingPuntaje.calcularGrupoRango(540.0, 500.0));
        assertEquals(2, MatchmakingPuntaje.calcularGrupoRango(610.0, 500.0));
        assertEquals(3, MatchmakingPuntaje.calcularGrupoRango(700.0, 500.0));
        assertEquals(4, MatchmakingPuntaje.calcularGrupoRango(800.0, 500.0));
        assertEquals(5, MatchmakingPuntaje.calcularGrupoRango(920.0, 500.0));

        var compFuerte = MatchmakingPuntaje.comparar(827.43, 412.19, "pro_rival", "novato");
        assertEquals(5, compFuerte.grupoRango());
        assertEquals(5, compFuerte.nivelesDiferencia());
        assertTrue(compFuerte.esReceptorMejor());
        assertFalse(compFuerte.esBuscadorMejor());
        assertTrue(compFuerte.requiereConfirmacionBuscador());
        // Jamás exponer el puntaje numérico exacto en las etiquetas
        assertFalse(compFuerte.etiquetaParaCandidato().contains("827"));
        assertFalse(compFuerte.etiquetaParaBuscador().contains("412"));

        var compInferior = MatchmakingPuntaje.comparar(412.19, 827.43, "novato", "pro_buscador");
        assertEquals(0, compInferior.grupoRango());
        assertEquals(5, compInferior.nivelesDiferencia());
        assertFalse(compInferior.esReceptorMejor());
        assertTrue(compInferior.esBuscadorMejor());
        assertFalse(compInferior.requiereConfirmacionBuscador());
    }

    @Test
    void oleadasCubrenDiezMinutosDejandoAlMejorDosMinutosAntesDelVencimiento() {
        int duracion = 600;
        int margen = 120;

        assertEquals(0L, MatchmakingPuntaje.calcularSegundosOleada(0, duracion, margen));
        assertEquals(96L, MatchmakingPuntaje.calcularSegundosOleada(1, duracion, margen));
        assertEquals(192L, MatchmakingPuntaje.calcularSegundosOleada(2, duracion, margen));
        assertEquals(288L, MatchmakingPuntaje.calcularSegundosOleada(3, duracion, margen));
        assertEquals(384L, MatchmakingPuntaje.calcularSegundosOleada(4, duracion, margen));
        // Grupo 5 ("el mejor mejor") llega exactamente 120s (2 minutos) antes de los 600s
        assertEquals(480L, MatchmakingPuntaje.calcularSegundosOleada(5, duracion, margen));

        assertEquals(0, MatchmakingPuntaje.grupoMaximoPorTranscurrido(0, duracion, margen));
        assertEquals(1, MatchmakingPuntaje.grupoMaximoPorTranscurrido(100, duracion, margen));
        assertEquals(2, MatchmakingPuntaje.grupoMaximoPorTranscurrido(200, duracion, margen));
        assertEquals(3, MatchmakingPuntaje.grupoMaximoPorTranscurrido(300, duracion, margen));
        assertEquals(4, MatchmakingPuntaje.grupoMaximoPorTranscurrido(400, duracion, margen));
        assertEquals(5, MatchmakingPuntaje.grupoMaximoPorTranscurrido(480, duracion, margen));

        // Timeouts cortos usan margen proporcional del 20% para nunca colapsar a 0s
        long g5Corto = MatchmakingPuntaje.calcularSegundosOleada(5, 60, 120);
        assertEquals(48L, g5Corto);
        assertEquals(6, MatchmakingPuntaje.describirRangosOleadas(duracion, margen).size());
    }

    @Test
    void parseaRespuestaDeClashApiIncluyendoLastPolFallback() {
        JsonObject json = new JsonObject();
        json.addProperty("trophies", 7500);
        json.addProperty("bestTrophies", 8200);
        JsonObject lastPol = new JsonObject();
        lastPol.addProperty("leagueNumber", 9);
        lastPol.addProperty("trophies", 1950);
        lastPol.addProperty("rank", 1200);
        json.add("lastPathOfLegendSeasonResult", lastPol);

        MatchmakingPuntaje.DatosClash datos = MatchmakingPuntaje.DatosClash.desdeJsonApi(json);
        assertNotNull(datos);
        assertEquals(7500, datos.trophies());
        assertEquals(8200, datos.bestTrophies());
        assertEquals(9, datos.polLeague());
        assertEquals(1950, datos.polTrophies());
        assertEquals(1200, datos.polRank());
    }
}
