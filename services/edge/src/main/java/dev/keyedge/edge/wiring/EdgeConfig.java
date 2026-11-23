package dev.keyedge.edge.wiring;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;

/** Typed settings of the sidecar ({@code keyedge.edge.*}). */
@ConfigurationProperties("keyedge.edge")
public record EdgeConfig(
    @Bindable(defaultValue = "http://127.0.0.1:15081") String appUrl,
    @NotBlank @Bindable(defaultValue = "http://127.0.0.1:15100") String keysUrl,
    @NotBlank @Bindable(defaultValue = "edge-quotes") String callerId,
    @Bindable(defaultValue = "keyedge/callers") String callerSecret,
    @Bindable(defaultValue = "keyedge/principal") String principalSecret,
    @Bindable(defaultValue = "1s") Duration verifyTimeout,
    @Bindable(defaultValue = "1500ms") Duration verifyWait,
    @Bindable(defaultValue = "5s") Duration negativeTtl,
    @DecimalMin("0.1") @DecimalMax("1.0") @Bindable(defaultValue = "0.7") double refreshRatio,
    @Bindable(defaultValue = "100000") long maxCachedVerdicts,
    @Bindable(defaultValue = "100000") long maxRateLimitedKeys,
    @Bindable(defaultValue = "60") int maxPendingUsageWindows,
    @Bindable(defaultValue = "5s") Duration drainGrace,
    @Bindable(defaultValue = "20s") Duration drainTimeout,
    /**
     * "METHOD /path-prefix scope", e.g. "GET /v1/quotes quotes:read". Unlisted routes are not
     * exposed.
     */
    @Bindable(
            defaultValue =
                "GET /v1/quotes quotes:read,GET /v1/quotes-reactive quotes:read,"
                    + "POST /v1/quotes quotes:reserve")
        List<String> routes) {}
