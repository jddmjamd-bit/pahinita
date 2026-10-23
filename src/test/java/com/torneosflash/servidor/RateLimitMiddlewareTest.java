package com.torneosflash.servidor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    public void clasificaRutasFinancierasYAuthEnCubosSeparados() {
        assertEquals(RateLimitMiddleware.Categoria.FINANCIERO,
                RateLimitMiddleware.clasificar("POST", RateLimitMiddleware.normalizar("/api/deposit/")));
        assertEquals(RateLimitMiddleware.Categoria.FINANCIERO,
                RateLimitMiddleware.clasificar("POST", "/api/wompi/init"));
        assertEquals(RateLimitMiddleware.Categoria.AUTH,
                RateLimitMiddleware.clasificar("POST", "/api/password/forgot"));
        assertEquals(RateLimitMiddleware.Categoria.AUTH,
                RateLimitMiddleware.clasificar("GET", "/api/check-email/test@example.test"));
    }

    @Test
    public void evitaLimitarPreflightWebhookWompiYArchivosDelFrontend() {
        assertEquals(RateLimitMiddleware.Categoria.EXENTO,
                RateLimitMiddleware.clasificar("OPTIONS", "/api/deposit"));
        assertEquals(RateLimitMiddleware.Categoria.EXENTO,
                RateLimitMiddleware.clasificar("POST", "/api/wompi/webhook"));
        assertEquals(RateLimitMiddleware.Categoria.EXENTO,
                RateLimitMiddleware.clasificar("GET", "/assets/app.js"));
    }

    @Test
    public void limitaMediaSeparadaYNormalizaRuta() {
        assertEquals(RateLimitMiddleware.Categoria.MEDIA,
                RateLimitMiddleware.clasificar("GET", RateLimitMiddleware.normalizar("/API//MEDIA/7/")));
        assertEquals(RateLimitMiddleware.Categoria.GENERAL,
                RateLimitMiddleware.clasificar("POST", "/api/media/7"));
    }
}
