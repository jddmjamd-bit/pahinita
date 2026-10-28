package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        // La nueva zona combinada suma ambas sin alterar ninguna de las dos
        assertMonto("1250.00", stats.get("gananciaComisionesAdmin").getAsBigDecimal());
        assertMonto("1625.50", stats.get("gananciaTotalCombinadaAdmin").getAsBigDecimal());

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
        assertMonto("2500.50", primero.get("comision_total_aportada").getAsBigDecimal());

        JsonObject segundo = usuarios.get(1).getAsJsonObject();
        assertMonto("0", segundo.get("ganancia_generada").getAsBigDecimal());
        assertMonto("0", segundo.get("gen_sorteos").getAsBigDecimal());
        assertMonto("0", segundo.get("comision_total_aportada").getAsBigDecimal());
    }

    private static void assertMonto(String esperado, BigDecimal actual) {
        assertEquals(0, new BigDecimal(esperado).compareTo(actual),
                () -> "Esperado " + esperado + " pero fue " + actual);
    }
}
