package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.socketio.SocketIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gestiona los temporizadores de las salas privadas de negociación antes de iniciar la partida:
 * <ul>
 *   <li><b>Gracia de desconexión (30 s por defecto):</b> el tiempo de desconexión empieza a contar
 *       únicamente pasados {@code NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS} segundos desde que el socket cae.</li>
 *   <li><b>Exención única al entrar a Discord:</b> la primera vez que un jugador abre o vincula Discord desde la
 *       sala privada ({@link Eventos#VOICE_DISCORD_OPENED}), esa desconexión no cuenta tiempo de desconexión;
 *       al volver queda marcada como {@code usada}, por lo que una segunda desconexión sí cuenta.</li>
 *   <li><b>Temporizador individual opcional de 2:30 (estilo Pokémon Showdown):</b> cualquiera de los dos jugadores
 *       puede activarlo en cualquier momento de la negociación. Cada jugador tiene su propio reloj de
 *       {@code NEGOCIACION_TIMER_ACCION_SEGUNDOS} (default 150 s = 2:30) que se reinicia únicamente con las
 *       acciones propias (enviar mensaje privado, cambiar modo o monto, o pulsar comenzar / confirmar) sin aumentar
 *       el tiempo del rival.</li>
 * </ul>
 */
public class TemporizadorSalaService {
    private static final Logger logger = LoggerFactory.getLogger(TemporizadorSalaService.class);

    public static final String EXENCION_DISPONIBLE = "disponible";
    public static final String EXENCION_ARMADA = "armada";
    public static final String EXENCION_ACTIVA = "activa";
    public static final String EXENCION_USADA = "usada";
    private static final Set<String> ESTADOS_EXENCION = Set.of(
            EXENCION_DISPONIBLE, EXENCION_ARMADA, EXENCION_ACTIVA, EXENCION_USADA);

    /** Margen interno para que, si nadie actúa tras activar el reloj, venza primero el rival inactivo. */
    private static final long MARGEN_DESEMPATE_ACTIVADOR_MS = 250L;

    /** Contrato de la sala activa que sincroniza sobre la misma instancia {@code SalaNegociacion}. */
    public interface SalaNegociacion {
        int player1Id();
        int player2Id();
        String player1Username();
        String player2Username();
        boolean estaActivaEnServidor();
        boolean estaIniciadaOBloqueada();
        boolean estaConectado(int userId);
        boolean cancelarVotoInicioSiExiste(int userId);
        void cerrarSalaPorTimeout(int userIdCulpable, String motivo, String logTexto,
                                  boolean penalizarDesconexion, boolean penalizarSalidaChat);
        EstadoTimers estadoTimers();
    }

    /** Estado de temporizadores y exención Discord asociado a una sala en memoria (reconstruible desde BD). */
    public static final class EstadoTimers {
        String discordExencionP1 = EXENCION_DISPONIBLE;
        String discordExencionP2 = EXENCION_DISPONIBLE;
        final ConcurrentHashMap<Integer, ScheduledFuture<?>> graciaTimers = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, ScheduledFuture<?>> desconexionTimers = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Long> desconexionDeadlinesMs = new ConcurrentHashMap<>();
        boolean timerAccionActivo = false;
        int timerActivadoPor = 0;
        long timerP1VenceMs = 0L;
        long timerP2VenceMs = 0L;
        final ConcurrentHashMap<Integer, ScheduledFuture<?>> accionTimers = new ConcurrentHashMap<>();

        public String getDiscordExencionP1() { return discordExencionP1; }
        public String getDiscordExencionP2() { return discordExencionP2; }
        public boolean isTimerAccionActivo() { return timerAccionActivo; }
    }

    private final GenericDAO db;
    private final SocketIOServer io;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, SalaNegociacion> salasActivas = new ConcurrentHashMap<>();

    public TemporizadorSalaService(GenericDAO db, SocketIOServer io, AppConfig config,
                                   ScheduledExecutorService scheduler) {
        this.db = db;
        this.io = io;
        this.config = config;
        this.scheduler = scheduler;
    }

    /** Se suscribe al bus para escuchar cuando un jugador abre Discord desde su sala privada. */
    public void registrar(EventBus bus) {
        if (bus != null) {
            bus.on(Eventos.VOICE_DISCORD_OPENED, "sala-exencion-discord", this::alAbrirDiscord);
        }
    }

    public void registrarSala(String salaId, SalaNegociacion sala) {
        if (salaId != null && sala != null) {
            salasActivas.put(salaId, sala);
        }
    }

    public void desregistrarSala(String salaId, SalaNegociacion sala) {
        if (sala != null) {
            cancelarTodosLosTimers(sala);
        }
        if (salaId != null) {
            if (sala != null) salasActivas.remove(salaId, sala);
            else salasActivas.remove(salaId);
        }
    }

    public void cancelarTodosLosTimers(SalaNegociacion sala) {
        if (sala == null) return;
        EstadoTimers est = sala.estadoTimers();
        for (ScheduledFuture<?> f : est.graciaTimers.values()) f.cancel(false);
        est.graciaTimers.clear();
        for (ScheduledFuture<?> f : est.desconexionTimers.values()) f.cancel(false);
        est.desconexionTimers.clear();
        est.desconexionDeadlinesMs.clear();
        for (ScheduledFuture<?> f : est.accionTimers.values()) f.cancel(false);
        est.accionTimers.clear();
    }

    // ------------------------------------------------------------------
    // Exención única de Discord + gracia de 30 s + desconexión
    // ------------------------------------------------------------------

    private void alAbrirDiscord(EventBus.Evento evento) {
        Eventos.AperturaDiscordSala datos = evento.datos(Eventos.AperturaDiscordSala.class);
        if (datos == null || datos.userId() <= 0) return;
        marcarAperturaDiscord(datos.userId(), datos.salaId());
    }

    /** Marca la intención de entrar a Discord para eximir únicamente la primera desconexión en esta sala. */
    public void marcarAperturaDiscord(int userId, String salaIdSugerida) {
        if (userId <= 0) return;
        String salaId = null;
        SalaNegociacion sala = null;
        if (salaIdSugerida != null && !salaIdSugerida.isBlank()) {
            sala = salasActivas.get(salaIdSugerida);
            if (sala != null) salaId = salaIdSugerida;
        }
        if (sala == null) {
            for (Map.Entry<String, SalaNegociacion> entry : salasActivas.entrySet()) {
                SalaNegociacion candidata = entry.getValue();
                if (candidata.player1Id() == userId || candidata.player2Id() == userId) {
                    salaId = entry.getKey();
                    sala = candidata;
                    break;
                }
            }
        }
        if (sala == null || salaId == null) return;

        synchronized (sala) {
            if (!sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return;
            if (userId != sala.player1Id() && userId != sala.player2Id()) return;
            String actual = getExencion(sala, userId);
            if (!EXENCION_DISPONIBLE.equals(actual)) {
                return; // Solo aplica la primera vez; 'armada', 'activa' o 'usada' no se rearman
            }
            if (sala.estaConectado(userId)) {
                setExencionYPersistir(salaId, sala, userId, EXENCION_ARMADA);
            } else {
                // El móvil pudo cortar el WebSocket milisegundos antes de terminar la petición HTTP
                cancelarTimersDesconexionJugador(sala, userId);
                boolean habiaAviso = sala.estadoTimers().desconexionDeadlinesMs.remove(userId) != null;
                setExencionYPersistir(salaId, sala, userId, EXENCION_ACTIVA);
                if (habiaAviso) {
                    JsonObject reData = new JsonObject();
                    reData.addProperty("username", nombreJugador(sala, userId));
                    emitirASala(salaId, "rival_reconectado", reData);
                }
            }
        }
    }

    /**
     * Procesa la desconexión de un jugador durante la negociación:
     * si tenía la exención única de Discord armada, pasa a {@code activa} sin iniciar tiempo de desconexión;
     * en caso contrario espera la gracia de 30 s antes de iniciar el conteo de abandono por desconexión.
     */
    public void alDesconectarEnNegociacion(String salaId, SalaNegociacion sala,
                                           int userId, String username, boolean penalizarSalida) {
        if (salaId == null || sala == null) return;
        synchronized (sala) {
            if (!sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return;
            cancelarTimersDesconexionJugador(sala, userId);
            sala.estadoTimers().desconexionDeadlinesMs.remove(userId);

            String exencion = getExencion(sala, userId);
            if (EXENCION_ARMADA.equals(exencion)) {
                setExencionYPersistir(salaId, sala, userId, EXENCION_ACTIVA);
                if (sala.cancelarVotoInicioSiExiste(userId)) {
                    emitirASala(salaId, "rival_cancelo_inicio", null);
                }
                logger.info("🎧 {} salió a Discord en negociación (sala {}); exención única activa", username, salaId);
                return;
            }
            if (EXENCION_ACTIVA.equals(exencion)) {
                return;
            }

            final int graciaSegundos = config.getNegociacionGraciaDesconexionSegundos();
            logger.info("🔌 {} desconectado en negociación (sala {}). Gracia de {}s antes del timer de desconexión.",
                    username, salaId, graciaSegundos);

            ScheduledFuture<?> tareaGracia = scheduler.schedule(() -> {
                synchronized (sala) {
                    sala.estadoTimers().graciaTimers.remove(userId);
                    if (!sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return;
                    if (sala.estaConectado(userId)) return;
                    if (EXENCION_ACTIVA.equals(getExencion(sala, userId))) return;

                    if (sala.cancelarVotoInicioSiExiste(userId)) {
                        emitirASala(salaId, "rival_cancelo_inicio", null);
                    }
                    iniciarConteoDesconexionTrasGracia(salaId, sala, userId, username, penalizarSalida);
                }
            }, graciaSegundos, TimeUnit.SECONDS);

            sala.estadoTimers().graciaTimers.put(userId, tareaGracia);
        }
    }

    private void iniciarConteoDesconexionTrasGracia(String salaId, SalaNegociacion sala,
                                                    int userId, String username, boolean penalizarSalida) {
        final int esperaSegundos = config.getNegociacionDesconexionSegundos();
        EstadoTimers est = sala.estadoTimers();
        ScheduledFuture<?> previo = est.desconexionTimers.remove(userId);
        if (previo != null) previo.cancel(false);

        long venceEn = System.currentTimeMillis() + esperaSegundos * 1000L;
        est.desconexionDeadlinesMs.put(userId, venceEn);

        JsonObject timerData = new JsonObject();
        timerData.addProperty("tiempo", esperaSegundos);
        emitirASala(salaId, "rival_desconectado", timerData);
        logger.info("⏳ Gracia vencida para {} en sala {}. Contando {}s de desconexión.", username, salaId, esperaSegundos);

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            synchronized (sala) {
                if (!sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return;
                if (sala.estaConectado(userId) || EXENCION_ACTIVA.equals(getExencion(sala, userId))) {
                    est.desconexionDeadlinesMs.remove(userId);
                    est.desconexionTimers.remove(userId);
                    return;
                }
                desregistrarSala(salaId, sala);
                sala.cerrarSalaPorTimeout(
                        userId,
                        username + " abandonó por desconexión",
                        "⚠️ ABANDONO Negociación: " + username + " no volvió (" + esperaSegundos + "s)",
                        penalizarSalida,
                        penalizarSalida);
            }
        }, esperaSegundos, TimeUnit.SECONDS);

        est.desconexionTimers.put(userId, timer);
    }

    /**
     * Procesa la reconexión del jugador a la sala: cancela gracia/desconexión y, si venía de la exención única de
     * Discord, la marca como {@code usada} para que cualquier segunda desconexión sí cuente.
     *
     * @return {@code true} si ya se había avisado {@code rival_desconectado} y por tanto se emitió {@code rival_reconectado}
     */
    public boolean alReconectarJugador(String salaId, SalaNegociacion sala, int userId, String username) {
        if (sala == null) return false;
        synchronized (sala) {
            cancelarTimersDesconexionJugador(sala, userId);
            boolean habiaAviso = sala.estadoTimers().desconexionDeadlinesMs.remove(userId) != null;

            String exencion = getExencion(sala, userId);
            if (EXENCION_ACTIVA.equals(exencion) || EXENCION_ARMADA.equals(exencion)) {
                setExencionYPersistir(salaId, sala, userId, EXENCION_USADA);
            }

            if (habiaAviso && salaId != null) {
                JsonObject reData = new JsonObject();
                reData.addProperty("username", username);
                emitirASala(salaId, "rival_reconectado", reData);
            }
            return habiaAviso;
        }
    }

    /**
     * Devuelve los segundos restantes del conteo de desconexión SOLO si ya pasaron los 30 s de gracia;
     * {@code null} si el jugador está conectado, en sus primeros 30 s de gracia o en exención de Discord.
     */
    public Long obtenerSegundosDesconexionRestantes(SalaNegociacion sala, int userId) {
        if (sala == null) return null;
        Long venceEn = sala.estadoTimers().desconexionDeadlinesMs.get(userId);
        if (venceEn == null) return null;
        return Math.max(1L, (venceEn - System.currentTimeMillis() + 999L) / 1000L);
    }

    private void cancelarTimersDesconexionJugador(SalaNegociacion sala, int userId) {
        EstadoTimers est = sala.estadoTimers();
        ScheduledFuture<?> gracia = est.graciaTimers.remove(userId);
        if (gracia != null) gracia.cancel(false);
        ScheduledFuture<?> desc = est.desconexionTimers.remove(userId);
        if (desc != null) desc.cancel(false);
    }

    // ------------------------------------------------------------------
    // Temporizador opcional individual de 2:30 (estilo Pokémon Showdown)
    // ------------------------------------------------------------------

    /**
     * Activa el temporizador individual de negociación (2:30 por jugador). Si ya estaba activo, devuelve el estado
     * actual sin reiniciar ningún reloj.
     *
     * @return el payload JSON emitido en {@code timer_negociacion}, o {@code null} si no se pudo activar
     */
    public JsonObject activarTimerAccion(String salaId, SalaNegociacion sala, int userIdActivador) {
        if (salaId == null || sala == null) return null;
        synchronized (sala) {
            if (!sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return null;
            if (userIdActivador != sala.player1Id() && userIdActivador != sala.player2Id()) return null;

            EstadoTimers est = sala.estadoTimers();
            if (est.timerAccionActivo) {
                return construirEstadoTimerJson(salaId, sala, false);
            }

            int duracionSegundos = config.getNegociacionTimerAccionSegundos();
            long duracionMs = duracionSegundos * 1000L;
            if (db != null) {
                int filas = db.update(
                        "UPDATE active_matches SET timer_activo = TRUE, timer_activado_por = ?, " +
                        "timer_p1_vence_en = CURRENT_TIMESTAMP + make_interval(secs => ?), " +
                        "timer_p2_vence_en = CURRENT_TIMESTAMP + make_interval(secs => ?) " +
                        "WHERE sala_id = ? AND iniciado = FALSE AND inicio_revision = FALSE",
                        userIdActivador, duracionSegundos, duracionSegundos, salaId);
                if (filas != 1) return null;
            }

            long ahora = System.currentTimeMillis();
            est.timerAccionActivo = true;
            est.timerActivadoPor = userIdActivador;
            est.timerP1VenceMs = ahora + duracionMs;
            est.timerP2VenceMs = ahora + duracionMs;

            int rivalId = userIdActivador == sala.player1Id() ? sala.player2Id() : sala.player1Id();
            programarVencimientoAccion(salaId, sala, rivalId, duracionMs);
            programarVencimientoAccion(salaId, sala, userIdActivador, duracionMs + MARGEN_DESEMPATE_ACTIVADOR_MS);

            JsonObject payload = construirEstadoTimerJson(salaId, sala, true);
            emitirASala(salaId, "timer_negociacion", payload);
            logger.info("⏱️ Temporizador de negociación ({}s) activado en {} por userId={}",
                    duracionSegundos, salaId, userIdActivador);
            return payload;
        }
    }

    /**
     * Reinicia únicamente el reloj individual del jugador que realizó la acción (mensaje privado, cambio de modo/monto
     * o pulsar comenzar/confirmar), sin alterar el tiempo restante del rival.
     */
    public void registrarAccionJugador(String salaId, SalaNegociacion sala, int userId) {
        if (salaId == null || sala == null) return;
        synchronized (sala) {
            EstadoTimers est = sala.estadoTimers();
            if (!est.timerAccionActivo || !sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return;
            if (userId != sala.player1Id() && userId != sala.player2Id()) return;

            int duracionSegundos = config.getNegociacionTimerAccionSegundos();
            long duracionMs = duracionSegundos * 1000L;
            long ahora = System.currentTimeMillis();

            if (userId == sala.player1Id()) {
                est.timerP1VenceMs = ahora + duracionMs;
                if (db != null) {
                    db.update(
                            "UPDATE active_matches SET timer_p1_vence_en = CURRENT_TIMESTAMP + make_interval(secs => ?) " +
                            "WHERE sala_id = ? AND iniciado = FALSE AND timer_activo = TRUE",
                            duracionSegundos, salaId);
                }
            } else {
                est.timerP2VenceMs = ahora + duracionMs;
                if (db != null) {
                    db.update(
                            "UPDATE active_matches SET timer_p2_vence_en = CURRENT_TIMESTAMP + make_interval(secs => ?) " +
                            "WHERE sala_id = ? AND iniciado = FALSE AND timer_activo = TRUE",
                            duracionSegundos, salaId);
                }
            }

            programarVencimientoAccion(salaId, sala, userId, duracionMs);
            emitirASala(salaId, "timer_negociacion", construirEstadoTimerJson(salaId, sala, false));
        }
    }

    private void programarVencimientoAccion(String salaId, SalaNegociacion sala, int userId, long delayMs) {
        EstadoTimers est = sala.estadoTimers();
        ScheduledFuture<?> previo = est.accionTimers.remove(userId);
        if (previo != null) previo.cancel(false);

        ScheduledFuture<?> futuro = scheduler.schedule(() -> {
            synchronized (sala) {
                est.accionTimers.remove(userId);
                if (!est.timerAccionActivo || !sala.estaActivaEnServidor() || sala.estaIniciadaOBloqueada()) return;
                long venceJugador = userId == sala.player1Id() ? est.timerP1VenceMs : est.timerP2VenceMs;
                long restante = venceJugador - System.currentTimeMillis();
                if (restante > 50L) {
                    // Se reinició justo cuando corría el tick anterior; reprogramar por lo que falta
                    programarVencimientoAccion(salaId, sala, userId, restante);
                    return;
                }
                String username = nombreJugador(sala, userId);
                desregistrarSala(salaId, sala);
                sala.cerrarSalaPorTimeout(
                        userId,
                        "Se agotó el tiempo de " + username + " por inactividad",
                        "⏱️ TIEMPO AGOTADO Negociación: " + username + " agotó su temporizador individual",
                        false,
                        true);
            }
        }, Math.max(1L, delayMs), TimeUnit.MILLISECONDS);

        est.accionTimers.put(userId, futuro);
    }

    /** Estado actual del temporizador para enviar en {@code restaurar_partida} o {@code timer_negociacion}. */
    public JsonObject construirEstadoTimerJson(String salaId, SalaNegociacion sala, boolean recienActivado) {
        EstadoTimers est = sala.estadoTimers();
        int duracionSegundos = config.getNegociacionTimerAccionSegundos();
        long duracionMs = duracionSegundos * 1000L;
        long ahora = System.currentTimeMillis();

        JsonObject json = new JsonObject();
        json.addProperty("salaId", salaId);
        json.addProperty("activo", est.timerAccionActivo);
        json.addProperty("duracionSegundos", duracionSegundos);
        json.addProperty("recienActivado", recienActivado);
        json.addProperty("p1Id", sala.player1Id());
        json.addProperty("p2Id", sala.player2Id());
        if (est.timerAccionActivo) {
            json.addProperty("activadoPor", est.timerActivadoPor > 0 ? nombreJugador(sala, est.timerActivadoPor) : "");
            json.addProperty("p1RestanteMs", Math.max(0L, Math.min(duracionMs, est.timerP1VenceMs - ahora)));
            json.addProperty("p2RestanteMs", Math.max(0L, Math.min(duracionMs, est.timerP2VenceMs - ahora)));
        } else {
            json.addProperty("p1RestanteMs", duracionMs);
            json.addProperty("p2RestanteMs", duracionMs);
        }
        return json;
    }

    // ------------------------------------------------------------------
    // Restauración al reiniciar el servidor (A2)
    // ------------------------------------------------------------------

    /**
     * Reconstruye desde la fila de {@code active_matches} tanto el estado de la exención de Discord como el
     * temporizador individual de 2:30 si estaba activo, reprogramando los tiempos restantes reales.
     */
    public void restaurarDesdeBD(String salaId, SalaNegociacion sala, JsonObject row) {
        if (salaId == null || sala == null || row == null) return;
        synchronized (sala) {
            EstadoTimers est = sala.estadoTimers();
            est.discordExencionP1 = normalizarExencion(str(row, "discord_exencion_p1"));
            est.discordExencionP2 = normalizarExencion(str(row, "discord_exencion_p2"));

            boolean timerActivo = row.has("timer_activo") && !row.get("timer_activo").isJsonNull()
                    && row.get("timer_activo").getAsBoolean();
            if (timerActivo && !sala.estaIniciadaOBloqueada()) {
                long ahora = System.currentTimeMillis();
                long fallbackMs = config.getNegociacionTimerAccionSegundos() * 1000L;
                long p1RestanteMs = leerMilisegundosRestantes(row, "timer_p1_restante_ms", fallbackMs);
                long p2RestanteMs = leerMilisegundosRestantes(row, "timer_p2_restante_ms", fallbackMs);

                est.timerAccionActivo = true;
                est.timerActivadoPor = row.has("timer_activado_por") && !row.get("timer_activado_por").isJsonNull()
                        ? row.get("timer_activado_por").getAsInt() : 0;
                est.timerP1VenceMs = ahora + Math.max(1000L, p1RestanteMs);
                est.timerP2VenceMs = ahora + Math.max(1000L, p2RestanteMs);

                programarVencimientoAccion(salaId, sala, sala.player1Id(), Math.max(1000L, p1RestanteMs));
                programarVencimientoAccion(salaId, sala, sala.player2Id(), Math.max(1000L, p2RestanteMs));
            }
        }
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    private String getExencion(SalaNegociacion sala, int userId) {
        if (userId == sala.player1Id()) return sala.estadoTimers().discordExencionP1;
        if (userId == sala.player2Id()) return sala.estadoTimers().discordExencionP2;
        return EXENCION_USADA;
    }

    private void setExencionYPersistir(String salaId, SalaNegociacion sala, int userId, String nuevoEstado) {
        if (!ESTADOS_EXENCION.contains(nuevoEstado)) return;
        if (userId == sala.player1Id()) {
            sala.estadoTimers().discordExencionP1 = nuevoEstado;
            if (db != null && salaId != null) {
                db.update("UPDATE active_matches SET discord_exencion_p1 = ? WHERE sala_id = ?", nuevoEstado, salaId);
            }
        } else if (userId == sala.player2Id()) {
            sala.estadoTimers().discordExencionP2 = nuevoEstado;
            if (db != null && salaId != null) {
                db.update("UPDATE active_matches SET discord_exencion_p2 = ? WHERE sala_id = ?", nuevoEstado, salaId);
            }
        }
    }

    private static String nombreJugador(SalaNegociacion sala, int userId) {
        if (userId == sala.player1Id()) return sala.player1Username();
        if (userId == sala.player2Id()) return sala.player2Username();
        return "Jugador";
    }

    private void emitirASala(String salaId, String evento, JsonObject payload) {
        if (io == null || salaId == null) return;
        if (payload == null) {
            io.to(salaId).emit(evento);
        } else {
            io.to(salaId).emit(evento, payload);
        }
    }

    private static String normalizarExencion(String valor) {
        return (valor != null && ESTADOS_EXENCION.contains(valor)) ? valor : EXENCION_DISPONIBLE;
    }

    private static long leerMilisegundosRestantes(JsonObject row, String campo, long fallbackMs) {
        if (row == null || !row.has(campo) || row.get(campo).isJsonNull()) return fallbackMs;
        try {
            return row.get(campo).getAsBigDecimal().longValue();
        } catch (RuntimeException e) {
            return fallbackMs;
        }
    }

    private static String str(JsonObject o, String clave) {
        return (o != null && o.has(clave) && !o.get(clave).isJsonNull() && o.get(clave).isJsonPrimitive())
                ? o.get(clave).getAsString() : null;
    }
}

