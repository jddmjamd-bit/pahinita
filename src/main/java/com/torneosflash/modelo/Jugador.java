package com.torneosflash.modelo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.torneosflash.interfaces.Procesable;

/**
 * CONCEPTO POO: Herencia - Jugador extiende Usuario (que extiende Entidad)
 * CONCEPTO POO: Interface - implementa Procesable
 * CONCEPTO POO: Polimorfismo - puede ser tratado como Usuario, Entidad o Procesable
 * CONCEPTO POO: Override - sobreescribe mostrarInformacion(), obtenerTipo(), procesar(), estaListo()
 */
public class Jugador extends Usuario implements Procesable {
    private static final Logger logger = LoggerFactory.getLogger(Jugador.class);


    // --- Atributos privados (ENCAPSULAMIENTO) ---
    private String tipoSuscripcion;
    private int totalVictorias;
    private int victoriasNormales;
    private int victoriasDisputa;
    private int totalDerrotas;
    private int derrotasNormales;
    private int derrotasDisputa;
    private int totalPartidas;
    private int salidasChat;
    private int salidasDesconexion;
    private int salidasX;
    private int salidasCanal;
    private double totalApostado;
    private double totalGanado;

    // --- Constructor vacío (CONSTRUCTOR) ---
    public Jugador() {
        super();
        this.tipoSuscripcion = "free";
        inicializarEstadisticas();
    }

    // --- Constructor con parámetros (CONSTRUCTOR) ---
    public Jugador(int id, String username, String email, String password,
                   String playerTag, String telefono, String tipoSuscripcion) {
        super(id, username, email, password, playerTag, telefono);
        this.tipoSuscripcion = tipoSuscripcion;
        inicializarEstadisticas();
    }

    private void inicializarEstadisticas() {
        this.totalVictorias = 0;
        this.victoriasNormales = 0;
        this.victoriasDisputa = 0;
        this.totalDerrotas = 0;
        this.derrotasNormales = 0;
        this.derrotasDisputa = 0;
        this.totalPartidas = 0;
        this.salidasChat = 0;
        this.salidasDesconexion = 0;
        this.salidasX = 0;
        this.salidasCanal = 0;
        this.totalApostado = 0.0;
        this.totalGanado = 0.0;
    }

    // --- Métodos con retorno ---
    public double calcularWinRate() {
        if (totalPartidas == 0) return 0.0;
        return (double) totalVictorias / totalPartidas * 100.0;
    }

    public boolean esPremium() {
        return "premium".equals(this.tipoSuscripcion);
    }

    public double calcularGananciaNeta() {
        return this.totalGanado - this.totalApostado;
    }

    // --- Métodos void ---
    public void registrarVictoria(String tipo) {
        this.totalVictorias++;
        this.totalPartidas++;
        if ("normal".equals(tipo)) this.victoriasNormales++;
        else if ("disputa".equals(tipo)) this.victoriasDisputa++;
        logger.info("Victoria registrada para " + getUsername() + " (" + tipo + ")");
    }

    public void registrarDerrota(String tipo) {
        this.totalDerrotas++;
        this.totalPartidas++;
        if ("normal".equals(tipo)) this.derrotasNormales++;
        else if ("disputa".equals(tipo)) this.derrotasDisputa++;
        logger.info("Derrota registrada para " + getUsername() + " (" + tipo + ")");
    }

    public void registrarApuesta(double monto) {
        this.totalApostado += monto;
    }

    public void registrarGanancia(double monto) {
        this.totalGanado += monto;
        setGananciaGenerada(getGananciaGenerada() + monto);
    }

    // --- Implementación de Procesable (INTERFACE + OVERRIDE) ---
    @Override
    public void procesar() {
        logger.info("Procesando jugador " + getUsername() + " - Win Rate: " +
                String.format("%.1f", calcularWinRate()) + "%");
    }

    @Override
    public boolean estaListo() {
        return estaActivo() && verificarSaldo(1000);
    }

    // --- Override de Entidad (OVERRIDE) ---
    @Override
    public void mostrarInformacion() {
        imprimirSeparador();
        logger.info("JUGADOR: " + getUsername());
        logger.info("   ID: " + getId() + " | Email: " + getEmail());
        logger.info("   Player Tag: " + getPlayerTag() + " | Tel: " + getTelefono());
        logger.info("   Saldo: $" + getSaldo() + " | Suscripcion: " + tipoSuscripcion);
        logger.info("   Victorias: " + totalVictorias + " | Derrotas: " + totalDerrotas);
        logger.info("   Win Rate: " + String.format("%.1f", calcularWinRate()) + "%");
        logger.info("   Total Apostado: $" + totalApostado + " | Total Ganado: $" + totalGanado);
        imprimirSeparador();
    }

    @Override
    public String obtenerTipo() { return "Jugador"; }

    // --- Getters y Setters (GETTER Y SETTER) ---
    public String getTipoSuscripcion() { return tipoSuscripcion; }
    public void setTipoSuscripcion(String tipoSuscripcion) { this.tipoSuscripcion = tipoSuscripcion; }
    public int getTotalVictorias() { return totalVictorias; }
    public void setTotalVictorias(int totalVictorias) { this.totalVictorias = totalVictorias; }
    public int getVictoriasNormales() { return victoriasNormales; }
    public void setVictoriasNormales(int v) { this.victoriasNormales = v; }
    public int getVictoriasDisputa() { return victoriasDisputa; }
    public void setVictoriasDisputa(int v) { this.victoriasDisputa = v; }
    public int getTotalDerrotas() { return totalDerrotas; }
    public void setTotalDerrotas(int totalDerrotas) { this.totalDerrotas = totalDerrotas; }
    public int getDerrotasNormales() { return derrotasNormales; }
    public void setDerrotasNormales(int d) { this.derrotasNormales = d; }
    public int getDerrotasDisputa() { return derrotasDisputa; }
    public void setDerrotasDisputa(int d) { this.derrotasDisputa = d; }
    public int getTotalPartidas() { return totalPartidas; }
    public void setTotalPartidas(int totalPartidas) { this.totalPartidas = totalPartidas; }
    public int getSalidasChat() { return salidasChat; }
    public void setSalidasChat(int s) { this.salidasChat = s; }
    public int getSalidasDesconexion() { return salidasDesconexion; }
    public void setSalidasDesconexion(int s) { this.salidasDesconexion = s; }
    public int getSalidasX() { return salidasX; }
    public void setSalidasX(int s) { this.salidasX = s; }
    public int getSalidasCanal() { return salidasCanal; }
    public void setSalidasCanal(int s) { this.salidasCanal = s; }
    public double getTotalApostado() { return totalApostado; }
    public void setTotalApostado(double t) { this.totalApostado = t; }
    public double getTotalGanado() { return totalGanado; }
    public void setTotalGanado(double t) { this.totalGanado = t; }
}
