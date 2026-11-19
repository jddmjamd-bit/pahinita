package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Convierte los eventos confirmados del dominio en avisos persistentes y push para usuarios desconectados. */
public final class NotificacionesListener {
    private final NotificacionesService notificaciones;
    private final GenericDAO db;

    public NotificacionesListener(NotificacionesService notificaciones, GenericDAO db) {
        this.notificaciones = notificaciones;
        this.db = db;
    }

    public void registrar(EventBus bus) {
        bus.on(Eventos.CHAT_MESSAGE_PUBLISHED, "notificacion-chat-publico", this::mensajePublico);
        bus.on(Eventos.PRIVATE_MESSAGE_PUBLISHED, "notificacion-chat-privado", this::mensajePrivado);
        // Las notificaciones iniciales y progresivas de búsqueda las gestiona MatchmakingService por oleadas (Prioridad 15).
        bus.on(Eventos.MATCH_SEARCH_ENDED, "notificacion-busqueda-terminada", this::busquedaTerminada);
        bus.on(Eventos.MATCH_ROOM_CREATED, "limpiar-aviso-busqueda-al-emparejar", this::emparejada);
        bus.on(Eventos.LEADERBOARD_CUT_COMPLETED, "notificacion-corte-ranking", this::corteRanking);
        bus.on(Eventos.RAFFLE_CREATED, "notificacion-sorteo-nuevo", this::sorteoNuevo);
        bus.on(Eventos.RAFFLE_FINISHED, "notificacion-sorteo-resultado", this::sorteoFinalizado);
        bus.on(Eventos.RAFFLE_CANCELLED, "notificacion-sorteo-cancelado", this::sorteoCancelado);
        bus.on(Eventos.RAFFLE_POLL_RESET, "notificacion-encuesta-sorteos", this::encuestaReiniciada);
        bus.on(Eventos.TRANSACCION_PENDIENTE_CREADA, "notificacion-tx-pendiente", this::transaccionPendiente);
        bus.on(Eventos.TRANSACCION_PROCESADA, "notificacion-tx-procesada", this::transaccionProcesada);
        bus.on(Eventos.WALLET_DEPOSITED, "notificacion-deposito-manual", this::depositoDirecto);
        bus.on(Eventos.WALLET_AWARDED, "notificacion-premio-wallet", this::premioAcreditado);
        bus.on(Eventos.MATCH_STARTED, "notificacion-partida-iniciada", this::partidaIniciada);
        bus.on(Eventos.MATCH_FINISHED, "notificacion-partida-fin-finanzas", this::partidaFinalizadaFinanzas);
    }

    private void mensajePublico(EventBus.Evento evento) {
        Eventos.MensajeChatPublicado mensaje = evento.datos(Eventos.MensajeChatPublicado.class);
        if (mensaje == null || mensaje.messageId() <= 0 || mensaje.canal() == null
                || "clash_logs".equals(mensaje.canal())) return;
        String nombreCanal = switch (mensaje.canal()) {
            case "general" -> "General";
            case "anuncios" -> "Anuncios";
            case "clash" -> "Clash";
            case "pagos" -> "Pagos";
            default -> mensaje.canal();
        };
        JsonObject datos = new JsonObject();
        datos.addProperty("canal", mensaje.canal());
        datos.addProperty("messageId", mensaje.messageId());
        notificaciones.notificarATodos(Set.of(mensaje.emisorId()), "chat", "Mensaje en " + nombreCanal,
                mensaje.username() + ": " + mensaje.preview(), "/chat?canal=" + mensaje.canal(),
                "chat:" + mensaje.messageId(), datos, true);
    }

    private void mensajePrivado(EventBus.Evento evento) {
        Eventos.MensajePrivadoPublicado mensaje = evento.datos(Eventos.MensajePrivadoPublicado.class);
        if (mensaje == null || mensaje.destinatarioId() <= 0) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("salaId", mensaje.salaId());
        notificaciones.notificar(mensaje.destinatarioId(), "partida", "Mensaje de " + mensaje.username(),
                mensaje.preview(), "/match", "mensaje-privado:" + mensaje.messageId(), datos, true);
    }

