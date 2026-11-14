package com.torneosflash.servicio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias puras para el reparto de comisión de referidos (0% a 100%).
 */
class ReferidosServiceTest {

    @Test
    void calcularRepartoReferidoCubreCeroPorcientoPorDefectoYPorcentajesExactos() {
        BigDecimal base = new BigDecimal("150.00");

        WalletService.RepartoReferido cero = WalletService.calcularRepartoReferido(base, BigDecimal.ZERO);
        assertMonto(new BigDecimal("150.00"), cero.montoReferidor());
        assertMonto(new BigDecimal("0.00"), cero.montoDevueltoReferido());
        assertMonto(new BigDecimal("0.00"), cero.porcentajeAplicado());

        WalletService.RepartoReferido cuarto = WalletService.calcularRepartoReferido(base, new BigDecimal("25"));
        assertMonto(new BigDecimal("112.50"), cuarto.montoReferidor());
        assertMonto(new BigDecimal("37.50"), cuarto.montoDevueltoReferido());
        assertMonto(new BigDecimal("25.00"), cuarto.porcentajeAplicado());

        WalletService.RepartoReferido mitad = WalletService.calcularRepartoReferido(base, new BigDecimal("50"));
        assertMonto(new BigDecimal("75.00"), mitad.montoReferidor());
        assertMonto(new BigDecimal("75.00"), mitad.montoDevueltoReferido());

        WalletService.RepartoReferido cien = WalletService.calcularRepartoReferido(base, new BigDecimal("100"));
        assertMonto(new BigDecimal("0.00"), cien.montoReferidor());
        assertMonto(new BigDecimal("150.00"), cien.montoDevueltoReferido());
    }

    @Test
    void calcularRepartoReferidoConservaLaSumaExactaConCentavosImparesYDecimales() {
        BigDecimal base = new BigDecimal("15.50");
        WalletService.RepartoReferido r25 = WalletService.calcularRepartoReferido(base, new BigDecimal("25"));
        // 15.50 * 0.25 = 3.875 -> floor 3.87 para referido; referidor recibe 11.63
        assertMonto(new BigDecimal("3.87"), r25.montoDevueltoReferido());
        assertMonto(new BigDecimal("11.63"), r25.montoReferidor());
        assertMonto(base, r25.montoReferidor().add(r25.montoDevueltoReferido()));

        BigDecimal base150 = new BigDecimal("150.00");
        WalletService.RepartoReferido tercio = WalletService.calcularRepartoReferido(base150, new BigDecimal("33.33"));
        assertMonto(new BigDecimal("49.99"), tercio.montoDevueltoReferido());
        assertMonto(new BigDecimal("100.01"), tercio.montoReferidor());
        assertMonto(base150, tercio.montoReferidor().add(tercio.montoDevueltoReferido()));
    }

    @Test
    void calcularRepartoReferidoAcotaPorcentajesFueraDeRangoYMontoNulo() {
        WalletService.RepartoReferido negativo = WalletService.calcularRepartoReferido(
                new BigDecimal("100.00"), new BigDecimal("-10"));
        assertMonto(new BigDecimal("100.00"), negativo.montoReferidor());
        assertMonto(new BigDecimal("0.00"), negativo.montoDevueltoReferido());
        assertMonto(new BigDecimal("0.00"), negativo.porcentajeAplicado());

        WalletService.RepartoReferido excesivo = WalletService.calcularRepartoReferido(
                new BigDecimal("100.00"), new BigDecimal("140"));
        assertMonto(new BigDecimal("0.00"), excesivo.montoReferidor());
        assertMonto(new BigDecimal("100.00"), excesivo.montoDevueltoReferido());
        assertMonto(new BigDecimal("100.00"), excesivo.porcentajeAplicado());

        WalletService.RepartoReferido baseCero = WalletService.calcularRepartoReferido(
                BigDecimal.ZERO, new BigDecimal("50"));
        assertMonto(new BigDecimal("0.00"), baseCero.montoReferidor());
        assertMonto(new BigDecimal("0.00"), baseCero.montoDevueltoReferido());
    }

    private static void assertMonto(BigDecimal esperado, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, esperado.compareTo(actual),
                () -> "Esperado " + esperado + " pero fue " + actual);
    }
}

