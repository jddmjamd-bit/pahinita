package com.torneosflash.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ComisionService — Única fuente de verdad de la política de comisiones (D4).
 *
 * Qué hace:
 *  - Calcula el porcentaje de comisión escalonado según el pozo de la partida.
 *  - Reparte la comisión entre las categorías (sorteos, misiones, logros, leaderboard,
 *    devolución, referidos y ganancia del dueño).
 *  - Expone la distribución para que el frontend NO replique porcentajes ni fórmulas.
 *
 * Qué NO hace: no toca la base de datos ni mueve dinero. Es lógica pura (sin estado);
 * quien mueve el dinero es {@link WalletService}, que usa este servicio al liquidar.
 *
 * Reglas:
 *  - Todo con BigDecimal (nunca double).
 *  - Cada categoría se redondea hacia abajo a pesos enteros; la "ganancia" es el residuo,
 *    así que la suma de todas las categorías es SIEMPRE igual a la comisión total.
 *
 * // REVIEW-MONEY — Archivo completo trata con dinero real.
 */
public class ComisionService {

    // ═══════════════════════════════════════════
    // POLÍTICA DE COMISIÓN (editar aquí y solo aquí)
    // ═══════════════════════════════════════════

    /** Pozo (monto x 2) con el que se cobra el porcentaje máximo. */
    private static final BigDecimal POZO_MINIMO = BigDecimal.valueOf(2000);
    /** Pozo (monto x 2) a partir del cual se cobra el porcentaje mínimo. */
    private static final BigDecimal POZO_MAXIMO = BigDecimal.valueOf(20000);
    /** Comisión máxima (pozo pequeño): 25%. */
    private static final BigDecimal PORCENTAJE_MAXIMO = new BigDecimal("0.25");
    /** Comisión mínima (pozo grande): 10%. */
    private static final BigDecimal PORCENTAJE_MINIMO = new BigDecimal("0.10");

    private static final BigDecimal DOS = BigDecimal.valueOf(2);

    /**
     * Categorías en las que se reparte la comisión de cada partida.
     * El orden de declaración es el orden en que se registran en {@code admin_wallet}
     * y en que se muestran en el panel admin.
     *
     * La fracción es el porcentaje DE LA COMISIÓN (no del pozo). {@link #GANANCIA} no tiene
     * fracción fija: es lo que sobra, para que el reparto siempre cuadre al peso.
     */
    public enum Categoria {
        SORTEOS("sorteos", "0.20"),
        MISIONES("misiones", "0.10"),
        LOGROS("logros", "0.05"),
        LEADERBOARD("leaderboard", "0.15"),
        DEVOLUCION("devolucion", "0.15"),
        GANANCIA("ganancia", null),
        REFERIDOS("referidos", "0.10");

        private final String clave;
        private final BigDecimal fraccion;

        Categoria(String clave, String fraccion) {
            this.clave = clave;
            this.fraccion = fraccion != null ? new BigDecimal(fraccion) : null;
        }

        /** Valor guardado en {@code admin_wallet.categoria}. */
        public String getClave() { return clave; }

        /** Fracción fija de la comisión (0.20 = 20%), o null si es el residuo. */
        public BigDecimal getFraccion() { return fraccion; }

        /** true si la categoría recibe lo que sobra del reparto (ganancia del dueño). */
        public boolean esResiduo() { return fraccion == null; }
    }

    // ═══════════════════════════════════════════
    // RESULTADO DEL CÁLCULO
    // ═══════════════════════════════════════════

    /**
     * Desglose inmutable de la comisión de una partida.
     */
    public static final class Desglose {
        private final BigDecimal montoPorJugador;
        private final BigDecimal pozo;
        private final BigDecimal porcentaje;
        private final BigDecimal comisionTotal;
        private final BigDecimal premio;
        private final Map<Categoria, BigDecimal> montos;

        private Desglose(BigDecimal montoPorJugador, BigDecimal pozo, BigDecimal porcentaje,
                         BigDecimal comisionTotal, BigDecimal premio, Map<Categoria, BigDecimal> montos) {
            this.montoPorJugador = montoPorJugador;
            this.pozo = pozo;
            this.porcentaje = porcentaje;
            this.comisionTotal = comisionTotal;
            this.premio = premio;
            this.montos = Collections.unmodifiableMap(montos);
        }

        /** Lo que puso cada jugador. */
        public BigDecimal getMontoPorJugador() { return montoPorJugador; }
        /** Total en juego (monto x 2). */
        public BigDecimal getPozo() { return pozo; }
        /** Porcentaje de comisión aplicado al pozo (0.25 = 25%). */
        public BigDecimal getPorcentaje() { return porcentaje; }
        /** Comisión total cobrada (pozo - premio), en pesos enteros. */
        public BigDecimal getComisionTotal() { return comisionTotal; }
        /** Lo que recibe el ganador (pozo - comisión). */
        public BigDecimal getPremio() { return premio; }

