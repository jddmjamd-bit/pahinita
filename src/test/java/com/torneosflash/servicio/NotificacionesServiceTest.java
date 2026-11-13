package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketEspia;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class NotificacionesServiceTest {

    private static final class DaoMemoria extends GenericDAO {
        private int secuenciaId = 0;
        final List<JsonObject> filas = new ArrayList<>();

        DaoMemoria() {
            super(null);
        }

        @Override
        public int insertReturningId(String sql, Object... params) {
            if (sql.contains("INSERT INTO user_notifications")) {
                int userId = (Integer) params[0];
                String eventoClave = params[5] == null ? null : String.valueOf(params[5]);
                // Replica uq_user_notifications_evento_usuario (evento_clave IS NOT NULL).
                if (eventoClave != null && filas.stream().anyMatch(f -> f.get("user_id").getAsInt() == userId
                        && f.has("evento_clave") && !f.get("evento_clave").isJsonNull()
                        && eventoClave.equals(f.get("evento_clave").getAsString()))) {
                    return 0;
                }
                int id = ++secuenciaId;
                JsonObject n = new JsonObject();
                n.addProperty("id", id);
                n.addProperty("user_id", userId);
                n.addProperty("categoria", String.valueOf(params[1]));
                n.addProperty("titulo", String.valueOf(params[2]));
                n.addProperty("mensaje", String.valueOf(params[3]));
                n.addProperty("ruta", params[4] == null ? "" : String.valueOf(params[4]));
                if (eventoClave == null) {
                    n.add("evento_clave", com.google.gson.JsonNull.INSTANCE);
                } else {
                    n.addProperty("evento_clave", eventoClave);
                }
                String datosRaw = params[6] == null ? null : String.valueOf(params[6]);
                if (datosRaw != null && !datosRaw.isBlank() && !"null".equals(datosRaw)) {
                    n.add("datos", JsonParser.parseString(datosRaw));
                }
                n.add("leida_en", com.google.gson.JsonNull.INSTANCE);
                n.add("descartada_en", com.google.gson.JsonNull.INSTANCE);
                n.addProperty("created_at", "2026-11-13T12:00:0" + Math.min(id, 9) + "Z");
                filas.add(n);
                return id;
            }
            fail("SQL no esperado en insertReturningId: " + sql);
            return 0;
        }

        @Override
        public JsonObject queryOne(String sql, Object... params) {
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains("from user_notifications") && lower.contains("count(")) {
                int userId = ((Number) params[0]).intValue();
                long total = filas.stream()
                        .filter(f -> f.get("user_id").getAsInt() == userId)
                        .filter(f -> !lower.contains("leida_en is null") || f.get("leida_en").isJsonNull())
                        .filter(f -> !(lower.contains("categoria = ?") && params.length > 1)
                                || String.valueOf(params[1]).equals(f.get("categoria").getAsString()))
                        .count();
                JsonObject res = new JsonObject();
                res.addProperty("total", total);
                return res;
            }
            if (lower.contains("from user_notifications") && lower.contains("where id = ?")) {
                long id = ((Number) params[0]).longValue();
                return filas.stream()
                        .filter(f -> f.get("id").getAsLong() == id)
                        .findFirst()
                        .map(JsonObject::deepCopy)
                        .orElse(null);
            }
            fail("SQL no esperado en queryOne: " + sql);
            return null;
        }

        @Override
        public ArrayList<JsonObject> query(String sql, Object... params) {
            String lower = sql.toLowerCase(Locale.ROOT);
            if (!lower.contains("from user_notifications") || params.length < 1) {
                fail("SQL no esperado en query: " + sql);
                return new ArrayList<>();
            }
            int userId = ((Number) params[0]).intValue();
            if (lower.contains("group by categoria")) {
                java.util.Map<String, Long> porCategoria = new java.util.LinkedHashMap<>();
                filas.stream()
                        .filter(f -> f.get("user_id").getAsInt() == userId)
                        .filter(f -> f.get("leida_en").isJsonNull() && f.get("descartada_en").isJsonNull())
                        .forEach(f -> porCategoria.merge(f.get("categoria").getAsString(), 1L, Long::sum));
                ArrayList<JsonObject> resultado = new ArrayList<>();
                porCategoria.forEach((cat, total) -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("categoria", cat);
                    row.addProperty("total", total);
                    resultado.add(row);
                });
                return resultado;
            }
            if (lower.contains("as canal, count(*) as total") && lower.contains("group by 1")) {
                java.util.Map<String, Long> porCanal = new java.util.LinkedHashMap<>();
                filas.stream()
                        .filter(f -> f.get("user_id").getAsInt() == userId)
                        .filter(f -> f.get("leida_en").isJsonNull() && f.get("descartada_en").isJsonNull())
                        .filter(f -> "chat".equals(f.get("categoria").getAsString())
                                || "/chat?canal=pagos".equals(f.get("ruta").getAsString())
                                || "pagos".equals(canalDeDatos(f)))
                        .forEach(f -> porCanal.merge(resolverCanal(f), 1L, Long::sum));
                ArrayList<JsonObject> resultado = new ArrayList<>();
                porCanal.forEach((canal, total) -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("canal", canal);
                    row.addProperty("total", total);
                    resultado.add(row);
                });
                return resultado;
            }
            ArrayList<JsonObject> resultado = new ArrayList<>();
            filas.stream()
                    .filter(f -> f.get("user_id").getAsInt() == userId)
                    .filter(f -> !lower.contains("descartada_en is null") || f.get("descartada_en").isJsonNull())
                    .filter(f -> !lower.contains("leida_en is null") || f.get("leida_en").isJsonNull())
                    .sorted(Comparator.comparingInt(f -> -f.get("id").getAsInt()))
                    .forEach(f -> resultado.add(f.deepCopy()));
            return resultado;
        }

        private String canalDeDatos(JsonObject fila) {
            if (fila.has("datos") && fila.get("datos").isJsonObject() && fila.getAsJsonObject("datos").has("canal")) {
                return fila.getAsJsonObject("datos").get("canal").getAsString();
            }
            return "";
        }

        private String resolverCanal(JsonObject fila) {
            String deDatos = canalDeDatos(fila);
            if (!deDatos.isBlank()) return deDatos;
            String ruta = fila.has("ruta") ? fila.get("ruta").getAsString() : "";
            return switch (ruta) {
                case "/chat?canal=anuncios" -> "anuncios";
                case "/chat?canal=clash" -> "clash";
                case "/chat?canal=pagos" -> "pagos";
                case "/chat?canal=clash_logs" -> "clash_logs";
                default -> "general";
            };
        }

        @Override
        public int update(String sql, Object... params) {
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains("update user_notifications") && lower.contains("leida_en")) {
                int userId = ((Number) params[0]).intValue();
                int actualizadas = 0;
                for (JsonObject fila : filas) {
                    if (fila.get("user_id").getAsInt() != userId || !fila.get("leida_en").isJsonNull()) {
                        continue;
                    }
                    if (coincideFiltroUpdateCanalOCategoria(lower, fila, params)) {
                        fila.addProperty("leida_en", "2026-11-13T12:05:00Z");
                        actualizadas++;
                    }
                }
                return actualizadas;
            }
            fail("SQL no esperado en update: " + sql);
            return 0;
        }

        private boolean coincideFiltroUpdateCanalOCategoria(String lower, JsonObject fila, Object... params) {
            String cat = fila.get("categoria").getAsString();
            String ruta = fila.has("ruta") ? fila.get("ruta").getAsString() : "";
            String datosCanal = canalDeDatos(fila);

            if (lower.contains("(categoria = 'chat' or categoria = 'finanzas')") && params.length >= 3) {
                String canalParam = String.valueOf(params[1]);
                String rutaParam = String.valueOf(params[2]);
                return ("chat".equals(cat) || "finanzas".equals(cat))
                        && (canalParam.equals(datosCanal) || rutaParam.equals(ruta));
            }
            if (lower.contains("categoria = 'chat'") && params.length >= 3) {
                if (!"chat".equals(cat)) return false;
                String canalParam = String.valueOf(params[1]);
                String rutaParam = String.valueOf(params[2]);
                if (canalParam.equals(datosCanal) || rutaParam.equals(ruta)) {
                    return true;
                }
                if (lower.contains("ruta = '/chat'")) {
                    return datosCanal.isBlank() && "/chat".equals(ruta);
                }
                return false;
            }
            if (lower.contains("categoria = ?") && params.length >= 2) {
                return String.valueOf(params[1]).equals(cat);
            }
            return false;
        }
    }

    @Test
    void conteosPorCanalDeChatYLecturaSelectivaActualizanSoloElCanalSolicitado() {
        DaoMemoria dao = new DaoMemoria();
        SocketEspia io = new SocketEspia();
        io.registrarUsuario(7);
        NotificacionesService servicio = new NotificacionesService(dao, io, null, "");

        JsonObject datosGeneral = new JsonObject();
        datosGeneral.addProperty("canal", "general");
        JsonObject datosAnuncios = new JsonObject();
        datosAnuncios.addProperty("canal", "anuncios");
        JsonObject datosClash = new JsonObject();
        datosClash.addProperty("canal", "clash");
        JsonObject datosPagos = new JsonObject();
        datosPagos.addProperty("canal", "pagos");

        assertNotNull(servicio.notificar(7, "chat", "General", "Mensaje en general", "/chat?canal=general", "", datosGeneral, false));
        assertNotNull(servicio.notificar(7, "chat", "Legado", "Ruta /chat sin query", "/chat", "", null, false));
        assertNotNull(servicio.notificar(7, "chat", "Anuncios", "Anuncio nuevo", "/chat?canal=anuncios", "", datosAnuncios, false));
        assertNotNull(servicio.notificar(7, "chat", "Clash", "Sala de clash", "/chat?canal=clash", null, datosClash, false));
        assertNotNull(servicio.notificar(7, "finanzas", "Pagos", "Comprobante", "/chat?canal=pagos", null, datosPagos, false));
        assertNotNull(servicio.notificar(7, "finanzas", "Saldo", "Recarga", "/finance", null, null, false));

        JsonObject listado = servicio.listar(7);
        JsonObject sinLeerInicial = listado.getAsJsonObject("sinLeer");
        assertNotNull(sinLeerInicial);
        assertEquals(4, sinLeerInicial.get("chat").getAsInt());
        assertEquals(2, sinLeerInicial.get("finanzas").getAsInt());
        JsonObject chatSinLeer = listado.getAsJsonObject("chatSinLeer");
        assertEquals(2, chatSinLeer.get("general").getAsInt());
        assertEquals(1, chatSinLeer.get("anuncios").getAsInt());
        assertEquals(1, chatSinLeer.get("clash").getAsInt());
        assertEquals(1, chatSinLeer.get("pagos").getAsInt());

        io.emitidos.clear();
        servicio.marcarCategoriaLeida(7, "chat", "anuncios");

        JsonObject trasAnuncios = servicio.conteosSinLeer(7);
        assertEquals(3, conteo(trasAnuncios.getAsJsonObject("sinLeer"), "chat"));
        assertEquals(2, conteo(trasAnuncios.getAsJsonObject("sinLeer"), "finanzas"));
        assertEquals(0, conteo(trasAnuncios.getAsJsonObject("chatSinLeer"), "anuncios"));
        assertEquals(2, conteo(trasAnuncios.getAsJsonObject("chatSinLeer"), "general"));
        assertEquals(1, conteo(trasAnuncios.getAsJsonObject("chatSinLeer"), "clash"));
        assertEquals(1, conteo(trasAnuncios.getAsJsonObject("chatSinLeer"), "pagos"));

        assertEquals(1, io.emitidos.size());
        assertEquals("notificaciones_leidas", io.emitidos.get(0).evento());
        assertEquals("chat", io.emitidos.get(0).payload().get("categoria").getAsString());
        assertEquals("anuncios", io.emitidos.get(0).payload().get("canal").getAsString());
        assertEquals(3, io.emitidos.get(0).payload().get("sinLeerCategoria").getAsInt());

        io.emitidos.clear();
        servicio.marcarCategoriaLeida(7, "chat", "pagos");
        JsonObject trasPagos = servicio.conteosSinLeer(7);
        assertEquals(3, conteo(trasPagos.getAsJsonObject("sinLeer"), "chat"));
        assertEquals(1, conteo(trasPagos.getAsJsonObject("sinLeer"), "finanzas"));
        assertEquals(0, conteo(trasPagos.getAsJsonObject("chatSinLeer"), "pagos"));
        assertTrue(io.emitidos.get(0).payload().has("sinLeerFinanzas"));
        assertEquals(1, io.emitidos.get(0).payload().get("sinLeerFinanzas").getAsInt());

        servicio.marcarCategoriaLeida(7, "chat", "general");
        JsonObject trasGeneral = servicio.conteosSinLeer(7);
        assertEquals(1, conteo(trasGeneral.getAsJsonObject("sinLeer"), "chat"));
        assertEquals(0, conteo(trasGeneral.getAsJsonObject("chatSinLeer"), "general"));
        assertEquals(1, conteo(trasGeneral.getAsJsonObject("chatSinLeer"), "clash"));

        // Sobrecarga de 2 argumentos mantiene limpieza total de categoría chat sin tocar /finance.
        servicio.marcarCategoriaLeida(7, "chat");
        JsonObject trasTodo = servicio.conteosSinLeer(7);
        assertEquals(0, conteo(trasTodo.getAsJsonObject("sinLeer"), "chat"));
        assertEquals(1, conteo(trasTodo.getAsJsonObject("sinLeer"), "finanzas"));
        assertEquals(0, conteo(trasTodo.getAsJsonObject("chatSinLeer"), "clash"));
    }

    @Test
    void claveEventoEnBlancoNoColisionaYClaveRealDeduplica() {
        DaoMemoria dao = new DaoMemoria();
        NotificacionesService servicio = new NotificacionesService(dao, new SocketEspia(), null, "");

        assertNotNull(servicio.notificar(9, "chat", "Aviso 1", "Primero", "/chat", "", null, false));
        assertNotNull(servicio.notificar(9, "chat", "Aviso 2", "Segundo", "/chat", "   ", null, false));
        assertNotNull(servicio.notificar(9, "ranking", "Corte 1", "Ganaste", "/leaderboard", "corte:1:9", null, false));
        assertNull(servicio.notificar(9, "ranking", "Corte 1 dup", "Duplicado", "/leaderboard", "corte:1:9", null, false));

        assertEquals(3, dao.filas.size());
    }

    private static int conteo(JsonObject mapa, String clave) {
        return mapa != null && mapa.has(clave) && !mapa.get(clave).isJsonNull()
                ? mapa.get(clave).getAsInt()
                : 0;
    }
}
