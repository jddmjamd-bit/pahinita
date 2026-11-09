package com.torneosflash.servicio;

import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Envía al correo administrativo un resumen de disputas y movimientos de wallet. */
public final class CorreoAdminActividadListener {
    private static final Logger logger = LoggerFactory.getLogger(CorreoAdminActividadListener.class);

    private final CorreoServicio correo;
    private final GenericDAO db;
    private final String appBaseUrl;

    public CorreoAdminActividadListener(CorreoServicio correo, GenericDAO db, String appBaseUrl) {
        this.correo = correo;
        this.db = db;
        this.appBaseUrl = appBaseUrl == null ? "" : appBaseUrl.replaceAll("/+$", "");
    }

    public void registrar(EventBus bus) {
        bus.on(Eventos.WALLET_DEPOSITED, "correo-admin-deposito", this::alDepositar);
        bus.on(Eventos.WALLET_WITHDRAWN, "correo-admin-retiro", this::alRetirar);
        bus.on(Eventos.MATCH_DISPUTE_CREATED, "correo-admin-disputa", this::alCrearDisputa);
    }

    private void alDepositar(EventBus.Evento evento) {
        Eventos.MovimientoWallet movimiento = evento.datos(Eventos.MovimientoWallet.class);
        if (movimiento == null || movimiento.tipo() != Eventos.TipoMovimiento.DEPOSITO) return;
        String username = username(movimiento.userId());
        enviar("Depósito acreditado", "Se acreditó un depósito en la cuenta de un usuario.\n\n"
                + "Usuario: " + username + " (id " + movimiento.userId() + ")\n"
                + "Monto: $" + movimiento.monto().toPlainString() + " COP\n"
                + "Saldo actual: $" + movimiento.nuevoSaldo().toPlainString() + " COP\n"
                + "Detalle: " + texto(movimiento.motivo()) + "\n\n"
                + "Panel de administración: " + appBaseUrl + "/admin");
    }

    private void alRetirar(EventBus.Evento evento) {
        Eventos.MovimientoWallet movimiento = evento.datos(Eventos.MovimientoWallet.class);
        if (movimiento == null || movimiento.tipo() != Eventos.TipoMovimiento.RETIRO) return;
        String username = username(movimiento.userId());
        enviar("Retiro registrado", "Se registró un retiro de un usuario.\n\n"
                + "Usuario: " + username + " (id " + movimiento.userId() + ")\n"
                + "Monto: $" + movimiento.monto().toPlainString() + " COP\n"
                + "Saldo actual: $" + movimiento.nuevoSaldo().toPlainString() + " COP\n"
                + "Detalle: " + texto(movimiento.motivo()) + "\n\n"
                + "Panel de administración: " + appBaseUrl + "/admin");
    }

    private void alCrearDisputa(EventBus.Evento evento) {
        Eventos.DisputaCreada disputa = evento.datos(Eventos.DisputaCreada.class);
        if (disputa == null) return;
        enviar("Partida en disputa #" + disputa.matchId(),
                "Una partida pasó a disputa y requiere revisión.\n\n"
                        + "Partida: #" + disputa.matchId() + "\n"
                        + "Jugadores: " + texto(disputa.jugador1()) + " vs " + texto(disputa.jugador2()) + "\n"
                        + "Monto por jugador: $" + disputa.monto().toPlainString() + " COP\n"
                        + "Motivo: " + texto(disputa.motivo()) + "\n\n"
                        + "Panel de administración: " + appBaseUrl + "/admin");
    }

    private String username(int userId) {
        var fila = db.queryOne("SELECT username FROM users WHERE id = ?", userId);
        if (fila == null || !fila.has("username") || fila.get("username").isJsonNull()) {
            return "Usuario";
        }
        return texto(fila.get("username").getAsString());
    }

    private void enviar(String asunto, String detalle) {
        if (correo == null || !correo.isHabilitado()) {
            logger.info("{} (correo administrativo no configurado)", asunto);
            return;
        }
        correo.notificarAdmin(asunto, detalle);
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? "No disponible" : valor.trim();
    }
}
