package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.config.Ejecutores;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.eventos.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuentaRegistradaYSorteoCorreoTest {

    private record CorreoCapturado(String para, String asunto, String cuerpo) {}

    private static final class CorreoMemoria extends CorreoServicio {
        final List<CorreoCapturado> enviados = new ArrayList<>();

        CorreoMemoria(String senderEmail) {
            super("fake-brevo-key", senderEmail, null);
        }

        @Override
        public void enviar(String to, String subject, String body) {
            enviados.add(new CorreoCapturado(to, subject, body));
        }
    }

    @Test
    void alCrearCuentaEnviaBienvenidaSimpleAlUsuarioYTokenConNametagAlAdminSinDatosPersonalesExtra() {
        String jwtSecret = "secreto-prueba-cifrado-token-32-caracteres-minimo";
        AppConfig config = new AppConfig(Map.of(
                "JWT_SECRET", jwtSecret,
                "BREVO_API_KEY", "fake-brevo-key",
                "BREVO_SENDER_EMAIL", "remitente@torneos.test",
                "SOPORTE_ADMIN_EMAIL", "admin@torneos.test",
                "APP_BASE_URL", "https://torneos.test",
                "CPU_POOL_THREADS", "1",
                "IO_POOL_THREADS", "1",
                "PUSH_POOL_THREADS", "1",
                "TIMER_POOL_THREADS", "1"
        ));
        CifradoTokenJugador cifrado = new CifradoTokenJugador(jwtSecret);
        CorreoMemoria correo = new CorreoMemoria("remitente@torneos.test");
        EventBus bus = new EventBus();

        final String[] tokenGuardado = new String[1];
        GenericDAO daoMemoria = new GenericDAO(null) {
            @Override
            public JsonObject queryOne(String sql, Object... params) {
                assertEquals(77, params[0]);
                JsonObject fila = new JsonObject();
                fila.addProperty("username", "rey_azul");
                fila.addProperty("email", "jugador@correo.test");
                fila.addProperty("player_tag", "#2PP89YJ0");
                fila.addProperty("player_verification_token_ciphertext", tokenGuardado[0]);
                return fila;
            }
        };

        UsuarioDAO usuariosFalso = new UsuarioDAO(null) {
            int llamadas = 0;

            @Override public JsonObject buscarPorUsername(String u) { return null; }
            @Override public JsonObject buscarPorEmailLower(String e) { return null; }
            @Override public JsonObject buscarPorPlayerTag(String t) { return null; }
            @Override public JsonObject buscarPorTelefonoNormalizado(String tel) { return null; }

            @Override
            public int registrarConAceptacionLegal(String username, String email, String hashedPass,
                                                   String tag, String telefono,
                                                   String versionTerminos, String versionPrivacidad,
                                                   String tokenVerificacionCifrado) {
                llamadas++;
                if (llamadas > 1) return -1; // Conflicto de unicidad concurrente en el segundo intento
                tokenGuardado[0] = tokenVerificacionCifrado;
                return 77;
            }
        };

        ClashApiServicio clashFalso = new ClashApiServicio("") {
            @Override
            public JsonObject verificarTag(String tag) {
                JsonObject perfil = new JsonObject();
                perfil.addProperty("tag", tag);
                perfil.addProperty("name", "KingArthurCR");
                return perfil;
            }
        };

        new CuentaRegistradaListener(correo, daoMemoria, cifrado, config).registrar(bus);
        Ejecutores ejecutores = new Ejecutores(config);
        try {
            AuthService auth = new AuthService(usuariosFalso, daoMemoria, clashFalso, ejecutores, config, bus, cifrado);
            auth.registrar("rey_azul", "jugador@correo.test", "ClaveSegura!123",
                    "#2PP89YJ0", "3151234567", "crtok_99887766", true);

            assertEquals(2, correo.enviados.size());

            CorreoCapturado correoUsuario = correo.enviados.get(0);
            assertEquals("jugador@correo.test", correoUsuario.para());
            assertTrue(correoUsuario.cuerpo().contains("fue creada"));
            assertFalse(correoUsuario.cuerpo().contains("crtok_99887766"),
                    "Al usuario únicamente se le avisa que su cuenta fue creada, nunca el token.");
            assertFalse(correoUsuario.cuerpo().contains("#2PP89YJ0"));

            CorreoCapturado correoAdmin = correo.enviados.get(1);
            assertEquals("admin@torneos.test", correoAdmin.para());
            assertTrue(correoAdmin.cuerpo().contains("Token: crtok_99887766"),
                    "El admin debe recibir enseguida el token descifrado.");
            assertTrue(correoAdmin.cuerpo().contains("Nametag: #2PP89YJ0 (KingArthurCR)"),
                    "El admin debe recibir enseguida el nametag con el Player Tag y nombre de Clash.");
            assertTrue(correoAdmin.cuerpo().contains("rey_azul (id 77)"));
            assertFalse(correoAdmin.cuerpo().contains("jugador@correo.test"),
                    "El correo al admin no debe exponer el email del usuario.");
            assertFalse(correoAdmin.cuerpo().contains("3151234567"),
                    "El correo al admin no debe exponer el teléfono del usuario.");

            // Un segundo intento duplicado no crea cuenta ni reenvía correos
            auth.registrar("rey_azul", "jugador@correo.test", "ClaveSegura!123",
                    "#2PP89YJ0", "3151234567", "crtok_99887766", true);
            assertEquals(2, correo.enviados.size());
        } finally {
            ejecutores.cerrar();
        }
    }

    @Test
    void sorteoEnviaCorreoAlGanadorValidoYOmiteSorteosVaciosOCorreosInvalidos() {
        CorreoMemoria correo = new CorreoMemoria("admin@torneos.test");
        AppConfig config = new AppConfig(Map.of("JWT_SECRET", "secreto-prueba-sorteo-con-32-caracteres-min"));
        SorteoService sorteos = new SorteoService(null, null, correo, null, new ValidadorMonto(config));

        sorteos.enviarCorreoGanadorSorteo("Pass Royale Diamante", 42, "campeon_cr", "campeon@correo.test");
        assertEquals(1, correo.enviados.size());
        assertEquals("campeon@correo.test", correo.enviados.get(0).para());
        assertTrue(correo.enviados.get(0).asunto().contains("Pass Royale Diamante"));
        assertTrue(correo.enviados.get(0).cuerpo().contains("campeon_cr"));

        // Sin participantes (ganadorId == -1) o con correo inválido no se envía nada
        sorteos.enviarCorreoGanadorSorteo("Sorteo Vacío", -1, "Nadie (sin participantes)", "campeon@correo.test");
        sorteos.enviarCorreoGanadorSorteo("Pass Royale", 42, "campeon_cr", "");
        sorteos.enviarCorreoGanadorSorteo("Pass Royale", 42, "campeon_cr", "correo-invalido");
        assertEquals(1, correo.enviados.size());
    }
}
