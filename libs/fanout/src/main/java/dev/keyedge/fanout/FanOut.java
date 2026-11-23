package dev.keyedge.fanout;

/**
 * Runs three calls concurrently under one deadline. Contract (FanOutContract): one result per call;
 * returns by the deadline (late calls → {@link CallResult.TimedOut}); a required call that does not
 * succeed cancels the others ({@link CallResult.Skipped}); an optional failure cancels nothing; the
 * caller's request context is visible inside the calls.
 */
public interface FanOut {
  <A, B, C> Tri<A, B, C> all(Call<A> a, Call<B> b, Call<C> c, Deadline deadline);
}
