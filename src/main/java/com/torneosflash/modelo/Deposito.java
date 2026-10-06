package com.torneosflash.modelo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * CONCEPTO POO: Herencia - Deposito extiende Transaccion (abstract)
 * CONCEPTO POO: Override - implementa el método abstracto procesar()
 */
public class Deposito extends Transaccion {
    private static final Logger logger = LoggerFactory.getLogger(Deposito.class);


    private String llavePublica;
    private double comision;

    public Deposito() {
        super();
        this.llavePublica = "";
        this.comision = 0.0;
    }

    public Deposito(int id, int usuarioId, String usuarioNombre, double monto,
                    String referencia, String llavePublica) {
        super(id, usuarioId, usuarioNombre, "deposito", "wompi", monto, referencia);
        this.llavePublica = llavePublica;
        this.comision = calcularComision();
    }

    // --- Override del método abstracto procesar() (OVERRIDE + POLIMORFISMO) ---
    @Override
    public void procesar() {
        this.comision = calcularComision();
        setEstado("completado");
        logger.info("Deposito de $" + getMonto() + " procesado para " + getUsuarioNombre());
        logger.info("   Comision cobrada: $" + String.format("%.2f", comision));
    }

    public double calcularComision() {
        double baseCara = getMonto() + 840;
        double totalCobrado = Math.ceil(baseCara / 0.964);
        return totalCobrado - getMonto();
    }

    public void procesarPago() {
        logger.info("Procesando pago con llave: " + llavePublica);
        procesar();
    }

    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        logger.info("DEPOSITO #" + getId());
        logger.info("   Usuario: " + getUsuarioNombre());
        logger.info("   Monto: $" + getMonto() + " | Comision: $" + String.format("%.2f", comision));
        logger.info("   Estado: " + getEstado());
        logger.info("   Referencia: " + getReferencia());
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Deposito"; }

    public String getLlavePublica() { return llavePublica; }
    public void setLlavePublica(String llavePublica) { this.llavePublica = llavePublica; }
    public double getComision() { return comision; }
    public void setComision(double comision) { this.comision = comision; }
}
