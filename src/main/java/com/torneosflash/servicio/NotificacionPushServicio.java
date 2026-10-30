package com.torneosflash.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.GenericDAO;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servicio para enviar notificaciones push via FCM HTTP v1 API.
 * Usa Google Auth Library para obtener tokens OAuth2 y java.net.http para las peticiones.
 * Soporta envío a usuarios específicos y broadcast paginado a todos los usuarios registrados.
 */
public class NotificacionPushServicio {
    private static final Logger logger = LoggerFactory.getLogger(NotificacionPushServicio.class);


    private GoogleCredentials credentials;
    private final String projectId;
    private final HttpClient httpClient;
    private final Ejecutores ejecutores;
    private final Gson gson = new Gson();
    private final String siteUrl;
    private final int maxPushPorUsuarioPorMin;
    private final ConcurrentHashMap<Integer, VentanaPush> ventanasPorUsuario = new ConcurrentHashMap<>();
    private final AtomicLong ultimaLimpiezaVentanas = new AtomicLong();
    private boolean disponible = false;

    /** Tiempo máximo de una llamada a FCM. */
    private static final Duration TIMEOUT_PETICION = Duration.ofSeconds(15);
    private static final int PAGINA_BROADCAST = 100;
    private static final int MAX_TOKENS_POR_USUARIO = 10;
    private static final long VENTANA_RATE_LIMIT_NANOS = Duration.ofMinutes(1).toNanos();

    public NotificacionPushServicio(String serviceAccountJson, String siteUrl, Ejecutores ejecutores) {
        this.siteUrl = siteUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.ejecutores = ejecutores;
        this.maxPushPorUsuarioPorMin = ejecutores.getPushMaxPorUsuarioPorMin();

        if (serviceAccountJson == null || serviceAccountJson.isEmpty()) {
            logger.info("⚠️ FIREBASE_SERVICE_ACCOUNT no configurado. Push notifications deshabilitadas.");
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
            logger.info("🔔 FCM Web Push inicializado (proyecto: " + projectId + ")");
        } catch (Exception e) {
            logger.error("❌ Error inicializando Firebase credentials: " + e.getMessage());
            throw new RuntimeException("No se pudo inicializar Firebase", e);
        }
    }

    public boolean isDisponible() {
        return disponible;
    }