    private void busquedaTerminada(EventBus.Evento evento) {
        Eventos.BusquedaMatch busqueda = evento.datos(Eventos.BusquedaMatch.class);
        if (busqueda != null) notificaciones.eliminarPorEvento(claveBusqueda(busqueda.userId()));
    }

    private void emparejada(EventBus.Evento evento) {
        Eventos.SalaPartidaCreada sala = evento.datos(Eventos.SalaPartidaCreada.class);
        if (sala == null) return;
        for (int userId : sala.participantes()) notificaciones.eliminarPorEvento(claveBusqueda(userId));
    }

    private void corteRanking(EventBus.Evento evento) {
        Eventos.CorteLeaderboard corte = evento.datos(Eventos.CorteLeaderboard.class);
        if (corte == null || corte.corteId() <= 0) return;
        String periodo = nombrePeriodo(corte.periodo());
        JsonObject datosCorte = new JsonObject();
        datosCorte.addProperty("corteId", corte.corteId());
        datosCorte.addProperty("periodo", corte.periodo());
        notificaciones.notificarATodos(Set.of(), "ranking", "Nuevo corte del ranking",
                "Se completó el corte " + periodo + ". Revisa los resultados y el historial de ganadores.",
                "/leaderboard", "corte-ranking:" + corte.corteId(), datosCorte, true);

        for (JsonObject ganador : db.query(
                "SELECT user_id, username, posicion, victorias, premio FROM leaderboard_cut_snapshots " +
                        "WHERE corte_id = ? AND posicion <= 10 ORDER BY posicion", corte.corteId())) {
            int userId = ganador.get("user_id").getAsInt();
            int posicion = ganador.get("posicion").getAsInt();
            JsonObject datos = new JsonObject();
            datos.addProperty("corteId", corte.corteId());
            datos.addProperty("periodo", periodo);
            datos.addProperty("posicion", posicion);
            datos.add("premio", ganador.get("premio"));
            notificaciones.notificar(userId, "ranking", "Ganaste el ranking " + periodo,
                    "Quedaste #" + posicion + " en " + periodo + " y ganaste $" +
                            ganador.get("premio").getAsBigDecimal().toPlainString() + ".",
                    "/leaderboard", "ranking-ganador:" + corte.corteId(), datos, true);
        }

        for (int adminId : obtenerAdminIds()) {
            emitirMovimientoActualizado(adminId, "ranking", "positivo", null, "Corte ranking " + periodo);
        }
    }

    private void sorteoNuevo(EventBus.Evento evento) {
        Eventos.SorteoCreado sorteo = evento.datos(Eventos.SorteoCreado.class);
        if (sorteo == null) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("raffleId", sorteo.raffleId());
        notificaciones.notificarATodos(Set.of(), "sorteos", "Nuevo sorteo", "Ya está disponible «" +
                sorteo.nombre() + "».", "/sorteos", "sorteo-nuevo:" + sorteo.raffleId(), datos, true);
    }

