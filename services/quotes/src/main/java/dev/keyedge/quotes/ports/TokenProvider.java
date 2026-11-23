package dev.keyedge.quotes.ports;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import java.util.concurrent.CompletableFuture;

/** An OAuth access token for pricing: shared, refreshed ahead of expiry, fetched once at a time. */
public interface TokenProvider {
  String token(Deadline deadline) throws CallException, InterruptedException;

  /** The same, for non-blocking callers. */
  CompletableFuture<String> tokenAsync();

  /** The upstream rejected this token (401): drop it so the next call fetches a new one. */
  void invalidate(String token);
}
