package dev.keyedge.quotes.ports;

import java.util.Map;

/**
 * Observes each upstream HTTP call (Phase 011): a client span whose context travels in the request
 * headers (W3C {@code traceparent}), a call counter by outcome, a JFR event. The adapters stay free
 * of telemetry libraries; this port is implemented in {@code adapters.observe}.
 */
public interface UpstreamTelemetry {
  /** The current request's trace context, captured on the thread that has it. */
  Parent capture();

  /** Starts a call under {@code parent}; send {@link Call#headers()}, then {@link Call#end}. */
  Call start(String upstream, Parent parent);

  /** Opaque captured context. */
  interface Parent {}

  /** One upstream call in progress. */
  interface Call {
    Map<String, String> headers();

    /**
     * {@code ok}, {@code status_4xx}, {@code status_5xx}, {@code timeout}, {@code io} or {@code
     * cancelled}.
     */
    void end(String outcome);
  }

  static UpstreamTelemetry none() {
    Parent parent = new Parent() {};
    Call call =
        new Call() {
          @Override
          public Map<String, String> headers() {
            return Map.of();
          }

          @Override
          public void end(String outcome) {}
        };
    return new UpstreamTelemetry() {
      @Override
      public Parent capture() {
        return parent;
      }

      @Override
      public Call start(String upstream, Parent p) {
        return call;
      }
    };
  }

  /** Outcome label for an HTTP status. */
  static String outcome(int status) {
    return status / 100 == 2 ? "ok" : status >= 500 ? "status_5xx" : "status_4xx";
  }
}