    private void sorteoFinalizado(EventBus.Evento evento) {
        Eventos.SorteoFinalizado sorteo = evento.datos(Eventos.SorteoFinalizado.class);
        if (sorteo == null) return;
        String causa = sorteo.motivo() == null || sorteo.motivo().isBlank()
                ? "se completó" : sorteo.motivo();
        Set<Integer> participantes = Set.copyOf(sorteo.participantes());
        JsonObject resultadoGeneral = new JsonObject();
        resultadoGeneral.addProperty("raffleId", sorteo.raffleId());
        resultadoGeneral.addProperty("motivo", causa);
        resultadoGeneral.addProperty("ganadorNombre", sorteo.ganadorNombre());
        notificaciones.notificarATodos(participantes, "sorteos", "Sorteo finalizado",
                "«" + sorteo.nombre() + "» terminó: " + causa + ". Ganó " + sorteo.ganadorNombre() + ".",
                "/sorteos", "sorteo-fin:" + sorteo.raffleId(), resultadoGeneral, false);
        for (int userId : sorteo.participantes()) {
            boolean ganador = userId == sorteo.ganadorId();
            JsonObject datos = new JsonObject();
            datos.addProperty("raffleId", sorteo.raffleId());
            datos.addProperty("ganador", ganador);
            datos.addProperty("ganadorNombre", sorteo.ganadorNombre());
            datos.addProperty("motivo", causa);
            String texto = ganador
                    ? "¡Ganaste «" + sorteo.nombre() + "»! Terminó porque " + causa + ". El administrador coordinará la entrega."
                    : "«" + sorteo.nombre() + "» terminó porque " + causa + ". Ganó " + sorteo.ganadorNombre() + ".";
            notificaciones.notificar(userId, "sorteos", ganador ? "Ganaste un sorteo" : "Sorteo finalizado",
                    texto, "/sorteos", "sorteo-resultado:" + sorteo.raffleId(), datos, true);
        }
    }

    private void sorteoCancelado(EventBus.Evento evento) {
        Eventos.SorteoCancelado sorteo = evento.datos(Eventos.SorteoCancelado.class);
        if (sorteo == null) return;
        JsonObject datos = new JsonObject();
        datos.addProperty("raffleId", sorteo.raffleId());
        for (int userId : sorteo.participantes()) {
            notificaciones.notificar(userId, "sorteos", "Sorteo cancelado",
                    "Se canceló «" + sorteo.nombre() + "» y tus tickets fueron devueltos.",
                    "/sorteos", "sorteo-cancelado:" + sorteo.raffleId(), datos, true);
        }
    }

    private void encuestaReiniciada(EventBus.Evento evento) {
        JsonObject datos = new JsonObject();
        notificaciones.notificarATodos(Set.of(), "sorteos", "Nueva encuesta de sorteos",
                "Se reinició la encuesta. Entra a elegir tu premio favorito.", "/sorteos",
                "encuesta-sorteos:" + System.currentTimeMillis(), datos, true);
    }

    private void transaccionPendiente(EventBus.Evento evento) {
        Eventos.TransaccionPendiente tx = evento.datos(Eventos.TransaccionPendiente.class);
        if (tx == null || tx.userId() <= 0) return;

        boolean esDeposito = "deposito".equalsIgnoreCase(tx.tipo());
        String origen = esDeposito
                ? ("wompi".equalsIgnoreCase(tx.metodo()) ? "Wompi (Pasarela)" : "Nequi Manual")
                : "Retiro Nequi";
        String montoTxt = formatoCop(tx.monto());
        String tagUser = tagUsuario(tx.username(), tx.userId());

        JsonObject datosUser = construirDatosMovimiento(
                "pendiente",
                esDeposito ? "" : "-",
                tx.monto(),
                origen,
                esDeposito ? "recarga" : "retiro"
        );
        if (tx.transactionId() > 0) {
            datosUser.addProperty("transaccionId", tx.transactionId());
        }
        String tituloUser = esDeposito ? "Recarga en revisión" : "Retiro en revisión";
        String mensajeUser = esDeposito
                ? "$" + montoTxt + " · " + origen + " (pendiente de confirmación)"
                : "-$" + montoTxt + " · Solicitud de retiro en revisión";

        notificaciones.notificar(tx.userId(), "finanzas", tituloUser, mensajeUser,
                "/finance", claveTx("tx-pendiente:", tx.transactionId()), datosUser, true);
        emitirMovimientoActualizado(tx.userId(), esDeposito ? "recarga" : "retiro", "pendiente", tx.monto(), origen);

        JsonObject datosAdmin = construirDatosMovimiento(
                "pendiente",
                esDeposito ? "+" : "-",
                tx.monto(),
                tagUser + " · " + origen,
                esDeposito ? "recarga" : "retiro"
        );
        if (tx.transactionId() > 0) {
            datosAdmin.addProperty("transaccionId", tx.transactionId());
        }
        String tituloAdmin = esDeposito ? "Nueva recarga pendiente" : "Nuevo retiro pendiente";
        String mensajeAdmin = tagUser + " · $" + montoTxt + " por " + origen;
        Integer modId = obtenerModeradorDeUsuario(tx.userId());
        if (modId != null) {
            if (modId != tx.userId()) {
                notificaciones.notificar(modId, "finanzas", tituloAdmin, mensajeAdmin,
                        "/admin", claveTx("admin-tx-pendiente:", tx.transactionId()), datosAdmin, true);
            }
            emitirMovimientoActualizado(modId, esDeposito ? "recarga" : "retiro", "pendiente", tx.monto(), origen);
        } else {
            for (int adminId : obtenerAdminIds()) {
                if (adminId != tx.userId()) {
                    notificaciones.notificar(adminId, "finanzas", tituloAdmin, mensajeAdmin,
                            "/admin", claveTx("admin-tx-pendiente:", tx.transactionId()), datosAdmin, true);
                }
                emitirMovimientoActualizado(adminId, esDeposito ? "recarga" : "retiro", "pendiente", tx.monto(), origen);
            }
        }
    }

