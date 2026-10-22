package io.github.lordship.access;


// token is the short access token. refreshToken keeps this computer signed in.
public record AgentAuthResult(AgentIdentity identity, String token, String refreshToken) {

}