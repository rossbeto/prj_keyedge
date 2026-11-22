package dev.keyedge.edge.wiring;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Min;
import java.time.Duration;

/**
 * The sidecar's concurrency gate ({@code keyedge.edge.concurrency.*}, Phase 008): {@code fixed} (a
 * non-blocking semaphore), {@code aimd} or {@code gradient} (adaptive, libs/ratelimit).
 */
@ConfigurationProperties("keyedge.edge.concurrency")
public record ConcurrencyConfig(
    @Bindable(defaultValue = "GRADIENT") Mode mode,
    @Min(1) @Bindable(defaultValue = "200") int fixedLimit,
    @Min(1) @Bindable(defaultValue = "50") int initial,
    @Min(1) @Bindable(defaultValue = "10") int min,
    @Min(1) @Bindable(defaultValue = "500") int max,
    @Bindable(defaultValue = "0.2") double smoothing,
    @Min(1) @Bindable(defaultValue = "100") int gradientWindow,
    @Bindable(defaultValue = "500ms") Duration aimdSlow,
    @Bindable(defaultValue = "0.9") double aimdBackoff) {
  /** Gate algorithm. */
  public enum Mode {
    FIXED,
    AIMD,
    GRADIENT
  }
}
