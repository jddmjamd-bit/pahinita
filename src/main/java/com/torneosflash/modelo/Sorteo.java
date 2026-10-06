package com.torneosflash.modelo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.interfaces.Exportable;

/**
 * CONCEPTO POO: Interface Exportable - implementa exportarDatos() y exportarResumen()
 */
public class Sorteo extends Entidad implements Exportable {
    private static final Logger logger = LoggerFactory.getLogger(Sorteo.class);


    private String nombre;
    private String categoria;
    private int precio;
    private int ticketsNecesarios;
    private int ticketsActuales;
    private String fechaLimite;
    private String estado;
    private int ganadorId;
    private String ganadorNombre;
    private String fechaCompletado;

    public Sorteo() {
        super();
        this.nombre = "";
        this.categoria = "";
        this.precio = 0;
        this.ticketsNecesarios = 0;
        this.ticketsActuales = 0;
        this.fechaLimite = "";
        this.estado = "activo";
        this.ganadorId = 0;
        this.ganadorNombre = "";
        this.fechaCompletado = null;
    }

    public Sorteo(int id, String nombre, String categoria, int precio,
                  int ticketsNecesarios, String fechaLimite) {
        super(id, java.time.LocalDateTime.now().toString());
        this.nombre = nombre;
        this.categoria = categoria;
        this.precio = precio;
        this.ticketsNecesarios = ticketsNecesarios;
        this.ticketsActuales = 0;
        this.fechaLimite = fechaLimite;
        this.estado = "activo";
        this.ganadorId = 0;
        this.ganadorNombre = "";
        this.fechaCompletado = null;
    }

    public boolean estaActivo() { return "activo".equals(this.estado); }
    public boolean estaCompleto() { return this.ticketsActuales >= this.ticketsNecesarios; }

    public void agregarTickets(int cantidad) {
        this.ticketsActuales += cantidad;
        logger.info("+" + cantidad + " tickets al sorteo '" + nombre + "'. Progreso: " + ticketsActuales + "/" + ticketsNecesarios);
    }

    public void seleccionarGanador(int ganadorId, String ganadorNombre) {
        this.ganadorId = ganadorId;
        this.ganadorNombre = ganadorNombre;
        this.estado = "completado";
        this.fechaCompletado = java.time.LocalDateTime.now().toString();
        logger.info("Ganador del sorteo '" + nombre + "': " + ganadorNombre);
    }

    public double calcularProgreso() {
        if (ticketsNecesarios == 0) return 0.0;
        return (double) ticketsActuales / ticketsNecesarios * 100.0;
    }

    // --- Implementación de Exportable (INTERFACE + OVERRIDE) ---
    @Override
    public String exportarDatos() {
        return String.format("SORTEO: %s | Categoria: %s | Precio: $%d | Progreso: %d/%d (%.1f%%) | Estado: %s | Ganador: %s",
                nombre, categoria, precio, ticketsActuales, ticketsNecesarios, calcularProgreso(),
                estado, ganadorNombre.isEmpty() ? "Pendiente" : ganadorNombre);
    }

    @Override
    public String exportarResumen() {
        return String.format("%s - $%d (%d/%d tickets)", nombre, precio, ticketsActuales, ticketsNecesarios);
    }

    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        logger.info("SORTEO #" + getId() + ": " + nombre);
        logger.info("   Categoria: " + categoria + " | Precio: $" + precio);
        logger.info("   Progreso: " + ticketsActuales + "/" + ticketsNecesarios +
                " (" + String.format("%.1f", calcularProgreso()) + "%)");
        logger.info("   Estado: " + estado);
        if (ganadorNombre != null && !ganadorNombre.isEmpty()) {
            logger.info("   Ganador: " + ganadorNombre);
        }
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Sorteo"; }

    // --- Getters y Setters ---
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }
    public int getPrecio() { return precio; }
    public void setPrecio(int precio) { this.precio = precio; }
    public int getTicketsNecesarios() { return ticketsNecesarios; }
    public void setTicketsNecesarios(int t) { this.ticketsNecesarios = t; }
    public int getTicketsActuales() { return ticketsActuales; }
    public void setTicketsActuales(int t) { this.ticketsActuales = t; }
    public String getFechaLimite() { return fechaLimite; }
    public void setFechaLimite(String fechaLimite) { this.fechaLimite = fechaLimite; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getGanadorId() { return ganadorId; }
    public void setGanadorId(int ganadorId) { this.ganadorId = ganadorId; }
    public String getGanadorNombre() { return ganadorNombre; }
    public void setGanadorNombre(String ganadorNombre) { this.ganadorNombre = ganadorNombre; }
    public String getFechaCompletado() { return fechaCompletado; }
    public void setFechaCompletado(String fechaCompletado) { this.fechaCompletado = fechaCompletado; }
}
