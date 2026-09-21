package com.torneosflash.servicio;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio para enviar notificaciones push via FCM HTTP v1 API.
 * Usa Google Auth Library para obtener tokens OAuth2 y java.net.http para las peticiones.
 * Soporta envío a usuarios específicos y broadcast a todos los tokens registrados.
 */
public class NotificacionPushServicio {

    private GoogleCredentials credentials;
    private final String projectId;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Gson gson = new Gson();
    private final String siteUrl;
    private boolean disponible = false;

    public NotificacionPushServicio(String serviceAccountJson, String siteUrl) {
        this.siteUrl = siteUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.executor = Executors.newFixedThreadPool(4);

        if (serviceAccountJson == null || serviceAccountJson.isEmpty()) {
            System.out.println("⚠️ FIREBASE_SERVICE_ACCOUNT no configurado. Push notifications deshabilitadas.");
            this.projectId = "";
            return;
        }

        try {
            JsonObject json = gson.fromJson(serviceAccountJson, JsonObject.class);
            this.projectId = json.get("project_id").getAsString();

            this.credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))
            ).createScoped("https://www.googleapis.com/auth/firebase.messaging");

            this.disponible = true;
            System.out.println("🔔 FCM Web Push inicializado (proyecto: " + projectId + ")");
        } catch (Exception e) {
            System.err.println("❌ Error inicializando Firebase credentials: " + e.getMessage());
            throw new RuntimeException("No se pudo inicializar Firebase", e);
        }
    }

    public boolean isDisponible() {
        return disponible;
    }

    private String getAccessToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            System.err.println("🔔 Error obteniendo access token FCM: " + e.getMessage());
            return null;
        }
    }

    /**
     * Envía push notification a un usuario específico (todos sus tokens).
     */
    public void enviarPush(GenericDAO db, int userId, String titulo, String body) {
        if (!disponible) return;
        executor.submit(() -> {
            try {
                ArrayList<JsonObject> tokens = db.query(
                        "SELECT fcm_token FROM user_tokens WHERE user_id = ?", userId);
                if (tokens.isEmpty()) return;
                String accessToken = getAccessToken();
                if (accessToken == null) return;
                for (JsonObject row : tokens) {
                    String token = row.get("fcm_token").getAsString();
                    enviarFCM(db, accessToken, token, titulo, body);
                }
            } catch (Exception e) {
                System.err.println("🔔 Error enviando push a usuario " + userId + ": " + e.getMessage());
            }
        });
    }

    /**
     * Envía push notification a TODOS los usuarios registrados excepto los excluidos.
     * Útil para eventos broadcast (nuevo sorteo, alguien buscando, mensajes de chat).
     */
    public void enviarPushATodos(GenericDAO db, Set<Integer> excluirUserIds, String titulo, String body) {
        if (!disponible) return;
        executor.submit(() -> {
            try {
                ArrayList<JsonObject> tokens = db.query("SELECT user_id, fcm_token FROM user_tokens");
                if (tokens.isEmpty()) return;
                String accessToken = getAccessToken();
                if (accessToken == null) return;
                for (JsonObject row : tokens) {
                    int uid = row.get("user_id").getAsNumber().intValue();
                    if (excluirUserIds.contains(uid)) continue;
                    String token = row.get("fcm_token").getAsString();
                    enviarFCM(db, accessToken, token, titulo, body);
                }
            } catch (Exception e) {
                System.err.println("🔔 Error enviando push broadcast: " + e.getMessage());
            }
        });
    }

    /**
     * Envía un mensaje FCM a un token específico usando la HTTP v1 API.
     * Si el token es inválido (404/410), lo elimina de la base de datos.
     */
    private void enviarFCM(GenericDAO db, String accessToken, String fcmToken, String titulo, String body) {
        try {
            JsonObject message = new JsonObject();
            JsonObject msg = new JsonObject();
            msg.addProperty("token", fcmToken);

            // Notification payload (se muestra automáticamente en background)
            JsonObject notification = new JsonObject();
            notification.addProperty("title", titulo);
            notification.addProperty("body", body);
            msg.add("notification", notification);

            // Web Push config
            JsonObject webpush = new JsonObject();
            JsonObject fcmOptions = new JsonObject();
            fcmOptions.addProperty("link", siteUrl);
            webpush.add("fcm_options", fcmOptions);

            JsonObject webNotif = new JsonObject();
            webNotif.addProperty("icon", siteUrl + "/icon-192.png");
            webNotif.addProperty("badge", siteUrl + "/icon-192.png");
            webpush.add("notification", webNotif);

            msg.add("webpush", webpush);
            message.add("message", msg);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(message)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Éxito silencioso (no spam de logs)
            } else if (response.statusCode() == 404 || response.statusCode() == 410) {
                // Token inválido o expirado - eliminar de la BD
                System.out.println("🔔 Token FCM inválido, eliminando...");
                db.update("DELETE FROM user_tokens WHERE fcm_token = ?", fcmToken);
            } else {
                System.err.println("🔔 Error FCM (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("🔔 Error enviando FCM: " + e.getMessage());
        }
    }
}