    private void transaccionProcesada(EventBus.Evento evento) {
        Eventos.TransaccionProcesada tx = evento.datos(Eventos.TransaccionProcesada.class);
        if (tx == null || tx.userId() <= 0) return;

        if (tx.transactionId() > 0) {
            notificaciones.eliminarPorEvento("tx-pendiente:" + tx.transactionId());
            notificaciones.eliminarPorEvento("admin-tx-pendiente:" + tx.transactionId());
        }

        boolean esDeposito = "deposito".equalsIgnoreCase(tx.tipo());
        boolean completado = "completado".equalsIgnoreCase(tx.accion());
        String metodoOrigen = "wompi".equalsIgnoreCase(tx.metodo()) ? "Wompi (Pasarela)" : "Nequi Manual";
        String origen = esDeposito ? metodoOrigen : (completado ? "Retiro Nequi" : "Devolución de retiro");
        String montoTxt = formatoCop(tx.monto());
        String tagUser = tagUsuario(tx.username(), tx.userId());

        String estadoVisual;
        String signo;
        String tituloUser;
        String msgUser;
        String tituloAdmin;
        String msgAdmin;

        if (esDeposito && completado) {
            estadoVisual = "positivo";
            signo = "+";
            tituloUser = "Recarga aprobada";
            msgUser = "+$" + montoTxt + " acreditados desde " + origen + ".";
            tituloAdmin = "Recarga aprobada";
            msgAdmin = tagUser + " · +$" + montoTxt + " (" + origen + ")";
        } else if (esDeposito) {
            estadoVisual = "negativo";
            signo = "";
            tituloUser = "Recarga rechazada";
            msgUser = "Tu recarga de $" + montoTxt + " (" + origen + ") fue rechazada.";
            tituloAdmin = "Recarga rechazada";
            msgAdmin = tagUser + " · $" + montoTxt + " rechazada (" + origen + ")";
        } else if (completado) {
            estadoVisual = "negativo";
            signo = "-";
            tituloUser = "Retiro enviado";
            msgUser = "-$" + montoTxt + " · Tu retiro por " + origen + " fue enviado.";
            tituloAdmin = "Retiro enviado";
            msgAdmin = tagUser + " · -$" + montoTxt + " (" + origen + ")";
        } else {
            estadoVisual = "positivo";
            signo = "+";
            tituloUser = "Retiro rechazado (saldo devuelto)";
            msgUser = "+$" + montoTxt + " devueltos a tu billetera (" + origen + ").";
            tituloAdmin = "Retiro rechazado";
            msgAdmin = tagUser + " · +$" + montoTxt + " devueltos";
        }

        JsonObject datosUser = construirDatosMovimiento(estadoVisual, signo, tx.monto(), origen,
                esDeposito ? "recarga" : "retiro");
        notificaciones.notificar(tx.userId(), "finanzas", tituloUser, msgUser,
                "/finance", claveTx("tx-resuelta:", tx.transactionId()), datosUser, true);
        emitirMovimientoActualizado(tx.userId(), esDeposito ? "recarga" : "retiro", estadoVisual, tx.monto(), origen);

        JsonObject datosAdmin = construirDatosMovimiento(estadoVisual, signo, tx.monto(),
                tagUser + " · " + origen, esDeposito ? "recarga" : "retiro");
        Integer modId = obtenerModeradorDeUsuario(tx.userId());
        if (modId != null) {
            if (modId != tx.userId()) {
                notificaciones.notificar(modId, "finanzas", tituloAdmin, msgAdmin,
                        "/admin", claveTx("admin-tx-resuelta:", tx.transactionId()), datosAdmin, true);
            }
            emitirMovimientoActualizado(modId, esDeposito ? "recarga" : "retiro", estadoVisual, tx.monto(), origen);
        } else {
            for (int adminId : obtenerAdminIds()) {
                if (adminId != tx.userId()) {
                    notificaciones.notificar(adminId, "finanzas", tituloAdmin, msgAdmin,
                            "/admin", claveTx("admin-tx-resuelta:", tx.transactionId()), datosAdmin, true);
                }
                emitirMovimientoActualizado(adminId, esDeposito ? "recarga" : "retiro", estadoVisual, tx.monto(), origen);
            }
        }
    }

