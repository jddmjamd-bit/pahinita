package com.torneosflash.modelo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class TicketUsuario extends Entidad {
    private static final Logger logger = LoggerFactory.getLogger(TicketUsuario.class);

    private int userId;
    private int cantidad;
    private int acumulado;

    public TicketUsuario() { super(); this.userId = 0; this.cantidad = 0; this.acumulado = 0; }

    public TicketUsuario(int id, int userId, int cantidad, int acumulado) {
        super(id, java.time.LocalDateTime.now().toString());
        this.userId = userId; this.cantidad = cantidad; this.acumulado = acumulado;
    }

    public void agregarTickets(int cantidad) {
        this.cantidad += cantidad; this.acumulado += cantidad;
        logger.info("+" + cantidad + " tickets agregados. Total: " + this.cantidad);
    }

    public void usarTickets(int cantidad) {
        if (this.cantidad >= cantidad) { this.cantidad -= cantidad;
            logger.info("-" + cantidad + " tickets usados. Restantes: " + this.cantidad);
        } else { logger.info("Tickets insuficientes. Tienes: " + this.cantidad); }
    }

    public boolean tieneTickets() { return this.cantidad > 0; }

    @Override public void mostrarInformacion() {
        imprimirSeparador();
        logger.info("TICKETS USUARIO #" + getId());
        logger.info("   Usuario ID: " + userId + " | Cantidad: " + cantidad + " | Acumulado: " + acumulado);
        imprimirSeparador();
    }
    @Override public String obtenerTipo() { return "TicketUsuario"; }

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    public int getCantidad() { return cantidad; }
    public void setCantidad(int cantidad) { this.cantidad = cantidad; }
    public int getAcumulado() { return acumulado; }
    public void setAcumulado(int acumulado) { this.acumulado = acumulado; }
}
