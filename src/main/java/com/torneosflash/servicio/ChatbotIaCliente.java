package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.torneosflash.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Cliente HTTP de la IA del chatbot (roadmap parte 20). Habla con Gemini (default) o con OpenAI según
 * {@code CHATBOT_PROVIDER}, usando solo {@code java.net.http} (sin SDKs ni dependencias nuevas).
 *
 * <ul>
 *   <li><b>Sin estado:</b> cada llamada lleva las instrucciones y la conversación completas; el contexto lo arma
 *       {@code ChatbotService}.</li>
 *   <li><b>Acotado:</b> la llamada bloquea al hilo que la pide hasta {@code CHATBOT_TIMEOUT_SEGUNDOS}; para que un
 *       pico de usuarios no se coma los hilos HTTP, solo {@code CHATBOT_MAX_CONCURRENTES} llamadas corren a la vez y
 *       las demás esperan 3 s por un cupo y luego se rechazan con un mensaje amable.</li>
 *   <li><b>Clave segura:</b> la clave va en una cabecera (nunca en la URL, así no aparece en logs ni en trazas) y nunca
 *       se escribe en el log. Los errores del proveedor solo van al log (recortados), el usuario recibe un mensaje genérico.</li>
 * </ul>
 */
public class ChatbotIaCliente {
    private static final Logger logger = LoggerFactory.getLogger(ChatbotIaCliente.class);

    public static final String ROL_USUARIO = "usuario";
    public static final String ROL_BOT = "bot";

    private static final String URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String URL_OPENAI = "https://api.openai.com/v1/chat/completions";
    /** Baja a propósito: se quiere ceñirse a la base de conocimiento, no creatividad. */
    private static final double TEMPERATURA_GEMINI = 0.3;
    private static final long ESPERA_CUPO_SEGUNDOS = 3;
    /** Intentos totales ante un fallo transitorio del proveedor (503 "alta demanda", 500, 502, 504). */
    private static final int MAX_INTENTOS = 3;
    /** Espera antes del reintento n = n x esto (1 s, luego 2 s). */
    private static final long ESPERA_REINTENTO_MS = 1000;

    /** Un mensaje de la conversación; {@code rol} es {@link #ROL_USUARIO} o {@link #ROL_BOT}. */
    public record Mensaje(String rol, String texto) {}

    /** Fallo al hablar con la IA. {@link #getMensajeUsuario()} es seguro de mostrar; el detalle técnico solo va al log. */
    public static class IaException extends Exception {
        private final String mensajeUsuario;

        public IaException(String mensajeUsuario, String detalle) {
            super(detalle);
            this.mensajeUsuario = mensajeUsuario;
        }

        public String getMensajeUsuario() {
            return mensajeUsuario;
        }
    }

    private final HttpClient http;
    private final String proveedor;
    private final String apiKey;
    private final String modelo;
    private final Duration timeout;
    private final int maxTokens;
    private final Semaphore cupos;
    private final boolean disponible;

    public ChatbotIaCliente(AppConfig config) {
        this.proveedor = config.getChatbotProveedor();
        this.apiKey = config.getChatbotApiKey();
        this.modelo = config.getChatbotModelo();
        this.timeout = Duration.ofSeconds(config.getChatbotTimeoutSegundos());
        this.maxTokens = config.getChatbotMaxTokens();
        this.cupos = new Semaphore(config.getChatbotMaxConcurrentes(), true);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.disponible = config.hasChatbot();

        if (disponible) {
            logger.info("🤖 Chatbot IA listo (proveedor: {}, modelo: {}, máx. {} llamadas simultáneas)",
                    proveedor, modelo, config.getChatbotMaxConcurrentes());
        } else {
            logger.info("⚠️ Chatbot IA desactivado: define CHATBOT_API_KEY (y CHATBOT_MODEL si el proveedor es openai)");
        }
    }

    /** false si falta la clave o el modelo: el chatbot responde "no disponible" y el resto de la app sigue igual. */
    public boolean isDisponible() {
        return disponible;
    }