    private void depositoDirecto(EventBus.Evento evento) {
        Eventos.MovimientoWallet mov = evento.datos(Eventos.MovimientoWallet.class);
        if (mov == null || mov.userId() <= 0) return;
        String motivo = mov.motivo() != null ? mov.motivo().trim() : "";
        if (motivo.startsWith("Depósito aprobado por admin #")
                || motivo.startsWith("Pago Wompi ref:")
                || motivo.startsWith("Retiro rechazado, devolución #")) {
            return;
        }
        String origen = !motivo.isBlank() ? motivo : "Admin manual";
        JsonObject datos = construirDatosMovimiento("positivo", "+", mov.monto(), origen, "recarga");
        notificaciones.notificar(mov.userId(), "finanzas", "Recarga manual acreditada",
                "+$" + formatoCop(mov.monto()) + " · " + origen, "/finance", null, datos, true);
        emitirMovimientoActualizado(mov.userId(), "recarga", "positivo", mov.monto(), origen);
        for (int adminId : obtenerAdminIds()) {
            emitirMovimientoActualizado(adminId, "recarga", "positivo", mov.monto(), origen);
        }
    }

    private void premioAcreditado(EventBus.Evento evento) {
        Eventos.MovimientoWallet mov = evento.datos(Eventos.MovimientoWallet.class);
        if (mov == null || mov.userId() <= 0) return;
        String motivo = mov.motivo() != null ? mov.motivo().trim() : "";
        boolean esReferidoSinRef = motivo.startsWith("Comisión referidos (sin referidor");
        boolean esDevolucionRef = motivo.startsWith("Devolución comisión referid");
        boolean esComisionRef = motivo.startsWith("Comisión de referido");
        boolean esSobranteRanking = motivo.startsWith("Leaderboard sobrantes");

        if (esReferidoSinRef || esDevolucionRef || esComisionRef) {
            String titulo = esDevolucionRef
                    ? "Devolución por referido"
                    : (esReferidoSinRef ? "Comisión referidos (sin referidor)" : "Comisión de referido");
            String ruta = esReferidoSinRef ? "/admin" : "/finance";
            JsonObject datos = construirDatosMovimiento("positivo", "+", mov.monto(), motivo, "referido");
            notificaciones.notificar(mov.userId(), "finanzas", titulo,
                    "+$" + formatoCop(mov.monto()) + " · " + motivo, ruta, null, datos, true);
        } else if (esSobranteRanking) {
            JsonObject datos = construirDatosMovimiento("positivo", "+", mov.monto(), motivo, "ranking");
            notificaciones.notificar(mov.userId(), "finanzas", "Sobrante de ranking acreditado",
                    "+$" + formatoCop(mov.monto()) + " · " + motivo, "/admin", null, datos, true);
        }

        emitirMovimientoActualizado(mov.userId(), "premio", "positivo", mov.monto(), motivo);
        for (int adminId : obtenerAdminIds()) {
            if (adminId != mov.userId()) {
                emitirMovimientoActualizado(adminId, "premio", "positivo", mov.monto(), motivo);
            }
        }
    }

