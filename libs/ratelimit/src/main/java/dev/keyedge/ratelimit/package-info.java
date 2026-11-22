/**
 * Rate limiters for the sidecar: GCRA and a token bucket, each one {@code AtomicLong} per key
 * updated with a CAS loop, with time injected through {@link dev.keyedge.ratelimit.Ticker}.
 */
@NullMarked
package dev.keyedge.ratelimit;

import org.jspecify.annotations.NullMarked;
