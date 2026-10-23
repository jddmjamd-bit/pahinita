package com.torneosflash.servicio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServicioAcceptanceTest {
    private static final String SECRETO = "solo-para-pruebas-secretas-con-longitud-suficiente";

    @Test
    void emiteYValidaUnaSesionConJtiYRol() {
        JwtServicio servicio = new JwtServicio(SECRETO, 3600);
        var emitido = servicio.generarToken(42, "admin", "jti-prueba");
        var sesion = servicio.verificar(emitido.valor());
        assertNotNull(sesion);
        assertEquals(42, sesion.userId());
        assertEquals("admin", sesion.rol());
        assertEquals("jti-prueba", sesion.jti());
    }

    @Test
    void rechazaTokenManipuladoODeOtroSecreto() {
        String token = new JwtServicio(SECRETO, 3600).generarToken(42, "free").valor();
        assertNull(new JwtServicio("otro-secreto-con-longitud-suficiente-123", 3600).verificar(token));
        assertNull(new JwtServicio(SECRETO, 3600).verificar(token + "x"));
        assertNull(new JwtServicio(SECRETO, 3600).verificar(""));
    }

    @Test
    void rechazaUnTokenExpirado() throws InterruptedException {
        JwtServicio servicio = new JwtServicio(SECRETO, 1);
        String token = servicio.generarToken(42, "free").valor();
        Thread.sleep(1100);
        assertNull(servicio.verificar(token));
    }

    @Test
    void renuevaConservandoElJtiParaQueLaRevocacionApliqueATodasLasCopias() {
        JwtServicio servicio = new JwtServicio(SECRETO, 3600);
        var original = servicio.generarToken(42, "free", "sesion-compartida");
        var renovado = servicio.generarToken(42, "free", original.jti());
        assertEquals(original.jti(), servicio.verificar(renovado.valor()).jti());
        assertEquals(original.jti(), servicio.verificar(original.valor()).jti());
    }

    @Test
    void exigeUnSecretoConLongitudMinima() {
        assertThrows(IllegalArgumentException.class, () -> new JwtServicio("corto", 3600));
    }
}
