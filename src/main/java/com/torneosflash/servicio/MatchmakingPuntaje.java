package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.OptionalDouble;

/**
 * Motor puro del puntaje oculto de matchmaking justo (Prioridad 15).
 *
 * <p>Combina tres fuentes normalizadas en escala interna [0, 1000]:
 * <ol>
 *   <li>Win rate de la plataforma (peso base 0.35, omitido si {@code total_partidas == 0}).</li>
 *   <li>Liga de la plataforma (peso base 0.25, omitido mientras Ligas 12.2 no exista o el jugador no tenga liga).</li>
 *   <li>Clash Royale: copas, puntaje competitivo Path of Legends y medallas/ranking mundial
 *       (peso base 0.40, cada métrica ausente se omite mediante renormalización dinámica).</li>
 * </ol>
 * Nunca expone el valor numérico exacto a los usuarios: solo compara niveles relativos y rangos de oleada.</p>
 */
public final class MatchmakingPuntaje {

    public static final double PESO_WIN_RATE = 0.35;
    public static final double PESO_LIGA = 0.25;
    public static final double PESO_CLASH = 0.40;

    public static final double PESO_CLASH_COPAS = 0.45;
    public static final double PESO_CLASH_COMPETITIVO = 0.35;
    public static final double PESO_CLASH_MEDALLAS_MUNDIALES = 0.20;

    public static final double PUNTAJE_NEUTRO_DEFECTO = 500.0;
    public static final double CONFIANZA_PRIOR_PARTIDAS = 6.0;
    public static final double TOLERANCIA_NIVEL_SIMILAR = 15.0;

    public static final double LIMITE_GRUPO_1 = 60.0;
    public static final double LIMITE_GRUPO_2 = 130.0;
    public static final double LIMITE_GRUPO_3 = 220.0;
    public static final double LIMITE_GRUPO_4 = 330.0;
    public static final int MAX_GRUPO_RANGO = 5;

    /** Número oficial recomendado de ligas para Prioridad 12.2 (ver project-conventions.md). */
    public static final int LIGAS_RECOMENDADAS_TOTAL = 8;

    private MatchmakingPuntaje() {}

    public record DatosClash(
            Integer trophies,
            Integer bestTrophies,
            Integer polLeague,
            Integer polTrophies,
            Integer bestPolLeague,
            Integer bestPolTrophies,
            Integer polRank,
            Integer bestPolRank
    ) {
        public static DatosClash desdeJsonApi(JsonObject playerJson) {
            if (playerJson == null) return null;
            Integer trophies = intOpcional(playerJson, "trophies");
            Integer bestTrophies = maxPositivo(
                    trophies,
                    intOpcional(playerJson, "bestTrophies"),
                    intOpcional(playerJson, "legacyTrophyRoadHighScore")
            );

            JsonObject polActual = objOpcional(playerJson, "currentPathOfLegendSeasonResult");
            if (!tieneDatosPol(polActual)) {
                polActual = objOpcional(playerJson, "lastPathOfLegendSeasonResult");
            }
            JsonObject polMejor = objOpcional(playerJson, "bestPathOfLegendSeasonResult");

            Integer polLeague = intPositivoOpcional(polActual, "leagueNumber");
            Integer polTrophies = intPositivoOpcional(polActual, "trophies");
            Integer polRank = intPositivoOpcional(polActual, "rank");

            Integer bestPolLeague = intPositivoOpcional(polMejor, "leagueNumber");
            Integer bestPolTrophies = intPositivoOpcional(polMejor, "trophies");
            Integer bestPolRank = intPositivoOpcional(polMejor, "rank");

            if (trophies == null && bestTrophies == null
                    && polLeague == null && polTrophies == null && polRank == null
                    && bestPolLeague == null && bestPolTrophies == null && bestPolRank == null) {
                return null;
            }
            return new DatosClash(trophies, bestTrophies, polLeague, polTrophies,
                    bestPolLeague, bestPolTrophies, polRank, bestPolRank);
        }
    }

    public record DatosLiga(String clave, Integer nivel, Integer totalNiveles) {
        public static DatosLiga sinLiga() {
            return new DatosLiga(null, null, null);
        }
    }

