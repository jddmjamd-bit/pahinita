package com.torneosflash.modelo;

/**
 * CONCEPTO POO: Herencia - Usuario extiende Entidad
 * CONCEPTO POO: Override - sobreescribe mostrarInformacion() y obtenerTipo()
 * CONCEPTO POO: Encapsulamiento - atributos privados con getters/setters
 * CONCEPTO POO: Constructores - vacío y con parámetros
 */
public class Usuario extends Entidad {

    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private String username;
    private String email;
    private String password;
    private String playerTag;
    private String telefono;
    private double saldo;
    private String estado;
    private String salaActual;
    private int pasoJuego;
    private double gananciaGenerada;
    private int faltas;

    // --- Constructor vacío (CONSTRUCTOR) ---
    public Usuario() {
        super();
        this.username = "";
        this.email = "";
        this.password = "";
        this.playerTag = "";
        this.telefono = "";
        this.saldo = 0.0;
        this.estado = "normal";
        this.salaActual = null;
        this.pasoJuego = 0;
        this.gananciaGenerada = 0.0;
        this.faltas = 0;
    }

    // --- Constructor con parámetros (CONSTRUCTOR) ---
    public Usuario(int id, String username, String email, String password,
                   String playerTag, String telefono) {
        super(id, java.time.LocalDateTime.now().toString());
        this.username = username;
        this.email = email;
        this.password = password;
        this.playerTag = playerTag;
        this.telefono = telefono;
        this.saldo = 0.0;
        this.estado = "normal";
        this.salaActual = null;
        this.pasoJuego = 0;
        this.gananciaGenerada = 0.0;
        this.faltas = 0;
    }

    // --- Métodos con retorno ---
    public boolean verificarSaldo(double monto) {
        return this.saldo >= monto;
    }

    public boolean estaActivo() {
        return "normal".equals(this.estado);
    }

    // --- Métodos void ---
    public void depositar(double monto) {
        if (monto > 0) {
            this.saldo += monto;
            System.out.println("Deposito de $" + monto + " realizado para " + this.username);
        } else {
            System.out.println("El monto debe ser mayor a 0");
        }
    }

    public void retirar(double monto) {
        if (verificarSaldo(monto)) {
            this.saldo -= monto;
            System.out.println("Retiro de $" + monto + " realizado para " + this.username);
        } else {
            System.out.println("Saldo insuficiente para " + this.username);
        }
    }

    public void registrarFalta() {
        this.faltas++;
        System.out.println("Falta registrada para " + this.username + ". Total: " + this.faltas);
    }

    // --- Override de métodos abstractos (OVERRIDE) ---
    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        System.out.println("USUARIO: " + username);
        System.out.println("   ID: " + getId());
        System.out.println("   Email: " + email);
        System.out.println("   Player Tag: " + playerTag);
        System.out.println("   Telefono: " + telefono);
        System.out.println("   Saldo: $" + saldo);
        System.out.println("   Estado: " + estado);
        System.out.println("   Faltas: " + faltas);
        System.out.println("   Ganancia Generada: $" + gananciaGenerada);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() {
        return "Usuario";
    }

    // --- Getters y Setters (GETTER Y SETTER) ---
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPlayerTag() { return playerTag; }
    public void setPlayerTag(String playerTag) { this.playerTag = playerTag; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public double getSaldo() { return saldo; }
    public void setSaldo(double saldo) { this.saldo = saldo; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getSalaActual() { return salaActual; }
    public void setSalaActual(String salaActual) { this.salaActual = salaActual; }
    public int getPasoJuego() { return pasoJuego; }
    public void setPasoJuego(int pasoJuego) { this.pasoJuego = pasoJuego; }
    public double getGananciaGenerada() { return gananciaGenerada; }
    public void setGananciaGenerada(double gananciaGenerada) { this.gananciaGenerada = gananciaGenerada; }
    public int getFaltas() { return faltas; }
    public void setFaltas(int faltas) { this.faltas = faltas; }
}
