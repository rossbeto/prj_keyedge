package dev.keyedge.edge.wiring;

import dev.keyedge.edge.domain.FailurePolicy;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.time.Duration;

/**
 * Control-plane resilience ({@code keyedge.edge.resilience.*}, Phase 008): breaker and bulkhead
 * around verify (D-20) and what to do when it cannot answer (D-27). The time limit is {@code
 * keyedge.edge.verify-timeout} (the HTTP client's own timeout).
 */
@ConfigurationProperties("keyedge.edge.resilience")
public record ResilienceConfig(
    @Bindable(defaultValue = "true") boolean breakerEnabled,
    @Bindable(defaultValue = "FAIL_STATIC") FailurePolicy failurePolicy,
    @Bindable(defaultValue = "5m") Duration staleWindow,
    @Min(1) @Bindable(defaultValue = "50") int slidingWindow,
    @Min(1) @Bindable(defaultValue = "20") int minimumCalls,
    @DecimalMin("1") @DecimalMax("100") @Bindable(defaultValue = "50") float failureRateThreshold,
    @Bindable(defaultValue = "500ms") Duration slowCallDuration,
    @DecimalMin("1") @DecimalMax("100") @Bindable(defaultValue = "80") float slowCallRateThreshold,
    @Bindable(defaultValue = "10s") Duration waitInOpen,
    @Min(1) @Bindable(defaultValue = "5") int halfOpenCalls,
    @Min(1) @Bindable(defaultValue = "64") int bulkheadMaxConcurrent) {}