    /**
     * Pide una respuesta a la IA.
     *
     * @param instrucciones instrucciones del sistema (rol, reglas, tono y base de conocimiento)
     * @param conversacion  mensajes en orden; debe empezar y terminar con un mensaje del usuario y alternar roles
     *                      (lo garantiza {@code ChatbotService}, que junta los mensajes seguidos del mismo rol)
     * @return el texto que generó la IA (puede venir vacío; quien llama decide qué hacer)
     * @throws IaException si la IA no está disponible, está saturada, falló o respondió algo ilegible
     */
    public String generar(String instrucciones, List<Mensaje> conversacion) throws IaException {
        if (!disponible) {
            throw new IaException("El asistente no está disponible en este momento.", "chatbot desactivado");
        }

        boolean cupo;
        try {
            cupo = cupos.tryAcquire(ESPERA_CUPO_SEGUNDOS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IaException("Intenta de nuevo en unos segundos.", "interrumpido esperando cupo");
        }
        if (!cupo) {
            throw fallo("El asistente está atendiendo a muchas personas. Intenta de nuevo en unos segundos.",
                    "demasiadas llamadas simultáneas a la IA");
        }

        try {
            boolean openai = "openai".equals(proveedor);
            HttpRequest peticion = openai
                    ? peticionOpenAi(instrucciones, conversacion)
                    : peticionGemini(instrucciones, conversacion);

            HttpResponse<String> respuesta = null;
            int estado = 0;
            for (int intento = 1; intento <= MAX_INTENTOS; intento++) {
                respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
                estado = respuesta.statusCode();
                if (!esTransitorio(estado) || intento == MAX_INTENTOS) break;
                long espera = ESPERA_REINTENTO_MS * intento;
                logger.warn("🤖 La IA respondió HTTP {} (intento {}/{}); reintento en {} ms", estado, intento, MAX_INTENTOS, espera);
                Thread.sleep(espera);
            }
            if (estado != 200) {
                throw fallo(mensajeParaEstado(estado), "HTTP " + estado + ": " + recortar(respuesta.body(), 400));
            }
            return openai ? extraerOpenAi(respuesta.body()) : extraerGemini(respuesta.body());
        } catch (IOException e) {
            // Incluye HttpTimeoutException (la IA tardó más que CHATBOT_TIMEOUT_SEGUNDOS)
            throw fallo("No pude comunicarme con el asistente. Intenta de nuevo en un momento.", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IaException("Intenta de nuevo en unos segundos.", "interrumpido esperando a la IA");
        } catch (IllegalArgumentException e) {
            // URI o cabecera inválida (p. ej. una clave con saltos de línea): es un error de configuración
            throw fallo("El asistente no está disponible en este momento.", "petición inválida (revisa CHATBOT_API_KEY/CHATBOT_MODEL): " + e.getMessage());
        } finally {
            cupos.release();
        }
    }

    // ------------------------------------------------------------------
    // Gemini (generateContent)
    // ------------------------------------------------------------------

    private HttpRequest peticionGemini(String instrucciones, List<Mensaje> conversacion) {
        JsonObject cuerpo = new JsonObject();
        cuerpo.add("systemInstruction", contenidoGemini(null, instrucciones));

        JsonArray contenidos = new JsonArray();
        for (Mensaje m : conversacion) {
            contenidos.add(contenidoGemini(ROL_USUARIO.equals(m.rol()) ? "user" : "model", m.texto()));
        }
        cuerpo.add("contents", contenidos);

        JsonObject generacion = new JsonObject();
        generacion.addProperty("temperature", TEMPERATURA_GEMINI);
        generacion.addProperty("maxOutputTokens", maxTokens);
        cuerpo.add("generationConfig", generacion);

        return HttpRequest.newBuilder()
                .uri(URI.create(URL_GEMINI + modelo + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build();
    }

    private static JsonObject contenidoGemini(String rol, String texto) {
        JsonObject parte = new JsonObject();
        parte.addProperty("text", texto);
        JsonArray partes = new JsonArray();
        partes.add(parte);

        JsonObject contenido = new JsonObject();
        if (rol != null) contenido.addProperty("role", rol);
        contenido.add("parts", partes);
        return contenido;
    }

    private String extraerGemini(String cuerpo) throws IaException {
        try {
            JsonObject json = JsonParser.parseString(cuerpo).getAsJsonObject();
            JsonElement candidatos = json.get("candidates");
            if (candidatos == null || !candidatos.isJsonArray() || candidatos.getAsJsonArray().size() == 0) {
                // Sin candidatos: Gemini bloqueó el mensaje (promptFeedback.blockReason) o no generó nada
                throw fallo("No pude responder a ese mensaje. Intenta reformularlo.",
                        "Gemini sin candidatos: " + recortar(cuerpo, 300));
            }

            JsonObject candidato = candidatos.getAsJsonArray().get(0).getAsJsonObject();
            JsonElement contenido = candidato.get("content");
            if (contenido == null || !contenido.isJsonObject() || !contenido.getAsJsonObject().has("parts")) {
                throw fallo("No pude responder a ese mensaje. Intenta reformularlo.",
                        "Gemini sin contenido (finishReason=" + textoDe(candidato, "finishReason") + ")");
            }

            StringBuilder texto = new StringBuilder();
            JsonElement partes = contenido.getAsJsonObject().get("parts");
            if (partes.isJsonArray()) {
                for (JsonElement parte : partes.getAsJsonArray()) {
                    if (!parte.isJsonObject()) continue;
                    JsonObject p = parte.getAsJsonObject();
                    // Si el modelo devuelve su "razonamiento" como parte aparte, no es para el usuario
                    if (p.has("thought") && p.get("thought").isJsonPrimitive() && p.get("thought").getAsBoolean()) continue;
                    if (p.has("text") && p.get("text").isJsonPrimitive()) texto.append(p.get("text").getAsString());
                }
            }
            return texto.toString();
        } catch (IllegalStateException | JsonParseException | ClassCastException e) {
            throw fallo("No pude entender la respuesta del asistente. Intenta de nuevo.", "respuesta de Gemini ilegible: " + e);
        }
    }

    // ------------------------------------------------------------------
    // OpenAI (chat completions)
    // ------------------------------------------------------------------

    private HttpRequest peticionOpenAi(String instrucciones, List<Mensaje> conversacion) {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("model", modelo);

        JsonArray mensajes = new JsonArray();
        mensajes.add(mensajeOpenAi("system", instrucciones));
        for (Mensaje m : conversacion) {
            mensajes.add(mensajeOpenAi(ROL_USUARIO.equals(m.rol()) ? "user" : "assistant", m.texto()));
        }
        cuerpo.add("messages", mensajes);
        // max_completion_tokens lo aceptan todos los modelos de chat actuales (max_tokens lo rechazan los de razonamiento)
        cuerpo.addProperty("max_completion_tokens", maxTokens);

        return HttpRequest.newBuilder()
                .uri(URI.create(URL_OPENAI))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build();
    }

    private static JsonObject mensajeOpenAi(String rol, String texto) {
        JsonObject mensaje = new JsonObject();
        mensaje.addProperty("role", rol);
        mensaje.addProperty("content", texto);
        return mensaje;
    }

    private String extraerOpenAi(String cuerpo) throws IaException {
        try {
            JsonObject json = JsonParser.parseString(cuerpo).getAsJsonObject();
            JsonElement opciones = json.get("choices");
            if (opciones == null || !opciones.isJsonArray() || opciones.getAsJsonArray().size() == 0) {
                throw fallo("No pude responder a ese mensaje. Intenta reformularlo.",
                        "OpenAI sin choices: " + recortar(cuerpo, 300));
            }
            JsonObject opcion = opciones.getAsJsonArray().get(0).getAsJsonObject();
            JsonElement mensaje = opcion.get("message");
            if (mensaje == null || !mensaje.isJsonObject()) {
                throw fallo("No pude responder a ese mensaje. Intenta reformularlo.", "OpenAI sin message");
            }
            JsonElement contenido = mensaje.getAsJsonObject().get("content");
            // content es null cuando el modelo se niega (refusal) o se queda sin tokens
            return (contenido != null && contenido.isJsonPrimitive()) ? contenido.getAsString() : "";
        } catch (IllegalStateException | JsonParseException | ClassCastException e) {
            throw fallo("No pude entender la respuesta del asistente. Intenta de nuevo.", "respuesta de OpenAI ilegible: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Registra el detalle técnico (sin claves) y devuelve la excepción con el mensaje seguro para el usuario. */
    private IaException fallo(String mensajeUsuario, String detalle) {
        logger.warn("🤖 Fallo de la IA ({} / {}): {}", proveedor, modelo, detalle);
        return new IaException(mensajeUsuario, detalle);
    }

    /** Errores del lado del proveedor que suelen pasar solos (p. ej. Gemini 503 por alta demanda). 429 no se reintenta. */
    private static boolean esTransitorio(int estado) {
        return estado == 500 || estado == 502 || estado == 503 || estado == 504;
    }

    /** 429 = el proveedor nos limita; el resto de 4xx son de configuración (clave o modelo mal) y el admin los ve en el log. */
    private static String mensajeParaEstado(int estado) {
        if (estado == 429) return "El asistente está recibiendo muchas consultas. Intenta de nuevo en un momento.";
        if (estado >= 500) return "El asistente tuvo un problema. Intenta de nuevo en un momento.";
        return "El asistente no está disponible en este momento.";
    }

    private static String textoDe(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        return (valor != null && valor.isJsonPrimitive()) ? valor.getAsString() : "?";
    }

    private static String recortar(String texto, int max) {
        if (texto == null) return "";
        String limpio = texto.replaceAll("\\s+", " ").trim();
        return limpio.length() <= max ? limpio : limpio.substring(0, max) + "…";
    }
}