    private void partidaIniciada(EventBus.Evento evento) {
        Eventos.PartidaIniciada partida = evento.datos(Eventos.PartidaIniciada.class);
        if (partida == null || partida.matchId() <= 0) return;

        JsonObject match = db.queryOne(
                "SELECT jugador1, jugador2, modo, monto FROM matches WHERE id = ?",
                partida.matchId());
        String j1 = textoJson(match, "jugador1");
        String j2 = textoJson(match, "jugador2");
        BigDecimal montoEntrada = match != null && match.has("monto") && !match.get("monto").isJsonNull()
                ? match.get("monto").getAsBigDecimal() : BigDecimal.ZERO;
        String montoTxt = formatoCop(montoEntrada);

        for (int uid : partida.participantes()) {
            if (uid <= 0) continue;
            JsonObject userRow = db.queryOne("SELECT username FROM users WHERE id = ?", uid);
            String miNombre = textoJson(userRow, "username");
            String rival = !j1.isBlank() && j1.equalsIgnoreCase(miNombre) ? j2 : j1;
            String tagRival = !rival.isBlank() ? "@" + rival : "rival";
            String origen = "Partida #" + partida.matchId() + " vs " + tagRival;
            JsonObject datos = construirDatosMovimiento("pendiente", "-", montoEntrada, origen, "partida");
            datos.addProperty("matchId", partida.matchId());
            notificaciones.notificar(uid, "finanzas",
                    "Partida #" + partida.matchId() + " en curso",
                    "vs " + tagRival + " · Entrada $" + montoTxt + " en juego.",
                    "/finance", "match-curso:" + partida.matchId() + ":" + uid, datos, false);
            emitirMovimientoActualizado(uid, "partida", "pendiente", montoEntrada, origen);
        }

        String origenAdmin = "Partida #" + partida.matchId() + " (@" + j1 + " vs @" + j2 + ")";
        JsonObject datosAdmin = construirDatosMovimiento("pendiente", "",
                montoEntrada.multiply(BigDecimal.valueOf(2)), origenAdmin, "partida");
        datosAdmin.addProperty("matchId", partida.matchId());
        for (int adminId : obtenerAdminIds()) {
            if (!partida.participantes().contains(adminId)) {
                notificaciones.notificar(adminId, "finanzas",
                        "Partida #" + partida.matchId() + " iniciada",
                        "@" + j1 + " vs @" + j2 + " · $" + montoTxt + " por jugador.",
                        "/admin", "admin-match-curso:" + partida.matchId(), datosAdmin, false);
            }
            emitirMovimientoActualizado(adminId, "partida", "pendiente", montoEntrada, origenAdmin);
        }

        Set<Integer> modIds = new HashSet<>();
        for (int uid : partida.participantes()) {
            Integer modId = obtenerModeradorDeUsuario(uid);
            if (modId != null) {
                modIds.add(modId);
            }
        }
        for (int modId : modIds) {
            emitirMovimientoActualizado(modId, "partida", "pendiente", montoEntrada, origenAdmin);
        }
    }