        /** Monto de una categoría, en pesos enteros. */
        public BigDecimal getMonto(Categoria categoria) { return montos.get(categoria); }

        /** Todas las categorías en orden de declaración. No modificable. */
        public Map<Categoria, BigDecimal> getMontos() { return montos; }

        /**
         * Mitad de una categoría que se atribuye a cada jugador (ambos generaron la comisión).
         * Escala 2, HALF_UP. Es la misma cuenta que se usa para las columnas {@code gen_*} de users.
         */
        public BigDecimal porJugador(Categoria categoria) {
            return getMonto(categoria).divide(DOS, 2, RoundingMode.HALF_UP);
        }

        /** Ganancia del dueño atribuida a cada jugador (columna {@code ganancia_generada}). */
        public BigDecimal utilidadPorJugador() {
            return porJugador(Categoria.GANANCIA);
        }
    }

    // ═══════════════════════════════════════════
    // CÁLCULO
    // ═══════════════════════════════════════════

    /**
     * Calcula la comisión y su reparto para una partida.
     *
     * @param montoPorJugador lo que puso cada jugador (debe ser positivo)
     * @return desglose completo; la suma de {@link Desglose#getMontos()} es igual a la comisión total
     * @throws IllegalArgumentException si el monto es nulo o no es positivo
     */
    // REVIEW-MONEY
    public Desglose calcular(BigDecimal montoPorJugador) {
        if (montoPorJugador == null || montoPorJugador.signum() <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo");
        }

        BigDecimal pozo = montoPorJugador.multiply(DOS);
        BigDecimal porcentaje = calcularPorcentaje(pozo);
        BigDecimal comision = pozo.multiply(porcentaje).setScale(0, RoundingMode.FLOOR);
        BigDecimal premio = pozo.subtract(comision);

        Map<Categoria, BigDecimal> montos = new EnumMap<>(Categoria.class);
        BigDecimal repartido = BigDecimal.ZERO;
        for (Categoria categoria : Categoria.values()) {
            if (categoria.esResiduo()) continue;
            BigDecimal monto = comision.multiply(categoria.getFraccion()).setScale(0, RoundingMode.FLOOR);
            montos.put(categoria, monto);
            repartido = repartido.add(monto);
        }
        // La ganancia es el residuo: así el reparto siempre suma exactamente la comisión.
        montos.put(Categoria.GANANCIA, comision.subtract(repartido));

        return new Desglose(montoPorJugador, pozo, porcentaje, comision, premio, montos);
    }

    /**
     * Porcentaje de comisión escalonado según el pozo: 25% con pozo de 2.000, baja
     * linealmente hasta 10% con pozo de 20.000 o más.
     *
     * @param pozo monto total en juego (monto x 2)
     * @return porcentaje entre 0.10 y 0.25
     */
    // REVIEW-MONEY
    public static BigDecimal calcularPorcentaje(BigDecimal pozo) {
        BigDecimal rangoPozo = POZO_MAXIMO.subtract(POZO_MINIMO);
        BigDecimal rangoPorcentaje = PORCENTAJE_MAXIMO.subtract(PORCENTAJE_MINIMO);

        BigDecimal avance = pozo.subtract(POZO_MINIMO).divide(rangoPozo, 10, RoundingMode.HALF_UP);
        BigDecimal porcentaje = PORCENTAJE_MAXIMO.subtract(avance.multiply(rangoPorcentaje));

        if (porcentaje.compareTo(PORCENTAJE_MAXIMO) > 0) porcentaje = PORCENTAJE_MAXIMO;
        if (porcentaje.compareTo(PORCENTAJE_MINIMO) < 0) porcentaje = PORCENTAJE_MINIMO;
        return porcentaje;
    }

    // ═══════════════════════════════════════════
    // DISTRIBUCIÓN (para mostrar en el panel admin)
    // ═══════════════════════════════════════════

    /**
     * Fracción de la comisión que recibe cada categoría (0.20 = 20%). La ganancia del
     * dueño es lo que sobra (1 - suma del resto), de modo que siempre suma 100%.
     */
    public Map<Categoria, BigDecimal> distribucion() {
        Map<Categoria, BigDecimal> fracciones = new LinkedHashMap<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (Categoria categoria : Categoria.values()) {
            if (categoria.esResiduo()) continue;
            fracciones.put(categoria, categoria.getFraccion());
            suma = suma.add(categoria.getFraccion());
        }
        fracciones.put(Categoria.GANANCIA, BigDecimal.ONE.subtract(suma));

        // Se reordena con el orden de declaración del enum (ganancia no va al final)
        Map<Categoria, BigDecimal> ordenado = new LinkedHashMap<>();
        for (Categoria categoria : Categoria.values()) {
            ordenado.put(categoria, fracciones.get(categoria));
        }
        return Collections.unmodifiableMap(ordenado);
    }
}
