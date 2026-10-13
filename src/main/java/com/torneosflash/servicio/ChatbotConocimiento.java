package com.torneosflash.servicio;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lógica pura (sin BD ni red) de la base de conocimiento del chatbot (roadmap parte 20).
 *
 * Hace dos cosas:
 *  1. {@link #normalizar(String)}: deja una pregunta en forma canónica (minúsculas, sin tildes ni signos) para
 *     detectar la misma duda escrita de formas parecidas ("¿Cómo retiro?" == "como retiro").
 *  2. {@link #relevantes(String, List)}: elige qué entradas de la base se le pasan a la IA con cada mensaje.
 *     Con pocas entradas se pasan todas (así la IA nunca se pierde una respuesta por un mal ranking); con muchas
 *     se eligen las que más palabras clave comparten con la pregunta.
 *
 * La comparación usa "raíces" de palabra (sin la -s final y recortadas a 5 letras) para que retiro / retiros /
 * retirar / retirada cuenten como lo mismo. No es búsqueda semántica: es lo bastante buena para una base de
 * decenas o cientos de respuestas cortas y no necesita ni una librería ni otra llamada a la IA.
 */
public final class ChatbotConocimiento {

    /** Con hasta tantas entradas respondidas se envían todas a la IA; con más, solo las más relevantes. */
    static final int TODAS_HASTA = 15;
    /** Máximo de entradas relevantes que se envían cuando la base es grande. */
    static final int MAX_RELEVANTES = 8;

    /** Palabras tan comunes que no sirven para saber de qué trata una pregunta (ya normalizadas, sin tildes). */
    private static final Set<String> PALABRAS_VACIAS = Set.copyOf(Arrays.asList(
            "que", "como", "cual", "cuales", "cuando", "donde", "quien", "quienes", "para", "por", "con", "sin",
            "una", "uno", "unos", "unas", "del", "los", "las", "les", "mis", "tus", "sus", "este", "esta", "estos",
            "estas", "esto", "ese", "esa", "eso", "hay", "muy", "mas", "pero", "porque", "puedo", "puede", "pueden",
            "hacer", "tengo", "tiene", "tienen", "son", "ser", "soy", "estoy", "estan", "algo", "todo", "toda",
            "todos", "todas", "sobre", "desde", "hasta", "entre", "cuanto", "cuanta", "cuantos", "cuantas", "hola",
            "gracias", "favor", "quiero", "saber", "necesito", "buenas", "buenos", "dias", "tardes", "noches",
            "tambien", "aqui", "alli", "dime", "decir", "pagina", "plataforma", "app", "aplicacion"));

    private ChatbotConocimiento() {}

    /** Una entrada respondida de la base de conocimiento. */
    public record Entrada(int id, String pregunta, String respuesta) {}

    /** Minúsculas, sin tildes y solo letras/números separados por un espacio. Nunca devuelve null. */
    public static String normalizar(String texto) {
        if (texto == null) return "";
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Raíces de las palabras con significado del texto (ver la descripción de la clase). */
    static Set<String> claves(String texto) {
        Set<String> claves = new HashSet<>();
        for (String palabra : normalizar(texto).split(" ")) {
            if (palabra.length() < 3 || PALABRAS_VACIAS.contains(palabra)) continue;
            String raiz = palabra;
            if (raiz.length() > 3 && raiz.endsWith("s")) raiz = raiz.substring(0, raiz.length() - 1);
            if (raiz.length() > 5) raiz = raiz.substring(0, 5);
            claves.add(raiz);
        }
        return claves;
    }

    /**
     * Elige las entradas que se le pasan a la IA para responder {@code pregunta}.
     *
     * @param todas todas las entradas respondidas (de la más reciente a la más antigua)
     * @return todas si son pocas; si no, hasta {@link #MAX_RELEVANTES} con palabras clave en común (la pregunta de la
     *         entrada pesa el doble que su respuesta); lista vacía si ninguna coincide
     */
    public static List<Entrada> relevantes(String pregunta, List<Entrada> todas) {
        if (todas == null || todas.isEmpty()) return List.of();
        if (todas.size() <= TODAS_HASTA) return List.copyOf(todas);

        Set<String> buscadas = claves(pregunta);
        if (buscadas.isEmpty()) return List.of();

        record Puntuada(Entrada entrada, int puntos) {}
        List<Puntuada> puntuadas = new ArrayList<>();
        for (Entrada entrada : todas) {
            int puntos = 2 * coincidencias(buscadas, claves(entrada.pregunta()))
                    + coincidencias(buscadas, claves(entrada.respuesta()));
            if (puntos > 0) puntuadas.add(new Puntuada(entrada, puntos));
        }
        // Más puntos primero; a igualdad, la más reciente (id mayor)
        puntuadas.sort((a, b) -> {
            if (a.puntos() != b.puntos()) return Integer.compare(b.puntos(), a.puntos());
            return Integer.compare(b.entrada().id(), a.entrada().id());
        });

        List<Entrada> resultado = new ArrayList<>();
        for (Puntuada p : puntuadas) {
            if (resultado.size() >= MAX_RELEVANTES) break;
            resultado.add(p.entrada());
        }
        return resultado;
    }

    private static int coincidencias(Set<String> buscadas, Set<String> claves) {
        int n = 0;
        for (String clave : buscadas) {
            if (claves.contains(clave)) n++;
        }
        return n;
    }
}
