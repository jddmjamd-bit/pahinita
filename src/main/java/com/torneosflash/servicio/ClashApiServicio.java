package com.torneosflash.servicio;

import com.google.gson.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Servicio para consultar la API de Clash Royale.
 * Equivalente a las funciones fetchBattleLog() y findMatchingBattle() de index.js.
 */
public class ClashApiServicio {

    private final String apiToken;

    /**
     * Formatter para parsear el formato battleTime de la API de Clash Royale.
     * Formato: yyyyMMdd'T'HHmmss.SSS'Z' (ejemplo: 20260721T230000.000Z)
     */
    private static final DateTimeFormatter CLASH_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public ClashApiServicio(String apiToken) {
        this.apiToken = apiToken;
    }

    /**
     * Obtiene el battle log de un jugador.
     */
    public JsonArray fetchBattleLog(String playerTag) {
        if (apiToken.isEmpty() || playerTag == null || playerTag.isEmpty()) return null;

        String tag = playerTag.toUpperCase().replace("#", "");
        String urlStr = "https://api.clashroyale.com/v1/players/%23" + tag + "/battlelog";

        try {
            HttpURLConnection conn = (HttpURLConnection) new URI(urlStr).toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("Clash API error " + code + " para tag " + tag);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            return JsonParser.parseString(sb.toString()).getAsJsonArray();
        } catch (Exception e) {
            System.err.println("Error fetchBattleLog: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parsea el formato de tiempo de la API de Clash Royale a un Instant.
     * El formato es yyyyMMdd'T'HHmmss.SSS'Z' (ejemplo: 20260721T230000.000Z)
     * @return Instant parseado, o null si no se puede parsear
     */
    private Instant parseBattleTime(String battleTime) {
        if (battleTime == null || battleTime.isEmpty()) return null;
        try {
            return Instant.from(CLASH_TIME_FORMAT.parse(battleTime));
        } catch (Exception e) {
            System.err.println("Error parseando battleTime '" + battleTime + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Busca una batalla entre dos jugadores que haya ocurrido DESPUÉS de matchStartTime.
     * Retorna JsonObject con datos del resultado o null si no encuentra.
     *
     * @param tag1 Player tag del jugador 1
     * @param tag2 Player tag del jugador 2
     * @param matchStartTime Timestamp ISO de cuándo se inició el match en la plataforma.
     *                       Solo se aceptan batallas posteriores a este momento.
     */
    public JsonObject findMatchingBattle(String tag1, String tag2, String matchStartTime) {
        if (tag1 == null || tag2 == null) return null;

        String normalizedTag1 = tag1.toUpperCase().replace("#", "");
        String normalizedTag2 = tag2.toUpperCase().replace("#", "");

        // Parsear el timestamp de inicio del match
        Instant matchStart = null;
        if (matchStartTime != null && !matchStartTime.isEmpty()) {
            try {
                matchStart = Instant.parse(matchStartTime);
            } catch (Exception e) {
                System.err.println("Error parseando matchStartTime '" + matchStartTime + "': " + e.getMessage());
            }
        }

        JsonArray battles = fetchBattleLog(tag1);
        if (battles == null) return null;

        for (JsonElement elem : battles) {
            JsonObject battle = elem.getAsJsonObject();

            // Verificar tipo de batalla (solo 1v1)
            String type = battle.has("type") ? battle.get("type").getAsString() : "";
            if (type.contains("clanWar") || type.contains("challenge")) continue;

            // Filtrar por tiempo: solo aceptar batallas POSTERIORES al inicio del match
            if (matchStart != null) {
                String battleTimeStr = battle.has("battleTime") ? battle.get("battleTime").getAsString() : "";
                Instant battleInstant = parseBattleTime(battleTimeStr);
                if (battleInstant == null || !battleInstant.isAfter(matchStart)) {
                    continue; // Batalla anterior al inicio del match, ignorar
                }
            }

            // Obtener tags de los jugadores en la batalla
            JsonArray team = battle.getAsJsonArray("team");
            JsonArray opponent = battle.getAsJsonArray("opponent");
            if (team == null || opponent == null) continue;

            String teamTag = team.get(0).getAsJsonObject().get("tag").getAsString()
                    .replace("#", "").toUpperCase();
            String oppTag = opponent.get(0).getAsJsonObject().get("tag").getAsString()
                    .replace("#", "").toUpperCase();

            // Verificar que ambos tags coinciden
            boolean match = (teamTag.equals(normalizedTag1) && oppTag.equals(normalizedTag2)) ||
                           (teamTag.equals(normalizedTag2) && oppTag.equals(normalizedTag1));
            if (!match) continue;

            // Obtener resultado
            int teamCrowns = team.get(0).getAsJsonObject().has("crowns") ?
                    team.get(0).getAsJsonObject().get("crowns").getAsInt() : 0;
            int oppCrowns = opponent.get(0).getAsJsonObject().has("crowns") ?
                    opponent.get(0).getAsJsonObject().get("crowns").getAsInt() : 0;

            // Determinar ganador
            String winnerTag = null;
            if (teamCrowns > oppCrowns) {
                winnerTag = teamTag;
            } else if (oppCrowns > teamCrowns) {
                winnerTag = oppTag;
            }
            // Si empate, winnerTag queda null → disputa

            String battleTime = battle.has("battleTime") ? battle.get("battleTime").getAsString() : "";

            JsonObject result = new JsonObject();
            result.addProperty("teamCrowns", teamCrowns);
            result.addProperty("opponentCrowns", oppCrowns);
            result.addProperty("winnerTag", winnerTag != null ? winnerTag : "");
            result.addProperty("battleTime", battleTime);
            return result;
        }
        return null;
    }

    /**
     * Verifica si un player tag es válido consultando la API.
     * @throws Exception Si la API de Clash devuelve un error, se lanza con el detalle.
     */
    public JsonObject verificarTag(String tag) throws Exception {
        if (apiToken == null || apiToken.isEmpty()) {
            throw new Exception("CLASH_ROYALE_API_TOKEN no está configurado en el servidor");
        }
        if (tag == null || tag.isEmpty()) {
            throw new Exception("El tag proporcionado está vacío");
        }

        String cleanTag = tag.toUpperCase().replace("#", "");
        String urlStr = "https://api.clashroyale.com/v1/players/%23" + cleanTag;

        HttpURLConnection conn = (HttpURLConnection) new URI(urlStr).toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + apiToken.trim());
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        if (code != 200) {
            InputStream errorStream = conn.getErrorStream();
            if (errorStream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                throw new Exception("Error de Clash API (Código " + code + "): " + sb.toString());
            } else {
                throw new Exception("Error HTTP de Clash API (Código " + code + ")");
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        return JsonParser.parseString(sb.toString()).getAsJsonObject();
    }

    /**
     * Verifica la conexión general con la API de Clash Royale usando un endpoint estático (locations)
     * para asegurar que el token es válido y la API es accesible.
     */
    public boolean verificarConexionGlobal() {
        if (apiToken == null || apiToken.isEmpty()) {
            System.err.println("❌ ERROR: CLASH_ROYALE_API_TOKEN no está configurado.");
            return false;
        }

        try {
            // El endpoint /v1/locations no requiere parámetros y devuelve 200 OK si el auth funciona
            String urlStr = "https://api.clashroyale.com/v1/locations";
            HttpURLConnection conn = (HttpURLConnection) new URI(urlStr).toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + apiToken.trim());
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code == 200) {
                return true;
            } else {
                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    System.err.println("❌ ERROR de Clash API (Código " + code + "): " + sb.toString());
                } else {
                    System.err.println("❌ ERROR HTTP de Clash API (Código " + code + ")");
                }
                return false;
            }
        } catch (Exception e) {
            System.err.println("❌ ERROR al conectar con la API de Clash Royale: " + e.getMessage());
            return false;
        }
    }
}
