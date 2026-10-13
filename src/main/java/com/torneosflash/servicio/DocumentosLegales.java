package com.torneosflash.servicio;

/**
 * Versiones vigentes de los documentos legales (roadmap 1.6).
 *
 * El texto vive en {@code frontend/public/legal/contenido-legal.json} (única fuente: lo leen la página
 * {@code /terminos}, la {@code /privacidad} y el visor del registro). Cuando ese texto cambie de forma
 * que el usuario deba aceptarlo otra vez, hay que subir la versión en LOS DOS lados: el campo
 * {@code version} del documento en el JSON y la constante correspondiente de esta clase.
 *
 * Son constantes y no variables de entorno a propósito: la versión describe el texto que va dentro del
 * despliegue, no una configuración del servidor; si fuera editable sin tocar el texto, la constancia
 * de aceptación podría quedar apuntando a un documento distinto del que el usuario vio.
 */
public final class DocumentosLegales {

    public static final String TERMINOS_VERSION = "1.0";
    public static final String PRIVACIDAD_VERSION = "1.0";

    private DocumentosLegales() {}
}
