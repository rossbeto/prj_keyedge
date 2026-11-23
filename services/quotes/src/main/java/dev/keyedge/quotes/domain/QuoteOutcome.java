package dev.keyedge.quotes.domain;

/** A quote, or why there is none. */
public sealed interface QuoteOutcome {
  record Priced(Quote quote) implements QuoteOutcome {}

  /** A required upstream did not answer. */
  record Unavailable(String upstream, Reason reason) implements QuoteOutcome {}

  /** Why a required upstream gave no answer. */
  enum Reason {
    FAILED,
    TIMED_OUT,
    CANCELLED
  }
}