    private void partidaFinalizadaFinanzas(EventBus.Evento evento) {
        Eventos.PartidaFinalizada partida = evento.datos(Eventos.PartidaFinalizada.class);
        if (partida == null || partida.matchId() <= 0) return;

        Map<Integer, Integer> conteoPorMod = new HashMap<>();
        int jugadoresDeMod = 0;

        for (int uid : partida.participantes()) {
            if (uid <= 0) continue;
            notificaciones.eliminarPorEvento("match-curso:" + partida.matchId() + ":" + uid);
            String estadoVisual = partida.ganadorId() <= 0
                    ? "pendiente"
                    : (uid == partida.ganadorId() ? "positivo" : "negativo");
            emitirMovimientoActualizado(uid, "partida", estadoVisual,
                    uid == partida.ganadorId() ? partida.premio() : partida.monto(),
                    "Partida #" + partida.matchId());

            Integer modId = obtenerModeradorDeUsuario(uid);
            if (modId != null) {
                conteoPorMod.merge(modId, 1, Integer::sum);
                jugadoresDeMod++;
            }
        }

        notificaciones.eliminarPorEvento("admin-match-curso:" + partida.matchId());
        boolean hayGanador = partida.ganadorId() > 0;
        String estadoAdmin = hayGanador ? "positivo" : "pendiente";
        String origenAdmin = "Partida #" + partida.matchId()
                + (hayGanador ? " · Ganó @" + partida.ganadorNombre() : " · Cancelada");

        BigDecimal gananciaAdmin = BigDecimal.ZERO;
        if (partida.desglose() != null) {
            BigDecimal gananciaBaseAdmin = partida.desglose().getGananciaBaseAdmin();
            if (jugadoresDeMod > 0) {
                BigDecimal mitadBase = partida.desglose().porJugador(ComisionService.Categoria.GANANCIA);
                gananciaBaseAdmin = gananciaBaseAdmin.subtract(mitadBase.multiply(BigDecimal.valueOf(jugadoresDeMod)));
                if (gananciaBaseAdmin.compareTo(BigDecimal.ZERO) < 0) {
                    gananciaBaseAdmin = BigDecimal.ZERO;
                }
            }
            gananciaAdmin = gananciaBaseAdmin.add(partida.desglose().getGananciaInactivasAdmin());
        }
        BigDecimal comisionTotal = partida.desglose() != null
                ? partida.desglose().getComisionTotal()
                : BigDecimal.ZERO;
        String mensajeAdmin = hayGanador
                ? "Utilidad admin +$" + formatoCop(gananciaAdmin) + " (Comisión total $" + formatoCop(comisionTotal) + ")"
                : "Partida cancelada o sin ganador";
        JsonObject datosAdmin = construirDatosMovimiento(estadoAdmin, hayGanador ? "+" : "",
                gananciaAdmin, origenAdmin, "partida");
        datosAdmin.addProperty("matchId", partida.matchId());

        for (int adminId : obtenerAdminIds()) {
            if (!partida.participantes().contains(adminId)) {
                notificaciones.notificar(adminId, "finanzas",
                        "Partida #" + partida.matchId() + " finalizada",
                        mensajeAdmin, "/admin",
                        "admin-match-fin:" + partida.matchId(), datosAdmin, false);
            }
            emitirMovimientoActualizado(adminId, "partida", estadoAdmin, gananciaAdmin, origenAdmin);
        }

        for (Map.Entry<Integer, Integer> entry : conteoPorMod.entrySet()) {
            int modId = entry.getKey();
            int cant = entry.getValue();
            BigDecimal gananciaMod = (hayGanador && partida.desglose() != null)
                    ? partida.desglose().porJugador(ComisionService.Categoria.GANANCIA)
                            .multiply(BigDecimal.valueOf(cant))
                    : BigDecimal.ZERO;
            if (hayGanador && !partida.participantes().contains(modId)) {
                JsonObject datosMod = construirDatosMovimiento("positivo", "+", gananciaMod, origenAdmin, "partida");
                datosMod.addProperty("matchId", partida.matchId());
                notificaciones.notificar(modId, "finanzas",
                        "Partida #" + partida.matchId() + " finalizada",
                        "💰 +$" + formatoCop(gananciaMod) + " comisión Partida #" + partida.matchId(),
                        "/admin", "mod-match-fin:" + partida.matchId() + ":" + modId, datosMod, false);
            }
            emitirMovimientoActualizado(modId, "partida", estadoAdmin, gananciaMod, origenAdmin);
        }
    }

