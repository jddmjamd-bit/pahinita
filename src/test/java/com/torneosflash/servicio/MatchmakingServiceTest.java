package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.socketio.SocketIOServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchmakingServiceTest {

    private static final class DaoMemoria extends GenericDAO {
        final Map<Integer, JsonObject> usuarios = new HashMap<>();
        final Map<Integer, Double> puntajes = new HashMap<>();
        final Set<String> notificadosBusqueda = new HashSet<>();
        final List<JsonObject> notificacionesCreadas = new ArrayList<>();
        final List<String> eventosEliminados = new ArrayList<>();
        final Map<Integer, Map<Integer, JsonObject>> retosPorPar = new HashMap<>();
        int maxGrupoLiberadoCola = 0;
        boolean colaActiva = true;
        int secNotif = 100;
        int secReto = 200;

        DaoMemoria() {
            super(null);
            registrarUsuario(1, "buscador", 500.0, 5, 5, 10, 4500);
            registrarUsuario(2, "rival_igual", 495.0, 5, 5, 10, 4450);
            registrarUsuario(3, "rival_menor", 280.0, 1, 9, 10, 2000);
            registrarUsuario(4, "rival_elite", 900.0, 19, 1, 20, 9000);
        }

        private void registrarUsuario(int id, String username, double score, int w, int l, int t, int trophies) {
            JsonObject u = new JsonObject();
            u.addProperty("id", id);
            u.addProperty("mp_user_id", id);
            u.addProperty("username", username);
            u.addProperty("total_victorias", w);
            u.addProperty("total_derrotas", l);
            u.addProperty("total_partidas", t);
            u.addProperty("clash_trophies", trophies);
            u.addProperty("clash_best_trophies", trophies);
            u.addProperty("puntaje_interno", score);
            usuarios.put(id, u);
            puntajes.put(id, score);
        }

        @Override
        public Connection getConnection() {
            PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "executeUpdate" -> 1;
                        case "close" -> null;
                        default -> null;
                    });
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> ps;
                        case "close" -> null;
                        default -> null;
                    });
        }

        @Override
        public JsonObject queryOne(String sql, Object... params) {
            if (sql.contains("FROM users u") && sql.contains("LEFT JOIN matchmaking_profiles") && params.length >= 1) {
                return usuarios.get((Integer) params[0]);
            }
            if (sql.contains("FROM match_queue q JOIN users u")) {
                if (!colaActiva || ((Integer) params[0]) != 1) return null;
                JsonObject q = new JsonObject();
                q.addProperty("user_id", 1);
                q.addProperty("username", "buscador");
                q.addProperty("puntaje_buscador", puntajes.get(1));
                q.addProperty("max_grupo_liberado", maxGrupoLiberadoCola);
                return q;
            }
            if (sql.contains("FROM match_queue WHERE user_id = ?")) {
                if (!colaActiva || ((Integer) params[0]) != 1) return null;
                JsonObject q = new JsonObject();
                q.addProperty("user_id", 1);
                q.addProperty("username", "buscador");
                q.addProperty("puntaje_buscador", puntajes.get(1));
                q.addProperty("max_grupo_liberado", maxGrupoLiberadoCola);
                return q;
            }
            if (sql.contains("AS ocupado FROM users")) {
                JsonObject r = new JsonObject();
                r.addProperty("ocupado", false);
                return r;
            }
            if (sql.contains("FROM matchmaking_challenges") && sql.contains("WHERE buscador_id = ? AND retador_id = ?")) {
                Map<Integer, JsonObject> delBusc = retosPorPar.get(params[0]);
                return delBusc == null ? null : delBusc.get(params[1]);
            }
            if (sql.contains("UPDATE matchmaking_challenges") && sql.contains("RETURNING id, buscador_id")) {
                String estado = (String) params[0];
                int challengeId = (Integer) params[1];
                int buscadorId = (Integer) params[2];
                Map<Integer, JsonObject> delBusc = retosPorPar.getOrDefault(buscadorId, Map.of());
                for (JsonObject r : delBusc.values()) {
                    if (r.get("id").getAsInt() == challengeId && "pendiente".equals(r.get("estado").getAsString())) {
                        r.addProperty("estado", estado);
                        return r;
                    }
                }
                return null;
            }
            if (sql.contains("FROM user_notifications WHERE id = ?")) {
                long id = ((Number) params[0]).longValue();
                for (JsonObject n : notificacionesCreadas) {
                    if (n.get("id").getAsLong() == id) return n;
                }
            }
            return null;
        }

        @Override
        public ArrayList<JsonObject> query(String sql, Object... params) {
            ArrayList<JsonObject> lista = new ArrayList<>();
            if (sql.contains("FROM users u") && sql.contains("matchmaking_search_notified")) {
                int buscadorId = (Integer) params[0];
                for (JsonObject u : usuarios.values()) {
                    int id = u.get("id").getAsInt();
                    if (id != buscadorId && !notificadosBusqueda.contains(buscadorId + ":" + id)) {
                        lista.add(u);
                    }
                }
            } else if (sql.contains("FROM match_queue q JOIN users u") && sql.contains("q.user_id <> ?")) {
                int receptorId = (Integer) params[0];
                if (colaActiva && receptorId != 1) {
                    JsonObject q = new JsonObject();
                    q.addProperty("user_id", 1);
                    q.addProperty("username", "buscador");
                    q.addProperty("max_grupo_liberado", maxGrupoLiberadoCola);
                    lista.add(q);
                }
            }
            return lista;
        }

        @Override
        public int update(String sql, Object... params) {
            if (sql.contains("UPDATE match_queue SET max_grupo_liberado")) {
                maxGrupoLiberadoCola = Math.max(maxGrupoLiberadoCola, (Integer) params[0]);
                return 1;
            }
            if (sql.contains("INSERT INTO matchmaking_search_notified")) {
                String clave = params[0] + ":" + params[1];
                return notificadosBusqueda.add(clave) ? 1 : 0;
            }
            if (sql.contains("DELETE FROM user_notifications WHERE evento_clave")) {
                eventosEliminados.add(String.valueOf(params[0]));
                return 1;
            }
            return 0;
        }

        @Override
        public int insertReturningId(String sql, Object... params) {
            if (sql.contains("INSERT INTO user_notifications")) {
                int id = ++secNotif;
                JsonObject notif = new JsonObject();
                notif.addProperty("id", id);
                notif.addProperty("user_id", (Integer) params[0]);
                notif.addProperty("categoria", (String) params[1]);
                notif.addProperty("titulo", (String) params[2]);
                notif.addProperty("mensaje", (String) params[3]);
                notif.addProperty("ruta", (String) params[4]);
                notif.addProperty("evento_clave", String.valueOf(params[5]));
                notif.addProperty("datos", String.valueOf(params[6]));
                notificacionesCreadas.add(notif);
                return id;
            }
            if (sql.contains("INSERT INTO matchmaking_challenges")) {
                int id = ++secReto;
                int buscadorId = (Integer) params[0];
                int retadorId = (Integer) params[1];
                JsonObject r = new JsonObject();
                r.addProperty("id", id);
                r.addProperty("buscador_id", buscadorId);
                r.addProperty("retador_id", retadorId);
                r.addProperty("buscador_username", (String) params[2]);
                r.addProperty("retador_username", (String) params[3]);
                r.addProperty("grupo_rango", (Integer) params[4]);
                r.addProperty("estado", "pendiente");
                r.addProperty("vigente", true);
                retosPorPar.computeIfAbsent(buscadorId, k -> new HashMap<>()).put(retadorId, r);
                return id;
            }
            return 0;
        }
    }

    @Test
    void oleadasNotificanPorRangoSinDuplicadosNiExposicionDePuntaje() {
        DaoMemoria dao = new DaoMemoria();
        NotificacionesService notif = new NotificacionesService(dao, new SocketIOServer(null), null, "");
        MatchmakingService servicio = new MatchmakingService(dao, null, notif, new AppConfig(), null);

        List<String> socketsEmitidos = new ArrayList<>();
        servicio.setPuenteSocket(new MatchmakingService.PuertoSocket() {
            @Override
            public void emitirBusquedaVisible(int receptorId, JsonObject payload) {
                socketsEmitidos.add(receptorId + ":" + payload.toString());
            }

            @Override
            public void emitirEventoUsuario(int userId, String evento, JsonObject payload) {
                socketsEmitidos.add("evt:" + userId + ":" + evento + ":" + payload.toString());
            }
        });

        // Grupo 0: solo igual o menor puntaje reciben aviso inmediato (id 2 y 3); el élite (id 4) espera
        int env0 = servicio.notificarGrupoOleada(1, "buscador", 0);
        assertEquals(2, env0);
        assertTrue(dao.notificadosBusqueda.contains("1:2"));
        assertTrue(dao.notificadosBusqueda.contains("1:3"));
        assertFalse(dao.notificadosBusqueda.contains("1:4"));

        // Cada notificación incluye canales web/app/pc y jamás expone puntajes numéricos
        assertEquals(2, dao.notificacionesCreadas.size());
        for (JsonObject n : dao.notificacionesCreadas) {
            String datosJson = n.get("datos").getAsString();
            assertTrue(datosJson.contains("\"web\""));
            assertTrue(datosJson.contains("\"app\""));
            assertTrue(datosJson.contains("\"pc\""));
            assertFalse(datosJson.contains("puntajeInterno"));
            assertFalse(datosJson.contains("puntaje_interno"));
            assertFalse(datosJson.contains("500.0"));
        }

        // Oleada final (grupo 5): ahora sí recibe el élite (id 4), sin duplicar los ya avisados
        int env5 = servicio.notificarGrupoOleada(1, "buscador", 5);
        assertEquals(1, env5);
        assertTrue(dao.notificadosBusqueda.contains("1:4"));
        assertEquals(3, dao.notificacionesCreadas.size());
    }

    @Test
    void retoDeJugadorSuperiorPideConfirmacionAlBuscadorYRespondeCorrectamente() {
        DaoMemoria dao = new DaoMemoria();
        NotificacionesService notif = new NotificacionesService(dao, new SocketIOServer(null), null, "");
        MatchmakingService servicio = new MatchmakingService(dao, null, notif, new AppConfig(), null);

        List<String> eventos = new ArrayList<>();
        servicio.setPuenteSocket(new MatchmakingService.PuertoSocket() {
            @Override
            public void emitirBusquedaVisible(int receptorId, JsonObject payload) {}

            @Override
            public void emitirEventoUsuario(int userId, String evento, JsonObject payload) {
                eventos.add(userId + ":" + evento);
            }
        });

        // Antes de liberar grupo 5, el retador élite no puede saltarse la oleada
        var bloqueado = servicio.solicitarRetoContraBuscador(4, "rival_elite", 1);
        var rej = assertInstanceOf(MatchmakingService.ResultadoSolicitudReto.Rechazado.class, bloqueado);
        assertEquals("OLEADA_NO_LIBERADA", rej.codigo());

        // Rival de nivel similar juega directo sin pedir confirmación
        var directo = servicio.solicitarRetoContraBuscador(2, "rival_igual", 1);
        assertInstanceOf(MatchmakingService.ResultadoSolicitudReto.EmpezarPartidaDirecta.class, directo);

        // Tras liberar grupo 5, el retador élite dispara pregunta de confirmación al buscador
        dao.maxGrupoLiberadoCola = 5;
        var solicitud = servicio.solicitarRetoContraBuscador(4, "rival_elite", 1);
        var esperando = assertInstanceOf(MatchmakingService.ResultadoSolicitudReto.EsperandoConfirmacion.class, solicitud);
        assertTrue(esperando.challengeId() > 0);
        assertTrue(eventos.contains("1:matchmaking_reto_recibido"));

        boolean encontroAvisoConfirmacion = dao.notificacionesCreadas.stream()
                .anyMatch(n -> n.get("user_id").getAsInt() == 1
                        && n.get("mensaje").getAsString().contains("es mejor que tú")
                        && n.get("mensaje").getAsString().contains("¿Aun así quieres jugar contra él?"));
        assertTrue(encontroAvisoConfirmacion);

        // El buscador rechaza => limpia notificación de reto y conserva libertad para seguir buscando
        var rechazo = servicio.responderRetoBuscador(1, esperando.challengeId(), false);
        assertTrue(rechazo.valido());
        assertFalse(rechazo.aceptado());
        assertFalse(dao.eventosEliminados.isEmpty());

        // Estado público del usuario: sin puntaje numérico pero con canales Web/App/PC y guía de ligas
        JsonObject estado = servicio.estadoMatchmakingUsuario(4);
        assertTrue(estado.get("puntajeOculto").getAsBoolean());
        assertEquals(8, estado.get("ligasRecomendadas").getAsInt());
        JsonArray canales = estado.getAsJsonArray("canalesNotificacion");
        assertEquals(3, canales.size());
        assertNotNull(estado.getAsJsonArray("rangosOleadas"));
        assertFalse(estado.toString().contains("puntajeInterno"));
        assertFalse(estado.toString().contains("900.0"));
    }
}