    public record DatosJugador(
            int totalVictorias,
            int totalDerrotas,
            int totalPartidas,
            DatosClash clash,
            DatosLiga liga
    ) {}

    public record ResultadoPuntaje(
            double puntajeInterno,
            boolean tieneWinRate,
            boolean tieneClash,
            boolean tieneLiga,
            int variablesActivas,
            String perfilCualitativo
    ) {}

    public record ComparacionNivel(
            int grupoRango,
            int nivelesDiferencia,
            boolean esReceptorMejor,
            boolean esBuscadorMejor,
            boolean requiereConfirmacionBuscador,
            String nombreBanda,
            String etiquetaParaCandidato,
            String etiquetaParaBuscador
    ) {}

    public static ResultadoPuntaje calcular(DatosJugador datos) {
        if (datos == null) {
            return new ResultadoPuntaje(PUNTAJE_NEUTRO_DEFECTO, false, false, false, 0, "En calibración");
        }

        OptionalDouble winRate = calcularWinRateSubscore(
                datos.totalVictorias(), datos.totalDerrotas(), datos.totalPartidas());
        OptionalDouble liga = calcularLigaSubscore(datos.liga());
        OptionalDouble clash = calcularClashSubscore(datos.clash());

        double sumaPonderada = 0.0;
        double sumaPesos = 0.0;
        int activas = 0;

        if (winRate.isPresent()) {
            sumaPonderada += PESO_WIN_RATE * winRate.getAsDouble();
            sumaPesos += PESO_WIN_RATE;
            activas++;
        }
        if (liga.isPresent()) {
            sumaPonderada += PESO_LIGA * liga.getAsDouble();
            sumaPesos += PESO_LIGA;
            activas++;
        }
        if (clash.isPresent()) {
            sumaPonderada += PESO_CLASH * clash.getAsDouble();
            sumaPesos += PESO_CLASH;
            activas++;
        }

        double puntaje = sumaPesos > 0.0
                ? limitar(sumaPonderada / sumaPesos, 0.0, 1000.0)
                : PUNTAJE_NEUTRO_DEFECTO;

        return new ResultadoPuntaje(
                redondear2(puntaje),
                winRate.isPresent(),
                clash.isPresent(),
                liga.isPresent(),
                activas,
                clasificarPerfilCualitativo(puntaje, activas)
        );
    }

    /** Subpuntaje [0, 1000] de win rate con suavizado bayesiano; vacío si no tiene partidas jugadas. */
    public static OptionalDouble calcularWinRateSubscore(int victorias, int derrotas, int totalPartidas) {
        int jugadas = totalPartidas > 0
                ? totalPartidas
                : Math.max(0, victorias) + Math.max(0, derrotas);
        if (jugadas <= 0) {
            return OptionalDouble.empty();
        }
        int victoriasLimpias = Math.max(0, Math.min(victorias, jugadas));
        double tasaSuavizada = (victoriasLimpias + (0.5 * CONFIANZA_PRIOR_PARTIDAS))
                / (jugadas + CONFIANZA_PRIOR_PARTIDAS);
        return OptionalDouble.of(limitar(tasaSuavizada * 1000.0, 0.0, 1000.0));
    }

    /** Subpuntaje [0, 1000] de liga de plataforma; vacío hasta implementar Ligas 12.2 o si es null. */
    public static OptionalDouble calcularLigaSubscore(DatosLiga liga) {
        if (liga == null || liga.nivel() == null || liga.nivel() <= 0) {
            return OptionalDouble.empty();
        }
        int total = (liga.totalNiveles() != null && liga.totalNiveles() > 0)
                ? liga.totalNiveles()
                : LIGAS_RECOMENDADAS_TOTAL;
        if (total == 1) {
            return OptionalDouble.of(PUNTAJE_NEUTRO_DEFECTO);
        }
        int nivel = Math.max(1, Math.min(liga.nivel(), total));
        double subscore = ((nivel - 1.0) / (total - 1.0)) * 1000.0;
        return OptionalDouble.of(limitar(subscore, 0.0, 1000.0));
    }

