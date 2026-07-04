package com.torneosflash.modelo;

/**
 * CONCEPTO POO: Herencia - Deposito extiende Transaccion (abstract)
 * CONCEPTO POO: Override - implementa el método abstracto procesar()
 */
public class Deposito extends Transaccion {

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
        System.out.println("Deposito de $" + getMonto() + " procesado para " + getUsuarioNombre());
        System.out.println("   Comision cobrada: $" + String.format("%.2f", comision));
    }

    public double calcularComision() {
        double baseCara = getMonto() + 840;
        double totalCobrado = Math.ceil(baseCara / 0.964);
        return totalCobrado - getMonto();
    }

    public void procesarPago() {
        System.out.println("Procesando pago con llave: " + llavePublica);
        procesar();
    }

    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("DEPOSITO #" + getId());
        System.out.println("   Usuario: " + getUsuarioNombre());
        System.out.println("   Monto: $" + getMonto() + " | Comision: $" + String.format("%.2f", comision));
        System.out.println("   Estado: " + getEstado());
        System.out.println("   Referencia: " + getReferencia());
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Deposito"; }

    public String getLlavePublica() { return llavePublica; }
    public void setLlavePublica(String llavePublica) { this.llavePublica = llavePublica; }
    public double getComision() { return comision; }
    public void setComision(double comision) { this.comision = comision; }
}
