package com.torneosflash.interfaces;

/**
 * CONCEPTO POO: Interface (clase como interfaz para usar implements)
 * 
 * Define el contrato para objetos que pueden exportar sus datos como texto.
 * Implementada por: Sorteo, BovedaAdmin
 */
public interface Exportable {

    /**
     * Exporta los datos completos de la entidad como texto formateado.
     * @return String con todos los datos
     */
    String exportarDatos();

    /**
     * Exporta un resumen corto de la entidad.
     * @return String con el resumen
     */
    String exportarResumen();
}
