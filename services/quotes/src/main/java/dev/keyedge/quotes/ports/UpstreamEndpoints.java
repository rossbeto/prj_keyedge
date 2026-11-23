package dev.keyedge.quotes.ports;

import java.net.URI;
import java.time.Duration;

/** Upstream base URLs and per-call timeouts (each capped by the request deadline). */
public record UpstreamEndpoints(
    URI pricing,
    Duration pricingTimeout,
    URI stock,
    Duration stockTimeout,
    URI fx,
    Duration fxTimeout) {}
