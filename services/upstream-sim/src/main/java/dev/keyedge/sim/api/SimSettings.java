package dev.keyedge.sim.api;

/** API settings: the client-credentials client the token endpoint accepts. */
public record SimSettings(String clientId, String clientSecret) {}
