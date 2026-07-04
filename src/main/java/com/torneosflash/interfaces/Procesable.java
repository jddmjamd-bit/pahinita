package com.torneosflash.interfaces;

/**
 * CONCEPTO POO: Interface (clase como interfaz para usar implements)
 * 
 * Define el contrato para objetos que pueden ser procesados.
 * Implementada por: Jugador, Transaccion, Partida
 */
public interface Procesable {

    /**
     * Procesa la entidad (ejecuta su acción principal).
     * Deposito: acredita el saldo
     * Retiro: descuenta el saldo
     * Partida: inicia o finaliza la partida
     */
    void procesar();

    /**
     * Verifica si la entidad está lista para ser procesada.
     * @return true si está lista
     */
    boolean estaListo();
}
