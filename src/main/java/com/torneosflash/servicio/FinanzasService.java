package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.servicio.WalletService.WalletResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lógica de depósitos, retiros y pagos Wompi (A3). Todo movimiento de dinero pasa por {@link WalletService};
 * todo monto que llega del cliente pasa por {@link ValidadorMonto} antes de tocar la BD (D5), y todo cálculo
 * monetario usa {@link BigDecimal} (D1).
 * No conoce HTTP: {@code RutasFinanzas} solo parsea y responde.
 */
public class FinanzasService {
    private static final Logger logger = LoggerFactory.getLogger(FinanzasService.class);

    private static final BigDecimal TARIFA_FIJA_WOMPI = BigDecimal.valueOf(840);
    private static final BigDecimal DIVISOR_PASARELA_WOMPI = new BigDecimal("0.964");
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

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
    public void depositoManual(int userId, JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY

        WalletResult result = wallet.depositar(userId, monto, "Depósito manual admin"); // REVIEW-MONEY
        if (!result.isSuccess()) {
            throw ServicioException.respuestaConError(result.getError());
        }
        notificador.notificarSaldo(userId, "✅ Recarga acreditada.", result.getNuevoSaldo());
    }

    /** S2: Obtiene el username real desde la BD a partir del userId de la sesión JWT. */
    private String usernameDeUsuario(int userId) {
        JsonObject u = db.queryOne("SELECT username FROM users WHERE id = ?", userId);
        if (u == null || !u.has("username") || u.get("username").isJsonNull()) {
            throw ServicioException.noAutenticado("Usuario no encontrado");
        }
        return u.get("username").getAsString();
    }