    private synchronized String getAccessToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            logger.error("🔔 Error obteniendo access token FCM: " + e.getMessage());
            return null;
        }
    }

    /**
     * Envía push notification a un usuario específico (todos sus tokens).
     */
    public void enviarPush(GenericDAO db, int userId, String titulo, String body) {
        if (!disponible || userId <= 0) return;
        enviarPush(db, userId, titulo, body, siteUrl, "", "");
    }

    /** Push de una notificación del centro, con ruta y etiqueta para el service worker web. */
    public void enviarPush(GenericDAO db, int userId, String titulo, String body,
                           String link, String tag, String notificationId) {
        if (!disponible || userId <= 0) return;
        encolarPushUsuario(db, userId, titulo, body, link, tag, notificationId,
                "push a usuario " + userId);
    }

    /** Mensaje de control sin aviso visible: el service worker cierra una push efímera, como búsqueda cancelada. */
    public void enviarControlPush(GenericDAO db, int userId, String tag) {
        if (!disponible || userId <= 0) return;
        encolarPushUsuario(db, userId, "", "", "", tag, "", "cerrar push a usuario " + userId);
    }

    /**
     * Envía push notification a TODOS los usuarios registrados excepto los excluidos.
     * Útil para eventos broadcast (nuevo sorteo, alguien buscando, mensajes de chat).
     */
    public void enviarPushATodos(GenericDAO db, Set<Integer> excluirUserIds, String titulo, String body) {
        if (!disponible) return;
        Set<Integer> excluidos = excluirUserIds == null || excluirUserIds.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new HashSet<>(excluirUserIds));
        ejecutores.ejecutarPush("push broadcast", () -> encolarDestinatariosBroadcast(db, excluidos, titulo, body));
    }

    /**
     * Recorre destinatarios por páginas pequeñas y encola una tarea por usuario.
     * Así el broadcast nunca conserva todos los tokens ni hace las llamadas FCM en un solo hilo.
     */
    private void encolarDestinatariosBroadcast(GenericDAO db, Set<Integer> excluidos, String titulo, String body) {
        int despuesDeUserId = 0;
        try {
            while (true) {
                ArrayList<JsonObject> destinatarios = db.query(
                        "SELECT user_id FROM user_tokens WHERE user_id IS NOT NULL " +
                                "AND fcm_token IS NOT NULL AND fcm_token <> '' AND user_id > ? " +
                                "GROUP BY user_id ORDER BY user_id LIMIT ?",
                        despuesDeUserId, PAGINA_BROADCAST);
                if (destinatarios.isEmpty()) return;

                for (JsonObject destinatario : destinatarios) {
                    int userId = destinatario.get("user_id").getAsInt();
                    despuesDeUserId = Math.max(despuesDeUserId, userId);
                    if (excluidos.contains(userId)) continue;
                    if (!encolarPushUsuario(db, userId, titulo, body, siteUrl, "", "",
                            "push broadcast a usuario " + userId)) {
                        logger.warn("🔔 Broadcast FCM parcial: se detuvo al llenarse la cola de push.");
                        return;
                    }
                }
                if (destinatarios.size() < PAGINA_BROADCAST) return;
            }
        } catch (Exception e) {
            logger.error("🔔 Error preparando push broadcast: {}", e.getMessage(), e);
        }
    }

    private boolean encolarPushUsuario(GenericDAO db, int userId, String titulo, String body,
                                       String link, String tag, String notificationId, String descripcion) {
        return ejecutores.ejecutarPush(descripcion,
                () -> enviarPushUsuario(db, userId, titulo, body, link, tag, notificationId));
    }

    private void enviarPushUsuario(GenericDAO db, int userId, String titulo, String body,
                                   String link, String tag, String notificationId) {
        try {
            ArrayList<JsonObject> tokens = db.query(
                    "SELECT fcm_token FROM user_tokens WHERE user_id = ? " +
                            "AND fcm_token IS NOT NULL AND fcm_token <> '' ORDER BY id DESC LIMIT ?",
                    userId, MAX_TOKENS_POR_USUARIO);
            boolean mensajeDeControl = titulo == null || titulo.isBlank();
            if (tokens.isEmpty() || (!mensajeDeControl && !permitirPushUsuario(userId))) return;
            String accessToken = getAccessToken();
            if (accessToken == null) return;
            for (JsonObject row : tokens) {
                enviarFCM(db, accessToken, row.get("fcm_token").getAsString(), titulo, body,
                        link, tag, notificationId);
            }
        } catch (Exception e) {
            logger.error("🔔 Error enviando push a usuario {}: {}", userId, e.getMessage(), e);
        }
    }

    /** Límite móvil por usuario, compartido entre pushes directos y broadcasts. */
    private boolean permitirPushUsuario(int userId) {
        long ahora = System.nanoTime();
        AtomicBoolean permitido = new AtomicBoolean(false);
        ventanasPorUsuario.compute(userId, (id, anterior) -> {
            if (anterior == null || ahora - anterior.inicioNanos >= VENTANA_RATE_LIMIT_NANOS) {
                permitido.set(true);
                return new VentanaPush(ahora, 1);
            }
            if (anterior.cantidad < maxPushPorUsuarioPorMin) {
                permitido.set(true);
                return new VentanaPush(anterior.inicioNanos, anterior.cantidad + 1);
            }
            return anterior;
        });
        limpiarVentanasCaducadas(ahora);
        return permitido.get();
    }

    private void limpiarVentanasCaducadas(long ahora) {
        long ultima = ultimaLimpiezaVentanas.get();
        if (ahora - ultima < VENTANA_RATE_LIMIT_NANOS || !ultimaLimpiezaVentanas.compareAndSet(ultima, ahora)) return;
        ventanasPorUsuario.entrySet().removeIf(e -> ahora - e.getValue().inicioNanos >= VENTANA_RATE_LIMIT_NANOS);
    }

    private static final class VentanaPush {
        private final long inicioNanos;
        private final int cantidad;

        private VentanaPush(long inicioNanos, int cantidad) {
            this.inicioNanos = inicioNanos;
            this.cantidad = cantidad;
        }
    }

    /**
     * Envía un mensaje FCM a un token específico usando la HTTP v1 API.
     * Si el token es inválido (404/410), lo elimina de la base de datos.
     */
    private void enviarFCM(GenericDAO db, String accessToken, String fcmToken, String titulo, String body,
                           String link, String tag, String notificationId) {
        try {
            JsonObject message = new JsonObject();
            JsonObject msg = new JsonObject();
            msg.addProperty("token", fcmToken);

            JsonObject payloadDatos = new JsonObject();
            boolean cerrar = titulo == null || titulo.isBlank();
            payloadDatos.addProperty("accion", cerrar ? "cerrar" : "abrir");
            if (tag != null && !tag.isBlank()) payloadDatos.addProperty("tag", tag);
            if (notificationId != null && !notificationId.isBlank()) {
                payloadDatos.addProperty("notificationId", notificationId);
            }
            if (link != null && !link.isBlank()) payloadDatos.addProperty("link", link);
            msg.add("data", payloadDatos);

            // Web Push config
            JsonObject webpush = new JsonObject();
            if (cerrar) {
                JsonObject headers = new JsonObject();
                headers.addProperty("TTL", "60");
                webpush.add("headers", headers);
            } else {
                // El payload común mantiene la entrega nativa; en web el service worker personaliza el click.
                JsonObject notification = new JsonObject();
                notification.addProperty("title", titulo);
                notification.addProperty("body", body);
                msg.add("notification", notification);

                JsonObject fcmOptions = new JsonObject();
                fcmOptions.addProperty("link", link == null || link.isBlank() ? siteUrl : link);
                webpush.add("fcm_options", fcmOptions);

                JsonObject webNotif = new JsonObject();
                webNotif.addProperty("icon", siteUrl + "/icon-192.png");
                webNotif.addProperty("badge", siteUrl + "/icon-192.png");
                if (tag != null && !tag.isBlank()) webNotif.addProperty("tag", tag);
                webpush.add("notification", webNotif);
            }

            msg.add("webpush", webpush);
            message.add("message", msg);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .timeout(TIMEOUT_PETICION)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(message)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Éxito silencioso (no spam de logs)
            } else if (response.statusCode() == 404 || response.statusCode() == 410) {
                // Token inválido o expirado - eliminar de la BD
                logger.info("🔔 Token FCM inválido, eliminando...");
                db.update("DELETE FROM user_tokens WHERE fcm_token = ?", fcmToken);
            } else {
                logger.error("🔔 Error FCM (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            logger.error("🔔 Error enviando FCM: " + e.getMessage());
        }
    }
}
