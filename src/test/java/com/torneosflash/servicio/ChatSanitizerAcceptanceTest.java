package com.torneosflash.servicio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ChatSanitizerAcceptanceTest {

    @Test
    void limpiaHtmlYCaracteresDeControlYConservaTextoPlano() {
        assertEquals("alert(1)hola mundo", ChatSanitizer.limpiarTexto("<script>alert(1)</script>hola\u0000 mundo", 500));
    }

    @Test
    void descartaUnNombreDeCanalPrivadoYNoPermiteEscribirEnClashLogs() {
        assertNull(ChatSanitizer.validarMensajePublico("clash_logs", "usuario", "texto", "texto"));
        assertNull(ChatSanitizer.validarMensajePublico("sala_123", "usuario", "texto", "texto"));
    }

    @ParameterizedTest
    @CsvSource({"general", "anuncios", "clash"})
    void aceptaTextoEnCanalesPublicos(String canal) {
        var mensaje = ChatSanitizer.validarMensajePublico(canal, "jugador", "hola", "texto");
        assertNotNull(mensaje);
        assertEquals(canal, mensaje.canal());
    }

    @ParameterizedTest
    @CsvSource({"data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,", "https://example.test/x.png"})
    void rechazaSvgYFuentesDeImagenNoConfiables(String imagen) {
        assertNull(ChatSanitizer.validarMensajePublico("anuncios", "admin", imagen, "imagen"));
    }

    @Test
    void limitaMultimediaAAnunciosYPermiteSoloRutasInternasDeVideo() {
        assertNull(ChatSanitizer.validarMensajePublico("general", "admin", "data:image/png;base64,AAAA", "imagen"));
        assertNotNull(ChatSanitizer.validarMensajePublico("anuncios", "admin", "/api/media/42", "video"));
        assertNull(ChatSanitizer.validarMensajePublico("anuncios", "admin", "https://example.test/v.mp4", "video"));
    }

    @Test
    void validaMensajesPrivadosYNoPermiteSalasManipuladas() {
        var mensaje = ChatSanitizer.validarMensajePrivado("sala_42", "jugador", "<b>hola</b>");
        assertNotNull(mensaje);
        assertEquals("hola", mensaje.texto());
        assertNull(ChatSanitizer.validarMensajePrivado("sala_42; DROP TABLE users", "jugador", "hola"));
    }

    @ParameterizedTest
    @CsvSource({"ab, false", "jugador, true", "<img>mal, false", "nombre-con-largo-mayor-a-treinta-caracteres, false"})
    void validaUsernamesAntesDeMostrarlosEnChat(String username, boolean esperado) {
        assertEquals(esperado, ChatSanitizer.esNombreUsuarioSeguro(username));
    }
}
