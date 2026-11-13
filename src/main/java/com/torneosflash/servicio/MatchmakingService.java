package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Orquesta el matchmaking justo (Prioridad 15):
 * <ul>
 *   <li>Mantiene {@code matchmaking_profiles} actualizado con win rate de plataforma, liga (cuando exista)
 *       y estadísticas de Clash Royale (copas, Path of Legends y ranking/medallas mundiales).</li>
 *   <li>Envía notificaciones por oleadas de rango a través de Web, App (Capacitor/FCM) y PC:
 *       primero a jugadores de puntaje igual o inferior y progresivamente a los mejores hasta
 *       {@code MATCHMAKING_MARGEN_FINAL_SEGUNDOS} (2 min) antes del timeout total.</li>
 *   <li>Gestiona la confirmación previa al buscador cuando un jugador superior solicita enfrentarlo.</li>
 * </ul>
 */
public class MatchmakingService {

    private static final Logger logger = LoggerFactory.getLogger(MatchmakingService.class);

    public interface PuertoSocket {
        void emitirBusquedaVisible(int receptorId, JsonObject payload);
        void emitirEventoUsuario(int userId, String evento, JsonObject payload);
    }

    public sealed interface ResultadoSolicitudReto permits
            ResultadoSolicitudReto.EmpezarPartidaDirecta,
            ResultadoSolicitudReto.EsperandoConfirmacion,
            ResultadoSolicitudReto.Rechazado {
        record EmpezarPartidaDirecta(
                int buscadorId,
                String buscadorUsername,
                int retadorId,
                String retadorUsername,
                MatchmakingPuntaje.ComparacionNivel comparacion
        ) implements ResultadoSolicitudReto {}

        record EsperandoConfirmacion(
                int challengeId,
                int buscadorId,
                String buscadorUsername,
                int retadorId,
                String retadorUsername,
                MatchmakingPuntaje.ComparacionNivel comparacion,
                int expiraSegundos
        ) implements ResultadoSolicitudReto {}

        record Rechazado(String codigo, String mensaje) implements ResultadoSolicitudReto {}
    }

    public record ResultadoRespuestaReto(
            boolean valido,
            boolean aceptado,
            int challengeId,
            int buscadorId,
            String buscadorUsername,
            int retadorId,
            String retadorUsername,
            String mensajeError
    ) {
        static ResultadoRespuestaReto error(String mensaje) {
            return new ResultadoRespuestaReto(false, false, 0, 0, "", 0, "", mensaje);
        }
    }

    private static final String SQL_CARGAR_USUARIO_Y_PERFIL =
            "SELECT u.id, u.username, u.player_tag, u.total_victorias, u.total_derrotas, u.total_partidas, " +
            "mp.user_id AS mp_user_id, mp.clash_trophies, mp.clash_best_trophies, " +
            "mp.clash_pol_league, mp.clash_pol_trophies, mp.clash_pol_best_league, mp.clash_pol_best_trophies, " +
            "mp.clash_pol_rank, mp.clash_pol_best_rank, mp.clash_actualizado_en, " +
            "mp.liga_clave, mp.liga_nivel, mp.liga_total_niveles, mp.puntaje_interno " +
            "FROM users u " +
            "LEFT JOIN matchmaking_profiles mp ON mp.user_id = u.id " +
            "WHERE u.id = ?";

    private static final String SQL_UPSERT_PERFIL =
            "INSERT INTO matchmaking_profiles (" +
            "  user_id, clash_trophies, clash_best_trophies, clash_pol_league, clash_pol_trophies, " +
            "  clash_pol_best_league, clash_pol_best_trophies, clash_pol_rank, clash_pol_best_rank, " +
            "  clash_actualizado_en, liga_clave, liga_nivel, liga_total_niveles, " +
            "  puntaje_interno, tiene_win_rate, tiene_clash, tiene_liga, actualizado_en" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
            "ON CONFLICT (user_id) DO UPDATE SET " +
            "  clash_trophies = EXCLUDED.clash_trophies, " +
            "  clash_best_trophies = EXCLUDED.clash_best_trophies, " +
            "  clash_pol_league = EXCLUDED.clash_pol_league, " +
            "  clash_pol_trophies = EXCLUDED.clash_pol_trophies, " +
            "  clash_pol_best_league = EXCLUDED.clash_pol_best_league, " +
            "  clash_pol_best_trophies = EXCLUDED.clash_pol_best_trophies, " +
            "  clash_pol_rank = EXCLUDED.clash_pol_rank, " +
            "  clash_pol_best_rank = EXCLUDED.clash_pol_best_rank, " +
            "  clash_actualizado_en = EXCLUDED.clash_actualizado_en, " +
            "  liga_clave = EXCLUDED.liga_clave, " +
            "  liga_nivel = EXCLUDED.liga_nivel, " +
            "  liga_total_niveles = EXCLUDED.liga_total_niveles, " +
            "  puntaje_interno = EXCLUDED.puntaje_interno, " +
            "  tiene_win_rate = EXCLUDED.tiene_win_rate, " +
            "  tiene_clash = EXCLUDED.tiene_clash, " +
            "  tiene_liga = EXCLUDED.tiene_liga, " +
            "  actualizado_en = CURRENT_TIMESTAMP";

