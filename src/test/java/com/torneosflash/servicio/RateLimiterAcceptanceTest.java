package com.torneosflash.servicio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterAcceptanceTest {
    @Test
    void bloqueaAlSuperarElLimiteDuranteLaVentana() {
        RateLimiter limitador = new RateLimiter();
        assertTrue(limitador.intentar("general|203.0.113.1", 3, 1_000).isPermitido());
        assertTrue(limitador.intentar("general|203.0.113.1", 3, 2_000).isPermitido());
        assertTrue(limitador.intentar("general|203.0.113.1", 3, 3_000).isPermitido());
        var bloqueado = limitador.intentar("general|203.0.113.1", 3, 4_000);
        assertFalse(bloqueado.isPermitido());
        assertEquals(57, bloqueado.getReintentarEnSegundos());
    }

    @Test
    void permiteDeNuevoCuandoLasSolicitudesSalenDeLaVentanaYSeparaIPsYCategorias() {
        RateLimiter limitador = new RateLimiter();
        assertTrue(limitador.intentar("auth|203.0.113.1", 1, 1_000).isPermitido());
        assertFalse(limitador.intentar("auth|203.0.113.1", 1, 2_000).isPermitido());
        assertTrue(limitador.intentar("auth|203.0.113.2", 1, 2_000).isPermitido());
        assertTrue(limitador.intentar("auth|203.0.113.1", 1, 61_001).isPermitido());
        assertTrue(limitador.intentar("financial|203.0.113.1", 1, 61_001).isPermitido());
    }

    @Test
    void limiteNoValidoNoBloqueaLaSolicitud() {
        assertTrue(new RateLimiter().intentar("general|test", 0, 1_000).isPermitido());
    }
}
