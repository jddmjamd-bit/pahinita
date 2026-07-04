package com.torneosflash.modelo;

import com.torneosflash.interfaces.Exportable;

/**
 * CONCEPTO POO: Interface Exportable implementada
 */
public class BovedaAdmin extends Entidad implements Exportable {

    private double monto;
    private String razon;
    private String detalle;

    public BovedaAdmin() { super(); this.monto = 0.0; this.razon = ""; this.detalle = ""; }

    public BovedaAdmin(int id, double monto, String razon, String detalle) {
        super(id, java.time.LocalDateTime.now().toString());
        this.monto = monto; this.razon = razon; this.detalle = detalle;
    }

    public void registrarMovimiento() {
        System.out.println("Movimiento en boveda: $" + monto + " - " + razon);
    }

    public boolean esIngreso() { return this.monto > 0; }

    // --- Implementación de Exportable (INTERFACE + OVERRIDE) ---
    @Override
    public String exportarDatos() {
        return String.format("BOVEDA: $%.2f | Razón: %s | Detalle: %s | Fecha: %s",
                monto, razon, detalle, getFechaCreacion());
    }

    @Override
    public String exportarResumen() {
        return String.format("%s: $%.2f", razon, monto);
    }

    @Override public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("BOVEDA ADMIN #" + getId());
        System.out.println("   Monto: $" + monto + " | Razon: " + razon + " | Detalle: " + detalle);
        imprimirSeparador();
    }
    @Override public String obtenerTipo() { return "BovedaAdmin"; }

    public double getMonto() { return monto; }
    public void setMonto(double monto) { this.monto = monto; }
    public String getRazon() { return razon; }
    public void setRazon(String razon) { this.razon = razon; }
    public String getDetalle() { return detalle; }
    public void setDetalle(String detalle) { this.detalle = detalle; }
}
