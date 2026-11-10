package io.github.lordship.access.internal;

import io.github.lordship.identity.AgentAuthorizationCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a computer signed in. Each sign-in starts a session and hands back a random
 * refresh token. Glade trades that token for a new access token and a new refresh token.
 * The old refresh token stops working at that moment.
 *
 * <p>The database only stores a SHA-256 hash of each token. A copy of the table
 * cannot be used to sign in.
 *
 * <p>Every access token names its session (the sid claim), and every request checks that the session was not
 * ended (JwtAuthFilter). So each path here that ends a session drops the agent's cached snapshot
 * (AgentAuthorizationCache): the computers signed out are refused on their very next request, not when their
 * access token runs out.
 */
@Service
public class AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentSessionRepository sessionRepository;
    private final AgentAuthorizationCache authorizationCache;
    private final int idleDays;
    private final int maxDays;

    public AgentSessionService(AgentSessionRepository sessionRepository,
                               AgentAuthorizationCache authorizationCache,
                               @Value("${lordship.session.idle-days:30}") int idleDays,
                               @Value("${lordship.session.max-days:90}") int maxDays) {
        this.sessionRepository = sessionRepository;
        this.authorizationCache = authorizationCache;
        this.idleDays = idleDays;
        this.maxDays = maxDays;
    }

    /** A new session and its first refresh token. */
    public record Started(UUID sessionId, String refreshToken) {}

    /** The result of a good refresh: whose session it is, and the token that replaces the old one. */
    public record Rotated(UUID sessionId, UUID agentId, OffsetDateTime sessionStartedAt, String refreshToken) {}

    /** Starts a session and returns its refresh token. Only the hash is saved. */
    public Started start(UUID agentId, String ipAddress, String browserClient, String browserOs) {
        String token = newToken();
        AgentSessionRow row = sessionRepository.start(agentId, hash(token), ipAddress, browserClient, browserOs, maxDays);
        return new Started(row.uuid(), token);
    }

    /**
     * Swaps a refresh token for a new one. Empty when the token is unknown, already
     * swapped, ended, unused for too long, or past the hard limit.
     */
    @Transactional
    public Optional<Rotated> rotate(String refreshToken, String ipAddress) {
        String oldHash = hash(refreshToken);
        Optional<AgentSessionRow> found = sessionRepository.findByTokenHash(oldHash);

        if (found.isEmpty()) {
            // A used-up token came back. Someone else may hold a copy,
            // so end the whole session. The worker signs in again.
            sessionRepository.findBySpentTokenHash(oldHash)
                    .filter(row -> row.endedAt() == null)
                    .ifPresent(row -> {
                        sessionRepository.end(row.uuid());
                        authorizationCache.invalidate(row.agentId());
                        log.warn("A used-up refresh token for agent {} came back. Ended session {}",
                                row.agentId(), row.uuid());
                    });
            return Optional.empty();
        }

        AgentSessionRow row = found.get();
        if (row.endedAt() != null) {
            return Optional.empty();
        }

        OffsetDateTime now = OffsetDateTime.now();
        boolean pastHardLimit = now.isAfter(row.expiresAt());
        boolean idleTooLong = now.isAfter(row.lastUsedAt().plusDays(idleDays));
        if (pastHardLimit || idleTooLong) {
            sessionRepository.end(row.uuid());
            authorizationCache.invalidate(row.agentId());
            return Optional.empty();
        }

        String next = newToken();
        if (!sessionRepository.rotate(row.uuid(), oldHash, hash(next), ipAddress)) {
            return Optional.empty();
        }
        sessionRepository.markSpent(row.uuid(), oldHash);
        return Optional.of(new Rotated(row.uuid(), row.agentId(), row.createdAt(), next));
    }

    /** Log out of one computer. Does nothing for an unknown token. */
    public void end(String refreshToken) {
        sessionRepository.findByTokenHash(hash(refreshToken))
                .ifPresent(row -> {
                    if (sessionRepository.end(row.uuid())) {
                        authorizationCache.invalidate(row.agentId());
                    }
                });
    }

    /** The agent's computers that are still signed in, most recently used first. */
    public List<AgentSessionRow> listOpen(UUID agentId) {
        return sessionRepository.findOpenForAgent(agentId, idleDays);
    }

    /** An admin signs one computer out. False when it is not this agent's or already ended. */
    public boolean endForAgent(UUID agentId, UUID sessionId) {
        boolean ended = sessionRepository.endForAgent(agentId, sessionId);
        if (ended) {
            authorizationCache.invalidate(agentId);
        }
        return ended;
    }

    /** Log out of every computer. Used by "Sign out everywhere", password reset and agent removal. */
    public int endAll(UUID agentId) {
        int ended = sessionRepository.endAllForAgent(agentId);
        authorizationCache.invalidate(agentId);
        return ended;
    }

    /** The agent's sessions that were not ended, for the check on every request (JwtAuthFilter). */
    public Set<UUID> notEndedIds(UUID agentId) {
        return Set.copyOf(sessionRepository.findNotEndedIds(agentId));
    }

    // 32 random bytes, URL-safe base64 (43 characters)
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // SHA-256 as 64 hex characters, to match agent_session.token_hash
    static String hash(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
