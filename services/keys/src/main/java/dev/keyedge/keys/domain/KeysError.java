package dev.keyedge.keys.domain;

/** Expected failures of key use cases: values the API maps to problems, not exceptions. */
public sealed interface KeysError {
  /** No such key for this tenant (a key of another tenant is also "not found"). */
  record NotFound() implements KeysError {}

  /** Someone changed the key meanwhile (optimistic lock lost twice). */
  record Conflict() implements KeysError {}

  /** The operation is not valid in the key's current state or input. */
  record Invalid(String reason) implements KeysError {}
}
