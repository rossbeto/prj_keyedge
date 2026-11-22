package dev.keyedge.problem;

import java.net.URI;

/**
 * Every problem type keyedge returns. The slugs are a public contract (clients switch on them),
 * pinned by a snapshot test. Type URIs are URNs: RFC 9457 does not require them to resolve, and a
 * URN does not depend on owning a domain.
 */
public enum ProblemType {
  UNAUTHENTICATED("unauthenticated", "Authentication required", 401),
  MALFORMED_KEY("malformed-key", "The API key is malformed", 401),
  UNKNOWN_KEY("unknown-key", "The API key is not valid", 401),
  REVOKED_KEY("revoked-key", "The API key was revoked", 401),
  EXPIRED_KEY("expired-key", "The API key has expired", 401),
  MISSING_SCOPE("missing-scope", "The API key lacks a required scope", 403),
  RATE_LIMITED("rate-limited", "Too many requests for this key", 429),
  OVERLOADED("overloaded", "The service is overloaded", 503),
  IDEMPOTENCY_CONFLICT(
      "idempotency-conflict", "A request with this Idempotency-Key is in flight", 409),
  IDEMPOTENCY_MISMATCH(
      "idempotency-mismatch", "This Idempotency-Key was used with a different request", 422),
  VALIDATION("validation", "The request is invalid", 400),
  NOT_FOUND("not-found", "Not found", 404),
  PRECONDITION_FAILED("precondition-failed", "The resource changed", 412),
  CONFLICT("conflict", "The resource was changed concurrently; retry", 409),
  UPSTREAM_UNAVAILABLE("upstream-unavailable", "A dependency is unavailable", 503),
  INTERNAL("internal", "Internal error", 500);

  /** Prefix of every type URI. */
  public static final String URN_PREFIX = "urn:keyedge:problem:";

  private final String slug;
  private final String title;
  private final int status;

  ProblemType(String slug, String title, int status) {
    this.slug = slug;
    this.title = title;
    this.status = status;
  }

  public String slug() {
    return slug;
  }

  public String title() {
    return title;
  }

  public int status() {
    return status;
  }

  public URI uri() {
    return URI.create(URN_PREFIX + slug);
  }
}
