package com.torneosflash.servicio;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra tokens de Clash Royale retenidos mientras una cuenta espera verificación manual.
 * La clave se deriva de JWT_SECRET; cambiar ese secreto vuelve ilegibles las verificaciones pendientes.
 */
public final class CifradoTokenJugador {
    private static final String PREFIJO = "v1.";
    private static final String CONTEXTO_CLAVE = "TorneosFlash/player-verification-token/v1";
    private static final int TAMANO_IV = 12;
    private static final int TAMANO_TAG_BITS = 128;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final SecretKeySpec clave;

    public CifradoTokenJugador(String secretoBase) {
        if (secretoBase == null || secretoBase.length() < 32) {
            throw new IllegalArgumentException("JWT_SECRET debe tener al menos 32 caracteres");
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secretoBase.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] materialClave = hmac.doFinal(CONTEXTO_CLAVE.getBytes(StandardCharsets.UTF_8));
            this.clave = new SecretKeySpec(materialClave, "AES");
            Arrays.fill(materialClave, (byte) 0);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo inicializar el cifrado de tokens de jugador", e);
        }
    }

    public String cifrar(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Token vacío");
        byte[] iv = new byte[TAMANO_IV];
        ALEATORIO.nextBytes(iv);
        byte[] textoPlano = token.getBytes(StandardCharsets.UTF_8);
        byte[] cifrado = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(TAMANO_TAG_BITS, iv));
            cifrado = cipher.doFinal(textoPlano);
            byte[] payload = ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array();
            try {
                return PREFIJO + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el token de jugador", e);
        } finally {
            Arrays.fill(textoPlano, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            if (cifrado != null) Arrays.fill(cifrado, (byte) 0);
        }
    }

    public String descifrar(String valor) {
        if (valor == null || !valor.startsWith(PREFIJO)) {
            throw new IllegalArgumentException("Formato de token cifrado no válido");
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(valor.substring(PREFIJO.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Formato de token cifrado no válido", e);
        }
        if (payload.length <= TAMANO_IV + 16) throw new IllegalArgumentException("Token cifrado incompleto");
        byte[] iv = Arrays.copyOfRange(payload, 0, TAMANO_IV);
        byte[] cifrado = Arrays.copyOfRange(payload, TAMANO_IV, payload.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(TAMANO_TAG_BITS, iv));
            byte[] textoPlano = cipher.doFinal(cifrado);
            try {
                return new String(textoPlano, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(textoPlano, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar un token de jugador pendiente", e);
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(cifrado, (byte) 0);
        }
    }
}
