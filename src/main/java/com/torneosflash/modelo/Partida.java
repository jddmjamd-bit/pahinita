package com.torneosflash.modelo;

import com.torneosflash.interfaces.Procesable;
import com.torneosflash.interfaces.Validable;

/**
 * CONCEPTO POO: Herencia + múltiples Interfaces
 * Partida implements Procesable Y Validable (polimorfismo múltiple)
 */
public class Partida extends Entidad implements Procesable, Validable {

    private String jugador1;
    private String jugador2;
    private String modo;
    private double apuesta;
    private String ganador;
    private String estado;
    private String errores;

    public Partida() {
        super();
        this.jugador1 = "";
        this.jugador2 = "";
        this.modo = "";
        this.apuesta = 0.0;
        this.ganador = null;
        this.estado = "en_curso";
        this.errores = "";
    }

    public Partida(int id, String jugador1, String jugador2, String modo, double apuesta) {
        super(id, java.time.LocalDateTime.now().toString());
        this.jugador1 = jugador1;
        this.jugador2 = jugador2;
        this.modo = modo;
        this.apuesta = apuesta;
        this.ganador = null;
        this.estado = "en_curso";
        this.errores = "";
    }

    public void iniciarPartida() {
        this.estado = "en_curso";
        System.out.println("Partida iniciada: " + jugador1 + " vs " + jugador2 + " | Apuesta: $" + apuesta);
    }

    public void finalizarPartida(String ganador) {
        this.ganador = ganador;
        this.estado = "finalizado";
        System.out.println("Partida finalizada. Ganador: " + ganador);
    }

    public void crearDisputa() {
        this.estado = "disputa";
        System.out.println("Disputa creada para partida #" + getId());
    }

    public boolean estaEnCurso() { return "en_curso".equals(this.estado); }
    public boolean tieneDisputa() { return "disputa".equals(this.estado); }

    public double calcularPremio() {
        return this.apuesta * 2 * 0.8; // 80% del pozo (20% comisión)
    }

    // --- Implementación de Procesable (INTERFACE) ---
    @Override
    public void procesar() {
        if (validar()) {
            iniciarPartida();
        } else {
            System.out.println("No se puede procesar: " + errores);
        }
    }

    @Override
    public boolean estaListo() {
        return !jugador1.isEmpty() && !jugador2.isEmpty() && apuesta > 0;
    }

    // --- Implementación de Validable (INTERFACE) ---
    @Override
    public boolean validar() {
        this.errores = "";
        if (jugador1.isEmpty() || jugador2.isEmpty()) {
            errores = "Faltan jugadores";
            return false;
        }
        if (jugador1.equals(jugador2)) {
            errores = "Un jugador no puede jugar contra sí mismo";
            return false;
        }
        if (apuesta < 1000) {
            errores = "Apuesta mínima es $1000";
            return false;
        }
        return true;
    }

    @Override
    public String obtenerErrores() { return errores; }

    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("PARTIDA #" + getId());
        System.out.println("   " + jugador1 + " vs " + jugador2);
        System.out.println("   Modo: " + modo + " | Apuesta: $" + apuesta);
        System.out.println("   Estado: " + estado + " | Ganador: " + (ganador != null ? ganador : "Pendiente"));
        System.out.println("   Premio: $" + String.format("%.2f", calcularPremio()));
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Partida"; }

    // --- Getters y Setters ---
    public String getJugador1() { return jugador1; }
    public void setJugador1(String jugador1) { this.jugador1 = jugador1; }
    public String getJugador2() { return jugador2; }
    public void setJugador2(String jugador2) { this.jugador2 = jugador2; }
    public String getModo() { return modo; }
    public void setModo(String modo) { this.modo = modo; }
    public double getApuesta() { return apuesta; }
    public void setApuesta(double apuesta) { this.apuesta = apuesta; }
    public String getGanador() { return ganador; }
    public void setGanador(String ganador) { this.ganador = ganador; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
