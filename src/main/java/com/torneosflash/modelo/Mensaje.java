package com.torneosflash.modelo;

/**
 * CONCEPTO POO: Herencia - Mensaje extiende Entidad
 */
public class Mensaje extends Entidad {

    private String canal;
    private String usuario;
    private String texto;
    private String tipo;

    public Mensaje() {
        super();
        this.canal = "general";
        this.usuario = "";
        this.texto = "";
        this.tipo = "texto";
    }

    public Mensaje(int id, String canal, String usuario, String texto, String tipo) {
        super(id, java.time.LocalDateTime.now().toString());
        this.canal = canal;
        this.usuario = usuario;
        this.texto = texto;
        this.tipo = tipo;
    }

    public void enviarMensaje() {
        System.out.println("[" + canal + "] " + usuario + ": " + texto);
    }

    public boolean esDelSistema() { return "SISTEMA".equals(this.usuario); }
    public boolean esLog() { return "log".equals(this.tipo); }

    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("MENSAJE #" + getId());
        System.out.println("   Canal: " + canal + " | Usuario: " + usuario);
        System.out.println("   Tipo: " + tipo);
        System.out.println("   Texto: " + texto);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Mensaje"; }

    public String getCanal() { return canal; }
    public void setCanal(String canal) { this.canal = canal; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public String getTexto() { return texto; }
    public void setTexto(String texto) { this.texto = texto; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
}
