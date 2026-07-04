package com.torneosflash.modelo;

import com.torneosflash.interfaces.Procesable;

/**
 * CONCEPTO POO: Clase Abstracta - no se puede instanciar directamente
 * CONCEPTO POO: Herencia - extiende Entidad
 * CONCEPTO POO: Interface - implementa Procesable
 * CONCEPTO POO: Abstracción - define procesar() como abstracto para las subclases
 */
public abstract class Transaccion extends Entidad implements Procesable {

    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private int usuarioId;
    private String usuarioNombre;
    private String tipo;
    private String metodo;
    private double monto;
    private String referencia;
    private String estado;

    // --- Constructor vacío (CONSTRUCTOR) ---
    public Transaccion() {
        super();
        this.usuarioId = 0;
        this.usuarioNombre = "";
        this.tipo = "";
        this.metodo = "";
        this.monto = 0.0;
        this.referencia = "";
        this.estado = "pendiente";
    }

    // --- Constructor con parámetros (CONSTRUCTOR) ---
    public Transaccion(int id, int usuarioId, String usuarioNombre, String tipo,
                       String metodo, double monto, String referencia) {
        super(id, java.time.LocalDateTime.now().toString());
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.tipo = tipo;
        this.metodo = metodo;
        this.monto = monto;
        this.referencia = referencia;
        this.estado = "pendiente";
    }

    // --- Método abstracto heredado de Procesable ---
    // procesar() queda abstracto - cada subclase (Deposito, Retiro) lo implementa

    // --- Métodos concretos ---
    public boolean estaCompletada() {
        return "completado".equals(this.estado);
    }

    public boolean estaPendiente() {
        return "pendiente".equals(this.estado);
    }

    @Override
    public boolean estaListo() {
        return estaPendiente() && monto > 0;
    }

    // --- Override (OVERRIDE) ---
    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("TRANSACCION #" + getId());
        System.out.println("   Usuario: " + usuarioNombre + " (ID: " + usuarioId + ")");
        System.out.println("   Tipo: " + tipo + " | Metodo: " + metodo);
        System.out.println("   Monto: $" + monto + " | Estado: " + estado);
        System.out.println("   Referencia: " + referencia);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Transaccion"; }

    // --- Getters y Setters (GETTER Y SETTER) ---
    public int getUsuarioId() { return usuarioId; }
    public void setUsuarioId(int usuarioId) { this.usuarioId = usuarioId; }
    public String getUsuarioNombre() { return usuarioNombre; }
    public void setUsuarioNombre(String usuarioNombre) { this.usuarioNombre = usuarioNombre; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public String getMetodo() { return metodo; }
    public void setMetodo(String metodo) { this.metodo = metodo; }
    public double getMonto() { return monto; }
    public void setMonto(double monto) { this.monto = monto; }
    public String getReferencia() { return referencia; }
    public void setReferencia(String referencia) { this.referencia = referencia; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
