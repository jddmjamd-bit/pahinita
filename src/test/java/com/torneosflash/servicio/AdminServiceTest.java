package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.torneosflash.dao.ConexionDB;
import com.torneosflash.dao.GenericDAO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServiceTest {

    @Test
    void estadisticasSumaGananciaYSobrantesSinAfectarLosApartadosIndividualesYCalculaAportePorJugador() {
        Map<String, BigDecimal> montosPorCategoria = Map.of(
                "sorteos", new BigDecimal("1000.00"),
                "misiones", new BigDecimal("500.00"),
                "logros", new BigDecimal("250.00"),
                "leaderboard", new BigDecimal("750.00"),
                "devolucion", new BigDecimal("750.00"),
                "ganancia", new BigDecimal("1250.00"),
                "referidos", new BigDecimal("500.00")
        );

        GenericDAO daoFalso = new GenericDAO(null) {
            @Override
            public JsonObject queryOne(String sql, Object... params) {
                JsonObject fila = new JsonObject();
                if (sql.contains("FROM users")) {
                    fila.addProperty("total", new BigDecimal("150000.00"));
                    return fila;
                }
                if (sql.contains("FROM leaderboard_cut_payments")) {
                    assertEquals(7, params[0]);
                    fila.addProperty("total", new BigDecimal("375.50"));
                    return fila;
                }
                if (sql.contains("FROM admin_wallet WHERE categoria = ?")) {
                    String cat = (String) params[0];
                    fila.addProperty("total", montosPorCategoria.getOrDefault(cat, BigDecimal.ZERO));
                    return fila;
                }
                if (sql.contains("FROM admin_wallet WHERE monto > 0")) {
                    fila.addProperty("total", new BigDecimal("5000.00"));
                    return fila;
                }
                return null;
            }

            @Override
            public ArrayList<JsonObject> query(String sql, Object... params) {
                ArrayList<JsonObject> lista = new ArrayList<>();

                JsonObject u1 = new JsonObject();
                u1.addProperty("id", 10);
                u1.addProperty("username", "jugador_uno");
                u1.addProperty("email", "uno@example.invalid");
                u1.addProperty("saldo", new BigDecimal("12000.00"));
                u1.addProperty("tipo_suscripcion", "gratis");
                u1.addProperty("ganancia_generada", new BigDecimal("625.00"));
                u1.addProperty("gen_sorteos", new BigDecimal("500.00"));
                u1.addProperty("gen_misiones", new BigDecimal("250.00"));
                u1.addProperty("gen_logros", new BigDecimal("125.50"));
                u1.addProperty("gen_leaderboard", new BigDecimal("375.00"));
                u1.addProperty("gen_devolucion", new BigDecimal("375.00"));
                u1.addProperty("gen_referidos", new BigDecimal("250.00"));
                lista.add(u1);

                JsonObject u2 = new JsonObject();
                u2.addProperty("id", 11);
                u2.addProperty("username", "jugador_nuevo");
                u2.addProperty("email", "nuevo@example.invalid");
                u2.addProperty("saldo", BigDecimal.ZERO);
                u2.addProperty("tipo_suscripcion", "gratis");
                lista.add(u2);

                return lista;
            }
        };

        AdminService servicio = new AdminService(daoFalso, null, null, null, null, null);
        JsonObject stats = servicio.estadisticas(7);

        // Las cuentas originales permanecen intactas y separadas
        assertMonto("5000.00", stats.get("totalGanancias").getAsBigDecimal());
        JsonObject desglose = stats.getAsJsonObject("desglose");
        assertNotNull(desglose);
        assertMonto("1250.00", desglose.get("ganancia").getAsBigDecimal());
        assertMonto("375.50", stats.get("gananciaAdminLeaderboard").getAsBigDecimal());

        // La nueva zona combinada suma base + divisiones inactivas + sobrantes sin alterar las categorías individuales
        assertMonto("1250.00", stats.get("gananciaBaseComisionesAdmin").getAsBigDecimal());
        assertMonto("1500.00", stats.get("gananciaInactivasAdmin").getAsBigDecimal());
        assertMonto("2750.00", stats.get("gananciaComisionesAdmin").getAsBigDecimal());
        assertMonto("3125.50", stats.get("gananciaTotalCombinadaAdmin").getAsBigDecimal());

        // Cada usuario conserva el aporte por categoría dividido entre 2 y el total aportado
        JsonArray usuarios = stats.getAsJsonArray("listaUsuarios");
        assertEquals(2, usuarios.size());

        JsonObject primero = usuarios.get(0).getAsJsonObject();
        assertMonto("625.00", primero.get("ganancia_generada").getAsBigDecimal());
        assertMonto("500.00", primero.get("gen_sorteos").getAsBigDecimal());
        assertMonto("250.00", primero.get("gen_misiones").getAsBigDecimal());
        assertMonto("125.50", primero.get("gen_logros").getAsBigDecimal());
        assertMonto("375.00", primero.get("gen_leaderboard").getAsBigDecimal());
        assertMonto("375.00", primero.get("gen_devolucion").getAsBigDecimal());
        assertMonto("250.00", primero.get("gen_referidos").getAsBigDecimal());
        assertMonto("750.50", primero.get("ganancia_inactivas_admin").getAsBigDecimal());
        assertMonto("1375.50", primero.get("ganancia_efectiva_admin").getAsBigDecimal());
        assertMonto("2500.50", primero.get("comision_total_aportada").getAsBigDecimal());

        JsonObject segundo = usuarios.get(1).getAsJsonObject();
        assertMonto("0", segundo.get("ganancia_generada").getAsBigDecimal());
        assertMonto("0", segundo.get("gen_sorteos").getAsBigDecimal());
        assertMonto("0", segundo.get("ganancia_inactivas_admin").getAsBigDecimal());
        assertMonto("0", segundo.get("ganancia_efectiva_admin").getAsBigDecimal());
        assertMonto("0", segundo.get("comision_total_aportada").getAsBigDecimal());
    }

    @Test
    void comisionServiceRedirigeDivisionesInactivasAGananciaEfectivaAdmin() {
        assertTrue(ComisionService.Categoria.SORTEOS.isActiva());
        assertTrue(ComisionService.Categoria.LEADERBOARD.isActiva());
        assertTrue(ComisionService.Categoria.GANANCIA.isActiva());
        assertTrue(ComisionService.Categoria.REFERIDOS.isActiva());
        assertFalse(ComisionService.Categoria.MISIONES.isActiva());
        assertFalse(ComisionService.Categoria.LOGROS.isActiva());
        assertFalse(ComisionService.Categoria.DEVOLUCION.isActiva());

        ComisionService servicio = new ComisionService();
        assertMonto("0.30", servicio.fraccionInactivas());
        assertMonto("0.55", servicio.fraccionGananciaEfectivaAdmin());

        ComisionService.Desglose desglose = servicio.calcular(new BigDecimal("10000"));
        assertMonto("2000", desglose.getComisionTotal());
        assertMonto("500", desglose.getGananciaBaseAdmin());
        assertMonto("600", desglose.getGananciaInactivasAdmin());
        assertMonto("1100", desglose.getGananciaEfectivaAdmin());
        assertMonto("300.00", desglose.utilidadInactivasPorJugador());
        assertMonto("550.00", desglose.utilidadEfectivaPorJugador());
    }

    @Test
    void cambiarSuscripcionYZonaAdminValidaRolesYTiposPermitidos() {
        GenericDAO daoFalso = new GenericDAO(null) {
            @Override
            public JsonObject queryOne(String sql, Object... params) {
                int id = (int) params[0];
                JsonObject fila = new JsonObject();
                fila.addProperty("id", id);
                fila.addProperty("username", "user_" + id);
                fila.addProperty("tipo_suscripcion",
                        (id == 1 || id == 2) ? "admin" : (id == 50 ? "moderador" : "free"));
                return fila;
            }

            @Override
            public int update(String sql, Object... params) {
                return 1;
            }
        };

        AdminService servicio = new AdminService(daoFalso, null, null, null, null, null);

        JsonObject aPrueba = servicio.cambiarSuscripcionUsuario(1, 25, "prueba");
        assertTrue(aPrueba.get("success").getAsBoolean());
        assertEquals("prueba", aPrueba.get("tipoSuscripcion").getAsString());
        assertEquals("prueba", aPrueba.get("zonaAsignada").getAsString());

        JsonObject aFree = servicio.cambiarSuscripcionUsuario(1, 25, "free");
        assertTrue(aFree.get("success").getAsBoolean());
        assertEquals("free", aFree.get("tipoSuscripcion").getAsString());
        assertEquals("normal", aFree.get("zonaAsignada").getAsString());

        assertEquals(400, assertThrows(ServicioException.class,
                () -> servicio.cambiarSuscripcionUsuario(1, 1, "prueba")).getStatus());
        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.cambiarSuscripcionUsuario(1, 2, "prueba")).getStatus());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> servicio.cambiarSuscripcionUsuario(1, 25, "admin")).getStatus());

        // El admin también puede ingresar y sacar a un moderador de la zona de pruebas conservando su rol
        JsonObject modAPrueba = servicio.cambiarSuscripcionUsuario(1, 50, "prueba");
        assertTrue(modAPrueba.get("success").getAsBoolean());
        assertEquals("moderador", modAPrueba.get("tipoSuscripcion").getAsString());
        assertEquals("prueba", modAPrueba.get("zonaAsignada").getAsString());

        JsonObject modAFree = servicio.cambiarSuscripcionUsuario(1, 50, "free");
        assertTrue(modAFree.get("success").getAsBoolean());
        assertEquals("moderador", modAFree.get("tipoSuscripcion").getAsString());
        assertEquals("normal", modAFree.get("zonaAsignada").getAsString());

        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.cambiarSuscripcionUsuario(50, 25, "prueba")).getStatus());

        JsonObject zonaPrueba = servicio.cambiarZonaAdmin(1, "prueba");
        assertTrue(zonaPrueba.get("success").getAsBoolean());
        assertEquals("prueba", zonaPrueba.get("zona_activa").getAsString());

        JsonObject zonaNormal = servicio.cambiarZonaAdmin(1, "normal");
        assertTrue(zonaNormal.get("success").getAsBoolean());
        assertEquals("normal", zonaNormal.get("zona_activa").getAsString());

        assertEquals(400, assertThrows(ServicioException.class,
                () -> servicio.cambiarZonaAdmin(1, "invalida")).getStatus());
        ConexionDB.limpiarZonaActual();
    }

    @Test
    void moderadorTienePanelFinanzasAisladoYNoAccedeAZonaPruebasNiVerificaciones() {
        List<JsonObject> nequis = new ArrayList<>();
        AtomicInteger seqNequi = new AtomicInteger(10);

        GenericDAO daoFalso = new GenericDAO(null) {
            @Override
            public JsonObject queryOne(String sql, Object... params) {
                if (sql.contains("SELECT tipo_suscripcion FROM users WHERE id = ?")) {
                    int id = (int) params[0];
                    JsonObject fila = new JsonObject();
                    fila.addProperty("tipo_suscripcion", id == 50 ? "moderador" : (id == 1 ? "admin" : "free"));
                    return fila;
                }
                if (sql.contains("SELECT id, username, codigo_referido")) {
                    JsonObject mod = new JsonObject();
                    mod.addProperty("id", 50);
                    mod.addProperty("username", "aliado_norte");
                    mod.addProperty("codigo_referido", "MODNORTE");
                    mod.addProperty("pct", new BigDecimal("10"));
                    return mod;
                }
                if (sql.contains("SELECT COALESCE(SUM(u.saldo), 0) AS total")) {
                    JsonObject fila = new JsonObject();
                    fila.addProperty("total", new BigDecimal("45000.00"));
                    return fila;
                }
                if (sql.contains("FROM moderator_wallet") && sql.contains("categoria = 'ganancia'")) {
                    JsonObject fila = new JsonObject();
                    fila.addProperty("total", new BigDecimal("1250.00"));
                    return fila;
                }
                if (sql.contains("FROM moderator_wallet") && sql.contains("categoria = 'referidos'")) {
                    JsonObject fila = new JsonObject();
                    fila.addProperty("total", new BigDecimal("450.00"));
                    return fila;
                }
                if (sql.contains("SELECT COUNT(*) AS total") && sql.contains("FROM users u")) {
                    JsonObject fila = new JsonObject();
                    fila.addProperty("total", 1L);
                    return fila;
                }
                if (sql.contains("FROM referral_payouts WHERE referrer_user_id = ?")) {
                    JsonObject fila = new JsonObject();
                    fila.addProperty("total_base", new BigDecimal("500.00"));
                    fila.addProperty("total_referidor", new BigDecimal("450.00"));
                    fila.addProperty("total_devuelto", new BigDecimal("50.00"));
                    return fila;
                }
                if (sql.contains("SELECT usuario_id FROM transactions WHERE id = ?")) {
                    JsonObject tx = new JsonObject();
                    tx.addProperty("usuario_id", 200);
                    return tx;
                }
                if (sql.contains("AS moderator_id") && params.length > 0 && ((int) params[0]) == 100) {
                    JsonObject row = new JsonObject();
                    row.addProperty("moderator_id", 50);
                    return row;
                }
                if (sql.contains("SELECT COUNT(*) AS total FROM moderator_nequis")) {
                    JsonObject row = new JsonObject();
                    row.addProperty("total", nequis.size());
                    return row;
                }
                if (sql.contains("SELECT id FROM moderator_nequis WHERE moderator_id = ? AND numero = ?")) {
                    String num = (String) params[1];
                    return nequis.stream().filter(n -> num.equals(n.get("numero").getAsString())).findFirst().orElse(null);
                }
                return null;
            }

            @Override
            public ArrayList<JsonObject> query(String sql, Object... params) {
                ArrayList<JsonObject> res = new ArrayList<>();
                if (sql.contains("FROM moderator_nequis")) {
                    res.addAll(nequis);
                    return res;
                }
                if (sql.contains("mod_ganancia_partidas")) {
                    JsonObject u = new JsonObject();
                    u.addProperty("id", 100);
                    u.addProperty("username", "jugador_mod");
                    u.addProperty("email", "modj@example.invalid");
                    u.addProperty("saldo", new BigDecimal("45000.00"));
                    u.addProperty("tipo_suscripcion", "free");
                    u.addProperty("mod_ganancia_partidas", new BigDecimal("1250.00"));
                    u.addProperty("mod_ganancia_referidos", new BigDecimal("450.00"));
                    u.addProperty("devuelto_referido", new BigDecimal("50.00"));
                    u.addProperty("total_partidas", 4);
                    u.addProperty("total_victorias", 3);
                    u.addProperty("victorias_normales", 3);
                    u.addProperty("victorias_disputa", 0);
                    u.addProperty("total_derrotas", 1);
                    u.addProperty("derrotas_normales", 1);
                    u.addProperty("derrotas_disputa", 0);
                    u.addProperty("faltas", 0);
                    u.addProperty("salidas_chat", 0);
                    res.add(u);
                }
                return res;
            }

            @Override
            public int update(String sql, Object... params) {
                if (sql.contains("INTO moderator_nequis")) {
                    JsonObject n = new JsonObject();
                    n.addProperty("id", seqNequi.getAndIncrement());
                    n.addProperty("numero", (String) params[1]);
                    n.addProperty("titular", (String) params[2]);
                    n.addProperty("creado_en", "2026-11-17T20:00:00Z");
                    nequis.add(n);
                    return 1;
                }
                if (sql.contains("DELETE FROM moderator_nequis")) {
                    int idBorrar = (int) params[0];
                    return nequis.removeIf(n -> n.get("id").getAsInt() == idBorrar) ? 1 : 0;
                }
                return 0;
            }
        };

        AdminService servicio = new AdminService(daoFalso, null, null, null, null, null);

        JsonObject stats = servicio.estadisticas(50);
        assertTrue(stats.get("esModerador").getAsBoolean());
        assertEquals("MODNORTE", stats.get("codigoReferido").getAsString());
        assertMonto("45000.00", stats.get("totalUsuarios").getAsBigDecimal());
        assertMonto("1250.00", stats.get("gananciaComisionesModerador").getAsBigDecimal());
        assertMonto("450.00", stats.get("gananciaReferidosModerador").getAsBigDecimal());
        assertMonto("1700.00", stats.get("gananciaTotalModerador").getAsBigDecimal());
        assertFalse(stats.has("desglose"));
        assertFalse(stats.has("gananciaAdminLeaderboard"));

        JsonArray listaUsuarios = stats.getAsJsonArray("listaUsuarios");
        assertEquals(1, listaUsuarios.size());
        assertMonto("1700.00", listaUsuarios.get(0).getAsJsonObject().get("comision_total_aportada").getAsBigDecimal());

        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.estadoZonaPruebas(50)).getStatus());
        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.cambiarZonaAdmin(50, "prueba")).getStatus());
        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.confirmarVerificacionClash(100, 50)).getStatus());
        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.procesarTransaccion(50, 99, "approve")).getStatus());
        assertEquals(403, assertThrows(ServicioException.class,
                () -> servicio.listarNequisModerador(1)).getStatus());

        List<JsonObject> lista = servicio.agregarNequiModerador(50, "300 123 4567", "Aliado Norte");
        assertEquals(1, lista.size());
        assertEquals("3001234567", lista.get(0).get("numero").getAsString());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> servicio.agregarNequiModerador(50, "3001234567", "Duplicado")).getStatus());
        assertEquals(400, assertThrows(ServicioException.class,
                () -> servicio.agregarNequiModerador(50, "12345", "Corto")).getStatus());
        int creadoId = lista.get(0).get("id").getAsInt();
        assertTrue(servicio.eliminarNequiModerador(50, creadoId).isEmpty());
    }

    private static void assertMonto(String esperado, BigDecimal actual) {
        assertEquals(0, new BigDecimal(esperado).compareTo(actual),
                () -> "Esperado " + esperado + " pero fue " + actual);
    }
}
