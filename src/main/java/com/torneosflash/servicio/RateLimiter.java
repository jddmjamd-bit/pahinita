package com.torneosflash.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limitador de solicitudes por clave con ventana deslizante de 1 minuto (S8).
 *
 * Es lógica pura: no conoce Javalin ni HTTP. El middleware ({@code RateLimitMiddleware})
 * decide QUÉ clave y QUÉ límite usar (p. ej. {@code "financiero|203.0.113.7"} y 10) y aquí
 * solo se cuenta.
 *
 * Funcionamiento: por cada clave se guardan los instantes de las solicitudes PERMITIDAS en el
 * último minuto. Si ya hay {@code limite} dentro de la ventana, la solicitud se rechaza (y no se
 * guarda, así que una clave nunca ocupa más de {@code limite} entradas). La ventana se desliza:
 * no hay "picos" al cambiar de minuto como en una ventana fija.
 *
 * Todo el estado vive en memoria (igual que las colas de partidas): al reiniciar el servidor los
 * contadores empiezan en cero. Es suficiente para anti-spam; no es una garantía contable.
 *
 * Seguridad de hilos: cada clave se modifica dentro de {@link ConcurrentHashMap#compute}, que es
 * atómico por clave, así que dos solicitudes simultáneas de la misma IP no pueden "colarse" juntas.
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    /** Ventana de conteo: los límites se expresan en solicitudes por minuto. */
    public static final long VENTANA_MS = 60_000L;

    /** Tope de claves distintas en memoria (protege la RAM si alguien rota de IP). */
    static final int MAX_CLAVES = 100_000;

    private static final long INTERVALO_LIMPIEZA_MS = 60_000L;

    private final ConcurrentHashMap<String, ArrayDeque<Long>> solicitudes = new ConcurrentHashMap<>();
    /** Última vez que se avisó (log) del bloqueo de cada clave, para no llenar el log de avisos repetidos. */
    private final ConcurrentHashMap<String, Long> avisos = new ConcurrentHashMap<>();
    private final AtomicLong ultimaLimpieza = new AtomicLong(0L);
    private final AtomicLong ultimoAvisoLleno = new AtomicLong(0L);

    /** Resultado inmutable de un intento. */
    public static final class Resultado {
        private final boolean permitido;
        private final int limite;
        private final int restantes;
        private final long reintentarEnSegundos;
        private final boolean primerBloqueo;

        private Resultado(boolean permitido, int limite, int restantes, long reintentarEnSegundos, boolean primerBloqueo) {
            this.permitido = permitido;
            this.limite = limite;
            this.restantes = restantes;
            this.reintentarEnSegundos = reintentarEnSegundos;
            this.primerBloqueo = primerBloqueo;
        }

        static Resultado permitido(int limite, int restantes) {
            return new Resultado(true, limite, restantes, 0L, false);
        }

        static Resultado bloqueado(int limite, long reintentarEnSegundos, boolean primerBloqueo) {
            return new Resultado(false, limite, 0, reintentarEnSegundos, primerBloqueo);
        }

        public boolean isPermitido() { return permitido; }
        public int getLimite() { return limite; }
        /** Solicitudes que aún se pueden hacer en esta ventana (0 si fue bloqueada). */
        public int getRestantes() { return restantes; }
        /** Segundos hasta que se libere un cupo (mínimo 1). Solo tiene sentido si fue bloqueada. */
        public long getReintentarEnSegundos() { return reintentarEnSegundos; }
        /** true solo en el primer bloqueo de la clave dentro de la ventana (para loguear una vez). */
        public boolean isPrimerBloqueo() { return primerBloqueo; }
    }

    /** Registra un intento de la clave con el límite dado (solicitudes por minuto). */
    public Resultado intentar(String clave, int limite) {
        return intentar(clave, limite, System.currentTimeMillis());
    }

    /** Igual que {@link #intentar(String, int)} pero con el reloj inyectado (para pruebas). */
    public Resultado intentar(String clave, int limite, long ahoraMs) {
        if (limite <= 0) {
            // Límite no configurado o inválido: no se limita (AppConfig ya impide llegar aquí con valores < 1)
            return Resultado.permitido(limite, Integer.MAX_VALUE);
        }

        limpiarSiToca(ahoraMs);

        // Protección de memoria: con demasiadas claves nuevas no se rastrea la nueva (se deja pasar y se avisa)
        if (solicitudes.size() >= MAX_CLAVES && !solicitudes.containsKey(clave)) {
            long ultimo = ultimoAvisoLleno.get();
            if (ahoraMs - ultimo >= INTERVALO_LIMPIEZA_MS && ultimoAvisoLleno.compareAndSet(ultimo, ahoraMs)) {
                logger.warn("🚦 RateLimiter lleno ({} claves): las claves nuevas no se están limitando.", MAX_CLAVES);
            }
            return Resultado.permitido(limite, limite);
        }

        final long desde = ahoraMs - VENTANA_MS;
        // salida[0] = solicitudes restantes tras este intento, o -1 si se bloquea; salida[1] = ms hasta el próximo cupo
        final long[] salida = new long[2];

        solicitudes.compute(clave, (k, actual) -> {
            ArrayDeque<Long> cola = (actual != null) ? actual : new ArrayDeque<>();
            while (!cola.isEmpty() && cola.peekFirst() <= desde) {
                cola.pollFirst();
            }
            if (cola.size() >= limite) {
                salida[0] = -1L;
                salida[1] = cola.peekFirst() + VENTANA_MS - ahoraMs;
            } else {
                cola.addLast(ahoraMs);
                salida[0] = limite - cola.size();
            }
            return cola;
        });

        if (salida[0] >= 0) {
            return Resultado.permitido(limite, (int) salida[0]);
        }

        long segundos = Math.max(1L, (salida[1] + 999L) / 1000L);
        Long previo = avisos.get(clave);
        boolean primero = previo == null || ahoraMs - previo >= VENTANA_MS;
        if (primero) {
            avisos.put(clave, ahoraMs);
        }
        return Resultado.bloqueado(limite, segundos, primero);
    }

    /** Número de claves rastreadas ahora mismo (útil para diagnóstico y pruebas). */
    public int claves() {
        return solicitudes.size();
    }

    /** Borra las claves sin actividad en la última ventana. Se ejecuta como máximo una vez por minuto. */
    private void limpiarSiToca(long ahoraMs) {
        long ultima = ultimaLimpieza.get();
        if (ahoraMs - ultima < INTERVALO_LIMPIEZA_MS) return;
        if (!ultimaLimpieza.compareAndSet(ultima, ahoraMs)) return;

        final long desde = ahoraMs - VENTANA_MS;
        for (String clave : solicitudes.keySet()) {
            // computeIfPresent es atómico con compute(): no se pierde un intento que llegue justo ahora
            solicitudes.computeIfPresent(clave,
                    (k, cola) -> (cola.isEmpty() || cola.peekLast() <= desde) ? null : cola);
        }
        avisos.values().removeIf(t -> t <= desde);
    }
}
