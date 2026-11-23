package dev.keyedge.quotes.ports;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.domain.Upstream;

/** Stock levels (idempotent GET) and reservations (non-idempotent POST: never retried). */
public interface StockClient {
  Upstream.Stock stock(Sku sku, Deadline deadline) throws CallException, InterruptedException;

  Upstream.Reservation reserve(Sku sku, Quantity qty, String requestId, Deadline deadline)
      throws CallException, InterruptedException;
}
