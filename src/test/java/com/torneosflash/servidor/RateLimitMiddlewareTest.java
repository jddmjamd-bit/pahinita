package com.torneosflash.servidor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RateLimitMiddlewareTest {

    @Test
    public void ignoraXffSinSaltosConfiablesConfigurados() {
        assertEquals("203.0.113.8", RateLimitMiddleware.ipCliente(
                "203.0.113.8", "198.51.100.77", 0));
    }

    @Test
    public void usaLaDireccionAgregadaAlFinalAunqueElClienteFalsifiqueElPrefijo() {
        assertEquals("203.0.113.8", RateLimitMiddleware.ipCliente(
                "10.0.0.4", "198.51.100.77, 203.0.113.8", 1));
    }

    @Test
    public void noUsaLaPrimeraEntradaSiFaltanSaltosConfigurados() {
        assertEquals("10.0.0.4", RateLimitMiddleware.ipCliente(
                "10.0.0.4", "198.51.100.77", 2));
    }

    @Test
    public void rechazaUnaDireccionSeleccionadaQueNoSeaIpLiteral() {
        assertEquals("10.0.0.4", RateLimitMiddleware.ipCliente(
                "10.0.0.4", "198.51.100.77, atacante.example", 1));
    }

    @Test
    public void rechazaLaCadenaSiUnSaltoConfiableEsInvalido() {
        assertEquals("10.0.0.4", RateLimitMiddleware.ipCliente(
                "10.0.0.4", "198.51.100.77, 203.0.113.8, atacante.example", 2));
    }

    @Test
    public void rechazaOctetosIpv4FueraDeRango() {
        assertEquals("10.0.0.4", RateLimitMiddleware.ipCliente(
                "10.0.0.4", "198.51.100.77, 999.1.1.1", 1));
    }
}