    private Integer obtenerModeradorDeUsuario(int userId) {
        if (userId <= 0 || db == null) return null;
        JsonObject row = db.queryOne(
                "SELECT m.id AS moderator_id " +
                "FROM users u " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "JOIN users m ON m.id = COALESCE(u.referido_por_id, r.referrer_user_id) " +
                "WHERE u.id = ? AND m.id <> u.id AND LOWER(COALESCE(m.tipo_suscripcion, '')) = 'moderador' " +
                "LIMIT 1",
                userId);
        if (row == null || !row.has("moderator_id") || row.get("moderator_id").isJsonNull()) {
            return null;
        }
        try {
            int modId = row.get("moderator_id").getAsInt();
            return modId > 0 ? modId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Integer> obtenerAdminIds() {
        List<Integer> ids = new ArrayList<>();
        for (JsonObject row : db.query("SELECT id FROM users WHERE tipo_suscripcion = 'admin' ORDER BY id ASC")) {
            if (row != null && row.has("id") && !row.get("id").isJsonNull()) {
                ids.add(row.get("id").getAsInt());
            }
        }
        return ids;
    }

    private void emitirMovimientoActualizado(int userId, String tipo, String estadoVisual,
                                             BigDecimal monto, String origen) {
        if (userId <= 0) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("tipo", tipo);
        payload.addProperty("estadoVisual", estadoVisual);
        if (monto != null) payload.addProperty("monto", monto);
        payload.addProperty("origen", origen != null ? origen : "");
        notificaciones.emitirAUsuario(userId, "movimiento_actualizado", payload);
    }

    private static JsonObject construirDatosMovimiento(String estadoVisual, String signo, BigDecimal monto,
                                                       String origen, String categoriaMovimiento) {
        JsonObject datos = new JsonObject();
        datos.addProperty("estadoVisual", estadoVisual);
        datos.addProperty("signo", signo != null ? signo : "");
        if (monto != null) datos.addProperty("monto", monto);
        datos.addProperty("origen", origen != null ? origen : "");
        datos.addProperty("categoriaMovimiento", categoriaMovimiento != null ? categoriaMovimiento : "");
        return datos;
    }

    private static String claveTx(String prefijo, int txId) {
        return txId > 0 ? prefijo + txId : null;
    }

    private static String tagUsuario(String username, int userId) {
        return (username != null && !username.isBlank()) ? "@" + username.trim() : "usuario#" + userId;
    }

    private static String textoJson(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try { return o.get(key).getAsString().trim(); } catch (Exception e) { return ""; }
    }

    private static String formatoCop(BigDecimal monto) {
        if (monto == null) return "0";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return nf.format(monto);
    }

    private static String claveBusqueda(int userId) { return "busqueda:" + userId; }

    private static String nombrePeriodo(String periodo) {
        return switch (periodo == null ? "" : periodo) {
            case "dia" -> "diario";
            case "semana" -> "semanal";
            case "mes" -> "mensual";
            case "ano" -> "anual";
            default -> periodo;
        };
    }
}
