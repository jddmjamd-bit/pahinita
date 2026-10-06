package com.torneosflash.modelo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.interfaces.Validable;

/**
 * CONCEPTO POO: Herencia - Retiro extiende Transaccion
 * CONCEPTO POO: Interface - implementa Validable
 * CONCEPTO POO: Override - implementa procesar(), validar(), obtenerErrores()
 */
public class Retiro extends Transaccion implements Validable {
    private static final Logger logger = LoggerFactory.getLogger(Retiro.class);


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
            logger.info("Retiro de $" + getMonto() + " procesado para " + getUsuarioNombre());
        } else {
            setEstado("rechazado");
            logger.info("Retiro rechazado: " + errores);
        }
    }

    public void procesarRetiro() {
        logger.info("Procesando retiro a cuenta: " + datosCuenta);
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
        logger.info("RETIRO #" + getId());
        logger.info("   Usuario: " + getUsuarioNombre());
        logger.info("   Monto: $" + getMonto() + " | Estado: " + getEstado());
        logger.info("   Cuenta destino: " + datosCuenta);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Retiro"; }

    public String getDatosCuenta() { return datosCuenta; }
    public void setDatosCuenta(String datosCuenta) { this.datosCuenta = datosCuenta; }
}
