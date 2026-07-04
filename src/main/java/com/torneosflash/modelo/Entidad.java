package com.torneosflash.modelo;

/**
 * CONCEPTO POO: Clase Abstracta (abstract class)
 * CONCEPTO POO: Abstracción - define la estructura base sin implementación completa
 * CONCEPTO POO: Encapsulamiento - atributos private con getters/setters
 * 
 * Clase base para todas las entidades del sistema.
 * No se puede instanciar directamente, debe ser extendida.
 */
public abstract class Entidad {

    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private int id;
    private String fechaCreacion;

    // --- Constructor vacío (CONSTRUCTOR) ---
    public Entidad() {
        this.id = 0;
        this.fechaCreacion = "Sin fecha";
    }

    // --- Constructor con parámetros (CONSTRUCTOR) ---
    public Entidad(int id, String fechaCreacion) {
        this.id = id;
        this.fechaCreacion = fechaCreacion;
    }

    // --- Métodos abstractos (ABSTRACCIÓN) ---

    /**
     * Muestra la información completa de la entidad.
     * Cada subclase implementa su propia versión.
     */
    public abstract void mostrarInformacion();

    /**
     * Retorna el tipo de entidad como String.
     * @return nombre del tipo
     */
    public abstract String obtenerTipo();

    // --- Método concreto ---
    public void imprimirSeparador() {
        System.out.println("════════════════════════════════════════════════════");
    }

    // --- Getters y Setters (GETTER Y SETTER) ---
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(String fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    // --- Override de toString (OVERRIDE) ---
    @Override
    public String toString() {
        return obtenerTipo() + " [id=" + id + "]";
    }
}
