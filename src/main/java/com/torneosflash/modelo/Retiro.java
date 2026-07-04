package com.torneosflash.modelo;

import com.torneosflash.interfaces.Validable;

/**
 * CONCEPTO POO: Herencia - Retiro extiende Transaccion
 * CONCEPTO POO: Interface - implementa Validable
 * CONCEPTO POO: Override - implementa procesar(), validar(), obtenerErrores()
 */
public class Retiro extends Transaccion implements Validable {

    private String datosCuenta;
    private String errores;

    public Retiro() {
        super();
        this.datosCuenta = "";
        this.errores = "";
    }

    public Retiro(int id, int usuarioId, String usuarioNombre, double monto,
                  String referencia, String datosCuenta) {
        super(id, usuarioId, usuarioNombre, "retiro", "nequi_retiro", monto, referencia);
        this.datosCuenta = datosCuenta;
        this.errores = "";
    }

    // --- Override del método abstracto procesar() (OVERRIDE) ---
    @Override
    public void procesar() {
        if (validar()) {
            setEstado("completado");
            System.out.println("Retiro de $" + getMonto() + " procesado para " + getUsuarioNombre());
        } else {
            setEstado("rechazado");
            System.out.println("Retiro rechazado: " + errores);
        }
    }

    public void procesarRetiro() {
        System.out.println("Procesando retiro a cuenta: " + datosCuenta);
        procesar();
    }

    // --- Implementación de Validable (INTERFACE + OVERRIDE) ---
    @Override
    public boolean validar() {
        this.errores = "";
        if (datosCuenta == null || datosCuenta.isEmpty()) {
            this.errores = "Datos de cuenta inválidos";
            return false;
        }
        if (getMonto() <= 0) {
            this.errores = "Monto debe ser mayor a 0";
            return false;
        }
        return true;
    }

    @Override
    public String obtenerErrores() {
        return errores;
    }

    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("RETIRO #" + getId());
        System.out.println("   Usuario: " + getUsuarioNombre());
        System.out.println("   Monto: $" + getMonto() + " | Estado: " + getEstado());
        System.out.println("   Cuenta destino: " + datosCuenta);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Retiro"; }

    public String getDatosCuenta() { return datosCuenta; }
    public void setDatosCuenta(String datosCuenta) { this.datosCuenta = datosCuenta; }
}
