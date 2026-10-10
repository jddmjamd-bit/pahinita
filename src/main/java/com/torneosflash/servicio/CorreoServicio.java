package com.torneosflash.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.torneosflash.config.Ejecutores;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Servicio de envío de correos electrónicos via Brevo HTTP API.
 * Usa HTTPS (puerto 443) en vez de SMTP (puerto 587),
 * lo cual funciona en Render y otros hostings que bloquean SMTP.
 *
 * A8: el envío corre en el pool de IO ({@link Ejecutores}); antes se creaba un hilo nuevo por cada correo
 * (sin límite) y la petición no tenía timeout, así que un Brevo caído acumulaba hilos colgados.
 */
public class CorreoServicio {
    private static final Logger logger = LoggerFactory.getLogger(CorreoServicio.class);

    /** Tiempo máximo de una llamada a Brevo (A8: sin tope, un hilo del pool de IO quedaría ocupado para siempre). */
    private static final Duration TIMEOUT_PETICION = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final Ejecutores ejecutores;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;
    private boolean habilitado;

    public CorreoServicio(String brevoApiKey, String senderEmail, Ejecutores ejecutores) {
        this.apiKey = brevoApiKey;
        this.senderEmail = senderEmail;
        this.senderName = "Torneos Flash Bot";
        this.ejecutores = ejecutores;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.habilitado = brevoApiKey != null && !brevoApiKey.isEmpty() &&
                          senderEmail != null && !senderEmail.isEmpty();

        if (habilitado) {
            logger.info("✅ Servicio de correo (Brevo API) configurado");
        } else {
            logger.info("⚠️ Correo no configurado (faltan BREVO_API_KEY/BREVO_SENDER_EMAIL)");
        }
    }

    /**
     * Envía un correo electrónico via Brevo HTTP API (en segundo plano, no bloquea al que llama).
     */
    public void enviar(String to, String subject, String body) {
        if (!habilitado) return;

        ejecutores.ejecutarIO("correo a " + to, () -> {
            try {
                JsonObject payload = new JsonObject();

                // Remitente
                JsonObject sender = new JsonObject();
                sender.addProperty("name", senderName);
                sender.addProperty("email", senderEmail);
                payload.add("sender", sender);

                // Destinatario(s)
                JsonArray toArray = new JsonArray();
                JsonObject recipient = new JsonObject();
                recipient.addProperty("email", to);
                toArray.add(recipient);
                payload.add("to", toArray);

                // Contenido
                payload.addProperty("subject", subject);
                payload.addProperty("textContent", body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                        .timeout(TIMEOUT_PETICION)
                        .header("api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 201) {
                    logger.info("📧 Correo enviado a " + to);
                } else {
                    logger.error("📧 Error enviando correo (" + response.statusCode() + "): " + response.body());
                }
            } catch (Exception e) {
                logger.error("Error enviando correo: " + e.getMessage());
            }
        });
    }

    /**
     * Envía notificación al admin (al email configurado como remitente).
     */
    public void notificarAdmin(String asunto, String detalle) {
        enviar(senderEmail, "🔔 " + asunto, detalle);
    }

    public boolean isHabilitado() { return habilitado; }
}
