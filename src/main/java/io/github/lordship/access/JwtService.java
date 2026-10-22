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

    // JwtAuthFilter puts the token's session id here, for "this computer" checks
    public static final String SESSION_ID_ATTRIBUTE = "lordship.sessionId";

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
        return generateToken(agentWithPerson, null);
    }

    // sid is the agent_session the token came from, so the server knows which computer sent it.
    public String generateToken(AgentWithPerson agentWithPerson, UUID sessionId) {
        return Jwts.builder()
                .subject(agentWithPerson.agent().uuid().toString())
                .claim("user_type", "AGENT")
                .claim("person_uuid", agentWithPerson.person().uuid().toString())
                .claim("sid", sessionId == null ? null : sessionId.toString())
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

    // Null for a token made without a session, such as one from an older build.
    public UUID extractSessionId(Claims claims) {
        String sid = claims.get("sid", String.class);
        return sid == null ? null : UUID.fromString(sid);
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
