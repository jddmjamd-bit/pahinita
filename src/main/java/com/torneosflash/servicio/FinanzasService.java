package com.torneosflash.servicio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.torneosflash.config.AppConfig;
import com.torneosflash.dao.GenericDAO;
import com.torneosflash.eventos.EventBus;
import com.torneosflash.eventos.Eventos;
import com.torneosflash.servicio.WalletService.WalletResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private static final int INTENTOS_REFERENCIA_WOMPI = 5;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final GenericDAO db;
    private final AppConfig config;
    private final WalletService wallet;
    private final ValidadorMonto validador;
    private final NotificadorUsuarios notificador;
    private final CorreoServicio correo;
    private final EventBus eventos;
    private final ComisionService comisionService = new ComisionService();

    public FinanzasService(GenericDAO db, AppConfig config, WalletService wallet,
                           ValidadorMonto validador, NotificadorUsuarios notificador) {
        this(db, config, wallet, validador, notificador, null, null);
    }

    public FinanzasService(GenericDAO db, AppConfig config, WalletService wallet,
                           ValidadorMonto validador, NotificadorUsuarios notificador,
                           CorreoServicio correo) {
        this(db, config, wallet, validador, notificador, correo, null);
    }

    public FinanzasService(GenericDAO db, AppConfig config, WalletService wallet,
                           ValidadorMonto validador, NotificadorUsuarios notificador,
                           CorreoServicio correo, EventBus eventos) {
        this.db = db;
        this.config = config;
        this.wallet = wallet;
        this.validador = validador;
        this.notificador = notificador;
        this.correo = correo;
        this.eventos = eventos;
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
        // Wompi genera referencias en el espacio reservado "TF-" y se procesa por /api/wompi/init.
        // Impide que una recarga manual suplante ese método o reutilice una referencia Wompi.
        if (metodoLimpio.equalsIgnoreCase("wompi")
                || refLimpia.trim().regionMatches(true, 0, "TF-", 0, 3)) {
            throw ServicioException.solicitudInvalida(
                    "Los pagos por Wompi deben iniciarse desde el flujo de Wompi");
        }
        String username = usernameDeUsuario(userId);

        int txId = db.insertReturningId(
                "INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia) " +
                "VALUES (?, ?, 'deposito', ?, ?, ?) RETURNING id",
                userId, username, metodoLimpio, monto, refLimpia);
        if (txId <= 0) {
            throw ServicioException.interno("No se pudo registrar la solicitud de recarga", null);
        }
        if (eventos != null) {
            eventos.emit(Eventos.TRANSACCION_PENDIENTE_CREADA, new Eventos.TransaccionPendiente(
                    txId, userId, username, "deposito", metodoLimpio, monto, refLimpia));
        }
        if (correo != null && obtenerModeradorDeUsuario(userId) == null) {
            correo.notificarAdmin("Nueva solicitud de depósito",
                    "Un usuario registró una solicitud de recarga que requiere revisión.\n\n"
                            + "Usuario: " + username + " (id " + userId + ")\n"
                            + "Monto: $" + monto.toPlainString() + " COP\n"
                            + "Método: " + metodoLimpio + "\n"
                            + "Referencia: " + refLimpia + "\n\n"
                            + "Panel de administración: " + config.getAppBaseUrl() + "/admin");
        }
    }

    /**
     * C3: Solicita un retiro descontando el saldo e insertando la solicitud en UNA sola transacción SQL atómica.
     *
     * @return el saldo nuevo como {@link BigDecimal} (D1), para que el frontend lo actualice sin otra petición
     */
    public BigDecimal solicitarRetiro(int userId, String metodo, String referencia,
                                      JsonElement montoCrudo) {
        JsonObject estadoVerificacion = db.queryOne(
                "SELECT player_tag_verificado FROM users WHERE id = ?", userId);
        if (estadoVerificacion == null) throw ServicioException.noAutenticado("Usuario no encontrado");
        if (!estadoVerificacion.has("player_tag_verificado")
                || !estadoVerificacion.get("player_tag_verificado").getAsBoolean()) {
            throw ServicioException.prohibido(
                    "Los retiros se habilitan cuando el administrador verifique manualmente tu cuenta de Clash Royale.");
        }
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
            String error = result.getError();
            if ("Saldo insuficiente".equals(error)) {
                // A diferencia de los flujos heredados de depósito/admin, el retiro es un endpoint nuevo:
                // indicar 400 permite al cliente reconocer el rechazo sin reportarlo como una operación exitosa.
                throw ServicioException.solicitudInvalida(error);
            }
            throw ServicioException.interno(error != null ? error : "No se pudo solicitar el retiro", null);
        }

        notificador.notificarSaldo(userId, "⏳ Retiro en proceso...", result.getNuevoSaldo());
        return result.getNuevoSaldo();
    }

    /** Prepara un pago Wompi: registra la transacción pendiente y devuelve los datos firmados para el widget. */
    public JsonObject iniciarWompi(int userId, JsonElement montoCrudo) {
        BigDecimal monto = exigirValido(validador.deposito(montoCrudo)); // REVIEW-MONEY
        String username = usernameDeUsuario(userId);

        JsonObject pendiente = db.queryOne(
                "SELECT referencia FROM transactions WHERE usuario_id = ? AND tipo = 'deposito' " +
                        "AND metodo = 'wompi' AND estado = 'pendiente' AND external_id IS NULL AND monto = ? " +
                        "ORDER BY id DESC LIMIT 1",
                userId, monto);
        String reference = pendiente != null && pendiente.has("referencia")
                ? pendiente.get("referencia").getAsString()
                : registrarTransaccionWompi(userId, username, monto);

        return datosCheckoutWompi(reference, monto);
    }

    /** Retorna los pagos pendientes propios, con firma para reabrir el mismo checkout. */
    public JsonArray pagosWompiPendientes(int userId) {
        JsonArray pendientes = new JsonArray();
        for (JsonObject row : db.query(
                "SELECT referencia, monto, external_id FROM transactions WHERE usuario_id = ? AND tipo = 'deposito' " +
                        "AND metodo = 'wompi' AND estado = 'pendiente' ORDER BY id DESC LIMIT 10",
                userId)) {
            JsonObject pago = datosCheckoutWompi(row.get("referencia").getAsString(), row.get("monto").getAsBigDecimal());
            if (row.has("external_id") && !row.get("external_id").isJsonNull()) {
                pago.addProperty("transactionId", row.get("external_id").getAsString());
            }
            pendientes.add(pago);
        }
        return pendientes;
    }

    private JsonObject datosCheckoutWompi(String reference, BigDecimal monto) {
        long montoCentavos = calcularMontoCentavosWompi(monto);

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
     * Inserta el pendiente solo si la referencia Wompi es única. Ante una colisión, vuelve a generarla
     * antes de guardar nada; así nunca se crean dos pendientes que el webhook no pueda distinguir.
     */
    private String registrarTransaccionWompi(int userId, String username, BigDecimal monto) {
        String sql = "INSERT INTO transactions (usuario_id, usuario_nombre, tipo, metodo, monto, referencia, estado) " +
                "VALUES (?, ?, 'deposito', 'wompi', ?, ?, 'pendiente') " +
                "ON CONFLICT (referencia) " +
                "WHERE tipo = 'deposito' AND metodo = 'wompi' AND referencia IS NOT NULL DO NOTHING " +
                "RETURNING id";

        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int intento = 1; intento <= INTENTOS_REFERENCIA_WOMPI; intento++) {
                String reference = "TF-" + userId + "-" + System.currentTimeMillis() + "-"
                        + ThreadLocalRandom.current().nextInt(1000, 10000);
                ps.setInt(1, userId);
                ps.setString(2, username);
                ps.setBigDecimal(3, monto);
                ps.setString(4, reference);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int txId = rs.getInt(1);
                        if (eventos != null) {
                            eventos.emit(Eventos.TRANSACCION_PENDIENTE_CREADA, new Eventos.TransaccionPendiente(
                                    txId, userId, username, "deposito", "wompi", monto, reference));
                        }
                        return reference;
                    }
                }
                logger.warn("Colisión de referencia Wompi; se reintentará (intento {}/{})",
                        intento, INTENTOS_REFERENCIA_WOMPI);
            }
        } catch (SQLException e) {
            logger.error("No se pudo registrar la transacción Wompi para userId={}: {}", userId, e.getMessage());
            throw ServicioException.interno("No se pudo iniciar la transacción Wompi", e);
        }

        // Los intentos fallidos por colisión no insertan filas. El cliente puede reintentar sin dejar
        // una transacción pendiente que luego el webhook no pueda correlacionar.
        throw ServicioException.noDisponible(
                "No se pudo generar una referencia única para el pago. Inténtalo de nuevo.");
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

        if (!("PENDING".equals(status) || "APPROVED".equals(status) || "DECLINED".equals(status)
                || "ERROR".equals(status) || "VOIDED".equals(status))) return;

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

        // El ID firmado de Wompi permite consultar la transacción real y comparar referencia,
        // estado, moneda y monto antes de acreditar.
        verificarCorrelacionEnApiWompi(transactionId, reference, status, currency, pagadoCentavos);

        if ("PENDING".equals(status)) {
            int usuarioId = wallet.vincularDepositoWompi(reference, transactionId, pagadoCentavos,
                    FinanzasService::calcularMontoCentavosWompi);
            if (usuarioId > 0) {
                notificador.emitirA(usuarioId, "wompi_pago_actualizado", eventoWompi(reference, status));
            }
            return;
        }

        if ("APPROVED".equals(status)) {
            // 3. Marcar como completada y acreditar saldo en UNA sola transacción SQL atómica (C4) // REVIEW-MONEY
            WalletResult result = wallet.acreditarDepositoWompi(
                    reference, transactionId, pagadoCentavos, FinanzasService::calcularMontoCentavosWompi);
            if (result != null && result.isSuccess()) {
            JsonObject trans = db.queryOne(
                    "SELECT usuario_id FROM transactions " +
                    "WHERE referencia = ? AND tipo = 'deposito' AND metodo = 'wompi' " +
                    "AND estado = 'completado' AND external_id = ?",
                    reference, transactionId);
            if (trans != null && trans.has("usuario_id")) {
                int userId = trans.get("usuario_id").getAsInt();
                notificador.notificarSaldo(userId, "✅ Pago Wompi aprobado. Saldo acreditado.", result.getNuevoSaldo());
                    notificador.emitirA(userId, "wompi_pago_actualizado", eventoWompi(reference, status));
                }
            }
            return;
        }

        // Los pagos rechazados o anulados dejan de ser solicitudes pendientes y nunca pasan por el flujo admin.
        int usuarioId = wallet.rechazarDepositoWompi(reference, transactionId);
        if (usuarioId > 0) {
            notificador.emitirA(usuarioId, "wompi_pago_actualizado", eventoWompi(reference, status));
        }
    }

    private static JsonObject eventoWompi(String reference, String status) {
        JsonObject evento = new JsonObject();
        evento.addProperty("reference", reference);
        evento.addProperty("status", status);
        return evento;
    }

    /** Consulta una transacción que vuelve desde el checkout; Wompi se verifica siempre en el servidor. */
    public JsonObject sincronizarPagoWompi(int userId, String transactionId) {
        String id = transactionId == null ? "" : transactionId.trim();
        if (id.isEmpty() || id.length() > 120 || id.chars().anyMatch(Character::isISOControl)) {
            throw ServicioException.solicitudInvalida("El identificador de la transacción Wompi no es válido");
        }
        JsonObject transaccion = consultarTransaccionEnApiWompi(id);
        String reference = texto(transaccion, "reference");
        String status = texto(transaccion, "status");
        String currency = texto(transaccion, "currency");
        long amountInCents;
        try {
            amountInCents = transaccion.get("amount_in_cents").getAsLong();
        } catch (Exception e) {
            throw ServicioException.noAutenticado("Respuesta inválida de Wompi al verificar la transacción");
        }
        if (!"COP".equalsIgnoreCase(currency)) {
            throw ServicioException.noAutenticado("Moneda de transacción Wompi no válida");
        }

        JsonObject local = db.queryOne(
                "SELECT monto, estado, external_id FROM transactions WHERE usuario_id = ? AND referencia = ? " +
                        "AND tipo = 'deposito' AND metodo = 'wompi'",
                userId, reference);
        if (local == null) throw ServicioException.noEncontrado("No se encontró el pago Wompi de esta cuenta");
        String externalId = local.has("external_id") && !local.get("external_id").isJsonNull()
                ? local.get("external_id").getAsString() : null;
        String estadoLocal = local.get("estado").getAsString();
        if (externalId != null && !id.equals(externalId)) {
            throw ServicioException.noAutenticado("La transacción Wompi no coincide con el pago registrado");
        }
        long esperado = calcularMontoCentavosWompi(local.get("monto").getAsBigDecimal());
        if (amountInCents != esperado) {
            throw ServicioException.noAutenticado("El monto de la transacción Wompi no coincide con el pago registrado");
        }

        if ("pendiente".equals(estadoLocal)) {
            if ("PENDING".equals(status)) {
                wallet.vincularDepositoWompi(reference, id, amountInCents, FinanzasService::calcularMontoCentavosWompi);
            } else if ("APPROVED".equals(status)) {
                WalletResult result = wallet.acreditarDepositoWompi(
                        reference, id, amountInCents, FinanzasService::calcularMontoCentavosWompi);
                if (result != null && result.isSuccess()) {
                    notificador.notificarSaldo(userId, "✅ Pago Wompi aprobado. Saldo acreditado.", result.getNuevoSaldo());
                    notificador.emitirA(userId, "wompi_pago_actualizado", eventoWompi(reference, status));
                }
            } else if ("DECLINED".equals(status) || "ERROR".equals(status) || "VOIDED".equals(status)) {
                int afectado = wallet.rechazarDepositoWompi(reference, id);
                if (afectado > 0) {
                    notificador.emitirA(userId, "wompi_pago_actualizado", eventoWompi(reference, status));
                }
            }
        }
        JsonObject resultado = new JsonObject();
        resultado.addProperty("reference", reference);
        resultado.addProperty("status", status);
        return resultado;
    }

    // --- Helpers ---

    /**
     * S7: Verifica el checksum SHA-256 de Wompi concatenando los valores de {@code signature.properties}
     * (en orden), {@code timestamp} y el secreto de eventos.
     */
    private Set<String> verificarFirmaWebhookWompi(JsonObject body, String headerChecksum) {
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
        return propiedadesFirmadas;
    }

    /**
     * S7: Cuando el webhook no incluye {@code transaction.reference} ni {@code transaction.currency} dentro de
     * {@code signature.properties}, consulta la transacción por su {@code transaction.id} (que sí viene firmado)
     * en la API de Wompi para impedir que se reutilice un evento válido cambiando la referencia.
     */
    private void verificarCorrelacionEnApiWompi(String transactionId, String referenceEsperada,
                                                 String statusEsperado, String currencyEsperada,
                                                 long centavosEsperados) {
        JsonObject txApi = consultarTransaccionEnApiWompi(transactionId);
        String apiId = texto(txApi, "id");
        String apiRef = texto(txApi, "reference");
        String apiStatus = texto(txApi, "status");
        String apiCurrency = texto(txApi, "currency");
        long apiCentavos = txApi.has("amount_in_cents") && txApi.get("amount_in_cents").isJsonPrimitive()
                ? txApi.get("amount_in_cents").getAsLong() : -1L;

        if (!transactionId.equals(apiId)
                || !referenceEsperada.equals(apiRef)
                || !statusEsperado.equals(apiStatus)
                || !currencyEsperada.equalsIgnoreCase(apiCurrency)
                || centavosEsperados != apiCentavos) {
            logger.error("⛔ Discrepancia entre webhook y API Wompi para id {}: ref webhook='{}' vs api='{}'",
                    transactionId, referenceEsperada, apiRef);
            throw ServicioException.noAutenticado("Los datos del webhook no coinciden con la transacción en Wompi");
        }
    }

    private JsonObject consultarTransaccionEnApiWompi(String transactionId) {
        String pubKey = config.getWompiPublicKey() != null ? config.getWompiPublicKey().trim() : "";
        String eventsSecret = config.getWompiEventsSecret() != null ? config.getWompiEventsSecret().trim() : "";
        String privateKey = config.getWompiPrivateKey() != null ? config.getWompiPrivateKey().trim() : "";
        if (privateKey.isEmpty()) throw ServicioException.noDisponible("Falta configurar la llave privada de Wompi");
        boolean usarSandbox = pubKey.startsWith("pub_test_") || eventsSecret.startsWith("test_");
        String baseUrl = usarSandbox
                ? "https://sandbox.wompi.co/v1/transactions/"
                : "https://production.wompi.co/v1/transactions/";

        try {
            String url = baseUrl + URLEncoder.encode(transactionId, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + privateKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                logger.warn("⚠️ Verificación API Wompi falló para id {} (HTTP {})", transactionId, resp.statusCode());
                throw ServicioException.noAutenticado("No se pudo verificar la transacción en Wompi");
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject txApi = objeto(root, "data");
            if (txApi == null) {
                throw ServicioException.noAutenticado("Respuesta inválida de Wompi al verificar transacción");
            }
            return txApi;
        } catch (ServicioException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error consultando API de Wompi para id {}: {}", transactionId, e.getMessage());
            throw ServicioException.noDisponible("Error temporal verificando transacción con Wompi");
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

    /**
     * Historial unificado de movimientos financieros del usuario: recargas, retiros, partidas,
     * comisiones de referidos, devoluciones, premios de ranking y abonos manuales,
     * con estado visual (gris/verde/rojo), fecha+hora y origen exacto.
     */
    public JsonObject obtenerMovimientos(int userId) {
        String username = usernameDeUsuario(userId);
        List<MovimientoOrdenado> lista = new ArrayList<>();

        int pendientesCount = 0;
        int enCursoCount = 0;
        BigDecimal totalIngresos = BigDecimal.ZERO;
        BigDecimal totalEgresos = BigDecimal.ZERO;
        BigDecimal totalReferidos = BigDecimal.ZERO;
        BigDecimal totalPremios = BigDecimal.ZERO;

        // A. Transacciones (recargas y retiros)
        List<JsonObject> txs = db.query(
                "SELECT id, tipo, metodo, monto, referencia, estado, fecha " +
                "FROM transactions WHERE usuario_id = ? ORDER BY fecha DESC, id DESC LIMIT 120",
                userId);
        for (JsonObject tx : txs) {
            int id = enteroJson(tx, "id");
            String tipo = texto(tx, "tipo");
            String metodo = texto(tx, "metodo");
            String referencia = texto(tx, "referencia");
            String estado = texto(tx, "estado");
            String fecha = texto(tx, "fecha");
            BigDecimal monto = montoJson(tx, "monto");

            JsonObject item = new JsonObject();
            item.addProperty("id", "tx-" + id);
            item.addProperty("transaccionId", id);
            item.addProperty("tipoTransaccion", tipo);
            item.addProperty("metodo", metodo);
            item.addProperty("referencia", referencia);
            item.addProperty("estado", estado);
            item.addProperty("fecha", fecha);
            item.addProperty("monto", monto);

            String metodoLegible = metodoLegible(metodo, tipo);
            if ("deposito".equalsIgnoreCase(tipo)) {
                item.addProperty("categoriaMovimiento", "recarga");
                item.addProperty("tipo", "recarga");
                item.addProperty("origen", metodoLegible);
                if ("pendiente".equalsIgnoreCase(estado)) {
                    item.addProperty("estadoVisual", "pendiente");
                    item.addProperty("estadoTexto", "Pendiente");
                    item.addProperty("signo", "");
                    item.addProperty("titulo", "Recarga en revisión");
                    pendientesCount++;
                } else if ("completado".equalsIgnoreCase(estado)) {
                    item.addProperty("estadoVisual", "positivo");
                    item.addProperty("estadoTexto", "Aprobado");
                    item.addProperty("signo", "+");
                    item.addProperty("titulo", "Recarga aprobada");
                    totalIngresos = totalIngresos.add(monto);
                } else {
                    item.addProperty("estadoVisual", "negativo");
                    item.addProperty("estadoTexto", "Rechazado");
                    item.addProperty("signo", "");
                    item.addProperty("titulo", "Recarga rechazada");
                }
            } else {
                item.addProperty("categoriaMovimiento", "retiro");
                item.addProperty("tipo", "retiro");
                if ("pendiente".equalsIgnoreCase(estado)) {
                    item.addProperty("estadoVisual", "pendiente");
                    item.addProperty("estadoTexto", "Pendiente");
                    item.addProperty("signo", "-");
                    item.addProperty("titulo", "Retiro en revisión");
                    item.addProperty("origen", metodoLegible);
                    pendientesCount++;
                } else if ("completado".equalsIgnoreCase(estado)) {
                    item.addProperty("estadoVisual", "negativo");
                    item.addProperty("estadoTexto", "Enviado");
                    item.addProperty("signo", "-");
                    item.addProperty("titulo", "Retiro enviado");
                    item.addProperty("origen", metodoLegible);
                    totalEgresos = totalEgresos.add(monto);
                } else {
                    item.addProperty("estadoVisual", "positivo");
                    item.addProperty("estadoTexto", "Rechazado (saldo devuelto)");
                    item.addProperty("signo", "+");
                    item.addProperty("titulo", "Retiro rechazado (reembolso)");
                    item.addProperty("origen", "Devolución de retiro (" + metodoLegible + ")");
                }
            }
            item.addProperty("detalle", !referencia.isBlank() ? "Ref: " + referencia : "");
            lista.add(new MovimientoOrdenado(parsearFecha(fecha), id, item));
        }

        // B. Partidas (en curso, disputa y finalizadas)
        List<JsonObject> matches = db.query(
                "SELECT id, sala_id, jugador1, jugador2, modo, monto, ganador, estado, fecha " +
                "FROM matches WHERE jugador1 = ? OR jugador2 = ? ORDER BY fecha DESC, id DESC LIMIT 120",
                username, username);
        for (JsonObject m : matches) {
            int matchId = enteroJson(m, "id");
            String j1 = texto(m, "jugador1");
            String j2 = texto(m, "jugador2");
            String modo = texto(m, "modo");
            String ganador = texto(m, "ganador");
            String estado = texto(m, "estado");
            String fecha = texto(m, "fecha");
            BigDecimal montoEntrada = montoJson(m, "monto");
            String rival = username.equalsIgnoreCase(j1) ? j2 : j1;

            String origenPartida = "Partida #" + matchId + " vs @" + rival + (!modo.isBlank() ? " (" + modo + ")" : "");
            long tieIdMatch = matchId * 2L;
            JsonObject item = new JsonObject();
            item.addProperty("id", "match-" + matchId);
            item.addProperty("matchId", matchId);
            item.addProperty("categoriaMovimiento", "partida");
            item.addProperty("tipo", "partida");
            item.addProperty("estado", estado);
            item.addProperty("rival", rival);
            item.addProperty("modo", modo);
            item.addProperty("fecha", fecha);
            item.addProperty("origen", origenPartida);

            if ("en_curso".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "En curso");
                item.addProperty("signo", "-");
                item.addProperty("monto", montoEntrada);
                item.addProperty("titulo", "Partida #" + matchId + " vs @" + rival);
                item.addProperty("detalle", "Entrada de $" + formatoCop(montoEntrada) + " en juego");
                enCursoCount++;
                lista.add(new MovimientoOrdenado(parsearFecha(fecha), tieIdMatch, item));
            } else if ("disputa".equalsIgnoreCase(estado)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "En disputa");
                item.addProperty("signo", "-");
                item.addProperty("monto", montoEntrada);
                item.addProperty("titulo", "Partida #" + matchId + " vs @" + rival + " (En disputa)");
                item.addProperty("detalle", "Pendiente de resolución administrativa");
                enCursoCount++;
                lista.add(new MovimientoOrdenado(parsearFecha(fecha), tieIdMatch, item));
            } else if ("Cancelada".equalsIgnoreCase(ganador)) {
                item.addProperty("estadoVisual", "pendiente");
                item.addProperty("estadoTexto", "Cancelada (reembolso)");
                item.addProperty("signo", "");
                item.addProperty("monto", montoEntrada);
                item.addProperty("titulo", "Partida #" + matchId + " cancelada");
                item.addProperty("detalle", "Entrada de $" + formatoCop(montoEntrada) + " devuelta a tu billetera");
                lista.add(new MovimientoOrdenado(parsearFecha(fecha), tieIdMatch, item));
            } else if (username.equalsIgnoreCase(ganador)) {
                ComisionService.Desglose d = comisionService.calcular(montoEntrada);
                BigDecimal premio = d.getPremio();
                BigDecimal neto = premio.subtract(montoEntrada);
                item.addProperty("estadoVisual", "positivo");
                item.addProperty("estadoTexto", "Victoria");
                item.addProperty("signo", "+");
                item.addProperty("monto", premio);
                item.addProperty("montoSecundario", neto);
                item.addProperty("titulo", "Victoria en Partida #" + matchId + " vs @" + rival);
                item.addProperty("detalle", "Premio acreditado $" + formatoCop(premio)
                        + " (entrada $" + formatoCop(montoEntrada) + " + ganancia neta $" + formatoCop(neto) + ")");
                totalIngresos = totalIngresos.add(premio);
                totalPremios = totalPremios.add(premio);
                lista.add(new MovimientoOrdenado(parsearFecha(fecha), tieIdMatch + 1L, item));

                JsonObject entradaItem = new JsonObject();
                entradaItem.addProperty("id", "match-entry-" + matchId);
                entradaItem.addProperty("matchId", matchId);
                entradaItem.addProperty("categoriaMovimiento", "partida");
                entradaItem.addProperty("tipo", "partida_entrada");
                entradaItem.addProperty("estado", estado);
                entradaItem.addProperty("rival", rival);
                entradaItem.addProperty("modo", modo);
                entradaItem.addProperty("fecha", fecha);
                entradaItem.addProperty("origen", origenPartida);
                entradaItem.addProperty("estadoVisual", "negativo");
                entradaItem.addProperty("estadoTexto", "Entrada");
                entradaItem.addProperty("signo", "-");
                entradaItem.addProperty("monto", montoEntrada);
                entradaItem.addProperty("titulo", "Entrada a Partida #" + matchId + " vs @" + rival);
                entradaItem.addProperty("detalle", "Entrada descontada al iniciar la partida");
                totalEgresos = totalEgresos.add(montoEntrada);
                lista.add(new MovimientoOrdenado(parsearFecha(fecha), tieIdMatch, entradaItem));
            } else {
                item.addProperty("estadoVisual", "negativo");
                item.addProperty("estadoTexto", "Derrota");
                item.addProperty("signo", "-");
                item.addProperty("monto", montoEntrada);
                item.addProperty("titulo", "Derrota en Partida #" + matchId + " vs @" + rival);
                item.addProperty("detalle", !ganador.isBlank() ? "Ganador: @" + ganador : "Partida finalizada");
                totalEgresos = totalEgresos.add(montoEntrada);
                lista.add(new MovimientoOrdenado(parsearFecha(fecha), tieIdMatch, item));
            }
        }

        // C1. Comisiones ganadas como referidor (excluye billetera de moderador)
        List<JsonObject> pagosReferidor = db.query(
                "SELECT rp.id, rp.match_id, rp.monto_referidor, rp.porcentaje_compartido, rp.creado_en, " +
                "u.username AS invitado_username " +
                "FROM referral_payouts rp " +
                "JOIN users u ON u.id = rp.jugador_id " +
                "WHERE rp.referrer_user_id = ? AND rp.destino_tipo = 'referidor' AND rp.monto_referidor > 0 " +
                "ORDER BY rp.creado_en DESC, rp.id DESC LIMIT 120",
                userId);
        for (JsonObject rp : pagosReferidor) {
            int rpId = enteroJson(rp, "id");
            int matchId = enteroJson(rp, "match_id");
            BigDecimal monto = montoJson(rp, "monto_referidor");
            BigDecimal pct = montoJson(rp, "porcentaje_compartido");
            String invitado = texto(rp, "invitado_username");
            String fecha = texto(rp, "creado_en");
            String tagInvitado = !invitado.isBlank() ? "@" + invitado : "invitado";

            JsonObject item = new JsonObject();
            item.addProperty("id", "ref-gan-" + rpId);
            item.addProperty("categoriaMovimiento", "referido");
            item.addProperty("tipo", "referido_comision");
            item.addProperty("estadoVisual", "positivo");
            item.addProperty("estadoTexto", "Acreditado");
            item.addProperty("signo", "+");
            item.addProperty("monto", monto);
            item.addProperty("titulo", "Comisión de referido");
            item.addProperty("origen", "Referidos · Invitado " + tagInvitado
                    + (matchId > 0 ? " (Partida #" + matchId + ")" : ""));
            item.addProperty("detalle", pct.compareTo(BigDecimal.ZERO) > 0
                    ? "Compartiendo " + pct.stripTrailingZeros().toPlainString() + "% con " + tagInvitado
                    : "Comisión completa de referido (100%)");
            item.addProperty("fecha", fecha);
            totalIngresos = totalIngresos.add(monto);
            totalReferidos = totalReferidos.add(monto);
            lista.add(new MovimientoOrdenado(parsearFecha(fecha), rpId, item));
        }

        // C2. Devoluciones por comisión compartida como referido
        List<JsonObject> pagosDevueltos = db.query(
                "SELECT rp.id, rp.match_id, rp.monto_devuelto_referido, rp.porcentaje_compartido, rp.creado_en, " +
                "u.username AS referidor_username " +
                "FROM referral_payouts rp " +
                "LEFT JOIN users u ON u.id = rp.referrer_user_id " +
                "WHERE rp.jugador_id = ? AND rp.monto_devuelto_referido > 0 " +
                "ORDER BY rp.creado_en DESC, rp.id DESC LIMIT 120",
                userId);
        for (JsonObject rp : pagosDevueltos) {
            int rpId = enteroJson(rp, "id");
            int matchId = enteroJson(rp, "match_id");
            BigDecimal monto = montoJson(rp, "monto_devuelto_referido");
            BigDecimal pct = montoJson(rp, "porcentaje_compartido");
            String referidor = texto(rp, "referidor_username");
            String fecha = texto(rp, "creado_en");
            String tagRef = !referidor.isBlank() ? "@" + referidor : "tu referidor";

            JsonObject item = new JsonObject();
            item.addProperty("id", "ref-dev-" + rpId);
            item.addProperty("categoriaMovimiento", "referido");
            item.addProperty("tipo", "referido_devolucion");
            item.addProperty("estadoVisual", "positivo");
            item.addProperty("estadoTexto", "Acreditado");
            item.addProperty("signo", "+");
            item.addProperty("monto", monto);
            item.addProperty("titulo", "Devolución por comisión compartida");
            item.addProperty("origen", "Referidos · Compartido por " + tagRef
                    + (matchId > 0 ? " (Partida #" + matchId + ")" : ""));
            item.addProperty("detalle", "Reembolso automático de "
                    + pct.stripTrailingZeros().toPlainString() + "% compartido por tu referidor");
            item.addProperty("fecha", fecha);
            totalIngresos = totalIngresos.add(monto);
            totalReferidos = totalReferidos.add(monto);
            lista.add(new MovimientoOrdenado(parsearFecha(fecha), rpId, item));
        }

        // D. Premios de leaderboard para jugadores (los sobrantes de admin van solo al panel admin)
        List<JsonObject> lbPagos = db.query(
                "SELECT lcp.id, lcp.tipo_pago, lcp.posicion, lcp.monto, lcp.estado, lcp.creado_en, lcp.pagado_en, " +
                "lc.periodo, lc.clave_corte " +
                "FROM leaderboard_cut_payments lcp " +
                "JOIN leaderboard_cuts lc ON lc.id = lcp.corte_id " +
                "WHERE lcp.user_id = ? AND lcp.tipo_pago = 'jugador' " +
                "ORDER BY COALESCE(lcp.pagado_en, lcp.creado_en) DESC, lcp.id DESC LIMIT 100",
                userId);
        for (JsonObject lb : lbPagos) {
            int lbId = enteroJson(lb, "id");
            String tipoPago = texto(lb, "tipo_pago");
            int posicion = enteroJson(lb, "posicion");
            BigDecimal monto = montoJson(lb, "monto");
            String estado = texto(lb, "estado");
            String periodo = texto(lb, "periodo");
            String claveCorte = texto(lb, "clave_corte");
            String pagadoEn = texto(lb, "pagado_en");
            String creadoEn = texto(lb, "creado_en");
            String fecha = !pagadoEn.isBlank() ? pagadoEn : creadoEn;
            boolean pagado = "pagado".equalsIgnoreCase(estado);

            JsonObject item = new JsonObject();
            item.addProperty("id", "lb-" + lbId);
            item.addProperty("categoriaMovimiento", "ranking");
            item.addProperty("tipo", "ranking");
            item.addProperty("estadoVisual", pagado ? "positivo" : "pendiente");
            item.addProperty("estadoTexto", pagado ? "Acreditado" : "Pendiente");
            item.addProperty("signo", "+");
            item.addProperty("monto", monto);
            item.addProperty("titulo", "admin".equalsIgnoreCase(tipoPago)
                    ? "Sobrante de Ranking (" + periodo + ")"
                    : "Premio de Ranking #" + posicion + " (" + periodo + ")");
            item.addProperty("origen", "Ranking " + periodo + (!claveCorte.isBlank() ? " · " + claveCorte : ""));
            item.addProperty("detalle", "admin".equalsIgnoreCase(tipoPago)
                    ? "Sobrante acreditado desde corte " + claveCorte
                    : "Premio por puesto #" + posicion + " en corte " + claveCorte);
            item.addProperty("fecha", fecha);
            if (pagado) {
                totalIngresos = totalIngresos.add(monto);
                totalPremios = totalPremios.add(monto);
            } else {
                pendientesCount++;
            }
            lista.add(new MovimientoOrdenado(parsearFecha(fecha), lbId, item));
        }

        // E. Movimientos adicionales del ledger no duplicados en A-D (abonos manuales admin, referidos sin referidor, premios extra)
        List<JsonObject> ledger = db.query(
                "SELECT id, tipo, monto, referencia, fecha FROM wallet_ledger " +
                "WHERE user_id = ? ORDER BY fecha DESC, id DESC LIMIT 120",
                userId);
        for (JsonObject lg : ledger) {
            int lgId = enteroJson(lg, "id");
            String tipo = texto(lg, "tipo");
            BigDecimal monto = montoJson(lg, "monto");
            String ref = texto(lg, "referencia");
            String fecha = texto(lg, "fecha");
            if (yaCubiertoEnFuentesPrincipales(tipo, ref)) {
                continue;
            }
            boolean esRefAdmin = ref.startsWith("Comisión referidos (sin referidor");
            boolean esManual = "deposito".equalsIgnoreCase(tipo);

            JsonObject item = new JsonObject();
            item.addProperty("id", "ledger-" + lgId);
            item.addProperty("categoriaMovimiento", esRefAdmin ? "referido" : (esManual ? "recarga" : "sistema"));
            item.addProperty("tipo", esRefAdmin ? "referido_admin" : tipo);
            item.addProperty("estadoVisual", "positivo");
            item.addProperty("estadoTexto", "Acreditado");
            item.addProperty("signo", "+");
            item.addProperty("monto", monto);
            item.addProperty("titulo", esRefAdmin ? "Comisión referidos (sin referidor)"
                    : (esManual ? "Recarga manual administrativa" : "Crédito en billetera"));
            item.addProperty("origen", esRefAdmin ? ref
                    : (esManual ? "Abono manual admin" : (!ref.isBlank() ? ref : "Sistema TorneosFlash")));
            item.addProperty("detalle", ref);
            item.addProperty("fecha", fecha);
            totalIngresos = totalIngresos.add(monto);
            if (esRefAdmin) {
                totalReferidos = totalReferidos.add(monto);
            }
            lista.add(new MovimientoOrdenado(parsearFecha(fecha), lgId, item));
        }

        lista.sort(Comparator.comparing(MovimientoOrdenado::instante).reversed()
                .thenComparing(Comparator.comparingLong(MovimientoOrdenado::tieId).reversed()));

        JsonArray arr = new JsonArray();
        for (MovimientoOrdenado m : lista) {
            arr.add(m.datos());
        }

        JsonObject resumen = new JsonObject();
        resumen.addProperty("pendientesCount", pendientesCount);
        resumen.addProperty("enCursoCount", enCursoCount);
        resumen.addProperty("totalIngresos", totalIngresos);
        resumen.addProperty("totalEgresos", totalEgresos);
        resumen.addProperty("totalReferidos", totalReferidos);
        resumen.addProperty("totalPremios", totalPremios);
        resumen.addProperty("totalMovimientos", arr.size());

        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.add("movimientos", arr);
        resp.add("resumen", resumen);
        return resp;
    }

    private static boolean yaCubiertoEnFuentesPrincipales(String tipo, String ref) {
        if ("retiro".equalsIgnoreCase(tipo)
                || "torneo".equalsIgnoreCase(tipo)
                || "liquidacion".equalsIgnoreCase(tipo)
                || "comision".equalsIgnoreCase(tipo)) {
            return true;
        }
        if ("reembolso".equalsIgnoreCase(tipo)) {
            return ref.startsWith("Retiro rechazado")
                    || ref.contains("Match #")
                    || ref.toLowerCase(Locale.ROOT).contains("partida")
                    || ref.toLowerCase(Locale.ROOT).contains("cancel");
        }
        if ("deposito".equalsIgnoreCase(tipo)) {
            return ref.startsWith("Depósito aprobado por admin #")
                    || ref.startsWith("Pago Wompi ref:");
        }
        if ("premio".equalsIgnoreCase(tipo)) {
            return ref.startsWith("Comisión de referido")
                    || ref.startsWith("Comisión referidos (sin referidor")
                    || ref.startsWith("Devolución comisión referid")
                    || ref.startsWith("Premio leaderboard")
                    || ref.startsWith("Leaderboard sobrantes");
        }
        return false;
    }

    private static String metodoLegible(String metodo, String tipo) {
        if ("wompi".equalsIgnoreCase(metodo)) return "Wompi (Pasarela)";
        if ("nequi_retiro".equalsIgnoreCase(metodo)) return "Retiro Nequi";
        if ("nequi".equalsIgnoreCase(metodo)) return "Nequi Manual";
        if (!metodo.isBlank()) return metodo;
        return "retiro".equalsIgnoreCase(tipo) ? "Retiro manual" : "Recarga manual";
    }

    private static int enteroJson(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return 0;
        try { return o.get(k).getAsInt(); } catch (Exception e) { return 0; }
    }

    private static BigDecimal montoJson(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return BigDecimal.ZERO;
        try { return o.get(k).getAsBigDecimal(); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static Instant parsearFecha(String fecha) {
        if (fecha == null || fecha.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(fecha.trim());
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static String formatoCop(BigDecimal monto) {
        if (monto == null) return "0";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return nf.format(monto);
    }

    private Integer obtenerModeradorDeUsuario(int userId) {
        if (userId <= 0) return null;
        JsonObject row = db.queryOne(
                "SELECT m.id AS moderator_id " +
                "FROM users u " +
                "LEFT JOIN referrals r ON r.referred_user_id = u.id " +
                "JOIN users m ON m.id = COALESCE(u.referido_por_id, r.referrer_user_id) " +
                "WHERE u.id = ? AND m.id <> u.id AND LOWER(COALESCE(m.tipo_suscripcion, '')) = 'moderador' " +
                "LIMIT 1",
                userId);
        if (row == null || !row.has("moderator_id") || row.get("moderator_id").isJsonNull()) {
            return null;
        }
        int modId = enteroJson(row, "moderator_id");
        return modId > 0 ? modId : null;
    }

    /**
     * Devuelve los números Nequi disponibles para que {@code userId} realice su recarga manual.
     * Si el usuario entró con el referido de un moderador, devuelve exclusivamente los Nequis de ese moderador;
     * de lo contrario, devuelve el número principal del administrador.
     */
    public JsonObject obtenerNequisParaRecarga(int userId) {
        Integer modId = obtenerModeradorDeUsuario(userId);
        JsonObject resp = new JsonObject();
        JsonArray nequisArr = new JsonArray();
        if (modId != null) {
            List<JsonObject> filas = db.query(
                    "SELECT id, numero, titular FROM moderator_nequis WHERE moderator_id = ? ORDER BY id ASC",
                    modId);
            for (JsonObject f : filas) {
                JsonObject item = new JsonObject();
                item.addProperty("id", enteroJson(f, "id"));
                item.addProperty("numero", texto(f, "numero"));
                item.addProperty("titular", texto(f, "titular"));
                nequisArr.add(item);
            }
            resp.addProperty("success", true);
            resp.addProperty("esDeModerador", true);
            resp.add("nequis", nequisArr);
            return resp;
        }
        JsonObject def = new JsonObject();
        def.addProperty("id", 0);
        def.addProperty("numero", "3126655995");
        def.addProperty("titular", "");
        nequisArr.add(def);
        resp.addProperty("success", true);
        resp.addProperty("esDeModerador", false);
        resp.add("nequis", nequisArr);
        return resp;
    }

    private record MovimientoOrdenado(Instant instante, long tieId, JsonObject datos) {}

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
