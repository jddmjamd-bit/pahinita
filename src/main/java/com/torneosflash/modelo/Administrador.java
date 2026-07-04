package com.torneosflash.modelo;

/**
 * CONCEPTO POO: Herencia - Administrador extiende Usuario
 * CONCEPTO POO: Override - sobreescribe mostrarInformacion() y obtenerTipo()
 * CONCEPTO POO: Polimorfismo - puede ser tratado como Usuario o Entidad
 */
public class Administrador extends Usuario {

    // --- Atributo privado (ENCAPSULAMIENTO) ---
    private int nivelAcceso;

    // --- Constructor vacío (CONSTRUCTOR) ---
    public Administrador() {
        super();
        this.nivelAcceso = 1;
    }

    // --- Constructor con parámetros (CONSTRUCTOR) ---
    public Administrador(int id, String username, String email, String password,
                         String playerTag, String telefono, int nivelAcceso) {
        super(id, username, email, password, playerTag, telefono);
        this.nivelAcceso = nivelAcceso;
    }

    // --- Métodos propios del admin ---
    public void aprobarTransaccion(Transaccion transaccion) {
        transaccion.setEstado("completado");
        System.out.println("Admin " + getUsername() + " aprobo transaccion #" + transaccion.getId());
    }

    public void rechazarTransaccion(Transaccion transaccion) {
        transaccion.setEstado("rechazado");
        System.out.println("Admin " + getUsername() + " rechazo transaccion #" + transaccion.getId());
    }

    public void resolverDisputa(Partida partida, String ganador) {
        partida.setGanador(ganador);
        partida.setEstado("finalizado");
        System.out.println("Admin " + getUsername() + " resolvio disputa: ganador = " + ganador);
    }

    public boolean tienePermiso(int nivelRequerido) {
        return this.nivelAcceso >= nivelRequerido;
    }

    public void banearUsuario(Usuario usuario) {
        usuario.setEstado("baneado");
        System.out.println("Admin " + getUsername() + " baneo a " + usuario.getUsername());
    }

    // --- Override (OVERRIDE) ---
    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("ADMINISTRADOR: " + getUsername());
        System.out.println("   ID: " + getId() + " | Email: " + getEmail());
        System.out.println("   Nivel de Acceso: " + nivelAcceso);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Administrador"; }

    // --- Getter y Setter (GETTER Y SETTER) ---
    public int getNivelAcceso() { return nivelAcceso; }
    public void setNivelAcceso(int nivelAcceso) { this.nivelAcceso = nivelAcceso; }
}