    private static final String SQL_CANDIDATOS_OLEADA =
            "SELECT u.id, u.username, u.total_victorias, u.total_derrotas, u.total_partidas, " +
            "mp.user_id AS mp_user_id, mp.clash_trophies, mp.clash_best_trophies, " +
            "mp.clash_pol_league, mp.clash_pol_trophies, mp.clash_pol_best_league, mp.clash_pol_best_trophies, " +
            "mp.clash_pol_rank, mp.clash_pol_best_rank, mp.clash_actualizado_en, " +
            "mp.liga_clave, mp.liga_nivel, mp.liga_total_niveles, mp.puntaje_interno " +
            "FROM users u " +
            "LEFT JOIN matchmaking_profiles mp ON mp.user_id = u.id " +
            "WHERE u.id <> ? " +
            "  AND u.sala_actual IS NULL " +
            "  AND NOT EXISTS (SELECT 1 FROM active_matches am WHERE am.player1_id = u.id OR am.player2_id = u.id) " +
            "  AND NOT EXISTS (SELECT 1 FROM matchmaking_search_notified msn WHERE msn.buscador_id = ? AND msn.receptor_id = u.id)";

    private static final String SQL_CREAR_O_RENOVAR_RETO =
            "INSERT INTO matchmaking_challenges (" +
            "  buscador_id, retador_id, buscador_username, retador_username, grupo_rango, " +
            "  diferencia_puntaje, etiqueta_para_buscador, etiqueta_para_retador, estado, creado_en, expira_en, respondido_en" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pendiente', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + make_interval(secs => ?), NULL) " +
            "ON CONFLICT (buscador_id, retador_id) DO UPDATE SET " +
            "  buscador_username = EXCLUDED.buscador_username, " +
            "  retador_username = EXCLUDED.retador_username, " +
            "  grupo_rango = EXCLUDED.grupo_rango, " +
            "  diferencia_puntaje = EXCLUDED.diferencia_puntaje, " +
            "  etiqueta_para_buscador = EXCLUDED.etiqueta_para_buscador, " +
            "  etiqueta_para_retador = EXCLUDED.etiqueta_para_retador, " +
            "  estado = 'pendiente', " +
            "  creado_en = CURRENT_TIMESTAMP, " +
            "  expira_en = EXCLUDED.expira_en, " +
            "  respondido_en = NULL " +
            "WHERE matchmaking_challenges.estado <> 'pendiente' " +
            "   OR matchmaking_challenges.expira_en <= CURRENT_TIMESTAMP " +
            "RETURNING id";

    private final GenericDAO db;
    private final ClashApiServicio clashApi;
    private final NotificacionesService notificaciones;
    private final AppConfig config;
    private final Ejecutores ejecutores;
    private volatile PuertoSocket puertoSocket;

    public MatchmakingService(
            GenericDAO db,
            ClashApiServicio clashApi,
            NotificacionesService notificaciones,
            AppConfig config,
            Ejecutores ejecutores) {
        this.db = db;
        this.clashApi = clashApi;
        this.notificaciones = notificaciones;
        this.config = config;
        this.ejecutores = ejecutores;
    }

    public void setPuenteSocket(PuertoSocket puertoSocket) {
        this.puertoSocket = puertoSocket;
    }

    /**
     * Asegura que el usuario tenga fila en {@code matchmaking_profiles}, recalcula su puntaje oculto
     * con las variables actualmente disponibles y devuelve únicamente el resultado (de uso interno).
     */
    public MatchmakingPuntaje.ResultadoPuntaje asegurarYRecalcularPerfil(int userId) {
        if (userId <= 0) {
            return MatchmakingPuntaje.calcular(null);
        }
        JsonObject row = db.queryOne(SQL_CARGAR_USUARIO_Y_PERFIL, userId);
        if (row == null) {
            return MatchmakingPuntaje.calcular(null);
        }
        return calcularYPersistirDesdeFila(row, null, null, false);
    }

    /**
     * Actualiza las métricas de Clash Royale del jugador y recalcula su puntaje oculto.
     */
    public MatchmakingPuntaje.ResultadoPuntaje guardarDatosClashYRecalcular(
            int userId,
            MatchmakingPuntaje.DatosClash datosClash) {
        if (userId <= 0 || datosClash == null) {
            return asegurarYRecalcularPerfil(userId);
        }
        JsonObject row = db.queryOne(SQL_CARGAR_USUARIO_Y_PERFIL, userId);
        if (row == null) {
            return MatchmakingPuntaje.calcular(null);
        }
        return calcularYPersistirDesdeFila(row, datosClash, Instant.now(), false);
    }

    /**
     * Hook oficial de integración para Prioridad 12.2 (Ligas):
     * cuando el sistema de ligas asigne o actualice la liga de un jugador, recalcula el puntaje automáticamente.
     */
    public MatchmakingPuntaje.ResultadoPuntaje actualizarLigaUsuario(
            int userId,
            String ligaClave,
            Integer ligaNivel,
            Integer totalNiveles) {
        if (userId <= 0) {
            return MatchmakingPuntaje.calcular(null);
        }
        JsonObject row = db.queryOne(SQL_CARGAR_USUARIO_Y_PERFIL, userId);
        if (row == null) {
            return MatchmakingPuntaje.calcular(null);
        }
        MatchmakingPuntaje.DatosLiga nuevaLiga = new MatchmakingPuntaje.DatosLiga(ligaClave, ligaNivel, totalNiveles);
        return calcularYPersistirDesdeFila(row, null, null, true, nuevaLiga);
    }

