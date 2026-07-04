package com.torneosflash.modelo;

public class ParticipacionSorteo extends Entidad {
    private int sorteoId;
    private int userId;
    private int ticketsAsignados;

    public ParticipacionSorteo() { super(); this.sorteoId = 0; this.userId = 0; this.ticketsAsignados = 0; }

    public ParticipacionSorteo(int id, int sorteoId, int userId, int ticketsAsignados) {
        super(id, java.time.LocalDateTime.now().toString());
        this.sorteoId = sorteoId; this.userId = userId; this.ticketsAsignados = ticketsAsignados;
    }

    public void agregarTickets(int cantidad) { this.ticketsAsignados += cantidad; }

    public double calcularProbabilidad(int totalTickets) {
        if (totalTickets == 0) return 0.0;
        return (double) ticketsAsignados / totalTickets * 100.0;
    }

    @Override public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("PARTICIPACION #" + getId() + " | Sorteo: " + sorteoId + " | Usuario: " + userId + " | Tickets: " + ticketsAsignados);
        imprimirSeparador();
    }
    @Override public String obtenerTipo() { return "ParticipacionSorteo"; }

    public int getSorteoId() { return sorteoId; }
    public void setSorteoId(int sorteoId) { this.sorteoId = sorteoId; }
    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    public int getTicketsAsignados() { return ticketsAsignados; }
    public void setTicketsAsignados(int t) { this.ticketsAsignados = t; }
}
