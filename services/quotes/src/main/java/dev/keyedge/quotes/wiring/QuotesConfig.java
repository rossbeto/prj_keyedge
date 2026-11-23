package dev.keyedge.quotes.wiring;

import dev.keyedge.quotes.domain.RetryPolicy;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import java.time.Duration;

/** quotes settings ({@code keyedge.quotes.*}); the per-upstream numbers are D-20's defaults. */
@ConfigurationProperties("keyedge.quotes")
public record QuotesConfig(
    @Bindable(defaultValue = "STRUCTURED") FanOutMode fanout,
    @Bindable(defaultValue = "800ms") Duration budget,
    @Bindable(defaultValue = "EUR") String baseCurrency,
    @Bindable(defaultValue = "http://127.0.0.1:15201") String pricingUrl,
    @Bindable(defaultValue = "http://127.0.0.1:15201") String stockUrl,
    @Bindable(defaultValue = "http://127.0.0.1:15201") String fxUrl,
    @Bindable(defaultValue = "http://127.0.0.1:15201/oauth/token") String tokenUrl,
    @Bindable(defaultValue = "quotes") String clientId,
    @Bindable(defaultValue = "local-only-not-a-secret") String clientSecret,
    @Bindable(defaultValue = "0.8") double tokenRefreshAt,
    @Bindable(defaultValue = "400ms") Duration pricingTimeout,
    @Bindable(defaultValue = "400ms") Duration stockTimeout,
    @Bindable(defaultValue = "150ms") Duration fxTimeout,
    @Bindable(defaultValue = "3") int pricingAttempts,
    @Bindable(defaultValue = "2") int stockAttempts,
    @Bindable(defaultValue = "1") int fxAttempts,
    @Bindable(defaultValue = "50ms") Duration retryBase,
    @Bindable(defaultValue = "400ms") Duration retryCap,
    @Bindable(defaultValue = "DECORRELATED") RetryPolicy.Jitter jitter,
    @Bindable(defaultValue = "0.1") double retryBudgetRatio,
    @Bindable(defaultValue = "10") long retryBudgetAllowance,
    @Bindable(defaultValue = "50") int pricingBulkhead,
    @Bindable(defaultValue = "50") int stockBulkhead,
    @Bindable(defaultValue = "20") int fxBulkhead,
    @Bindable(defaultValue = "50") int breakerWindow,
    @Bindable(defaultValue = "20") int breakerMinimumCalls,
    @Bindable(defaultValue = "50") float breakerFailureRate,
    @Bindable(defaultValue = "10s") Duration breakerOpenFor) {
  /** Which fan-out adapter runs the blocking path. */
  public enum FanOutMode {
    STRUCTURED,
    FUTURE
  }
}
