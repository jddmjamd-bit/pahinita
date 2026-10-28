package com.torneosflash.servicio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VozDiscordServiceTest {
    @Test
    void generaNombresDeCanalNeutrosParaSalaPrivadaOPartida() {
        assertEquals("sala-890abc", VozDiscordService.nombreCanal("sala_1730123456890_abc", 0));
        assertEquals("sala-42", VozDiscordService.nombreCanal("sala_42", 99));
        assertEquals("partida-77", VozDiscordService.nombreCanal("", 77));
        assertEquals("partida-77", VozDiscordService.nombreCanal(null, 77));
    }
}
