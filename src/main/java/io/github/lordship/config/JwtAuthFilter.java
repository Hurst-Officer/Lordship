package io.github.lordship.config;

import io.github.lordship.access.JwtService;
import io.github.lordship.identity.AgentAuthorization;
import io.github.lordship.identity.AgentAuthorizationCache;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A token that is present but unusable is answered here with a 401 and a reason,
 * rather than falling through to anonymous and being denied later as though a
 * permission were missing. No header at all still falls through: the request may
 * be for a permitAll path, and if it is not, the entry point turns the denial
 * into a 401 of its own.
 *
 * <p>Everything below the token parsing used to be three database round trips per
 * request -- the agent, its permissions, its property assignments. It is now one
 * cache lookup, and on a miss one trip through AgentAuthorizationLoader. The
 * decisions and the reasons they produce are unchanged.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AgentAuthorizationCache authorizationCache;
    private final AgentAuthorizationLoader authorizationLoader;

    public JwtAuthFilter(JwtService jwtService,
                         AgentAuthorizationCache authorizationCache,
                         AgentAuthorizationLoader authorizationLoader) {
        this.jwtService = jwtService;
        this.authorizationCache = authorizationCache;
        this.authorizationLoader = authorizationLoader;
    }

    // Sign-in, refresh and logout never read the access token. An expired one sent
    // along by mistake must not turn a refresh into a 401.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return LoginBodySizeFilter.isPublicPost(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || authHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authHeader.startsWith("Bearer ")) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_request",
                    "Authorization header must use the Bearer scheme");
            return;
        }

        String token = authHeader.substring(7).trim();

        if (token.isEmpty()) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_request",
                    "Bearer token is empty");
            return;
        }

        Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (ExpiredJwtException e) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_token",
                    "Access token expired");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_token",
                    "Access token is malformed or its signature does not verify");
            return;
        }

        String userType = jwtService.extractUserType(claims);

        if (!"AGENT".equals(userType)) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_token",
                    "Unsupported user type in token");
            return;
        }

        UUID agentId;
        try {
            agentId = jwtService.extractAgentId(claims);
        } catch (IllegalArgumentException | NullPointerException e) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_token",
                    "Token subject is not an agent id");
            return;
        }

        // A token outliving the agent it names is a stale credential, not a
        // permission problem, so it gets the same 401 as an expired one. The loader
        // returns null for a missing agent and Caffeine does not cache that, so this
        // path still costs a lookup every time -- as it did before.
        AgentAuthorization authorization = authorizationCache.get(agentId, authorizationLoader::load);
        if (authorization == null) {
            LordshipAuthenticationEntryPoint.send(response, "invalid_token",
                    "The agent this token identifies no longer exists");
            return;
        }

        // Sessions ended by a password change or an explicit revoke. The token is
        // still signed and still unexpired, so nothing above catches it. This is the
        // highest-risk field in the snapshot: AgentService.setAgentPassword evicts
        // synchronously so a revoke takes effect on the very next request, and the
        // TTL only bounds a path somebody forgets to wire.
        if (authorization.tokensValidFrom() != null) {
            Date issuedAt = claims.getIssuedAt();
            if (issuedAt == null || issuedAt.toInstant().isBefore(authorization.tokensValidFrom().toInstant())) {
                LordshipAuthenticationEntryPoint.send(response, "invalid_token",
                        "Session ended, sign in again");
                return;
            }
        }

        Set<SimpleGrantedAuthority> authorities = authorization.permissions()
                .stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(authorization.principal(), null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            UUID sessionId = jwtService.extractSessionId(claims);
            if (sessionId != null) {
                request.setAttribute(JwtService.SESSION_ID_ATTRIBUTE, sessionId);
            }
        } catch (IllegalArgumentException e) {
            // A malformed sid only costs the "this computer" label. It is signed, so this should not happen.
        }

        filterChain.doFilter(request, response);
    }
}