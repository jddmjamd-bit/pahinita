package com.torneosflash.config;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pools de hilos de la aplicación (A8). Un solo lugar decide cuántos hilos hay y para qué sirve cada pool,
 * así el trabajo lento no deja sin hilos a las peticiones de los usuarios.
 *
 * <ul>
 *   <li><b>http</b> (Jetty, {@link #crearPoolHttp()}): atiende las peticiones HTTP y los mensajes de Socket.IO.
 *       Acotado (antes era el default de Javalin: hasta 250 hilos, demasiado para 512 MB de RAM y para
 *       un pool de BD de 20 conexiones).</li>
 *   <li><b>io</b> ({@link #ejecutarIO}): tareas que esperan red y no deben bloquear a un usuario
 *       (push FCM, correos Brevo, sondeo de la API de Clash). Cola acotada: si se llena, la tarea se descarta y se avisa.</li>
 *   <li><b>cpu</b> ({@link #enCpu}): trabajo que gasta procesador (hash BCrypt). Un hilo por núcleo: más hilos solo
 *       harían que se estorben entre sí y dejarían sin CPU al resto del servidor.</li>
 *   <li><b>timers</b> ({@link #timers()}): solo temporizadores cortos (timeouts de búsqueda, abandono, revisión de
 *       sorteos). NUNCA debe correr HTTP externo aquí: si un temporizador se bloquea, los demás se retrasan.</li>
 * </ul>
 *
 * Los pools propios son hilos daemon con nombre (se ven como {@code io-1}, {@code cpu-1}, {@code timer-1} en un
 * volcado de hilos) y registran las excepciones que se les escapen.
 */
public class Ejecutores {
    private static final Logger logger = LoggerFactory.getLogger(Ejecutores.class);

    private final AppConfig config;
    private final ThreadPoolExecutor io;
    private final ThreadPoolExecutor cpu;
    private final ScheduledThreadPoolExecutor timers;

    public Ejecutores(AppConfig config) {
        this.config = config;

        // IO: hilos de red. Los hilos ociosos se liberan para no gastar RAM cuando no hay tráfico.
        this.io = new ThreadPoolExecutor(
                config.getIoPoolThreads(), config.getIoPoolThreads(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getIoPoolQueue()),
                fabrica("io"));
        this.io.allowCoreThreadTimeOut(true);

        // CPU: si la cola se llena, el hilo que pidió el trabajo lo hace él mismo (contrapresión; nunca se pierde un hash).
        this.cpu = new ThreadPoolExecutor(
                config.getCpuPoolThreads(), config.getCpuPoolThreads(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getCpuPoolQueue()),
                fabrica("cpu"),
                (tarea, pool) -> {
                    if (pool.isShutdown()) {
                        throw new RejectedExecutionException("El pool de CPU está cerrado");
                    }
                    tarea.run();
                });
        this.cpu.allowCoreThreadTimeOut(true);

        // Timers: las tareas canceladas (búsquedas de 10 min, timers de 90 s) salen de la cola al cancelarse
        // en vez de quedarse en memoria hasta que venzan.
        this.timers = new ScheduledThreadPoolExecutor(config.getTimerPoolThreads(), fabrica("timer"));
        this.timers.setRemoveOnCancelPolicy(true);

        logger.info("🧵 Pools de hilos: HTTP {}-{} (ocioso {} ms) | IO {} (cola {}) | CPU {} (cola {}) | timers {} | BD max {}",
                config.getHttpMinThreads(), config.getHttpMaxThreads(), config.getHttpIdleTimeoutMs(),
                config.getIoPoolThreads(), config.getIoPoolQueue(),
                config.getCpuPoolThreads(), config.getCpuPoolQueue(),
                config.getTimerPoolThreads(), config.getDbPoolMax());
    }

    /** Pool de Jetty para {@code config.jetty.threadPool} de Javalin. */
    public QueuedThreadPool crearPoolHttp() {
        QueuedThreadPool pool = new QueuedThreadPool(
                config.getHttpMaxThreads(), config.getHttpMinThreads(), config.getHttpIdleTimeoutMs());
        pool.setName("http");
        return pool;
    }

    /**
     * Encola una tarea que espera red (push, correo, sondeo). Es "mejor esfuerzo": si el pool está lleno o
     * cerrado la tarea se descarta, se avisa en el log y se devuelve {@code false}. Nunca lanza.
     * La tarea NO debe depender de otra tarea del mismo pool (podría quedarse esperando para siempre).
     */
    public boolean ejecutarIO(String descripcion, Runnable tarea) {
        try {
            io.execute(tarea);
            return true;
        } catch (RejectedExecutionException e) {
            logger.warn("⚠️ Pool de IO lleno o cerrado: se descarta '{}' (cola {}/{}).",
                    descripcion, io.getQueue().size(), config.getIoPoolQueue());
            return false;
        }
    }

    /**
     * Ejecuta trabajo de CPU en el pool de CPU y espera el resultado. Así, aunque lleguen cien logins a la vez,
     * solo corren tantos hashes como núcleos haya. Las excepciones de la tarea se relanzan tal cual.
     */
    public <T> T enCpu(Callable<T> tarea) throws Exception {
        Future<T> futuro = cpu.submit(tarea);
        try {
            return futuro.get();
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof Exception) throw (Exception) causa;
            if (causa instanceof Error) throw (Error) causa;
            throw e;
        } catch (InterruptedException e) {
            futuro.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Temporizadores cortos (no bloquear aquí con HTTP externo; ver la descripción de la clase). */
    public ScheduledExecutorService timers() {
        return timers;
    }

    /** Apagado ordenado: los temporizadores se cancelan, a IO y CPU se les deja terminar lo que ya tienen. */
    public void cerrar() {
        timers.shutdownNow();
        io.shutdown();
        cpu.shutdown();
        esperar("io", io, 5);
        esperar("cpu", cpu, 2);
    }

    private static void esperar(String nombre, ThreadPoolExecutor pool, int segundos) {
        try {
            if (!pool.awaitTermination(segundos, TimeUnit.SECONDS)) {
                int pendientes = pool.shutdownNow().size();
                logger.warn("⚠️ Pool {} no terminó en {} s: {} tareas descartadas.", nombre, segundos, pendientes);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory fabrica(String prefijo) {
        final AtomicInteger contador = new AtomicInteger(1);
        return tarea -> {
            Thread hilo = new Thread(tarea, prefijo + "-" + contador.getAndIncrement());
            hilo.setDaemon(true);
            hilo.setUncaughtExceptionHandler((t, e) ->
                    logger.error("Excepción no controlada en el hilo {}: {}", t.getName(), e.toString(), e));
            return hilo;
        };
    }
}
