package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.socketio.SocketEspia;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class MovimientosFinanzasAdminTest {

    @Test
    void finanzasObtenerMovimientosClasificaEstadosHorasYOrigenes() {
        GenericDAO dao = new GenericDAO(null) {
            @Override
            public JsonObject queryOne(String sql, Object... params) {
                if (sql.contains("SELECT username FROM users")) {
                    JsonObject u = new JsonObject();
                    u.addProperty("username", "jugador_pro");
                    return u;
                }
                return null;
            }

            @Override
            public ArrayList<JsonObject> query(String sql, Object... params) {
                ArrayList<JsonObject> lista = new ArrayList<>();
                if (sql.contains("FROM transactions WHERE usuario_id = ?")) {
                    lista.add(tx(1, "deposito", "manual_nequi", new BigDecimal("20000"), "NEQ-111", "pendiente", "2026-11-14T20:10:00Z"));
                    lista.add(tx(2, "deposito", "wompi", new BigDecimal("40000"), "TF-40000", "completado", "2026-11-14T19:00:00Z"));
                    lista.add(tx(3, "deposito", "manual_nequi", new BigDecimal("15000"), "NEQ-ERR", "rechazado", "2026-11-14T18:50:00Z"));
                    lista.add(tx(4, "retiro", "nequi_retiro", new BigDecimal("12000"), "3001112233", "pendiente", "2026-11-14T20:05:00Z"));
                    lista.add(tx(5, "retiro", "nequi_retiro", new BigDecimal("10000"), "3001112233", "completado", "2026-11-14T18:00:00Z"));
                    lista.add(tx(6, "retiro", "nequi_retiro", new BigDecimal("8000"), "3009998877", "rechazado", "2026-11-14T17:30:00Z"));
                } else if (sql.contains("FROM matches WHERE jugador1 = ?")) {
                    lista.add(match(101, "jugador_pro", "rival_1", "Mega Draft", new BigDecimal("5000"), "", "en_curso", "2026-11-14T20:15:00Z"));
                    lista.add(match(102, "jugador_pro", "rival_2", "Elección", new BigDecimal("10000"), "jugador_pro", "finalizada", "2026-11-14T19:30:00Z"));
                    lista.add(match(103, "rival_3", "jugador_pro", "Clásico", new BigDecimal("5000"), "rival_3", "finalizada", "2026-11-14T18:30:00Z"));
                    lista.add(match(104, "jugador_pro", "rival_4", "Clásico", new BigDecimal("5000"), "Cancelada", "finalizada", "2026-11-14T18:20:00Z"));
                } else if (sql.contains("FROM referral_payouts rp") && sql.contains("rp.referrer_user_id = ?")) {
                    JsonObject r = new JsonObject();
                    r.addProperty("id", 50);
                    r.addProperty("match_id", 102);
                    r.addProperty("monto_referidor", new BigDecimal("225"));
                    r.addProperty("porcentaje_compartido", new BigDecimal("25"));
                    r.addProperty("invitado_username", "invitado_vip");
                    r.addProperty("creado_en", "2026-11-14T19:31:00Z");
                    lista.add(r);
                } else if (sql.contains("FROM referral_payouts rp") && sql.contains("rp.jugador_id = ?")) {
                    JsonObject r = new JsonObject();
                    r.addProperty("id", 51);
                    r.addProperty("match_id", 103);
                    r.addProperty("monto_devuelto_referido", new BigDecimal("75"));
                    r.addProperty("porcentaje_compartido", new BigDecimal("25"));
                    r.addProperty("referidor_username", "mentor_pro");
                    r.addProperty("creado_en", "2026-11-14T18:31:00Z");
                    lista.add(r);
                } else if (sql.contains("FROM leaderboard_cut_payments lcp")) {
                    JsonObject lb = new JsonObject();
                    lb.addProperty("id", 70);
                    lb.addProperty("tipo_pago", "jugador");
                    lb.addProperty("posicion", 1);
                    lb.addProperty("monto", new BigDecimal("15000"));
                    lb.addProperty("estado", "pagado");
                    lb.addProperty("periodo", "dia");
                    lb.addProperty("clave_corte", "2026-11-14");
                    lb.addProperty("creado_en", "2026-11-14T17:00:00Z");
                    lb.addProperty("pagado_en", "2026-11-14T17:01:00Z");
                    lista.add(lb);
                } else if (sql.contains("FROM wallet_ledger")) {
                    JsonObject man = new JsonObject();
                    man.addProperty("id", 90);
                    man.addProperty("tipo", "deposito");
                    man.addProperty("monto", new BigDecimal("5000"));
                    man.addProperty("referencia", "Depósito manual admin");
                    man.addProperty("fecha", "2026-11-14T16:00:00Z");
                    lista.add(man);

                    // Este ya está cubierto en transactions/matches y no debe duplicarse
                    JsonObject dup = new JsonObject();
                    dup.addProperty("id", 91);
                    dup.addProperty("tipo", "deposito");
                    dup.addProperty("monto", new BigDecimal("40000"));
                    dup.addProperty("referencia", "Pago Wompi ref:TF-40000 id:w1");
                    dup.addProperty("fecha", "2026-11-14T19:00:00Z");
                    lista.add(dup);
                }
                return lista;
            }
        };

        AppConfig config = new AppConfig(java.util.Map.of());
        FinanzasService servicio = new FinanzasService(
                dao, config, null, new ValidadorMonto(config), null);
        JsonObject resp = servicio.obtenerMovimientos(7);

        assertTrue(resp.get("success").getAsBoolean());
        JsonArray movs = resp.getAsJsonArray("movimientos");
        assertEquals(15, movs.size());

        // Orden descendente: el más reciente es match-101 (20:15:00Z)
        JsonObject primero = movs.get(0).getAsJsonObject();
        assertEquals("match-101", primero.get("id").getAsString());
        assertEquals("pendiente", primero.get("estadoVisual").getAsString());
        assertEquals("En curso", primero.get("estadoTexto").getAsString());
        assertTrue(primero.get("origen").getAsString().contains("@rival_1"));

        JsonObject recargaPendiente = buscarPorId(movs, "tx-1");
        assertEquals("pendiente", recargaPendiente.get("estadoVisual").getAsString());
        assertTrue(recargaPendiente.get("origen").getAsString().contains("manual"));

        JsonObject wompiAprobado = buscarPorId(movs, "tx-2");
        assertEquals("positivo", wompiAprobado.get("estadoVisual").getAsString());
        assertEquals("+", wompiAprobado.get("signo").getAsString());
        assertEquals("Wompi (Pasarela)", wompiAprobado.get("origen").getAsString());

        JsonObject recargaRechazada = buscarPorId(movs, "tx-3");
        assertEquals("negativo", recargaRechazada.get("estadoVisual").getAsString());

        JsonObject retiroRechazado = buscarPorId(movs, "tx-6");
        assertEquals("positivo", retiroRechazado.get("estadoVisual").getAsString());
        assertEquals("+", retiroRechazado.get("signo").getAsString());

        JsonObject victoria = buscarPorId(movs, "match-102");
        assertEquals("positivo", victoria.get("estadoVisual").getAsString());
        assertEquals("Victoria", victoria.get("estadoTexto").getAsString());
        assertEquals("+", victoria.get("signo").getAsString());

        JsonObject entradaVictoria = buscarPorId(movs, "match-entry-102");
        assertNotNull(entradaVictoria);
        assertEquals("negativo", entradaVictoria.get("estadoVisual").getAsString());
        assertEquals("Entrada", entradaVictoria.get("estadoTexto").getAsString());
        assertEquals("-", entradaVictoria.get("signo").getAsString());
        assertEquals(0, new BigDecimal("10000").compareTo(entradaVictoria.get("monto").getAsBigDecimal()));

        JsonObject derrota = buscarPorId(movs, "match-103");
        assertEquals("negativo", derrota.get("estadoVisual").getAsString());
        assertEquals("-", derrota.get("signo").getAsString());

        JsonObject refGan = buscarPorId(movs, "ref-gan-50");
        assertEquals("positivo", refGan.get("estadoVisual").getAsString());
        assertTrue(refGan.get("origen").getAsString().contains("@invitado_vip"));
        assertTrue(refGan.get("origen").getAsString().contains("Partida #102"));

        JsonObject refDev = buscarPorId(movs, "ref-dev-51");
        assertEquals("positivo", refDev.get("estadoVisual").getAsString());
        assertTrue(refDev.get("origen").getAsString().contains("@mentor_pro"));

        assertNotNull(buscarPorId(movs, "ledger-90"));
        assertNull(buscarPorId(movs, "ledger-91"));

        JsonObject resumen = resp.getAsJsonObject("resumen");
        assertEquals(2, resumen.get("pendientesCount").getAsInt());
        assertEquals(1, resumen.get("enCursoCount").getAsInt());
        assertEquals(0, new BigDecimal("300").compareTo(resumen.get("totalReferidos").getAsBigDecimal()));
    }

    @Test
    void adminObtenerMovimientosClasificaPlataformaComisionesYReferidos() {
        GenericDAO dao = new GenericDAO(null) {
            @Override
            public ArrayList<JsonObject> query(String sql, Object... params) {
                ArrayList<JsonObject> lista = new ArrayList<>();
                if (sql.contains("FROM transactions ORDER BY")) {
                    JsonObject manual = tx(11, "deposito", "manual_nequi", new BigDecimal("25000"), "NEQ-9", "pendiente", "2026-11-14T21:00:00Z");
                    manual.addProperty("usuario_id", 8);
                    manual.addProperty("usuario_nombre", "ana");
                    lista.add(manual);

                    JsonObject retiro = tx(12, "retiro", "nequi_retiro", new BigDecimal("18000"), "310", "completado", "2026-11-14T20:00:00Z");
                    retiro.addProperty("usuario_id", 8);
                    retiro.addProperty("usuario_nombre", "ana");
                    lista.add(retiro);
                } else if (sql.contains("FROM matches ORDER BY")) {
                    lista.add(match(201, "ana", "beto", "Elección", new BigDecimal("10000"), "ana", "finalizada", "2026-11-14T20:30:00Z"));
                    lista.add(match(202, "carlos", "diana", "Clásico", new BigDecimal("5000"), "", "en_curso", "2026-11-14T21:05:00Z"));
                } else if (sql.contains("FROM referral_payouts rp")) {
                    JsonObject sinRef = new JsonObject();
                    sinRef.addProperty("id", 301);
                    sinRef.addProperty("match_id", 201);
                    sinRef.addProperty("destino_tipo", "admin");
                    sinRef.addProperty("monto_referidor", BigDecimal.ZERO);
                    sinRef.addProperty("monto_devuelto_referido", BigDecimal.ZERO);
                    sinRef.addProperty("monto_admin", new BigDecimal("300"));
                    sinRef.addProperty("porcentaje_compartido", BigDecimal.ZERO);
                    sinRef.addProperty("jugador_username", "beto");
                    sinRef.addProperty("referidor_username", "");
                    sinRef.addProperty("creado_en", "2026-11-14T20:31:00Z");
                    lista.add(sinRef);
                } else if (sql.contains("FROM leaderboard_cut_payments lcp")) {
                    JsonObject sobrante = new JsonObject();
                    sobrante.addProperty("id", 401);
                    sobrante.addProperty("tipo_pago", "admin");
                    sobrante.addProperty("username", "admin_principal");
                    sobrante.addProperty("posicion", 0);
                    sobrante.addProperty("monto", new BigDecimal("2500"));
                    sobrante.addProperty("estado", "pagado");
                    sobrante.addProperty("periodo", "semana");
                    sobrante.addProperty("clave_corte", "2026-W46");
                    sobrante.addProperty("creado_en", "2026-11-14T19:00:00Z");
                    sobrante.addProperty("pagado_en", "2026-11-14T19:01:00Z");
                    lista.add(sobrante);
                } else if (sql.contains("FROM raffle_pool_movements")) {
                    JsonObject pozo = new JsonObject();
                    pozo.addProperty("id", 501);
                    pozo.addProperty("tipo", "acumulacion");
                    pozo.addProperty("variacion", new BigDecimal("600"));
                    pozo.addProperty("referencia", "Match #201");
                    pozo.addProperty("fecha", "2026-11-14T20:30:05Z");
                    lista.add(pozo);
                }
                return lista;
            }
        };

        AdminService admin = new AdminService(dao, null, null, null, null, new SocketEspia());
        JsonObject resp = admin.obtenerMovimientosAdmin(1);
        assertTrue(resp.get("success").getAsBoolean());

        JsonArray movs = resp.getAsJsonArray("movimientos");
        assertEquals(7, movs.size());

        JsonObject txPend = buscarPorId(movs, "tx-11");
        assertEquals("pendiente", txPend.get("estadoVisual").getAsString());
        assertTrue(txPend.get("puedeProcesar").getAsBoolean());
        assertTrue(txPend.get("origen").getAsString().contains("@ana"));

        JsonObject matchFin = buscarPorId(movs, "match-201");
        assertEquals("positivo", matchFin.get("estadoVisual").getAsString());
        assertTrue(matchFin.get("detalle").getAsString().contains("Ganancia admin"));
        assertEquals(0, new BigDecimal("1100").compareTo(matchFin.get("monto").getAsBigDecimal()));
        assertEquals(0, new BigDecimal("500").compareTo(matchFin.get("gananciaBaseAdmin").getAsBigDecimal()));
        assertEquals(0, new BigDecimal("600").compareTo(matchFin.get("gananciaInactivasAdmin").getAsBigDecimal()));

        JsonObject refAdmin = buscarPorId(movs, "ref-301");
        assertEquals("positivo", refAdmin.get("estadoVisual").getAsString());
        assertEquals(0, new BigDecimal("300").compareTo(refAdmin.get("monto").getAsBigDecimal()));

        JsonObject resumen = resp.getAsJsonObject("resumen");
        assertEquals(1, resumen.get("pendientesAccionCount").getAsInt());
        assertEquals(1, resumen.get("partidasEnCursoCount").getAsInt());
        assertEquals(0, new BigDecimal("300").compareTo(resumen.get("referidosSinReferidorTotal").getAsBigDecimal()));
        assertEquals(0, new BigDecimal("1100").compareTo(resumen.get("gananciaNetaAdminPartidas").getAsBigDecimal()));
    }

    @Test
    void notificacionesListenerEmiteColoresOrigenYEliminaPendientesAlResolver() {
        List<JsonObject> notifs = new ArrayList<>();
        GenericDAO dao = new GenericDAO(null) {
            int seq = 0;

            @Override
            public int insertReturningId(String sql, Object... params) {
                if (sql.contains("INSERT INTO user_notifications")) {
                    int id = ++seq;
                    JsonObject row = new JsonObject();
                    row.addProperty("id", id);
                    row.addProperty("user_id", (Integer) params[0]);
                    row.addProperty("categoria", String.valueOf(params[1]));
                    row.addProperty("titulo", String.valueOf(params[2]));
                    row.addProperty("mensaje", String.valueOf(params[3]));
                    row.addProperty("ruta", String.valueOf(params[4]));
                    if (params[5] == null) row.add("evento_clave", com.google.gson.JsonNull.INSTANCE);
                    else row.addProperty("evento_clave", String.valueOf(params[5]));
                    row.add("datos", JsonParser.parseString(String.valueOf(params[6])));
                    row.add("leida_en", com.google.gson.JsonNull.INSTANCE);
                    row.add("descartada_en", com.google.gson.JsonNull.INSTANCE);
                    row.addProperty("creada_en", "2026-11-14T22:00:00Z");
                    notifs.add(row);
                    return id;
                }
                return 0;
            }

            @Override
            public JsonObject queryOne(String sql, Object... params) {
                if (sql.contains("FROM user_notifications WHERE id = ?")) {
                    int id = (Integer) params[0];
                    return notifs.stream().filter(n -> n.get("id").getAsInt() == id).findFirst().orElse(null);
                }
                if (sql.contains("FROM matches WHERE id = ?")) {
                    return match(55, "player_dos", "rival_tres", "Elección", new BigDecimal("10000"), "", "en_curso", "2026-11-14T22:00:00Z");
                }
                if (sql.contains("SELECT username FROM users WHERE id = ?")) {
                    int uid = (Integer) params[0];
                    JsonObject u = new JsonObject();
                    u.addProperty("username", uid == 2 ? "player_dos" : "rival_tres");
                    return u;
                }
                return null;
            }

            @Override
            public ArrayList<JsonObject> query(String sql, Object... params) {
                ArrayList<JsonObject> res = new ArrayList<>();
                if (sql.contains("SELECT id FROM users WHERE tipo_suscripcion = 'admin'")) {
                    JsonObject a = new JsonObject();
                    a.addProperty("id", 1);
                    res.add(a);
                } else if (sql.contains("FROM user_notifications") && sql.contains("evento_clave")) {
                    String exact = String.valueOf(params[0]);
                    for (JsonObject n : notifs) {
                        if (!n.get("evento_clave").isJsonNull()) {
                            String cl = n.get("evento_clave").getAsString();
                            if (cl.equals(exact) || cl.startsWith(exact + ":")) {
                                JsonObject f = new JsonObject();
                                f.addProperty("user_id", n.get("user_id").getAsInt());
                                f.addProperty("evento_clave", cl);
                                res.add(f);
                            }
                        }
                    }
                }
                return res;
            }

            @Override
            public int update(String sql, Object... params) {
                if (sql.contains("DELETE FROM user_notifications WHERE evento_clave")) {
                    String exact = String.valueOf(params[0]);
                    int before = notifs.size();
                    notifs.removeIf(n -> !n.get("evento_clave").isJsonNull()
                            && (n.get("evento_clave").getAsString().equals(exact)
                                || n.get("evento_clave").getAsString().startsWith(exact + ":")));
                    return before - notifs.size();
                }
                return 0;
            }
        };

        SocketEspia io = new SocketEspia();
        io.registrarUsuario(1); // admin
        io.registrarUsuario(2); // jugador
        io.registrarUsuario(3); // rival

        NotificacionesService ns = new NotificacionesService(dao, io, null, "");
        EventBus bus = new EventBus();
        new NotificacionesListener(ns, dao).registrar(bus);

        // 1. Transacción pendiente -> notificaciones grises (pendiente)
        bus.emit(Eventos.TRANSACCION_PENDIENTE_CREADA, new Eventos.TransaccionPendiente(
                77, 2, "player_dos", "deposito", "manual_nequi", new BigDecimal("20000"), "NEQ-77"));

        assertTrue(notifs.stream().anyMatch(n ->
                n.get("user_id").getAsInt() == 2
                        && !n.get("evento_clave").isJsonNull()
                        && "tx-pendiente:77".equals(n.get("evento_clave").getAsString())
                        && "pendiente".equals(n.getAsJsonObject("datos").get("estadoVisual").getAsString())));
        assertTrue(notifs.stream().anyMatch(n ->
                n.get("user_id").getAsInt() == 1
                        && !n.get("evento_clave").isJsonNull()
                        && "admin-tx-pendiente:77".equals(n.get("evento_clave").getAsString())));
        assertTrue(io.emitidos.stream().anyMatch(e -> "movimiento_actualizado".equals(e.evento())));

        // 2. Transacción aprobada -> elimina pendientes y crea verde (positivo)
        bus.emit(Eventos.TRANSACCION_PROCESADA, new Eventos.TransaccionProcesada(
                77, 2, "player_dos", "deposito", "manual_nequi", new BigDecimal("20000"), "completado", "NEQ-77"));

        assertFalse(notifs.stream().anyMatch(n ->
                !n.get("evento_clave").isJsonNull() && n.get("evento_clave").getAsString().contains("pendiente:77")));
        assertTrue(notifs.stream().anyMatch(n ->
                n.get("user_id").getAsInt() == 2
                        && "positivo".equals(n.getAsJsonObject("datos").get("estadoVisual").getAsString())
                        && "Nequi Manual".equals(n.getAsJsonObject("datos").get("origen").getAsString())));

        // 3. Comisión de referido -> notificación verde con origen
        bus.emit(Eventos.WALLET_AWARDED, new Eventos.MovimientoWallet(
                Eventos.TipoMovimiento.PREMIO, 2, new BigDecimal("225"), new BigDecimal("20225"),
                "Comisión de referido por @rival_tres (Partida #55)"));
        assertTrue(notifs.stream().anyMatch(n ->
                n.get("user_id").getAsInt() == 2
                        && "positivo".equals(n.getAsJsonObject("datos").get("estadoVisual").getAsString())
                        && n.getAsJsonObject("datos").get("origen").getAsString().contains("@rival_tres")));

        // 4. Partida iniciada y finalizada -> gris y luego admin comisión verde
        bus.emit(Eventos.MATCH_STARTED, new Eventos.PartidaIniciada(55, List.of(2, 3)));
        assertTrue(notifs.stream().anyMatch(n ->
                n.get("user_id").getAsInt() == 2
                        && !n.get("evento_clave").isJsonNull()
                        && "match-curso:55:2".equals(n.get("evento_clave").getAsString())
                        && "pendiente".equals(n.getAsJsonObject("datos").get("estadoVisual").getAsString())));

        ComisionService.Desglose desglose = new ComisionService().calcular(new BigDecimal("10000"));
        bus.emit(Eventos.MATCH_FINISHED, new Eventos.PartidaFinalizada(
                55, Eventos.Origen.API, List.of(2, 3), 2, "player_dos",
                new BigDecimal("10000"), desglose.getPremio(), new BigDecimal("35000"), desglose));
        assertFalse(notifs.stream().anyMatch(n ->
                !n.get("evento_clave").isJsonNull() && n.get("evento_clave").getAsString().startsWith("match-curso:55")));
        assertTrue(notifs.stream().anyMatch(n ->
                n.get("user_id").getAsInt() == 1
                        && !n.get("evento_clave").isJsonNull()
                        && "admin-match-fin:55".equals(n.get("evento_clave").getAsString())
                        && "positivo".equals(n.getAsJsonObject("datos").get("estadoVisual").getAsString())
                        && new BigDecimal("1100").compareTo(n.getAsJsonObject("datos").get("monto").getAsBigDecimal()) == 0));
    }

    private static JsonObject tx(int id, String tipo, String metodo, BigDecimal monto,
                                 String referencia, String estado, String fecha) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("tipo", tipo);
        o.addProperty("metodo", metodo);
        o.addProperty("monto", monto);
        o.addProperty("referencia", referencia);
        o.addProperty("estado", estado);
        o.addProperty("fecha", fecha);
        return o;
    }

    private static JsonObject match(int id, String j1, String j2, String modo, BigDecimal monto,
                                    String ganador, String estado, String fecha) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("sala_id", "sala-" + id);
        o.addProperty("jugador1", j1);
        o.addProperty("jugador2", j2);
        o.addProperty("modo", modo);
        o.addProperty("monto", monto);
        o.addProperty("ganador", ganador);
        o.addProperty("estado", estado);
        o.addProperty("fecha", fecha);
        return o;
    }

    private static JsonObject buscarPorId(JsonArray arr, String id) {
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            if (id.equals(obj.get("id").getAsString())) return obj;
        }
        return null;
    }
}
