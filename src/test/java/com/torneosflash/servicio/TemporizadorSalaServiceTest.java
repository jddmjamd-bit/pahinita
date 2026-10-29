package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporizadorSalaServiceTest {

    /** Scheduler determinista en memoria para simular segundos/minutos sin esperar reloj real. */
    private static final class RelojManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private static final AtomicLong SECUENCIA = new AtomicLong();
        private long ahoraMs = 0L;
        private boolean apagado = false;
        private final PriorityQueue<TareaProgramada<?>> cola = new PriorityQueue<>(
                Comparator.<TareaProgramada<?>>comparingLong(t -> t.ejecutarEnMs).thenComparingLong(t -> t.orden));

        void avanzarSegundos(long segundos) {
            avanzarMs(segundos * 1000L);
        }

        void avanzarMs(long deltaMs) {
            long destino = ahoraMs + deltaMs;
            while (!cola.isEmpty() && cola.peek().ejecutarEnMs <= destino) {
                TareaProgramada<?> siguiente = cola.poll();
                ahoraMs = siguiente.ejecutarEnMs;
                if (!siguiente.cancelada) {
                    siguiente.ejecutar();
                }
            }
            ahoraMs = destino;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            long cuando = ahoraMs + Math.max(0L, unit.toMillis(delay));
            TareaProgramada<Void> tarea = new TareaProgramada<>(() -> { command.run(); return null; }, cuando);
            cola.add(tarea);
            return tarea;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            long cuando = ahoraMs + Math.max(0L, unit.toMillis(delay));
            TareaProgramada<V> tarea = new TareaProgramada<>(callable, cuando);
            cola.add(tarea);
            return tarea;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public void shutdown() { apagado = true; }
        @Override public List<Runnable> shutdownNow() { apagado = true; cola.clear(); return List.of(); }
        @Override public boolean isShutdown() { return apagado; }
        @Override public boolean isTerminated() { return apagado && cola.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }

        private final class TareaProgramada<V> implements ScheduledFuture<V> {
            final Callable<V> accion;
            final long ejecutarEnMs;
            final long orden = SECUENCIA.incrementAndGet();
            boolean cancelada = false;
            boolean completada = false;
            V resultado;

            TareaProgramada(Callable<V> accion, long ejecutarEnMs) {
                this.accion = accion;
                this.ejecutarEnMs = ejecutarEnMs;
            }

            void ejecutar() {
                try {
                    resultado = accion.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    completada = true;
                }
            }

            @Override
            public long getDelay(TimeUnit unit) {
                return unit.convert(ejecutarEnMs - ahoraMs, TimeUnit.MILLISECONDS);
            }

            @Override
            public int compareTo(Delayed other) {
                return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (completada || cancelada) return false;
                cancelada = true;
                return true;
            }

            @Override public boolean isCancelled() { return cancelada; }
            @Override public boolean isDone() { return completada || cancelada; }
            @Override public V get() throws InterruptedException, ExecutionException { return resultado; }
            @Override public V get(long timeout, TimeUnit unit) throws TimeoutException { return resultado; }
        }
    }

    private static final class SalaPrueba implements TemporizadorSalaService.SalaNegociacion {
        final int p1Id = 10;
        final int p2Id = 20;
        boolean activa = true;
        boolean iniciada = false;
        boolean p1Conectado = true;
        boolean p2Conectado = true;
        boolean p1Voto = false;
        boolean p2Voto = false;
        int culpableCierre = 0;
        String motivoCierre = null;
        boolean penalizoDesconexion = false;
        boolean penalizoSalidaChat = false;
        final TemporizadorSalaService.EstadoTimers timers = new TemporizadorSalaService.EstadoTimers();

        @Override public int player1Id() { return p1Id; }
        @Override public int player2Id() { return p2Id; }
        @Override public String player1Username() { return "JugadorUno"; }
        @Override public String player2Username() { return "JugadorDos"; }
        @Override public boolean estaActivaEnServidor() { return activa; }
        @Override public boolean estaIniciadaOBloqueada() { return iniciada; }

        @Override
        public boolean estaConectado(int userId) {
            if (userId == p1Id) return p1Conectado;
            if (userId == p2Id) return p2Conectado;
            return false;
        }

        @Override
        public boolean cancelarVotoInicioSiExiste(int userId) {
            if (userId == p1Id && p1Voto) { p1Voto = false; return true; }
            if (userId == p2Id && p2Voto) { p2Voto = false; return true; }
            return false;
        }

        @Override
        public void cerrarSalaPorTimeout(int userIdCulpable, String motivo, String logTexto,
                                         boolean penalizarDesconexion, boolean penalizarSalidaChat) {
            this.activa = false;
            this.culpableCierre = userIdCulpable;
            this.motivoCierre = motivo;
            this.penalizoDesconexion = penalizarDesconexion;
            this.penalizoSalidaChat = penalizarSalidaChat;
        }

        @Override public TemporizadorSalaService.EstadoTimers estadoTimers() { return timers; }
    }

    @Test
    void tiempoDeDesconexionSoloCuentaPasados30SegundosDeGracia() {
        AppConfig config = new AppConfig(Map.of(
                "JWT_SECRET", "secreto-prueba-temporizador-sala-32-chars",
                "NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS", "30",
                "NEGOCIACION_DESCONEXION_SEGUNDOS", "90"
        ));
        RelojManualScheduler scheduler = new RelojManualScheduler();
        TemporizadorSalaService servicio = new TemporizadorSalaService(null, null, config, scheduler);
        SalaPrueba sala = new SalaPrueba();
        sala.p1Voto = true;
        servicio.registrarSala("sala_101", sala);

        // 1. P1 se desconecta pero vuelve a los 25s (< 30s de gracia): no cuenta desconexión ni pierde su voto
        sala.p1Conectado = false;
        servicio.alDesconectarEnNegociacion("sala_101", sala, 10, "JugadorUno", true);
        scheduler.avanzarSegundos(25);
        assertNull(servicio.obtenerSegundosDesconexionRestantes(sala, 10),
                "Antes de los 30s de gracia no debe correr el tiempo de desconexión.");
        assertTrue(sala.p1Voto);

        sala.p1Conectado = true;
        assertFalse(servicio.alReconectarJugador("sala_101", sala, 10, "JugadorUno"));
        scheduler.avanzarSegundos(200);
        assertTrue(sala.activa, "Reconectar dentro de los 30s cancela toda penalización.");

        // 2. P2 se desconecta y NO vuelve: a los 30s arranca el conteo de 90s y a los 120s totales se cierra la sala
        sala.p2Voto = true;
        sala.p2Conectado = false;
        servicio.alDesconectarEnNegociacion("sala_101", sala, 20, "JugadorDos", true);
        scheduler.avanzarSegundos(29);
        assertNull(servicio.obtenerSegundosDesconexionRestantes(sala, 20));
        assertTrue(sala.p2Voto);

        scheduler.avanzarSegundos(1); // t = 30s: vence la gracia y empieza el timer de 90s
        assertFalse(sala.p2Voto, "Al vencer los 30s de gracia se retira el voto de inicio.");
        assertNotNull(servicio.obtenerSegundosDesconexionRestantes(sala, 20));
        assertTrue(sala.activa);

        scheduler.avanzarSegundos(89);
        assertTrue(sala.activa);
        scheduler.avanzarSegundos(1); // t = 120s (30s gracia + 90s desconexión)
        assertFalse(sala.activa);
        assertEquals(20, sala.culpableCierre);
        assertTrue(sala.penalizoDesconexion);
        assertTrue(sala.penalizoSalidaChat);
    }

    @Test
    void entrarADiscordEximeElTiempoDeDesconexionUnicamenteLaPrimeraVez() {
        AppConfig config = new AppConfig(Map.of(
                "JWT_SECRET", "secreto-prueba-discord-exencion-32-chars",
                "NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS", "30",
                "NEGOCIACION_DESCONEXION_SEGUNDOS", "90"
        ));
        RelojManualScheduler scheduler = new RelojManualScheduler();
        EventBus bus = new EventBus();
        TemporizadorSalaService servicio = new TemporizadorSalaService(null, null, config, scheduler);
        servicio.registrar(bus);

        SalaPrueba sala = new SalaPrueba();
        servicio.registrarSala("sala_202", sala);

        // Primera vez: abre Discord desde celular y el navegador corta el socket
        bus.emit(Eventos.VOICE_DISCORD_OPENED, new Eventos.AperturaDiscordSala(10, "sala_202"));
        assertEquals(TemporizadorSalaService.EXENCION_ARMADA, sala.timers.getDiscordExencionP1());

        sala.p1Conectado = false;
        servicio.alDesconectarEnNegociacion("sala_202", sala, 10, "JugadorUno", true);
        assertEquals(TemporizadorSalaService.EXENCION_ACTIVA, sala.timers.getDiscordExencionP1());

        // Pasan 10 minutos completos en Discord: NO cuenta tiempo de desconexión por esa única vez
        scheduler.avanzarSegundos(600);
        assertTrue(sala.activa, "La primera ida a Discord no debe cerrar la sala por desconexión.");
        assertNull(servicio.obtenerSegundosDesconexionRestantes(sala, 10));

        // Regresa del bot/canal de Discord: la exención queda marcada como 'usada'
        sala.p1Conectado = true;
        servicio.alReconectarJugador("sala_202", sala, 10, "JugadorUno");
        assertEquals(TemporizadorSalaService.EXENCION_USADA, sala.timers.getDiscordExencionP1());

        // Segunda desconexión (incluso si vuelve a pulsar Discord): ahora SÍ cuenta tras los 30s de gracia
        bus.emit(Eventos.VOICE_DISCORD_OPENED, new Eventos.AperturaDiscordSala(10, "sala_202"));
        assertEquals(TemporizadorSalaService.EXENCION_USADA, sala.timers.getDiscordExencionP1());

        sala.p1Conectado = false;
        servicio.alDesconectarEnNegociacion("sala_202", sala, 10, "JugadorUno", true);
        scheduler.avanzarSegundos(30);
        assertNotNull(servicio.obtenerSegundosDesconexionRestantes(sala, 10),
                "En la segunda desconexión sí debe empezar a contar pasados 30s.");
        scheduler.avanzarSegundos(90);
        assertFalse(sala.activa);
        assertEquals(10, sala.culpableCierre);
    }

    @Test
    void carreraMovilCuandoSocketCaeMilisegundosAntesDelEventoDiscordCancelaGraciaSoloUnaVez() {
        AppConfig config = new AppConfig(Map.of(
                "JWT_SECRET", "secreto-prueba-carrera-discord-32-chars",
                "NEGOCIACION_GRACIA_DESCONEXION_SEGUNDOS", "30",
                "NEGOCIACION_DESCONEXION_SEGUNDOS", "90"
        ));
        RelojManualScheduler scheduler = new RelojManualScheduler();
        TemporizadorSalaService servicio = new TemporizadorSalaService(null, null, config, scheduler);
        SalaPrueba sala = new SalaPrueba();
        servicio.registrarSala("sala_203", sala);

        sala.p1Conectado = false;
        servicio.alDesconectarEnNegociacion("sala_203", sala, 10, "JugadorUno", true);
        scheduler.avanzarSegundos(2);
        servicio.marcarAperturaDiscord(10, "sala_203");
        assertEquals(TemporizadorSalaService.EXENCION_ACTIVA, sala.timers.getDiscordExencionP1());

        scheduler.avanzarSegundos(300);
        assertTrue(sala.activa);
    }

    @Test
    void temporizadorOpcional230EsIndividualYLaAccionDeUnJugadorNoAumentaElTiempoDelOtro() throws InterruptedException {
        AppConfig config = new AppConfig(Map.of(
                "JWT_SECRET", "secreto-prueba-showdown-timer-32-chars",
                "NEGOCIACION_TIMER_ACCION_SEGUNDOS", "2"
        ));
        RelojManualScheduler scheduler = new RelojManualScheduler();
        TemporizadorSalaService servicio = new TemporizadorSalaService(null, null, config, scheduler);
        SalaPrueba sala = new SalaPrueba();
        servicio.registrarSala("sala_303", sala);

        // Cualquiera de los dos activa el reloj opcional
        JsonObject activado = servicio.activarTimerAccion("sala_303", sala, 10);
        assertNotNull(activado);
        assertTrue(activado.get("activo").getAsBoolean());
        assertTrue(activado.get("recienActivado").getAsBoolean());
        assertEquals("JugadorUno", activado.get("activadoPor").getAsString());

        // Avanzamos tiempo real (~450 ms) y el reloj del scheduler para comprobar que solo se reinicia quien actúa
        Thread.sleep(450);
        scheduler.avanzarMs(450);

        long p2AntesDeAccionP1 = servicio.construirEstadoTimerJson("sala_303", sala, false)
                .get("p2RestanteMs").getAsLong();
        assertTrue(p2AntesDeAccionP1 < 1800L, "El reloj del rival debe haber disminuido.");

        // P1 realiza una acción (enviar mensaje, cambiar modo/monto o presionar comenzar)
        servicio.registrarAccionJugador("sala_303", sala, 10);
        JsonObject trasAccionP1 = servicio.construirEstadoTimerJson("sala_303", sala, false);
        long p1TrasAccion = trasAccionP1.get("p1RestanteMs").getAsLong();
        long p2TrasAccion = trasAccionP1.get("p2RestanteMs").getAsLong();

        assertTrue(p1TrasAccion >= 1900L, "El reloj de P1 debe reiniciarse al máximo.");
        assertTrue(p2TrasAccion <= p2AntesDeAccionP1,
                "La acción de P1 NUNCA debe aumentar el tiempo restante de P2.");

        // Dejamos vencer el reloj de P2 mientras P1 tenía más margen tras su reinicio
        Thread.sleep(1650);
        scheduler.avanzarMs(1650);

        assertFalse(sala.activa);
        assertEquals(20, sala.culpableCierre, "Debe vencer únicamente el jugador inactivo (P2).");
        assertFalse(sala.penalizoDesconexion);
        assertTrue(sala.penalizoSalidaChat);
    }

    @Test
    void restaurarDesdeBdReconstruyeExencionYRelojesRestantes() throws InterruptedException {
        AppConfig config = new AppConfig(Map.of(
                "JWT_SECRET", "secreto-prueba-restauracion-bd-32-chars",
                "NEGOCIACION_TIMER_ACCION_SEGUNDOS", "150"
        ));
        RelojManualScheduler scheduler = new RelojManualScheduler();
        TemporizadorSalaService servicio = new TemporizadorSalaService(null, null, config, scheduler);
        SalaPrueba sala = new SalaPrueba();
        servicio.registrarSala("sala_404", sala);

        JsonObject row = new JsonObject();
        row.addProperty("discord_exencion_p1", "activa");
        row.addProperty("discord_exencion_p2", "usada");
        row.addProperty("timer_activo", true);
        row.addProperty("timer_activado_por", 20);
        row.addProperty("timer_p1_restante_ms", 1000);
        row.addProperty("timer_p2_restante_ms", 90000);

        servicio.restaurarDesdeBD("sala_404", sala, row);
        assertEquals("activa", sala.timers.getDiscordExencionP1());
        assertEquals("usada", sala.timers.getDiscordExencionP2());
        assertTrue(sala.timers.isTimerAccionActivo());

        Thread.sleep(1050);
        scheduler.avanzarMs(1050);
        assertFalse(sala.activa);
        assertEquals(10, sala.culpableCierre);
    }
}