    /** Crea una solicitud de recarga pendiente (la aprueba un admin). */
    public void crearSolicitudRecarga(int userId, String metodo, String referencia,
                                      JsonElement montoCrudo) {
        // D5: nunca se guarda una solicitud con un monto que el servidor no haya validado
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY
        String metodoLimpio = ChatSanitizer.limpiarTexto(metodo, 40);
        String refLimpia = ChatSanitizer.limpiarTexto(referencia, 120);
        if (metodoLimpio.isEmpty() || refLimpia.isEmpty()) {
            throw ServicioException.solicitudInvalida("El método y la referencia son obligatorios");
        }
        String username = usernameDeUsuario(userId);

        int rows = db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia) " +
                "VALUES (?, ?, 'deposito', ?, ?, ?)", userId, username, metodoLimpio, monto, refLimpia);
        if (rows == 0) {
            throw ServicioException.interno("No se pudo registrar la solicitud de recarga", null);
        }
    }

    /**
     * C3: Solicita un retiro descontando el saldo e insertando la solicitud en UNA sola transacción SQL atómica.
     *
     * @return el saldo nuevo como {@link BigDecimal} (D1), para que el frontend lo actualice sin otra petición
     */
    public BigDecimal solicitarRetiro(int userId, String metodo, String referencia,
                                      JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.retiro(montoCrudo)); // REVIEW-MONEY
        String metodoLimpio = ChatSanitizer.limpiarTexto(metodo != null ? metodo : "nequi_retiro", 40);
        String refLimpia = ChatSanitizer.limpiarTexto(referencia, 160);
        if (refLimpia.isEmpty()) {
            throw ServicioException.solicitudInvalida("Los datos de la cuenta de destino son obligatorios");
        }
        String username = usernameDeUsuario(userId);

        // Descontar saldo e insertar en transactions dentro de la misma transacción SQL // REVIEW-MONEY
        WalletResult result = wallet.solicitarRetiroTransaccional(userId, username, metodoLimpio, refLimpia, monto);
        if (!result.isSuccess()) {
            throw ServicioException.respuestaConError(result.getError());
        }

        notificador.notificarSaldo(userId, "⏳ Retiro en proceso...", result.getNuevoSaldo());
        return result.getNuevoSaldo();
    }

    /** Prepara un pago Wompi: registra la transacción pendiente y devuelve los datos firmados para el widget. */
    public JsonObject iniciarWompi(int userId, JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY
        String username = usernameDeUsuario(userId);

        long montoCentavos = calcularMontoCentavosWompi(monto);
        String reference = "TF-" + userId + "-" + System.currentTimeMillis() + "-"
                + ThreadLocalRandom.current().nextInt(1000, 10000);

        int rows = db.update("INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado) " +
                "VALUES (?, ?, 'deposito', 'wompi', ?, ?, 'pendiente')",
                userId, username, monto, reference);
        if (rows == 0) {
            throw ServicioException.interno("No se pudo iniciar la transacción Wompi", null);
        }

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

    /** Compatibilidad: procesa el webhook de Wompi verificando la firma del body. */
    public void procesarWebhookWompi(JsonObject body) {
        procesarWebhookWompi(body, null);
    }

    /**
     * S7: Procesa el webhook de Wompi verificando primero su autenticidad e integridad con SHA-256
     * ({@code signature.properties} + {@code timestamp} + secreto de eventos), comparando en tiempo constante
     * contra {@code signature.checksum} y {@code X-Event-Checksum}, y validando moneda y monto pagado.
     */
    public void procesarWebhookWompi(JsonObject body, String headerChecksum) {
        if (body == null) {
            throw ServicioException.solicitudInvalida("Cuerpo del webhook vacío");
        }

        // 1. Verificar firma criptográfica antes de confiar en cualquier dato del evento (S7)
        verificarFirmaWebhookWompi(body, headerChecksum);

        JsonObject data = objeto(body, "data");
        JsonObject transaction = objeto(data, "transaction");
        if (transaction == null) {
            throw ServicioException.solicitudInvalida("Falta data.transaction en el webhook");
        }

        String transactionId = texto(transaction, "id");
        String status = texto(transaction, "status");
        String reference = texto(transaction, "reference");
        if (transactionId.isEmpty() || status.isEmpty() || reference.isEmpty()) {
            throw ServicioException.solicitudInvalida("Faltan id, status o reference en el webhook");
        }

        if (!"APPROVED".equals(status)) return;

        // 2. Verificar moneda ("COP" obligatoria) y monto cobrado en centavos (D5 / S7 / C4)
        String currency = texto(transaction, "currency");
        if (!"COP".equalsIgnoreCase(currency)) {
            logger.error("⚠️ Webhook Wompi rechazado ref {}: moneda inválida '{}'", reference, currency);
            throw ServicioException.solicitudInvalida("Moneda de transacción Wompi no válida");
        }
        JsonElement amountElem = transaction.get("amount_in_cents");
        if (amountElem == null || !amountElem.isJsonPrimitive()) {
            throw ServicioException.solicitudInvalida("Falta amount_in_cents en el webhook");
        }
        long pagadoCentavos;
        try {
            pagadoCentavos = amountElem.getAsLong();
        } catch (Exception e) {
            throw ServicioException.solicitudInvalida("amount_in_cents inválido en el webhook");
        }

        // 3. Marcar como completada y acreditar saldo en UNA sola transacción SQL atómica (C4) // REVIEW-MONEY
        WalletResult result = wallet.acreditarDepositoWompi(reference, pagadoCentavos, FinanzasService::calcularMontoCentavosWompi);
        if (result != null && result.isSuccess()) {
            JsonObject trans = db.queryOne("SELECT usuario_id FROM transactions WHERE referencia = ?", reference);
            if (trans != null && trans.has("usuario_id")) {
                int userId = trans.get("usuario_id").getAsInt();
                notificador.notificarSaldo(userId, "✅ Pago Wompi aprobado. Saldo acreditado.", result.getNuevoSaldo());
            }
        }
    }

    // --- Helpers ---

    /**
     * S7: Verifica el checksum SHA-256 de Wompi concatenando los valores de {@code signature.properties}
     * (en orden), {@code timestamp} y el secreto de eventos.
     */
    private void verificarFirmaWebhookWompi(JsonObject body, String headerChecksum) {
        String secreto = obtenerSecretoWebhookWompi();
        if (secreto.isEmpty()) {
            logger.error("❌ Webhook Wompi rechazado: ni WOMPI_EVENTS_SECRET ni WOMPI_INTEGRITY_SECRET están configurados");
            throw ServicioException.noDisponible("Webhook Wompi no configurado");
        }

        JsonObject data = objeto(body, "data");
        JsonObject signature = objeto(body, "signature");
        if (data == null || signature == null) {
            logger.warn("⚠️ Webhook Wompi rechazado: falta objeto data o signature");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }

        JsonElement propsElem = signature.get("properties");
        if (propsElem == null || !propsElem.isJsonArray()) {
            logger.warn("⚠️ Webhook Wompi rechazado: falta signature.properties");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }
        JsonArray properties = propsElem.getAsJsonArray();
        if (properties.isEmpty()) {
            logger.warn("⚠️ Webhook Wompi rechazado: signature.properties está vacío");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }

        Set<String> propiedadesFirmadas = new HashSet<>();
        StringBuilder aFirmar = new StringBuilder();
        for (JsonElement propElem : properties) {
            if (propElem == null || !propElem.isJsonPrimitive()) {
                throw ServicioException.noAutenticado("Firma de webhook inválida");
            }
            String nombreProp = propElem.getAsString().trim();
            String valor = extraerPropiedadEvento(data, nombreProp);
            if (valor == null) {
                logger.warn("⚠️ Webhook Wompi rechazado: no se encontró la propiedad '{}' en data", nombreProp);
                throw ServicioException.noAutenticado("Firma de webhook inválida");
            }
            propiedadesFirmadas.add(nombreProp);
            aFirmar.append(valor);
        }

        // C4 / S7: exigir que la firma cubra como mínimo id, status y amount_in_cents de la transacción
        if (!propiedadesFirmadas.contains("transaction.id")
                || !propiedadesFirmadas.contains("transaction.status")
                || !propiedadesFirmadas.contains("transaction.amount_in_cents")) {
            logger.warn("⚠️ Webhook Wompi rechazado: signature.properties no cubre id, status y amount_in_cents ({})", propiedadesFirmadas);
            throw ServicioException.noAutenticado("Firma de webhook incompleta");
        }

        String timestamp = texto(body, "timestamp");
        if (timestamp.isEmpty()) {
            timestamp = texto(signature, "timestamp");
        }
        if (timestamp.isEmpty()) {
            logger.warn("⚠️ Webhook Wompi rechazado: falta timestamp");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }

        aFirmar.append(timestamp).append(secreto);
        String hashCalculado = sha256(aFirmar.toString());
        if (hashCalculado.isEmpty()) {
            throw ServicioException.interno("Error calculando firma de webhook", null);
        }

        String checksumBody = texto(signature, "checksum");
        String checksumHeader = headerChecksum == null ? "" : headerChecksum.trim();

        if (checksumBody.isEmpty() && checksumHeader.isEmpty()) {
            logger.warn("⚠️ Webhook Wompi rechazado: falta checksum en signature y en header X-Event-Checksum");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }
        if (!checksumBody.isEmpty() && !hashIgual(hashCalculado, checksumBody)) {
            logger.warn("⚠️ Webhook Wompi rechazado: signature.checksum no coincide");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }
        if (!checksumHeader.isEmpty() && !hashIgual(hashCalculado, checksumHeader)) {
            logger.warn("⚠️ Webhook Wompi rechazado: X-Event-Checksum no coincide");
            throw ServicioException.noAutenticado("Firma de webhook inválida");
        }
    }

    /**
     * C4 / S7: Exige exclusivamente WOMPI_EVENTS_SECRET. Nunca cae a WOMPI_INTEGRITY_SECRET
     * (el secreto de integridad firma datos que se entregan al cliente en /api/wompi/init y son secretos distintos).
     */
    private String obtenerSecretoWebhookWompi() {
        String eventsSecret = config.getWompiEventsSecret();
        return (eventsSecret != null && !eventsSecret.isBlank()) ? eventsSecret.trim() : "";
    }

    /** Resuelve una ruta con puntos (ej. "transaction.amount_in_cents") dentro del objeto {@code data}. */
    private static String extraerPropiedadEvento(JsonObject data, String rutaPropiedad) {
        if (data == null || rutaPropiedad == null || rutaPropiedad.isBlank()) return null;
        String[] partes = rutaPropiedad.trim().split("\\.");
        JsonElement actual = data;
        for (String parte : partes) {
            if (actual == null || !actual.isJsonObject()) return null;
            actual = actual.getAsJsonObject().get(parte);
        }
        if (actual == null || actual.isJsonNull() || !actual.isJsonPrimitive()) return null;
        return actual.getAsString();
    }

    /** Comparación de hashes hexadecimales en tiempo constante para evitar ataques de timing. */
    private static boolean hashIgual(String hashCalculado, String hashRecibido) {
        if (hashCalculado == null || hashRecibido == null) return false;
        byte[] a = hashCalculado.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = hashRecibido.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * D1: Total en centavos de COP que cobra Wompi (incluye tarifa de pasarela), calculado íntegramente
     * con {@link BigDecimal} y {@link RoundingMode#CEILING} (sin {@code double}). // REVIEW-MONEY
     */
    static long calcularMontoCentavosWompi(BigDecimal monto) {
        BigDecimal baseCara = monto.add(TARIFA_FIJA_WOMPI);
        BigDecimal totalCobrado = baseCara.divide(DIVISOR_PASARELA_WOMPI, 0, RoundingMode.CEILING);
        return totalCobrado.multiply(CIEN).longValueExact();
    }

    private static JsonObject objeto(JsonObject padre, String campo) {
        if (padre == null) return null;
        JsonElement elem = padre.get(campo);
        return (elem != null && elem.isJsonObject()) ? elem.getAsJsonObject() : null;
    }

    private static String texto(JsonObject padre, String campo) {
        if (padre == null) return "";
        JsonElement elem = padre.get(campo);
        return (elem != null && elem.isJsonPrimitive()) ? elem.getAsString().trim() : "";
    }

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
