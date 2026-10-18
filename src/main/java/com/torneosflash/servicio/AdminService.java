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
 * D1: Todo monto y saldo se maneja con {@link BigDecimal} (nunca {@code double}).
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

        // Dinero en circulación = suma de saldos de todos los usuarios // REVIEW-MONEY
        JsonObject saldoSum = db.queryOne("SELECT COALESCE(SUM(saldo),0) as total FROM users");
        stats.addProperty("totalUsuarios", saldoSum != null ? saldoSum.get("total").getAsBigDecimal() : BigDecimal.ZERO);

        // Ganancias del admin = suma de admin_wallet (histórico total) // REVIEW-MONEY
        JsonObject walletSum = db.queryOne("SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet");
        stats.addProperty("totalGanancias", walletSum != null ? walletSum.get("total").getAsBigDecimal() : BigDecimal.ZERO);

        // Desglose por categorías (Actual e Histórico - por ahora es lo mismo)
        JsonObject desglose = new JsonObject();
        for (ComisionService.Categoria categoria : ComisionService.Categoria.values()) {
            String cat = categoria.getClave();
            JsonObject catSum = db.queryOne("SELECT COALESCE(SUM(monto),0) as total FROM admin_wallet WHERE categoria = ?", cat);
            desglose.addProperty(cat, catSum != null ? catSum.get("total").getAsBigDecimal() : BigDecimal.ZERO);
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
     * C3: Aprueba ("approve") o rechaza ("reject") una transacción pendiente de forma atómica.
     *
     * @return el mensaje para el cliente ("Rechazada" / "Aprobada")
     */
    public String procesarTransaccion(int transId, String action) {
        WalletService.ResultadoTransaccionAdmin res = wallet.procesarTransaccionAdmin(transId, action);

        if (!res.aprobada) {
            if ("retiro".equals(res.tipo)) {
                notificador.notificarSaldo(res.userId, "❌ Retiro rechazado. Saldo devuelto.", res.nuevoSaldo);
            } else {
                notificador.notificarSaldo(res.userId, "❌ Recarga rechazada.", res.nuevoSaldo);
            }
            return "Rechazada";
        }

        if ("deposito".equals(res.tipo)) {
            notificador.notificarSaldo(res.userId, "✅ Recarga aprobada.", res.nuevoSaldo);
        } else {
            notificador.notificarSaldo(res.userId, "✅ Tu retiro ha sido enviado.", res.nuevoSaldo);
        }
        return "Aprobada";
    }

    // ------------------------------------------------------------------
    // Disputas
    // ------------------------------------------------------------------

    /**
     * C2: Resuelve una disputa de forma atómica e idempotente.
     * Exige que la partida esté en estado {@code disputa}, que el ganador sea uno de los dos jugadores
     * de la partida y cierra el match dentro de la misma transacción SQL de {@link WalletService#liquidar}.
     */
    public void resolverDisputa(int matchId, String ganadorNombre, String culpableNombre) {
        JsonObject match = db.queryOne("SELECT * FROM matches WHERE id = ?", matchId);
        if (match == null) throw ServicioException.respuestaConError("No existe");

        String estadoActual = match.has("estado") && !match.get("estado").isJsonNull()
                ? match.get("estado").getAsString() : "";
        if (!"disputa".equals(estadoActual)) {
            throw ServicioException.respuestaConError("Esta disputa ya fue resuelta (" + estadoActual + ")");
        }

        String j1 = match.get("jugador1").getAsString();
        String j2 = match.get("jugador2").getAsString();
        if (ganadorNombre == null || (!ganadorNombre.equals(j1) && !ganadorNombre.equals(j2))) {
            throw ServicioException.respuestaConError("El ganador debe ser uno de los dos jugadores de la partida");
        }
        String culpableSeguro = (culpableNombre == null || culpableNombre.isBlank()) ? "nadie" : culpableNombre;
        if (!"nadie".equals(culpableSeguro) && !culpableSeguro.equals(j1) && !culpableSeguro.equals(j2)) {
            throw ServicioException.respuestaConError("El culpable debe ser uno de los dos jugadores o 'nadie'");
        }

        JsonObject winner = db.queryOne("SELECT id, saldo FROM users WHERE username = ?", ganadorNombre);
        if (winner == null) throw ServicioException.respuestaConError("Ganador no encontrado");

        int winnerId = winner.get("id").getAsNumber().intValue();
        BigDecimal monto = match.get("monto").getAsBigDecimal(); // REVIEW-MONEY

        String perdedor = ganadorNombre.equals(j1) ? j2 : j1;
        JsonObject perdedorData = db.queryOne("SELECT id FROM users WHERE username = ?", perdedor);
        int perdedorId = perdedorData != null ? perdedorData.get("id").getAsNumber().intValue() : 0;

        // C2: Liquidar partida y cerrarla (WHERE id = ? AND estado = 'disputa') en UNA sola transacción // REVIEW-MONEY
        LiquidacionResult liq = wallet.liquidar(winnerId, perdedorId, monto, matchId,
                "comision_disputa", "disputa", ganadorNombre, culpableSeguro);
        if (!liq.success) {
            throw ServicioException.respuestaConError("Error al liquidar: " + liq.error);
        }

        // Liberar jugadores y limpiar cualquier sala activa residual
        db.update("DELETE FROM active_matches WHERE match_db_id = ?", matchId);
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
                    s.getUserData().addProperty("saldo", liq.nuevoSaldoGanador);
                    s.emit("actualizar_saldo", new JsonPrimitive(liq.nuevoSaldoGanador));
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

    /**
     * S3: ¿este usuario es admin AHORA? Lo consulta {@code AdminMiddleware} en cada petición a una ruta de admin.
     * Lee {@code users.tipo_suscripcion} de la BD (no el claim {@code rol} del JWT, que puede estar viejo).
     * Cualquier duda (id inválido, usuario inexistente, error de BD) devuelve false.
     */
    public boolean esAdmin(int userId) {
        if (userId <= 0) return false;
        JsonObject fila = db.queryOne("SELECT tipo_suscripcion FROM users WHERE id = ?", userId);
        if (fila == null || !fila.has("tipo_suscripcion") || fila.get("tipo_suscripcion").isJsonNull()) {
            return false;
        }
        return "admin".equals(fila.get("tipo_suscripcion").getAsString());
    }

    /**
     * Resetea el estado de un jugador a normal. S3: ya no verifica aquí si quien lo pide es admin (antes recibía el
     * nombre de un admin por la URL y bastaba con escribir el de cualquiera); eso lo hace {@code AdminMiddleware}
     * con la sesión JWT antes de llegar a este método.
     */
    public void resetearEstado(String targetUser) {
        usuarioDAO.resetearEstado(targetUser);
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
