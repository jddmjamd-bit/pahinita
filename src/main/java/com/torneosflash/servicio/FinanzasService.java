package com.torneosflash.servicio;

import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.servicio.WalletService.WalletResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Lógica de depósitos, retiros y pagos Wompi (A3). Todo movimiento de dinero pasa por {@link WalletService};
 * todo monto que llega del cliente pasa por {@link ValidadorMonto} antes de tocar la BD (D5).
 * No conoce HTTP: {@code RutasFinanzas} solo parsea y responde.
 */
public class FinanzasService {
    private static final Logger logger = LoggerFactory.getLogger(FinanzasService.class);

    private final GenericDAO db;
    private final AppConfig config;
    private final WalletService wallet;
    private final ValidadorMonto validador;
    private final NotificadorUsuarios notificador;

    public FinanzasService(GenericDAO db, AppConfig config, WalletService wallet,
                           ValidadorMonto validador, NotificadorUsuarios notificador) {
        this.db = db;
        this.config = config;
        this.wallet = wallet;
        this.validador = validador;
        this.notificador = notificador;
    }

    /** Depósito manual (acredita saldo de inmediato). */
    public void depositoManual(int userId, com.google.gson.JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY

        WalletResult result = wallet.depositar(userId, monto, "Depósito manual admin"); // REVIEW-MONEY
        if (!result.isSuccess()) {
            throw ServicioException.respuestaConError(result.getError());
        }
        notificador.notificarSaldo(userId, "✅ Recarga acreditada.", result.getNuevoSaldoDouble());
    }

    /** Crea una solicitud de recarga pendiente (la aprueba un admin). */
    public void crearSolicitudRecarga(int userId, String username, String metodo, String referencia,
                                      com.google.gson.JsonElement montoCrudo) {
        // D5: nunca se guarda una solicitud con un monto que el servidor no haya validado
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY

        db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia) " +
                "VALUES (?, ?, 'deposito', ?, ?, ?)", userId, username, metodo, monto, referencia);
    }

    /**
     * Solicita un retiro: descuenta el saldo atómicamente y deja la solicitud pendiente.
     *
     * @return el saldo nuevo, para que el frontend lo actualice sin otra petición
     */
    public double solicitarRetiro(int userId, String username, String metodo, String referencia,
                                  com.google.gson.JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.retiro(montoCrudo)); // REVIEW-MONEY

        // Descontar saldo atómicamente con verificación // REVIEW-MONEY
        WalletResult result = wallet.retirar(userId, monto, "Retiro " + metodo);
        if (!result.isSuccess()) {
            throw ServicioException.respuestaConError(result.getError());
        }

        db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia) " +
                "VALUES (?, ?, 'retiro', ?, ?, ?)", userId, username, metodo, monto, referencia);

        notificador.notificarSaldo(userId, "⏳ Retiro en proceso...", result.getNuevoSaldoDouble());
        return result.getNuevoSaldoDouble();
    }

    /** Prepara un pago Wompi: registra la transacción pendiente y devuelve los datos firmados para el widget. */
    public JsonObject iniciarWompi(int userId, String username, com.google.gson.JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY

        // Total con la tarifa de pasarela (el cálculo con double queda para D1; el monto ya está acotado y es entero)
        double baseCara = monto.doubleValue() + 840;
        double totalCobrado = Math.ceil(baseCara / 0.964);
        long montoCentavos = (long) (totalCobrado * 100);

        String reference = "TF-" + System.currentTimeMillis();

        db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado) " +
                "VALUES (?, ?, 'deposito', 'wompi', ?, ?, 'pendiente')",
                userId, username, monto, reference);

        // Firma de integridad
        String toSign = reference + montoCentavos + "COP" + config.getWompiIntegritySecret();
        String signature = sha256(toSign);

        JsonObject res = new JsonObject();
        res.addProperty("publicKey", config.getWompiPublicKey());
        res.addProperty("reference", reference);
        res.addProperty("amountInCents", montoCentavos);
        res.addProperty("signature", signature);
        res.addProperty("montoReal", monto);
        return res;
    }

    /**
     * Procesa el webhook de Wompi. Si el pago está APPROVED y existe la transacción pendiente, acredita el saldo.
     * Lanza excepción si el cuerpo no tiene el formato esperado (la ruta responde 500, como antes).
     * (S7 — verificar la firma del webhook — sigue pendiente y es otra tarea.)
     */
    public void procesarWebhookWompi(JsonObject body) {
        JsonObject transaction = body.getAsJsonObject("data").getAsJsonObject("transaction");
        String status = transaction.get("status").getAsString();
        String reference = transaction.get("reference").getAsString();

        if (!"APPROVED".equals(status)) return;

        JsonObject trans = db.queryOne(
                "SELECT * FROM transactions WHERE referencia = ? AND estado = 'pendiente'", reference);
        if (trans == null) return;

        int userId = trans.get("usuario_id").getAsNumber().intValue();
        BigDecimal monto = BigDecimal.valueOf(trans.get("monto").getAsDouble()); // REVIEW-MONEY

        // Acreditar saldo atómicamente // REVIEW-MONEY
        WalletResult result = wallet.depositar(userId, monto, "Pago Wompi ref:" + reference);
        db.update("UPDATE transactions SET estado = 'completado' WHERE referencia = ?", reference);

        if (result.isSuccess()) {
            notificador.notificarSaldo(userId, "✅ Pago Wompi aprobado. Saldo acreditado.", result.getNuevoSaldoDouble());
        } else {
            logger.error("Wompi {}: la transacción quedó completada pero no se pudo acreditar: {}", reference, result.getError());
        }
    }

    // --- Helpers ---

    private static BigDecimal exigirValido(ValidadorMonto.Resultado r) {
        if (!r.isValido()) {
            throw ServicioException.solicitudInvalida(r.getError());
        }
        return r.getMonto();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
