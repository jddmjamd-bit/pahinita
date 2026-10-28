package com.torneosflash.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.torneosflash.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidadorMontoTest {

    private final ValidadorMonto validador = new ValidadorMonto(
            new AppConfig(Map.of("JWT_SECRET", "test-only-jwt-secret-with-at-least-32-characters")));

    @Test
    void aceptaLosLimitesInclusivosYConviertePesosEnteros() {
        assertEquals(new BigDecimal("1000"), validador.deposito(json("1000")).getMonto());
        assertEquals(new BigDecimal("5000000"), validador.deposito(json("5000000")).getMonto());
        assertEquals(new BigDecimal("10000"), validador.retiro(json("10000")).getMonto());
        assertEquals(new BigDecimal("10000"), validador.torneo(json("10000")).getMonto());
    }

    @Test
    void rechazaMontosFueraDeRangoNegativosDecimalesYNoNumericos() {
        assertFalse(validador.deposito(json("999")).isValido());
        assertFalse(validador.deposito(json("5000001")).isValido());
        assertFalse(validador.deposito(json("-1000")).isValido());
        assertFalse(validador.deposito(json("1000.5")).isValido());
        assertFalse(validador.deposito(json("\"mil\"")).isValido());
        assertFalse(validador.deposito(json("true")).isValido());
        assertFalse(validador.deposito(null).isValido());
    }

    @Test
    void aceptaDecimalConCerosFinalesComoValorEntero() {
        ValidadorMonto.Resultado resultado = validador.deposito(json("1000.00"));
        assertTrue(resultado.isValido());
        assertEquals(new BigDecimal("1000"), resultado.getMonto());
    }

    private static JsonElement json(String raw) {
        return JsonParser.parseString(raw);
    }
}
