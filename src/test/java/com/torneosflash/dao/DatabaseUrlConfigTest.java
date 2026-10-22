package com.torneosflash.dao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseUrlConfigTest {

    @Test
    void convierteFormatoRenderPostgresAJdbcYExtraeCredenciales() {
        ConexionDB.EnlaceBD enlace = ConexionDB.EnlaceBD.desde(
                "postgres://usuario:clave%40temporal@db.example.test:5433/torneosflash_test?sslmode=disable",
                "verify-full");

        assertEquals("usuario", enlace.usuario);
        assertEquals("clave@temporal", enlace.password);
        assertEquals("db.example.test:5433", enlace.host);
        assertEquals("torneosflash_test", enlace.nombreBase);
        assertTrue(enlace.jdbcUrl.startsWith("jdbc:postgresql://db.example.test:5433/torneosflash_test?"));
        assertTrue(enlace.jdbcUrl.contains("sslmode=verify-full"));
        assertFalse(enlace.jdbcUrl.contains("clave"));
    }

    @Test
    void aceptaJdbcPostgresYCredencialesEnParametrosSinBajarSsl() {
        ConexionDB.EnlaceBD enlace = ConexionDB.EnlaceBD.desde(
                "jdbc:postgresql://db.example.test/torneosflash_test?user=tester&password=clave%2Btemporal&ssl=true",
                "require");

        assertEquals("tester", enlace.usuario);
        assertEquals("clave+temporal", enlace.password);
        assertTrue(enlace.jdbcUrl.contains("sslmode=require"));
        assertFalse(enlace.jdbcUrl.contains("password"));
        assertFalse(enlace.jdbcUrl.contains("ssl=true"));
    }

    @Test
    void conservaParametrosNoCredencialesYUsaSslSeguroPorDefecto() {
        ConexionDB.EnlaceBD enlace = ConexionDB.EnlaceBD.desde(
                "postgresql://db.example.test/torneosflash_test?currentSchema=public", "invalido");

        assertEquals("require", enlace.sslMode);
        assertTrue(enlace.jdbcUrl.contains("currentSchema=public"));
        assertTrue(enlace.jdbcUrl.contains("sslmode=require"));
    }

    @Test
    void rechazaUrlsVaciasEsquemasDesconocidosYBasesSinNombre() {
        assertThrows(IllegalArgumentException.class, () -> ConexionDB.EnlaceBD.desde(" ", "require"));
        assertThrows(IllegalArgumentException.class,
                () -> ConexionDB.EnlaceBD.desde("mysql://db.example.test/app", "require"));
        assertThrows(IllegalArgumentException.class,
                () -> ConexionDB.EnlaceBD.desde("postgres://db.example.test/", "require"));
    }
}
