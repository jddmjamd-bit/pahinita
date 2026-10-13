package com.torneosflash.servicio;

/**
 * Error de negocio lanzado por la capa de servicio (A3).
 *
 * Los servicios no conocen HTTP ni Javalin: cuando algo no se puede hacer lanzan esta excepción con el
 * código de estado que corresponde y un mensaje listo para mostrar al usuario. El handler único
 * {@code servidor/ManejadorErrores} la convierte en {@code {"error": "..."}} con ese código.
 */
public class ServicioException extends RuntimeException {

    private final int status;

    public ServicioException(int status, String mensaje) {
        super(mensaje);
        this.status = status;
    }

    public ServicioException(int status, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** 400: el cliente mandó algo inválido. */
    public static ServicioException solicitudInvalida(String mensaje) {
        return new ServicioException(400, mensaje);
    }

    /** 404: el recurso no existe. */
    public static ServicioException noEncontrado(String mensaje) {
        return new ServicioException(404, mensaje);
    }

    /**
     * Contrato heredado: varios endpoints (depósito manual, panel admin) responden HTTP 200 con
     * {@code {"error": "..."}} y el frontend actual depende de eso. Se conserva para no romperlo.
     */
    public static ServicioException respuestaConError(String mensaje) {
        return new ServicioException(200, mensaje);
    }

    /** 500: fallo interno (BD, IO). El mensaje es el que verá el usuario; la causa solo va al log. */
    public static ServicioException interno(String mensaje, Throwable causa) {
        return new ServicioException(500, mensaje, causa);
    }

    /** 401: no hay sesión iniciada. */
    public static ServicioException noAutenticado(String mensaje) {
        return new ServicioException(401, mensaje);
    }

    /** 403: hay sesión pero no tiene permiso (p. ej. no es admin). */
    public static ServicioException prohibido(String mensaje) {
        return new ServicioException(403, mensaje);
    }

    /** 429: el usuario superó un límite de uso. */
    public static ServicioException demasiadasSolicitudes(String mensaje) {
        return new ServicioException(429, mensaje);
    }

    /** 503: un servicio externo (p. ej. la IA) no está disponible ahora. {@code ManejadorErrores} lo registra como aviso, sin traza. */
    public static ServicioException noDisponible(String mensaje) {
        return new ServicioException(503, mensaje);
    }
}
