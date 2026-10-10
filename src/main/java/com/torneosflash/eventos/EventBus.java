package com.torneosflash.eventos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bus de eventos en memoria (A4). Quien termina una acción de negocio (una partida, un pago...) avisa con
 * {@link #emit(String, Object)} y NO sabe quién escucha; cada funcionalidad nueva (notificaciones, tickets,
 * misiones, leaderboard...) se suscribe con {@link #on(String, String, Listener)} sin tocar al emisor.
 *
 * <p>Los nombres de los eventos y sus datos están en {@link Eventos}.
 *
 * <p><b>Reglas de funcionamiento</b></p>
 * <ul>
 *   <li><b>Síncrono:</b> los listeners corren en el mismo hilo que emite, uno tras otro y en el orden en que se
 *       registraron. Así el orden de lo que reciben los clientes es predecible y no se pierde ningún evento (no hay
 *       cola que se pueda llenar). Consecuencia: un listener NO debe bloquearse con HTTP externo; si necesita red
 *       (push, correo) debe usar {@code Ejecutores.ejecutarIO}, como ya hacen {@code NotificacionPushServicio} y
 *       {@code CorreoServicio}.</li>
 *   <li><b>Aislado:</b> si un listener lanza una excepción se registra en el log y los demás listeners siguen
 *       corriendo; {@code emit} nunca lanza, así un listener roto no puede tumbar una liquidación ni un handler.</li>
 *   <li><b>Se emite DESPUÉS de confirmar el cambio:</b> el emisor avisa cuando el dinero ya se movió y la BD ya quedó
 *       consistente (el evento cuenta lo que pasó, no lo que va a pasar). Los listeners no deben revertir nada.</li>
 *   <li><b>Sin persistencia:</b> si el servidor se reinicia a mitad de un evento, ese evento no se repite. Todo lo que
 *       deba ser atómico con el dinero (saldo, comisiones, estadísticas) va dentro de la transacción de
 *       {@code WalletService}, NO en un listener.</li>
 * </ul>
 */
public class EventBus {
    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    /** Tope de eventos anidados (un listener que emite otro evento que a su vez...): evita bucles infinitos. */
    private static final int PROFUNDIDAD_MAXIMA = 8;

    /** Código que reacciona a un evento. Puede lanzar excepciones: el bus las registra y sigue. */
    @FunctionalInterface
    public interface Listener {
        void manejar(Evento evento) throws Exception;
    }

    /** Lo que recibe cada listener: nombre del evento, sus datos y cuándo se emitió. */
    public record Evento(String nombre, Object datos, Instant fecha) {
        /** Datos del evento con su tipo concreto (ver las clases de {@link Eventos}). */
        public <T> T datos(Class<T> tipo) {
            return tipo.cast(datos);
        }
    }

    private record Suscripcion(String id, Listener listener) {}

    private final Map<String, List<Suscripcion>> suscripciones = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> profundidad = ThreadLocal.withInitial(() -> 0);

    /**
     * Suscribe un listener a un evento. Se invoca en el orden de registro.
     *
     * @param nombre     nombre del evento (constantes de {@link Eventos})
     * @param idListener identificador único del listener para ese evento; aparece en los logs. Si ya hay uno con el
     *                   mismo id suscrito al mismo evento se ignora (así registrar dos veces no duplica efectos,
     *                   p. ej. dar los tickets dos veces)
     * @throws IllegalArgumentException si algún argumento falta (error de programación: se ve al arrancar)
     */
    public synchronized void on(String nombre, String idListener, Listener listener) {
        if (nombre == null || nombre.isBlank() || idListener == null || idListener.isBlank() || listener == null) {
            throw new IllegalArgumentException("on() necesita nombre de evento, id de listener y listener");
        }
        List<Suscripcion> lista = suscripciones.computeIfAbsent(nombre, k -> new CopyOnWriteArrayList<>());
        for (Suscripcion existente : lista) {
            if (existente.id().equals(idListener)) {
                logger.warn("Listener '{}' ya estaba suscrito a '{}': se ignora el duplicado", idListener, nombre);
                return;
            }
        }
        lista.add(new Suscripcion(idListener, listener));
        logger.info("📣 Listener '{}' suscrito a '{}'", idListener, nombre);
    }

    /**
     * Avisa a todos los listeners del evento. No lanza nunca.
     *
     * @param nombre nombre del evento (constantes de {@link Eventos})
     * @param datos  datos del evento (el tipo lo define {@link Eventos} para cada nombre)
     * @return cuántos listeners lo procesaron sin error (0 si nadie escucha o el evento se descartó)
     */
    public int emit(String nombre, Object datos) {
        if (nombre == null || nombre.isBlank()) {
            logger.warn("emit() sin nombre de evento: se ignora");
            return 0;
        }
        List<Suscripcion> lista = suscripciones.get(nombre);
        if (lista == null || lista.isEmpty()) {
            logger.debug("Evento '{}' emitido sin listeners", nombre);
            return 0;
        }

        int nivel = profundidad.get();
        if (nivel >= PROFUNDIDAD_MAXIMA) {
            logger.error("Evento '{}' descartado: más de {} eventos anidados (¿un listener emite en bucle?)",
                    nombre, PROFUNDIDAD_MAXIMA);
            return 0;
        }

        Evento evento = new Evento(nombre, datos, Instant.now());
        int procesados = 0;
        profundidad.set(nivel + 1);
        try {
            for (Suscripcion s : lista) {
                try {
                    s.listener().manejar(evento);
                    procesados++;
                } catch (Exception e) {
                    logger.error("❌ Listener '{}' falló con el evento '{}': {}", s.id(), nombre, e.toString(), e);
                }
            }
        } finally {
            if (nivel == 0) profundidad.remove(); else profundidad.set(nivel);
        }
        return procesados;
    }
}
