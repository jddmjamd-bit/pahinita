package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.torneosflash.config.AppConfig;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ValidadorMonto — Valida en el servidor todo monto que llega del cliente (D5).
 *
 * Regla: nunca se confía en lo que manda el cliente. Un monto es válido solo si:
 *  - Es un número (JSON number o string numérico). Se rechazan null, booleanos, objetos, arrays,
 *    notación científica (1e9), NaN/Infinity, signos "+" y texto con basura.
 *  - Es positivo (> 0).
 *  - Es en pesos enteros (se aceptan "5000" y "5000.00", se rechaza "5000.5").
 *  - Está dentro del rango configurado para su tipo (AppConfig: MONTO_MIN_* / MONTO_MAX_*).
 *
 * Devuelve siempre un {@link BigDecimal} con escala 0, listo para pasarlo a {@code WalletService}.
 *
 * // REVIEW-MONEY — Archivo completo trata con dinero real.
 */
public class ValidadorMonto {

    /** Solo dígitos con signo opcional y decimales opcionales. Sin exponente, sin espacios internos. */
    private static final Pattern NUMERO = Pattern.compile("-?[0-9]{1,18}(\\.[0-9]{1,6})?");

    private final BigDecimal minDeposito;
    private final BigDecimal maxDeposito;
    private final BigDecimal minRetiro;
    private final BigDecimal maxRetiro;
    private final BigDecimal minTorneo;
    private final BigDecimal maxTorneo;
    private final BigDecimal minSorteo;
    private final BigDecimal maxSorteo;

    public ValidadorMonto(AppConfig config) {
        this.minDeposito = config.getMontoMinDeposito();
        this.maxDeposito = config.getMontoMaxDeposito();
        this.minRetiro = config.getMontoMinRetiro();
        this.maxRetiro = config.getMontoMaxRetiro();
        this.minTorneo = config.getMontoMinTorneo();
        this.maxTorneo = config.getMontoMaxTorneo();
        this.minSorteo = config.getMontoMinSorteo();
        // El precio del sorteo se maneja como int en el resto del código
        this.maxSorteo = config.getMontoMaxSorteo().min(BigDecimal.valueOf(Integer.MAX_VALUE));
    }

    // ═══════════════════════════════════════════
    // RESULTADO
    // ═══════════════════════════════════════════

    /** Resultado inmutable: monto validado o mensaje de error listo para mostrar al usuario. */
    public static final class Resultado {
        private final BigDecimal monto;
        private final String error;

        private Resultado(BigDecimal monto, String error) {
            this.monto = monto;
            this.error = error;
        }

        static Resultado ok(BigDecimal monto) { return new Resultado(monto, null); }
        static Resultado fail(String error) { return new Resultado(null, error); }

        public boolean isValido() { return error == null; }
        /** Monto validado (escala 0). Solo tiene valor si {@link #isValido()}. */
        public BigDecimal getMonto() { return monto; }
        /** Mensaje de error para el usuario. Solo tiene valor si no es válido. */
        public String getError() { return error; }
    }

    // ═══════════════════════════════════════════
    // VALIDACIONES POR TIPO
    // ═══════════════════════════════════════════

    /** Recarga / depósito (Nequi manual, Wompi, depósito de admin). */
    public Resultado deposito(JsonElement crudo) {
        return validar(crudo, minDeposito, maxDeposito, "recargar");
    }

    /** Solicitud de retiro. */
    public Resultado retiro(JsonElement crudo) {
        return validar(crudo, minRetiro, maxRetiro, "retirar");
    }

    /** Monto de un torneo (lo que pone cada jugador). */
    public Resultado torneo(JsonElement crudo) {
        return validar(crudo, minTorneo, maxTorneo, "un torneo");
    }

    /** Precio de un sorteo. */
    public Resultado sorteo(JsonElement crudo) {
        return validar(crudo, minSorteo, maxSorteo, "un sorteo");
    }

    public BigDecimal getMinTorneo() { return minTorneo; }
    public BigDecimal getMaxTorneo() { return maxTorneo; }

    /** Formatea un monto como pesos colombianos: 10000 → "$10.000". */
    public static String formatear(BigDecimal monto) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO"));
        return "$" + nf.format(monto);
    }

    // ═══════════════════════════════════════════
    // NÚCLEO
    // ═══════════════════════════════════════════

    private Resultado validar(JsonElement crudo, BigDecimal min, BigDecimal max, String accion) {
        if (crudo == null || crudo.isJsonNull()) {
            return Resultado.fail("El monto es requerido");
        }
        if (!crudo.isJsonPrimitive() || crudo.getAsJsonPrimitive().isBoolean()) {
            return Resultado.fail("El monto no es válido");
        }

        String texto = crudo.getAsString().trim();
        if (texto.isEmpty()) {
            return Resultado.fail("El monto es requerido");
        }
        if (!NUMERO.matcher(texto).matches()) {
            return Resultado.fail("El monto no es un número válido");
        }

        BigDecimal valor = new BigDecimal(texto);
        if (valor.signum() <= 0) {
            return Resultado.fail("El monto debe ser mayor a 0");
        }
        if (valor.stripTrailingZeros().scale() > 0) {
            return Resultado.fail("El monto debe ser en pesos enteros, sin decimales");
        }
        valor = valor.setScale(0); // exacto: ya se verificó que no hay decimales distintos de cero

        if (valor.compareTo(min) < 0) {
            return Resultado.fail("El monto mínimo para " + accion + " es " + formatear(min));
        }
        if (valor.compareTo(max) > 0) {
            return Resultado.fail("El monto máximo para " + accion + " es " + formatear(max));
        }
        return Resultado.ok(valor);
    }
}
