package com.torneosflash.interfaces;

/**
 * CONCEPTO POO: Interface (clase como interfaz para usar implements)
 * 
 * Define el contrato para objetos que necesitan validación antes de procesarse.
 * Implementada por: Retiro, Partida
 */
public interface Validable {

    /**
     * Valida que los datos de la entidad sean correctos.
     * @return true si la validación pasa
     */
    boolean validar();

    /**
     * Obtiene los mensajes de error de la última validación.
     * @return String con los errores, o cadena vacía si no hay errores
     */
    String obtenerErrores();
}
