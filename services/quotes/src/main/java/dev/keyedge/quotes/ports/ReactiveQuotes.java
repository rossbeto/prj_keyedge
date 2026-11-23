package dev.keyedge.quotes.ports;

import dev.keyedge.quotes.domain.QuoteOutcome;
import dev.keyedge.quotes.domain.QuoteRequest;
import java.util.concurrent.CompletionStage;

/**
 * The Reactor twin of {@code GetQuote} (D-11): same domain, a reactive flow behind a JDK type so
 * the port stays framework-free. Cancelling the stage cancels the flow.
 */
public interface ReactiveQuotes {
  CompletionStage<QuoteOutcome> quote(QuoteRequest request, RequestContext ctx);
}
