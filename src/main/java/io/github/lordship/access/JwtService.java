package io.github.lordship.access;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // Permissions are left out on purpose. Grants change during the day, and a token
    // would carry a stale copy. The server looks them up on every request instead.
    public String generateToken(AgentWithPerson agentWithPerson) {
        return Jwts.builder()
                .subject(agentWithPerson.agent().uuid().toString())
                .claim("user_type", "AGENT")
                .claim("person_uuid", agentWithPerson.person().uuid().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Throws rather than reporting a boolean, so the caller can tell an expired
     * token from a forged one and say which in the response. jjwt raises
     * ExpiredJwtException for the first and a sibling JwtException for the rest;
     * a token that is not even three base64 segments raises
     * IllegalArgumentException.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractAgentId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public String extractUserType(Claims claims) {
        return claims.get("user_type", String.class);
    }

    public UUID extractPersonUuid(Claims claims) {
        return UUID.fromString(claims.get("person_uuid", String.class));
    }

    public UUID extractAgentId(String token) {
        return extractAgentId(parse(token));
    }

    public String extractUserType(String token) {
        return extractUserType(parse(token));
    }

    public UUID extractPersonUuid(String token) {
        return extractPersonUuid(parse(token));
    }

    public boolean isTokenValid(String token) {
        try {
            parse(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
