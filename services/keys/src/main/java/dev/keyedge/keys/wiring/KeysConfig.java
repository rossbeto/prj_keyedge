package dev.keyedge.keys.wiring;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;

/** Typed settings of the keys service ({@code keyedge.keys.*}), validated at start-up (D-04). */
@ConfigurationProperties("keyedge.keys")
public record KeysConfig(
    @NotBlank @Bindable(defaultValue = "keyedge-keys") String table,
    @NotBlank @Bindable(defaultValue = "keyedge/peppers") String pepperSecret,
    @NotBlank @Bindable(defaultValue = "keyedge/callers") String callerSecret,
    @Bindable(defaultValue = "live") String environment,
    @Bindable(defaultValue = "30s") Duration idempotencyInFlight,
    @Bindable(defaultValue = "24h") Duration idempotencyRetention,
    @Bindable(defaultValue = "30d") Duration usageRetention,
    @Bindable(defaultValue = "60s") Duration verifyTtl,
    @Bindable(defaultValue = "24h") Duration defaultGrace,
    @Bindable(defaultValue = "7d") Duration maxGrace,
    @Bindable(defaultValue = "30s") Duration signatureSkew,
    @Bindable(defaultValue = "5m") Duration secretRefresh,
    @Bindable(defaultValue = "2s") Duration requestDeadline,
    @Min(1) @Max(1_000_000) @Bindable(defaultValue = "50") long defaultRatePerSecond,
    @Min(1) @Max(4194) @Bindable(defaultValue = "100") long defaultBurst) {}