    /**
     * Subpuntaje [0, 1000] de Clash Royale renormalizando entre copas, competitivo Path of Legends
     * y ranking/medallas mundiales según las métricas disponibles en el perfil.
     */
    public static OptionalDouble calcularClashSubscore(DatosClash clash) {
        if (clash == null) {
            return OptionalDouble.empty();
        }

        OptionalDouble copas = calcularSubscoreCopas(clash.trophies(), clash.bestTrophies());
        OptionalDouble competitivo = calcularSubscoreCompetitivoPol(clash);
        OptionalDouble medallasMundiales = calcularSubscoreRankingMundial(clash.polRank(), clash.bestPolRank());

        double suma = 0.0;
        double pesos = 0.0;
        if (copas.isPresent()) {
            suma += PESO_CLASH_COPAS * copas.getAsDouble();
            pesos += PESO_CLASH_COPAS;
        }
        if (competitivo.isPresent()) {
            suma += PESO_CLASH_COMPETITIVO * competitivo.getAsDouble();
            pesos += PESO_CLASH_COMPETITIVO;
        }
        if (medallasMundiales.isPresent()) {
            suma += PESO_CLASH_MEDALLAS_MUNDIALES * medallasMundiales.getAsDouble();
            pesos += PESO_CLASH_MEDALLAS_MUNDIALES;
        }
        if (pesos <= 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(limitar(suma / pesos, 0.0, 1000.0));
    }

    private static OptionalDouble calcularSubscoreCopas(Integer actual, Integer mejor) {
        if (actual == null && mejor == null) {
            return OptionalDouble.empty();
        }
        int baseActual = actual != null ? Math.max(0, actual) : Math.max(0, mejor);
        int baseMejor = mejor != null ? Math.max(baseActual, mejor) : baseActual;
        double copasEfectivas = (0.70 * baseActual) + (0.30 * baseMejor);
        // [0..9000] aporta [0..900] y [9000..15000] aporta [900..1000] para cubrir copas estacionales
        double tramoBase = limitar(copasEfectivas / 9000.0, 0.0, 1.0) * 900.0;
        double tramoAlto = copasEfectivas > 9000.0
                ? limitar((copasEfectivas - 9000.0) / 6000.0, 0.0, 1.0) * 100.0
                : 0.0;
        return OptionalDouble.of(limitar(tramoBase + tramoAlto, 0.0, 1000.0));
    }

    private static OptionalDouble calcularSubscoreCompetitivoPol(DatosClash clash) {
        OptionalDouble actual = subscoreTemporadaPol(clash.polLeague(), clash.polTrophies());
        OptionalDouble mejor = subscoreTemporadaPol(clash.bestPolLeague(), clash.bestPolTrophies());
        if (actual.isPresent() && mejor.isPresent()) {
            return OptionalDouble.of((0.70 * actual.getAsDouble()) + (0.30 * mejor.getAsDouble()));
        }
        return actual.isPresent() ? actual : mejor;
    }

    private static OptionalDouble subscoreTemporadaPol(Integer leagueNumber, Integer polTrophies) {
        boolean tieneLiga = leagueNumber != null && leagueNumber > 0;
        boolean tieneMedallasUc = polTrophies != null && polTrophies > 0;
        if (!tieneLiga && !tieneMedallasUc) {
            return OptionalDouble.empty();
        }
        int ligaEfectiva = tieneLiga ? Math.max(1, Math.min(leagueNumber, 10)) : 10;
        double baseLiga = (ligaEfectiva / 10.0) * 700.0;
        double bonusMedallas = tieneMedallasUc
                ? limitar(polTrophies / 3200.0, 0.0, 1.0) * 300.0
                : 0.0;
        return OptionalDouble.of(limitar(baseLiga + bonusMedallas, 0.0, 1000.0));
    }

    private static OptionalDouble calcularSubscoreRankingMundial(Integer rankActual, Integer rankMejor) {
        Integer rank = minPositivo(rankActual, rankMejor);
        if (rank == null || rank <= 0) {
            return OptionalDouble.empty();
        }
        if (rank > 10000) {
            return OptionalDouble.of(680.0);
        }
        double subscore = 1000.0 - (250.0 * (Math.log10(rank) / 4.0));
        return OptionalDouble.of(limitar(subscore, 750.0, 1000.0));
    }

    public static boolean sonNivelSimilar(double puntajeA, double puntajeB) {
        return Math.abs(puntajeA - puntajeB) <= TOLERANCIA_NIVEL_SIMILAR;
    }

    /**
     * Grupo de oleada para {@code candidato} respecto al {@code buscador}:
     * {@code 0} si tiene igual o menor puntaje (notificación inmediata);
     * {@code 1..5} según qué tan por encima esté del buscador.
     */
    public static int calcularGrupoRango(double puntajeCandidato, double puntajeBuscador) {
        double diferencia = puntajeCandidato - puntajeBuscador;
        if (diferencia <= TOLERANCIA_NIVEL_SIMILAR) return 0;
        return bandaPorDiferenciaPositiva(diferencia);
    }

    public static ComparacionNivel comparar(
            double puntajeCandidato,
            double puntajeBuscador,
            String usernameCandidato,
            String usernameBuscador) {

        double diferencia = puntajeCandidato - puntajeBuscador;
        int grupo = calcularGrupoRango(puntajeCandidato, puntajeBuscador);
        boolean receptorMejor = diferencia > TOLERANCIA_NIVEL_SIMILAR;
        boolean buscadorMejor = diferencia < -TOLERANCIA_NIVEL_SIMILAR;

        int niveles = receptorMejor
                ? grupo
                : (buscadorMejor ? bandaPorDiferenciaPositiva(-diferencia) : 0);
        String banda = nombreBanda(niveles);
        String nombreCand = (usernameCandidato == null || usernameCandidato.isBlank()) ? "Este jugador" : usernameCandidato;
        String nombreBusc = (usernameBuscador == null || usernameBuscador.isBlank()) ? "El rival" : usernameBuscador;

        String paraCandidato;
        String paraBuscador;
        if (receptorMejor) {
            paraCandidato = "Estás " + textoNiveles(niveles) + " por encima de " + nombreBusc + " (" + banda + ")";
            paraBuscador = nombreCand + " está " + textoNiveles(niveles) + " por encima de ti (" + banda + ")";
        } else if (buscadorMejor) {
            paraCandidato = nombreBusc + " está " + textoNiveles(niveles) + " por encima de ti (" + banda + ")";
            paraBuscador = "Estás " + textoNiveles(niveles) + " por encima de " + nombreCand + " (" + banda + ")";
        } else {
            paraCandidato = "Nivel competitivo similar al tuyo";
            paraBuscador = "Nivel competitivo similar al tuyo";
        }

        return new ComparacionNivel(
                grupo,
                niveles,
                receptorMejor,
                buscadorMejor,
                receptorMejor,
                banda,
                paraCandidato,
                paraBuscador
        );
    }

    /**
     * Segundo de la búsqueda en el que se libera la oleada del {@code grupoRango} (0..5),
     * garantizando que la última oleada (incluyendo al jugador más fuerte) llegue al menos
     * {@code margenFinalSegundos} (2 min por defecto sobre 10 min) antes del timeout total.
     */
    public static long calcularSegundosOleada(int grupoRango, int duracionTotalSegundos, int margenFinalSegundos) {
        if (grupoRango <= 0 || duracionTotalSegundos <= 0) {
            return 0L;
        }
        int grupoAcotado = Math.min(grupoRango, MAX_GRUPO_RANGO);
        int margenEfectivo = calcularMargenEfectivo(duracionTotalSegundos, margenFinalSegundos);
        int ventanaSegundos = Math.max(1, duracionTotalSegundos - margenEfectivo);
        return Math.round(ventanaSegundos * (grupoAcotado / (double) MAX_GRUPO_RANGO));
    }

    /** Máximo grupo (0..5) que ya debe haberse liberado tras {@code segundosTranscurridos}. */
    public static int grupoMaximoPorTranscurrido(long segundosTranscurridos, int duracionTotalSegundos, int margenFinalSegundos) {
        int maxGrupo = 0;
        long transcurrido = Math.max(0L, segundosTranscurridos);
        for (int g = 1; g <= MAX_GRUPO_RANGO; g++) {
            if (transcurrido >= calcularSegundosOleada(g, duracionTotalSegundos, margenFinalSegundos)) {
                maxGrupo = g;
            }
        }
        return maxGrupo;
    }

    public static JsonArray describirRangosOleadas(int duracionTotalSegundos, int margenFinalSegundos) {
        JsonArray lista = new JsonArray();
        JsonObject g0 = new JsonObject();
        g0.addProperty("grupo", 0);
        g0.addProperty("nombre", "Igual o menor nivel");
        g0.addProperty("segundos", 0);
        lista.add(g0);
        for (int g = 1; g <= MAX_GRUPO_RANGO; g++) {
            JsonObject item = new JsonObject();
            item.addProperty("grupo", g);
            item.addProperty("nombre", "+" + textoNiveles(g) + " (" + nombreBanda(g) + ")");
            item.addProperty("segundos", calcularSegundosOleada(g, duracionTotalSegundos, margenFinalSegundos));
            lista.add(item);
        }
        return lista;
    }

    public static String nombreBanda(int niveles) {
        return switch (niveles) {
            case 0 -> "Nivel similar";
            case 1 -> "Ligeramente por encima";
            case 2 -> "Por encima";
            case 3 -> "Superior";
            case 4 -> "Muy superior";
            default -> "Élite muy por encima";
        };
    }

    private static int bandaPorDiferenciaPositiva(double diferenciaPositiva) {
        if (diferenciaPositiva <= LIMITE_GRUPO_1) return 1;
        if (diferenciaPositiva <= LIMITE_GRUPO_2) return 2;
        if (diferenciaPositiva <= LIMITE_GRUPO_3) return 3;
        if (diferenciaPositiva <= LIMITE_GRUPO_4) return 4;
        return 5;
    }

    private static int calcularMargenEfectivo(int duracionTotalSegundos, int margenFinalSegundos) {
        if (duracionTotalSegundos <= 1) return 0;
        int margenSeguro = Math.max(0, margenFinalSegundos);
        if (duracionTotalSegundos <= margenSeguro) {
            return Math.max(1, (int) Math.round(duracionTotalSegundos * 0.20));
        }
        return margenSeguro;
    }

    private static String clasificarPerfilCualitativo(double puntaje, int variablesActivas) {
        if (variablesActivas <= 0) return "En calibración";
        if (puntaje >= 800.0) return "Élite";
        if (puntaje >= 650.0) return "Avanzado";
        if (puntaje >= 480.0) return "Competitivo";
        if (puntaje >= 300.0) return "Intermedio";
        return "Inicial";
    }

    private static String textoNiveles(int niveles) {
        return niveles == 1 ? "1 rango" : niveles + " rangos";
    }

    private static boolean tieneDatosPol(JsonObject obj) {
        return obj != null && (intPositivoOpcional(obj, "leagueNumber") != null
                || intPositivoOpcional(obj, "trophies") != null
                || intPositivoOpcional(obj, "rank") != null);
    }

    private static JsonObject objOpcional(JsonObject padre, String campo) {
        if (padre == null || !padre.has(campo) || !padre.get(campo).isJsonObject()) return null;
        return padre.getAsJsonObject(campo);
    }

    private static Integer intOpcional(JsonObject obj, String campo) {
        if (obj == null || !obj.has(campo) || obj.get(campo).isJsonNull()) return null;
        try {
            return obj.get(campo).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer intPositivoOpcional(JsonObject obj, String campo) {
        Integer valor = intOpcional(obj, campo);
        return (valor != null && valor > 0) ? valor : null;
    }

    private static Integer maxPositivo(Integer... valores) {
        Integer max = null;
        for (Integer v : valores) {
            if (v != null && v >= 0 && (max == null || v > max)) max = v;
        }
        return max;
    }

    private static Integer minPositivo(Integer... valores) {
        Integer min = null;
        for (Integer v : valores) {
            if (v != null && v > 0 && (min == null || v < min)) min = v;
        }
        return min;
    }

    private static double limitar(double valor, double min, double max) {
        return Math.max(min, Math.min(max, valor));
    }

    private static double redondear2(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}