    /**
     * Refresca copas / Path of Legends desde Clash API en el pool de IO si el caché venció.
     * Nunca bloquea sockets ni falla si Clash API no está disponible.
     */
    public void refrescarClashEnSegundoPlano(int userId, boolean forzar) {
        if (userId <= 0 || clashApi == null || config == null || !config.hasClashApi() || ejecutores == null) {
            return;
        }
        JsonObject row = db.queryOne(
                "SELECT u.player_tag, mp.clash_actualizado_en " +
                "FROM users u LEFT JOIN matchmaking_profiles mp ON mp.user_id = u.id WHERE u.id = ?",
                userId);
        if (row == null) return;
        String tag = str(row, "player_tag");
        if (tag == null || tag.isBlank()) return;

        if (!forzar) {
            String actualizado = str(row, "clash_actualizado_en");
            if (actualizado != null && !actualizado.isBlank()) {
                try {
                    Instant ts = Instant.parse(actualizado);
                    long minutos = Duration.between(ts, Instant.now()).toMinutes();
                    if (minutos < config.getMatchmakingClashCacheMinutos()) {
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // Si el timestamp tiene otro formato, refrescar normalmente.
                }
            }
        }

        ejecutores.ejecutarIO("mm-refrescar-clash", () -> {
            try {
                JsonObject perfilApi = clashApi.verificarTag(tag);
                MatchmakingPuntaje.DatosClash datos = MatchmakingPuntaje.DatosClash.desdeJsonApi(perfilApi);
                if (datos != null) {
                    guardarDatosClashYRecalcular(userId, datos);
                }
            } catch (Exception e) {
                logger.debug("No se pudo refrescar perfil Clash para userId={} (zona={}): {}",
                        userId, ConexionDB.zonaActual(), e.getMessage());
            }
        });
    }

    public double obtenerPuntajeInternoYAsegurar(int userId) {
        return asegurarYRecalcularPerfil(userId).puntajeInterno();
    }

    public MatchmakingPuntaje.ComparacionNivel compararUsuarios(
            int candidatoId,
            String candidatoUsername,
            int buscadorId,
            String buscadorUsername) {
        double scoreCand = obtenerPuntajeInternoYAsegurar(candidatoId);
        double scoreBusc = obtenerPuntajeInternoYAsegurar(buscadorId);
        return MatchmakingPuntaje.comparar(scoreCand, scoreBusc, candidatoUsername, buscadorUsername);
    }

    /**
     * Construye el payload seguro (sin puntaje numérico) para mostrar una búsqueda activa al receptor,
     * o {@code null} si la búsqueda aún no ha liberado la oleada correspondiente al receptor.
     */
    public JsonObject prepararBusquedaParaReceptor(
            int buscadorId,
            String buscadorUsername,
            int receptorId,
            int maxGrupoLiberado) {
        if (buscadorId <= 0 || receptorId <= 0 || buscadorId == receptorId) {
            return null;
        }
        JsonObject rowBuscador = db.queryOne(SQL_CARGAR_USUARIO_Y_PERFIL, buscadorId);
        JsonObject rowReceptor = db.queryOne(SQL_CARGAR_USUARIO_Y_PERFIL, receptorId);
        if (rowBuscador == null || rowReceptor == null) {
            return null;
        }
        String nameBusc = buscadorUsername != null && !buscadorUsername.isBlank()
                ? buscadorUsername
                : strDefault(rowBuscador, "username", "Jugador");
        String nameRecept = strDefault(rowReceptor, "username", "Jugador");
        double scoreBusc = puntajeDesdeFilaOAsegurar(rowBuscador);
        double scoreRecept = puntajeDesdeFilaOAsegurar(rowReceptor);

        MatchmakingPuntaje.ComparacionNivel comp =
                MatchmakingPuntaje.comparar(scoreRecept, scoreBusc, nameRecept, nameBusc);
        if (comp.grupoRango() > maxGrupoLiberado) {
            return null;
        }

        JsonObject retoExistente = db.queryOne(
                "SELECT id, estado FROM matchmaking_challenges " +
                "WHERE buscador_id = ? AND retador_id = ? " +
                "  AND (estado = 'rechazado' OR (estado = 'pendiente' AND expira_en > CURRENT_TIMESTAMP))",
                buscadorId, receptorId);

        JsonObject payload = construirPayloadBusquedaVisible(buscadorId, nameBusc, comp);
        if (retoExistente != null) {
            payload.addProperty("retoId", retoExistente.get("id").getAsInt());
            payload.addProperty("retoEstado", strDefault(retoExistente, "estado", ""));
        }
        return payload;
    }

    /**
     * Libera una oleada {@code grupoObjetivo} (0 = igual o menor nivel; 1..5 = rangos superiores)
     * para la búsqueda activa de {@code buscadorId}, enviando notificaciones Web, App y PC sin duplicados.
     *
     * @return cantidad de nuevos usuarios notificados en esta oleada
     */
    public int notificarGrupoOleada(int buscadorId, String buscadorUsername, int grupoObjetivo) {
        if (buscadorId <= 0) return 0;

        JsonObject cola = db.queryOne(
                "SELECT user_id, username, puntaje_buscador, max_grupo_liberado " +
                "FROM match_queue WHERE user_id = ? AND expira_en > CURRENT_TIMESTAMP",
                buscadorId);
        if (cola == null) {
            return 0;
        }

        int grupoAcotado = Math.max(0, Math.min(grupoObjetivo, MatchmakingPuntaje.MAX_GRUPO_RANGO));
        double puntajeBuscador = cola.has("puntaje_buscador") && !cola.get("puntaje_buscador").isJsonNull()
                ? cola.get("puntaje_buscador").getAsDouble()
                : obtenerPuntajeInternoYAsegurar(buscadorId);
        String nameBusc = (buscadorUsername != null && !buscadorUsername.isBlank())
                ? buscadorUsername
                : strDefault(cola, "username", "Jugador");

        db.update(
                "UPDATE match_queue SET max_grupo_liberado = GREATEST(max_grupo_liberado, ?), " +
                "margenes_actualizados_en = CURRENT_TIMESTAMP WHERE user_id = ?",
                grupoAcotado, buscadorId);

        ArrayList<JsonObject> candidatos = db.query(SQL_CANDIDATOS_OLEADA, buscadorId, buscadorId);
        int enviados = 0;

        for (JsonObject cand : candidatos) {
            int candId = cand.get("id").getAsInt();
            String candUsername = strDefault(cand, "username", "Jugador");
            double puntajeCand = puntajeDesdeFilaOAsegurar(cand);

            MatchmakingPuntaje.ComparacionNivel comp =
                    MatchmakingPuntaje.comparar(puntajeCand, puntajeBuscador, candUsername, nameBusc);
            if (comp.grupoRango() > grupoAcotado) {
                continue;
            }

            int insertados = db.update(
                    "INSERT INTO matchmaking_search_notified (buscador_id, receptor_id, grupo_rango, es_receptor_mejor, etiqueta_nivel) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT (buscador_id, receptor_id) DO NOTHING",
                    buscadorId, candId, comp.grupoRango(), comp.esReceptorMejor(), comp.etiquetaParaCandidato());
            if (insertados != 1) {
                continue;
            }

            enviados++;
            String titulo = comp.esReceptorMejor()
                    ? "Rival buscando partida (oleada progresiva)"
                    : "Alguien busca partida";
            String mensaje = nameBusc + " busca partida · " + comp.etiquetaParaCandidato() + ".";
            JsonObject datos = construirDatosNotificacionBusqueda(buscadorId, nameBusc, comp);

            if (notificaciones != null) {
                notificaciones.notificar(
                        candId,
                        "partida",
                        titulo,
                        mensaje,
                        "/match",
                        "busqueda:" + buscadorId + ":" + candId,
                        datos,
                        true
                );
            }
            PuertoSocket socket = this.puertoSocket;
            if (socket != null) {
                socket.emitirBusquedaVisible(candId, construirPayloadBusquedaVisible(buscadorId, nameBusc, comp));
            }
        }

        if (enviados > 0) {
            logger.info("🎯 Oleada grupo {} para {} ({}): {} jugador(es) notificado(s) por Web/App/PC",
                    grupoAcotado, nameBusc, buscadorId, enviados);
        }
        return enviados;
    }

    /**
     * Un candidato intenta jugar contra un buscador activo.
     * Si el retador es superior al buscador ({@code esReceptorMejor}), crea un reto pendiente y pide
     * confirmación explícita al buscador ("es mejor que tú, ¿aun así quieres jugar contra él?").
     */
    public ResultadoSolicitudReto solicitarRetoContraBuscador(
            int retadorId,
            String retadorUsername,
            int buscadorId) {
        if (retadorId <= 0 || buscadorId <= 0 || retadorId == buscadorId) {
            return new ResultadoSolicitudReto.Rechazado("SOLICITUD_INVALIDA", "No puedes desafiarte a ti mismo.");
        }

        JsonObject cola = db.queryOne(
                "SELECT q.user_id, q.username, q.puntaje_buscador, q.max_grupo_liberado " +
                "FROM match_queue q JOIN users u ON u.id = q.user_id " +
                "WHERE q.user_id = ? AND q.expira_en > CURRENT_TIMESTAMP " +
                "  AND u.estado = 'buscando_partida' AND u.sala_actual IS NULL " +
                "  AND NOT EXISTS (SELECT 1 FROM active_matches am WHERE am.player1_id = u.id OR am.player2_id = u.id)",
                buscadorId);
        if (cola == null) {
            return new ResultadoSolicitudReto.Rechazado("BUSQUEDA_NO_ACTIVA",
                    "Esta búsqueda ya terminó o el jugador ya está en partida.");
        }

        JsonObject retadorBusy = db.queryOne(
                "SELECT (u.sala_actual IS NOT NULL OR EXISTS (" +
                "  SELECT 1 FROM active_matches am WHERE am.player1_id = u.id OR am.player2_id = u.id" +
                ")) AS ocupado FROM users u WHERE u.id = ?",
                retadorId);
        if (retadorBusy != null && retadorBusy.has("ocupado") && !retadorBusy.get("ocupado").isJsonNull()
                && retadorBusy.get("ocupado").getAsBoolean()) {
            return new ResultadoSolicitudReto.Rechazado("YA_EN_PARTIDA",
                    "Ya estás en una sala o partida activa.");
        }

        String buscadorUsername = strDefault(cola, "username", "Jugador");
        String nombreRetador = (retadorUsername != null && !retadorUsername.isBlank()) ? retadorUsername : "Jugador";
        double scoreBuscador = cola.has("puntaje_buscador") && !cola.get("puntaje_buscador").isJsonNull()
                ? cola.get("puntaje_buscador").getAsDouble()
                : obtenerPuntajeInternoYAsegurar(buscadorId);
        double scoreRetador = obtenerPuntajeInternoYAsegurar(retadorId);
        int maxGrupoLiberado = cola.has("max_grupo_liberado") && !cola.get("max_grupo_liberado").isJsonNull()
                ? cola.get("max_grupo_liberado").getAsInt()
                : 0;

        MatchmakingPuntaje.ComparacionNivel comp =
                MatchmakingPuntaje.comparar(scoreRetador, scoreBuscador, nombreRetador, buscadorUsername);

        if (comp.grupoRango() > maxGrupoLiberado) {
            return new ResultadoSolicitudReto.Rechazado("OLEADA_NO_LIBERADA",
                    "Esta búsqueda aún está priorizando rivales de su nivel.");
        }

        if (!comp.requiereConfirmacionBuscador()) {
            return new ResultadoSolicitudReto.EmpezarPartidaDirecta(
                    buscadorId, buscadorUsername, retadorId, nombreRetador, comp);
        }

        // Retador es mejor que el buscador: requiere confirmación del buscador.
        JsonObject previo = db.queryOne(
                "SELECT id, estado, expira_en > CURRENT_TIMESTAMP AS vigente " +
                "FROM matchmaking_challenges WHERE buscador_id = ? AND retador_id = ?",
                buscadorId, retadorId);
        if (previo != null) {
            String est = strDefault(previo, "estado", "");
            boolean vigente = previo.has("vigente") && !previo.get("vigente").isJsonNull()
                    && previo.get("vigente").getAsBoolean();
            if ("rechazado".equals(est)) {
                return new ResultadoSolicitudReto.Rechazado("RETO_YA_RECHAZADO",
                        "Este jugador prefirió seguir buscando otro rival.");
            }
            if ("pendiente".equals(est) && vigente) {
                return new ResultadoSolicitudReto.Rechazado("RETO_YA_PENDIENTE",
                        "Ya enviaste una solicitud a " + buscadorUsername + ". Espera su respuesta.");
            }
        }

        JsonObject otroPendiente = db.queryOne(
                "SELECT id FROM matchmaking_challenges " +
                "WHERE buscador_id = ? AND retador_id <> ? AND estado = 'pendiente' AND expira_en > CURRENT_TIMESTAMP",
                buscadorId, retadorId);
        if (otroPendiente != null) {
            return new ResultadoSolicitudReto.Rechazado("BUSCADOR_CON_OTRO_RETO",
                    buscadorUsername + " está respondiendo otra solicitud. Inténtalo en unos segundos.");
        }

        int expiraSeg = config != null ? config.getMatchmakingRetoExpiracionSegundos() : 90;
        double diferencia = Math.round((scoreRetador - scoreBuscador) * 100.0) / 100.0;
        int challengeId = db.insertReturningId(
                SQL_CREAR_O_RENOVAR_RETO,
                buscadorId,
                retadorId,
                buscadorUsername,
                nombreRetador,
                comp.grupoRango(),
                diferencia,
                comp.etiquetaParaBuscador(),
                comp.etiquetaParaCandidato(),
                expiraSeg);
        if (challengeId <= 0) {
            return new ResultadoSolicitudReto.Rechazado("RETO_NO_CREADO",
                    "No se pudo enviar la solicitud en este momento.");
        }

        JsonObject payloadBuscador = construirPayloadRetoRecibido(
                challengeId, buscadorId, buscadorUsername, retadorId, nombreRetador, comp, expiraSeg);
        String titulo = "Rival superior quiere jugar contra ti";
        String mensaje = nombreRetador + " es mejor que tú (" + comp.etiquetaParaBuscador()
                + "). ¿Aun así quieres jugar contra él?";

        if (notificaciones != null) {
            notificaciones.notificar(
                    buscadorId,
                    "partida",
                    titulo,
                    mensaje,
                    "/match",
                    "reto_mm:" + challengeId + ":" + buscadorId,
                    payloadBuscador,
                    true
            );
        }
        PuertoSocket socket = this.puertoSocket;
        if (socket != null) {
            socket.emitirEventoUsuario(buscadorId, "matchmaking_reto_recibido", payloadBuscador);
        }

        programarExpiracionReto(challengeId, buscadorId, buscadorUsername, retadorId, expiraSeg);
        return new ResultadoSolicitudReto.EsperandoConfirmacion(
                challengeId, buscadorId, buscadorUsername, retadorId, nombreRetador, comp, expiraSeg);
    }

    /**
     * El buscador responde Sí o No ante la advertencia de que un jugador superior quiere enfrentarlo.
     */
    public ResultadoRespuestaReto responderRetoBuscador(int buscadorId, int challengeId, boolean acepta) {
        if (buscadorId <= 0 || challengeId <= 0) {
            return ResultadoRespuestaReto.error("Solicitud inválida.");
        }
        String nuevoEstado = acepta ? "aceptado" : "rechazado";
        JsonObject fila = db.queryOne(
                "UPDATE matchmaking_challenges " +
                "SET estado = ?, respondido_en = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND buscador_id = ? AND estado = 'pendiente' AND expira_en > CURRENT_TIMESTAMP " +
                "RETURNING id, buscador_id, retador_id, buscador_username, retador_username",
                nuevoEstado, challengeId, buscadorId);

        if (notificaciones != null) {
            notificaciones.eliminarPorEvento("reto_mm:" + challengeId);
        }

        if (fila == null) {
            return ResultadoRespuestaReto.error("La solicitud ya expiró o fue respondida.");
        }

        int retadorId = fila.get("retador_id").getAsInt();
        String buscadorUsername = strDefault(fila, "buscador_username", "Jugador");
        String retadorUsername = strDefault(fila, "retador_username", "Rival");

        if (!acepta) {
            notificarRespuestaARetador(challengeId, false, buscadorId, buscadorUsername, retadorId,
                    buscadorUsername + " prefirió seguir buscando un rival de su nivel.");
        }
        return new ResultadoRespuestaReto(true, acepta, challengeId, buscadorId, buscadorUsername,
                retadorId, retadorUsername, null);
    }

    public void notificarRespuestaARetador(
            int challengeId,
            boolean aceptado,
            int buscadorId,
            String buscadorUsername,
            int retadorId,
            String mensaje) {
        JsonObject payload = new JsonObject();
        payload.addProperty("challengeId", challengeId);
        payload.addProperty("aceptado", aceptado);
        payload.addProperty("buscadorId", buscadorId);
        payload.addProperty("buscadorUsername", buscadorUsername);
        payload.addProperty("mensaje", mensaje);

        PuertoSocket socket = this.puertoSocket;
        if (socket != null) {
            socket.emitirEventoUsuario(retadorId, "matchmaking_reto_respuesta", payload);
        }
        if (!aceptado && notificaciones != null) {
            notificaciones.notificar(
                    retadorId,
                    "partida",
                    "Solicitud no aceptada",
                    mensaje,
                    "/match",
                    null,
                    payload,
                    false
            );
        }
    }

    /**
     * Limpia las notificaciones enviadas y los retos asociados cuando la búsqueda termina o se cancela.
     */
    public void limpiarBusqueda(int buscadorId) {
        if (buscadorId <= 0) return;

        ArrayList<JsonObject> pendientes = db.query(
                "UPDATE matchmaking_challenges SET estado = 'cancelado', respondido_en = CURRENT_TIMESTAMP " +
                "WHERE buscador_id = ? AND estado = 'pendiente' " +
                "RETURNING id, retador_id, buscador_username",
                buscadorId);

        for (JsonObject reto : pendientes) {
            int retoId = reto.get("id").getAsInt();
            int retadorId = reto.get("retador_id").getAsInt();
            String buscadorUsername = strDefault(reto, "buscador_username", "El buscador");
            if (notificaciones != null) {
                notificaciones.eliminarPorEvento("reto_mm:" + retoId);
            }
            notificarRespuestaARetador(retoId, false, buscadorId, buscadorUsername, retadorId,
                    "La búsqueda de " + buscadorUsername + " ya finalizó.");
        }

        db.update("DELETE FROM matchmaking_search_notified WHERE buscador_id = ?", buscadorId);
        db.update("DELETE FROM matchmaking_challenges WHERE buscador_id = ?", buscadorId);
    }

    /**
     * Estado de matchmaking del usuario actual sin exponer nunca su puntaje numérico exacto.
     */
    public JsonObject estadoMatchmakingUsuario(int userId) {
        MatchmakingPuntaje.ResultadoPuntaje perfil = asegurarYRecalcularPerfil(userId);
        refrescarClashEnSegundoPlano(userId, false);

        int duracionSeg = (config != null ? config.getBusquedaTimeoutMinutos() : 10) * 60;
        int margenSeg = config != null ? config.getMatchmakingMargenFinalSegundos() : 120;

        JsonObject variables = new JsonObject();
        variables.addProperty("winRate", perfil.tieneWinRate());
        variables.addProperty("clash", perfil.tieneClash());
        variables.addProperty("liga", perfil.tieneLiga());

        JsonArray canales = new JsonArray();
        canales.add("web");
        canales.add("app");
        canales.add("pc");

        // Búsquedas activas visibles actualmente para este usuario
        JsonArray busquedasVisibles = new JsonArray();
        ArrayList<JsonObject> colasActivas = db.query(
                "SELECT q.user_id, q.username, q.max_grupo_liberado " +
                "FROM match_queue q JOIN users u ON u.id = q.user_id " +
                "WHERE q.user_id <> ? AND q.expira_en > CURRENT_TIMESTAMP AND u.estado = 'buscando_partida' " +
                "ORDER BY q.creado_en ASC",
                userId);
        for (JsonObject q : colasActivas) {
            int buscId = q.get("user_id").getAsInt();
            String buscName = strDefault(q, "username", "Jugador");
            int maxGrupo = q.has("max_grupo_liberado") && !q.get("max_grupo_liberado").isJsonNull()
                    ? q.get("max_grupo_liberado").getAsInt() : 0;
            JsonObject visible = prepararBusquedaParaReceptor(buscId, buscName, userId, maxGrupo);
            if (visible != null) {
                busquedasVisibles.add(visible);
            }
        }

        // Reto pendiente recibido como buscador
        JsonObject retoRecibido = null;
        JsonObject filaRecibido = db.queryOne(
                "SELECT id, buscador_id, retador_id, buscador_username, retador_username, " +
                "grupo_rango, etiqueta_para_buscador, EXTRACT(EPOCH FROM (expira_en - CURRENT_TIMESTAMP)) AS segs_restantes " +
                "FROM matchmaking_challenges " +
                "WHERE buscador_id = ? AND estado = 'pendiente' AND expira_en > CURRENT_TIMESTAMP " +
                "ORDER BY id DESC LIMIT 1",
                userId);
        if (filaRecibido != null) {
            int cid = filaRecibido.get("id").getAsInt();
            int buscId = filaRecibido.get("buscador_id").getAsInt();
            int retId = filaRecibido.get("retador_id").getAsInt();
            String buscName = strDefault(filaRecibido, "buscador_username", "Jugador");
            String retName = strDefault(filaRecibido, "retador_username", "Rival");
            int grupo = filaRecibido.get("grupo_rango").getAsInt();
            String etiq = strDefault(filaRecibido, "etiqueta_para_buscador", "");
            int expiraRest = Math.max(1, filaRecibido.get("segs_restantes").getAsBigDecimal().intValue());
            MatchmakingPuntaje.ComparacionNivel compSimulada = new MatchmakingPuntaje.ComparacionNivel(
                    grupo, grupo, true, false, true, MatchmakingPuntaje.nombreBanda(grupo), "", etiq);
            retoRecibido = construirPayloadRetoRecibido(cid, buscId, buscName, retId, retName, compSimulada, expiraRest);
        }

        // Reto pendiente enviado como retador superior
        JsonObject retoEnviado = null;
        JsonObject filaEnviado = db.queryOne(
                "SELECT id, buscador_id, buscador_username, grupo_rango, etiqueta_para_retador, " +
                "EXTRACT(EPOCH FROM (expira_en - CURRENT_TIMESTAMP)) AS segs_restantes " +
                "FROM matchmaking_challenges " +
                "WHERE retador_id = ? AND estado = 'pendiente' AND expira_en > CURRENT_TIMESTAMP " +
                "ORDER BY id DESC LIMIT 1",
                userId);
        if (filaEnviado != null) {
            retoEnviado = new JsonObject();
            retoEnviado.addProperty("challengeId", filaEnviado.get("id").getAsInt());
            retoEnviado.addProperty("buscadorId", filaEnviado.get("buscador_id").getAsInt());
            retoEnviado.addProperty("buscadorUsername", strDefault(filaEnviado, "buscador_username", "Jugador"));
            retoEnviado.addProperty("grupoRango", filaEnviado.get("grupo_rango").getAsInt());
            retoEnviado.addProperty("etiquetaNivel", strDefault(filaEnviado, "etiqueta_para_retador", ""));
            retoEnviado.addProperty("expiraEnSegundos",
                    Math.max(1, filaEnviado.get("segs_restantes").getAsBigDecimal().intValue()));
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        res.add("variables", variables);
        res.addProperty("variablesActivas", perfil.variablesActivas());
        res.addProperty("perfilCualitativo", perfil.perfilCualitativo());
        res.addProperty("puntajeOculto", true);
        res.addProperty("ligaImplementada", perfil.tieneLiga());
        res.addProperty("ligasRecomendadas", MatchmakingPuntaje.LIGAS_RECOMENDADAS_TOTAL);
        res.addProperty("duracionDefaultSegundos", duracionSeg);
        res.addProperty("margenFinalSegundos", margenSeg);
        res.add("canalesNotificacion", canales);
        res.add("rangosOleadas", MatchmakingPuntaje.describirRangosOleadas(duracionSeg, margenSeg));
        res.add("busquedasVisibles", busquedasVisibles);
        res.add("retoRecibidoPendiente", retoRecibido);
        res.add("retoEnviadoPendiente", retoEnviado);
        return res;
    }

    private void programarExpiracionReto(
            int challengeId,
            int buscadorId,
            String buscadorUsername,
            int retadorId,
            int expiraSegundos) {
        if (ejecutores == null || ejecutores.timers() == null) return;
        ejecutores.timers().schedule(() -> {
            try {
                JsonObject vencido = db.queryOne(
                        "UPDATE matchmaking_challenges " +
                        "SET estado = 'expirado', respondido_en = CURRENT_TIMESTAMP " +
                        "WHERE id = ? AND estado = 'pendiente' " +
                        "RETURNING id",
                        challengeId);
                if (vencido != null) {
                    if (notificaciones != null) {
                        notificaciones.eliminarPorEvento("reto_mm:" + challengeId);
                    }
                    PuertoSocket socket = this.puertoSocket;
                    if (socket != null) {
                        JsonObject expPayload = new JsonObject();
                        expPayload.addProperty("challengeId", challengeId);
                        socket.emitirEventoUsuario(buscadorId, "matchmaking_reto_expirado", expPayload);
                    }
                    notificarRespuestaARetador(challengeId, false, buscadorId, buscadorUsername, retadorId,
                            "El jugador no respondió a tiempo tu solicitud de partida.");
                }
            } catch (Exception e) {
                logger.warn("Error expirando reto matchmaking {}: {}", challengeId, e.getMessage());
            }
        }, Math.max(5L, expiraSegundos + 1L), TimeUnit.SECONDS);
    }

    private double puntajeDesdeFilaOAsegurar(JsonObject row) {
        boolean tieneFilaPerfil = row.has("mp_user_id") && !row.get("mp_user_id").isJsonNull();
        if (!tieneFilaPerfil) {
            return calcularYPersistirDesdeFila(row, null, null, false).puntajeInterno();
        }
        return row.has("puntaje_interno") && !row.get("puntaje_interno").isJsonNull()
                ? row.get("puntaje_interno").getAsDouble()
                : calcularYPersistirDesdeFila(row, null, null, false).puntajeInterno();
    }

    private MatchmakingPuntaje.ResultadoPuntaje calcularYPersistirDesdeFila(
            JsonObject row,
            MatchmakingPuntaje.DatosClash overrideClash,
            Instant clashTimestamp,
            boolean overrideLiga) {
        return calcularYPersistirDesdeFila(row, overrideClash, clashTimestamp, overrideLiga, null);
    }

    private MatchmakingPuntaje.ResultadoPuntaje calcularYPersistirDesdeFila(
            JsonObject row,
            MatchmakingPuntaje.DatosClash overrideClash,
            Instant clashTimestamp,
            boolean overrideLiga,
            MatchmakingPuntaje.DatosLiga nuevaLiga) {
        int userId = row.get("id").getAsInt();
        int victorias = intOrZero(row, "total_victorias");
        int derrotas = intOrZero(row, "total_derrotas");
        int partidas = intOrZero(row, "total_partidas");

        MatchmakingPuntaje.DatosClash clash = overrideClash != null
                ? overrideClash
                : clashDesdeFila(row);
        Instant clashActualizado = clashTimestamp != null
                ? clashTimestamp
                : parseTimestampOpcional(row, "clash_actualizado_en");

        MatchmakingPuntaje.DatosLiga liga = overrideLiga
                ? nuevaLiga
                : new MatchmakingPuntaje.DatosLiga(
                        str(row, "liga_clave"),
                        intOrNull(row, "liga_nivel"),
                        intOrNull(row, "liga_total_niveles"));

        MatchmakingPuntaje.ResultadoPuntaje resultado = MatchmakingPuntaje.calcular(
                new MatchmakingPuntaje.DatosJugador(victorias, derrotas, partidas, clash, liga));

        upsertPerfil(userId, clash, clashActualizado, liga, resultado);
        return resultado;
    }

    private void upsertPerfil(
            int userId,
            MatchmakingPuntaje.DatosClash clash,
            Instant clashActualizado,
            MatchmakingPuntaje.DatosLiga liga,
            MatchmakingPuntaje.ResultadoPuntaje resultado) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_PERFIL)) {
            ps.setInt(1, userId);
            setNullableInt(ps, 2, clash != null ? clash.trophies() : null);
            setNullableInt(ps, 3, clash != null ? clash.bestTrophies() : null);
            setNullableInt(ps, 4, clash != null ? clash.polLeague() : null);
            setNullableInt(ps, 5, clash != null ? clash.polTrophies() : null);
            setNullableInt(ps, 6, clash != null ? clash.bestPolLeague() : null);
            setNullableInt(ps, 7, clash != null ? clash.bestPolTrophies() : null);
            setNullableInt(ps, 8, clash != null ? clash.polRank() : null);
            setNullableInt(ps, 9, clash != null ? clash.bestPolRank() : null);
            if (clashActualizado != null) {
                ps.setTimestamp(10, Timestamp.from(clashActualizado));
            } else {
                ps.setNull(10, Types.TIMESTAMP);
            }
            if (liga != null && liga.clave() != null && !liga.clave().isBlank()) {
                ps.setString(11, liga.clave());
            } else {
                ps.setNull(11, Types.VARCHAR);
            }
            setNullableInt(ps, 12, liga != null ? liga.nivel() : null);
            setNullableInt(ps, 13, liga != null ? liga.totalNiveles() : null);
            ps.setDouble(14, resultado.puntajeInterno());
            ps.setBoolean(15, resultado.tieneWinRate());
            ps.setBoolean(16, resultado.tieneClash());
            ps.setBoolean(17, resultado.tieneLiga());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar matchmaking_profiles para userId=" + userId, e);
        }
    }

    private static MatchmakingPuntaje.DatosClash clashDesdeFila(JsonObject row) {
        Integer trophies = intOrNull(row, "clash_trophies");
        Integer bestTrophies = intOrNull(row, "clash_best_trophies");
        Integer polLeague = intOrNull(row, "clash_pol_league");
        Integer polTrophies = intOrNull(row, "clash_pol_trophies");
        Integer bestPolLeague = intOrNull(row, "clash_pol_best_league");
        Integer bestPolTrophies = intOrNull(row, "clash_pol_best_trophies");
        Integer polRank = intOrNull(row, "clash_pol_rank");
        Integer bestPolRank = intOrNull(row, "clash_pol_best_rank");
        if (trophies == null && bestTrophies == null
                && polLeague == null && polTrophies == null && polRank == null
                && bestPolLeague == null && bestPolTrophies == null && bestPolRank == null) {
            return null;
        }
        return new MatchmakingPuntaje.DatosClash(
                trophies, bestTrophies, polLeague, polTrophies,
                bestPolLeague, bestPolTrophies, polRank, bestPolRank);
    }

    private static JsonObject construirPayloadBusquedaVisible(
            int buscadorId,
            String buscadorUsername,
            MatchmakingPuntaje.ComparacionNivel comp) {
        JsonObject payload = new JsonObject();
        payload.addProperty("userId", buscadorId);
        payload.addProperty("buscadorId", buscadorId);
        payload.addProperty("username", buscadorUsername);
        payload.addProperty("grupoRango", comp.grupoRango());
        payload.addProperty("nivelesDiferencia", comp.nivelesDiferencia());
        payload.addProperty("nombreBanda", comp.nombreBanda());
        payload.addProperty("etiquetaNivel", comp.etiquetaParaCandidato());
        payload.addProperty("eresMejor", comp.esReceptorMejor());
        payload.addProperty("esBuscadorMejor", comp.esBuscadorMejor());
        payload.addProperty("requiereConfirmacionBuscador", comp.requiereConfirmacionBuscador());
        payload.add("canales", canalesJson());
        return payload;
    }

    private static JsonObject construirDatosNotificacionBusqueda(
            int buscadorId,
            String buscadorUsername,
            MatchmakingPuntaje.ComparacionNivel comp) {
        JsonObject datos = construirPayloadBusquedaVisible(buscadorId, buscadorUsername, comp);
        datos.addProperty("userId", buscadorId);
        datos.addProperty("tipoMatchmaking", "busqueda_partida");
        return datos;
    }

    private static JsonObject construirPayloadRetoRecibido(
            int challengeId,
            int buscadorId,
            String buscadorUsername,
            int retadorId,
            String retadorUsername,
            MatchmakingPuntaje.ComparacionNivel comp,
            int expiraSeg) {
        JsonObject payload = new JsonObject();
        payload.addProperty("challengeId", challengeId);
        payload.addProperty("buscadorId", buscadorId);
        payload.addProperty("buscadorUsername", buscadorUsername);
        payload.addProperty("retadorId", retadorId);
        payload.addProperty("retadorUsername", retadorUsername);
        payload.addProperty("grupoRango", comp.grupoRango());
        payload.addProperty("nivelesDiferencia", comp.nivelesDiferencia());
        payload.addProperty("nombreBanda", comp.nombreBanda());
        payload.addProperty("etiquetaNivel", comp.etiquetaParaBuscador());
        payload.addProperty("mensaje", retadorUsername + " es mejor que tú (" + comp.etiquetaParaBuscador()
                + "). ¿Aun así quieres jugar contra él?");
        payload.addProperty("expiraEnSegundos", expiraSeg);
        payload.addProperty("tipoMatchmaking", "reto_superior");
        payload.add("canales", canalesJson());
        return payload;
    }

    private static JsonArray canalesJson() {
        JsonArray canales = new JsonArray();
        canales.add("web");
        canales.add("app");
        canales.add("pc");
        return canales;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Instant parseTimestampOpcional(JsonObject row, String campo) {
        String s = str(row, campo);
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject obj, String campo) {
        if (obj == null || !obj.has(campo) || obj.get(campo).isJsonNull()) return null;
        return obj.get(campo).getAsString();
    }

    private static String strDefault(JsonObject obj, String campo, String defecto) {
        String val = str(obj, campo);
        return (val != null && !val.isBlank()) ? val : defecto;
    }

    private static int intOrZero(JsonObject obj, String campo) {
        Integer v = intOrNull(obj, campo);
        return v != null ? v : 0;
    }

    private static Integer intOrNull(JsonObject obj, String campo) {
        if (obj == null || !obj.has(campo) || obj.get(campo).isJsonNull()) return null;
        try {
            return obj.get(campo).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
