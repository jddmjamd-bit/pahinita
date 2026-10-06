package com.torneosflash.servicio;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Instant;

/**
 * Emisión y verificación de tokens de sesión (JWT HS256).
 * El token viaja únicamente en una cookie httpOnly; el frontend nunca lo lee.
 */
public class JwtServicio {

    private static final String ISSUER = "ultimateclash";
    private static final String CLAIM_ROL = "rol";

    /** Datos de sesión extraídos de un token válido. */
    public record Sesion(int userId, String rol) {}

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

    public String generarToken(int userId, String rol) {
        Instant ahora = Instant.now();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(String.valueOf(userId))
                .withClaim(CLAIM_ROL, rol != null ? rol : "free")
                .withIssuedAt(ahora)
                .withExpiresAt(ahora.plusSeconds(expiracionSegundos))
                .sign(algorithm);
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
            String rol = jwt.getClaim(CLAIM_ROL).asString();
            return new Sesion(userId, rol != null ? rol : "free");
        } catch (JWTVerificationException | NumberFormatException e) {
            return null;
        }
    }
}
