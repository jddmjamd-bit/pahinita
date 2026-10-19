package com.torneosflash.servicio;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Emisión y verificación de tokens de sesión (JWT HS256) con identificador JTI revocable.
 * El token viaja únicamente en una cookie httpOnly; el frontend nunca lo lee.
 */
public class JwtServicio {

    private static final String ISSUER = "ultimateclash";
    private static final String CLAIM_ROL = "rol";

    /** Datos de sesión extraídos de un token válido. */
    public record Sesion(int userId, String rol, String jti) {}

    /** JWT firmado y metadatos necesarios para persistir o renovar la sesión. */
    public record TokenEmitido(String valor, String jti, Instant expiraEn) {}

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final long expiracionSegundos;

    public JwtServicio(String secret, long expiracionSegundos) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("El secreto JWT debe tener al menos 32 caracteres");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
        this.expiracionSegundos = expiracionSegundos;
    }

    public long getExpiracionSegundos() {
        return expiracionSegundos;
    }

    public TokenEmitido generarToken(int userId, String rol) {
        return generarToken(userId, rol, UUID.randomUUID().toString());
    }

    /** Renueva el JWT conservando el JTI, de modo que logout también invalide copias anteriores. */
    public TokenEmitido generarToken(int userId, String rol, String jti) {
        Instant ahora = Instant.now();
        Instant expiraEn = Instant.ofEpochMilli(ahora.plusSeconds(expiracionSegundos).toEpochMilli());
        String valor = JWT.create()
                .withIssuer(ISSUER)
                .withSubject(String.valueOf(userId))
                .withJWTId(jti)
                .withClaim(CLAIM_ROL, rol != null ? rol : "free")
                .withIssuedAt(Date.from(ahora))
                .withExpiresAt(Date.from(expiraEn))
                .sign(algorithm);
        return new TokenEmitido(valor, jti, expiraEn);
    }

    /**
     * Verifica firma, emisor y expiración.
     * @return la sesión si el token es válido; {@code null} en cualquier otro caso.
     */
    public Sesion verificar(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            DecodedJWT jwt = verifier.verify(token);
            int userId = Integer.parseInt(jwt.getSubject());
            if (userId <= 0) return null;
            String jti = jwt.getId();
            Date expiraEn = jwt.getExpiresAt();
            if (jti == null || jti.isBlank() || jti.length() > 80 || expiraEn == null) return null;
            String rol = jwt.getClaim(CLAIM_ROL).asString();
            return new Sesion(userId, rol != null ? rol : "free", jti);
        } catch (JWTVerificationException | NumberFormatException e) {
            return null;
        }
    }
}
