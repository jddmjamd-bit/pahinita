package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Servicio de envío de correos electrónicos via Brevo HTTP API.
 * Usa HTTPS (puerto 443) en vez de SMTP (puerto 587),
 * lo cual funciona en Render y otros hostings que bloquean SMTP.
 */
public class CorreoServicio {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;
    private boolean habilitado;

    public CorreoServicio(String brevoApiKey, String senderEmail) {
        this.apiKey = brevoApiKey;
        this.senderEmail = senderEmail;
        this.senderName = "Torneos Flash Bot";
        this.httpClient = HttpClient.newHttpClient();
        this.habilitado = brevoApiKey != null && !brevoApiKey.isEmpty() &&
                          senderEmail != null && !senderEmail.isEmpty();

        if (habilitado) {
            System.out.println("✅ Servicio de correo (Brevo API) configurado");
        } else {
            System.out.println("⚠️ Correo no configurado (faltan BREVO_API_KEY/BREVO_SENDER_EMAIL)");
        }
    }

    /**
     * Envía un correo electrónico via Brevo HTTP API.
     */
    public void enviar(String to, String subject, String body) {
        if (!habilitado) return;

        new Thread(() -> {
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
                        .header("api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 201) {
                    System.out.println("📧 Correo enviado a " + to);
                } else {
                    System.err.println("📧 Error enviando correo (" + response.statusCode() + "): " + response.body());
                }
            } catch (Exception e) {
                System.err.println("Error enviando correo: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Envía notificación al admin (al email configurado como remitente).
     */
    public void notificarAdmin(String asunto, String detalle) {
        enviar(senderEmail, "🔔 " + asunto, detalle);
    }

    public boolean isHabilitado() { return habilitado; }
}
