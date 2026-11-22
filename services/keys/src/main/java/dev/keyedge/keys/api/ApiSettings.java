package dev.keyedge.keys.api;

import java.time.Duration;

/** API-layer settings, built by the composition root from typed configuration. */
public record ApiSettings(Duration defaultGrace, Duration signatureSkew) {}
