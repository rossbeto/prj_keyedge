package dev.keyedge.edge.domain;

/** What the sidecar does with a cached verdict when the control plane cannot answer (D-27). */
public enum FailurePolicy {
  /** Keep admitting keys whose last verdict was Valid, up to a stale window past its expiry. */
  FAIL_STATIC,
  /** Admit nothing that cannot be verified now (expired verdicts are never served). */
  FAIL_CLOSED
}
