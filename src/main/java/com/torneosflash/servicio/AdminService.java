package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.dao.UsuarioDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.WalletService.LiquidacionResult;
import com.torneosflash.servicio.WalletService.WalletResult;
import com.torneosflash.socketio.SocketIOClient;
import com.torneosflash.socketio.SocketIOServer;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Lógica del panel de administración: transacciones pendientes, disputas, estadísticas y utilidades (A3).
 * Antes vivía en {@code RutasAdmin}. No conoce HTTP.
 */
public class AdminService {

    private final GenericDAO db;
    private final UsuarioDAO usuarioDAO;
    private final WalletService wallet;
    private final EventBus eventos; // A4: avisa "match.finished" al resolver una disputa (antes acreditaba los tickets aquí)
    private final NotificadorUsuarios notificador;
    private final SocketIOServer io;

    public AdminService(GenericDAO db, UsuarioDAO usuarioDAO, WalletService wallet, EventBus eventos,
                        NotificadorUsuarios notificador, SocketIOServer io) {
        this.db = db;
        this.usuarioDAO = usuarioDAO;
        this.wallet = wallet;
        this.eventos = eventos;
        this.notificador = notificador;
        this.io = io;
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Solo las transacciones pendientes de aprobar. */
    public ArrayList<JsonObject> transaccionesPendientes() {
        return db.query("SELECT * FROM transactions WHERE estado = 'pendiente' ORDER BY id DESC");
    }

    public ArrayList<JsonObject> disputas() {
        return db.query("SELECT * FROM matches WHERE estado = 'disputa' ORDER BY id DESC");
    }

    public JsonObject estadisticas() {
        JsonObject stats = new JsonObject();

        // Dinero en circulación = suma de saldos de todos los usuarios
        JsonObject saldoSum = db.queryOne("SELECT COALESCE(SUM(saldo),0) as total FROM users");
        stats.addProperty("totalUsuarios", saldoSum != null ? saldoSum.get("total").getAsDouble() : 0);

        // Ganancias del admin = suma de admin_wallet (histórico total)
        JsonObject walletSum = db.queryOne("SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet");
        stats.addProperty("totalGanancias", walletSum != null ? walletSum.get("total").getAsDouble() : 0);

        // Desglose por categorías (Actual e Histórico - por ahora es lo mismo)
        JsonObject desglose = new JsonObject();
        for (ComisionService.Categoria categoria : ComisionService.Categoria.values()) {
            String cat = categoria.getClave();
            JsonObject catSum = db.queryOne("SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet WHERE categoria = ?", cat);
            desglose.addProperty(cat, catSum != null ? catSum.get("total").getAsDouble() : 0);
        }
        stats.add("desglose", desglose);

        // Lista de todos los usuarios con sus detalles
        ArrayList<JsonObject> usuarios = db.query(
                "SELECT id, username, email, saldo, tipo_suscripcion, ganancia_generada, " +
                "gen_sorteos, gen_misiones, gen_logros, gen_leaderboard, gen_devolucion, gen_referidos, " +
                "total_partidas, total_victorias, victorias_normales, victorias_disputa, " +
                "total_derrotas, derrotas_normales, derrotas_disputa, " +
                "faltas, salidas_chat, salidas_x, salidas_canal, salidas_desconexion " +
                "FROM users ORDER BY saldo DESC");
        JsonArray listaUsuarios = new JsonArray();
        for (JsonObject u : usuarios) listaUsuarios.add(u);
        stats.add("listaUsuarios", listaUsuarios);

        stats.addProperty("usuariosOnline", io.getSockets().size());
        return stats;
    }

    // ------------------------------------------------------------------
    // Transacciones (aprobar / rechazar)
    // ------------------------------------------------------------------

    /**
     * Aprueba o rechaza una transacción pendiente.
     *
     * @param action "reject" rechaza; cualquier otro valor aprueba (comportamiento heredado)
     * @return el mensaje para el cliente ("Rechazada" / "Aprobada")
     */
    public String procesarTransaccion(int transId, String action) {
        JsonObject trans = db.queryOne("SELECT * FROM transactions WHERE id = ?", transId);
        if (trans == null) throw ServicioException.respuestaConError("No existe");

        // Evitar procesar transacciones que ya fueron aprobadas/rechazadas
        String estadoActual = trans.get("estado").getAsString();
        if (!"pendiente".equals(estadoActual)) {
            throw ServicioException.respuestaConError("Esta transacción ya fue procesada (" + estadoActual + ")");
        }

        String tipo = trans.get("tipo").getAsString();
        int userId = trans.get("usuario_id").getAsNumber().intValue();
        double monto = trans.get("monto").getAsDouble();

        if ("reject".equals(action)) {
            if ("retiro".equals(tipo)) {
                // Devolver dinero // REVIEW-MONEY
                WalletResult result = wallet.depositar(userId, BigDecimal.valueOf(monto), "Retiro rechazado, devolución");
                double saldo = result.isSuccess() ? result.getNuevoSaldoDouble() : 0;
                notificador.notificarSaldo(userId, "❌ Retiro rechazado. Saldo devuelto.", saldo);
            } else {
                BigDecimal saldoActual = wallet.obtenerSaldo(userId);
                notificador.notificarSaldo(userId, "❌ Recarga rechazada.", saldoActual.doubleValue());
            }
            db.update("UPDATE transactions SET estado = 'rechazado' WHERE id = ?", transId);
            return "Rechazada";
        }

        // Aprobar
        if ("deposito".equals(tipo)) {
            // REVIEW-MONEY
            WalletResult result = wallet.depositar(userId, BigDecimal.valueOf(monto), "Depósito aprobado por admin");
            double saldo = result.isSuccess() ? result.getNuevoSaldoDouble() : 0;
            notificador.notificarSaldo(userId, "✅ Recarga aprobada.", saldo);
        } else {
            BigDecimal saldoActual = wallet.obtenerSaldo(userId);
            notificador.notificarSaldo(userId, "✅ Tu retiro ha sido enviado.", saldoActual.doubleValue());
        }
        db.update("UPDATE transactions SET estado = 'completado' WHERE id = ?", transId);
        return "Aprobada";
    }

    // ------------------------------------------------------------------
    // Disputas
    // ------------------------------------------------------------------

    /** Resuelve una disputa: liquida la partida a favor del ganador, suma la falta al culpable y libera a los jugadores. */
    public void resolverDisputa(int matchId, String ganadorNombre, String culpableNombre) {
        JsonObject match = db.queryOne("SELECT * FROM matches WHERE id = ?", matchId);
        if (match == null) throw ServicioException.respuestaConError("No existe");

        JsonObject winner = db.queryOne("SELECT id, saldo FROM users WHERE username = ?", ganadorNombre);
        if (winner == null) throw ServicioException.respuestaConError("Ganador no encontrado");

        int winnerId = winner.get("id").getAsNumber().intValue();
        BigDecimal monto = BigDecimal.valueOf(match.get("monto").getAsDouble()); // REVIEW-MONEY

        String j1 = match.get("jugador1").getAsString();
        String j2 = match.get("jugador2").getAsString();
        String perdedor = ganadorNombre.equals(j1) ? j2 : j1;
        JsonObject perdedorData = db.queryOne("SELECT id FROM users WHERE username = ?", perdedor);
        int perdedorId = perdedorData != null ? perdedorData.get("id").getAsNumber().intValue() : 0;

        // Liquidar partida atómicamente via WalletService // REVIEW-MONEY
        LiquidacionResult liq = wallet.liquidar(winnerId, perdedorId, monto, matchId, "comision_disputa");
        if (!liq.success) {
            throw ServicioException.respuestaConError("Error al liquidar: " + liq.error);
        }

        // liquidar() ya puso victorias_normales/derrotas_normales; para disputas queremos las de disputa
        db.update("UPDATE users SET victorias_normales = victorias_normales - 1, victorias_disputa = victorias_disputa + 1 WHERE id = ?", winnerId);
        db.update("UPDATE users SET derrotas_normales = derrotas_normales - 1, derrotas_disputa = derrotas_disputa + 1 WHERE username = ?", perdedor);

        if (culpableNombre != null && !"nadie".equals(culpableNombre)) {
            db.update("UPDATE users SET faltas = faltas + 1 WHERE username = ?", culpableNombre);
        }

        // Cerrar match y liberar jugadores
        db.update("UPDATE matches SET estado = 'finalizada', ganador = ? WHERE id = ?", ganadorNombre, matchId);
        db.update("UPDATE users SET estado = 'normal', sala_actual = NULL, paso_juego = 0 WHERE username IN (?, ?)", j1, j2);

        // A4: la liquidación y el match ya están confirmados; se avisa al bus (los listeners acreditan los tickets de sorteo
        // a ambos jugadores y les envían `tickets_ganados`, antes `acreditarTickets` en esta clase). emit() no lanza.
        // Participantes = los dos jugadores del match (no solo el ganador), igual que antes. // REVIEW-MONEY
        List<Integer> participantes = new ArrayList<>();
        for (String nombreJugador : new String[]{j1, j2}) {
            JsonObject jugador = db.queryOne("SELECT id FROM users WHERE username = ?", nombreJugador);
            if (jugador != null) participantes.add(jugador.get("id").getAsNumber().intValue());
        }
        eventos.emit(Eventos.MATCH_FINISHED, new Eventos.PartidaFinalizada(
                matchId, Eventos.Origen.DISPUTA, participantes, winnerId, ganadorNombre,
                monto, liq.premio, liq.nuevoSaldoGanador, liq.desglose));

        // Notificar sockets
        for (SocketIOClient s : io.getSockets().values()) {
            if (s.getUserData() != null) {
                int sId = s.getUserData().get("id").getAsNumber().intValue();
                if (sId == winnerId) {
                    s.getUserData().addProperty("saldo", liq.nuevoSaldoGanador.doubleValue());
                    s.emit("actualizar_saldo", new JsonPrimitive(liq.nuevoSaldoGanador.doubleValue()));
                }
                String uname = s.getUserData().get("username").getAsString();
                if (j1.equals(uname) || j2.equals(uname)) {
                    s.emit("flujo_completado");
                    s.getUserData().addProperty("estado", "normal");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Convierte a un usuario en admin. (S3 eliminará la ruta que lo expone.) */
    public void hacerAdmin(String username) {
        usuarioDAO.hacerAdmin(username);
    }

    /**
     * Resetea el estado de un jugador a normal si quien lo pide es admin.
     *
     * @return false si {@code adminUser} no existe o no es admin
     */
    public boolean resetearEstado(String targetUser, String adminUser) {
        JsonObject admin = db.queryOne("SELECT tipo_suscripcion FROM users WHERE username = ?", adminUser);
        if (admin == null || !"admin".equals(admin.get("tipo_suscripcion").getAsString())) {
            return false;
        }
        usuarioDAO.resetearEstado(targetUser);
        return true;
    }

    /** IP pública de salida del servidor (la que hay que autorizar en servicios externos). Con timeout de 5 s (A8). */
    public String ipPublicaServidor() {
        try {
            java.net.URLConnection conexion = new URI("https://api.ipify.org").toURL().openConnection();
            conexion.setConnectTimeout(5000);
            conexion.setReadTimeout(5000);
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conexion.getInputStream()))) {
                return in.readLine();
            }
        } catch (Exception e) {
            return "Error obteniendo IP: " + e.getMessage();
        }
    }
}
