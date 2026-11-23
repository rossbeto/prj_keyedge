package dev.keyedge.observe;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Experiment scoped-vs-threadlocal (Phase 011): why the log bridge reads a ScopedValue instead of
 * an MDC/ThreadLocal. 200 "requests" on a pool of 2 platform threads; each sets the tenant, does
 * its work, and (like real code under an exception) sometimes forgets to clear it. A later request
 * that does not set a tenant then sees someone else's. A ScopedValue binding cannot outlive its
 * scope.
 */
@Tag("experiment")
class ScopedVsThreadLocalExperiment {
  private static final ThreadLocal<String> TL_TENANT = new ThreadLocal<>();
  private static final ScopedValue<String> SV_TENANT = ScopedValue.newInstance();

  /** Every 10th request is "anonymous" (sets no tenant); every 3rd forgets to clean up. */
  private static int leaks(ExecutorService pool, boolean scoped) throws Exception {
    var leaked = new AtomicInteger();
    for (int i = 0; i < 200; i++) {
      int n = i;
      Callable<Void> request =
          () -> {
            if (n % 10 == 0) { // anonymous request: must see no tenant
              String seen =
                  scoped ? (SV_TENANT.isBound() ? SV_TENANT.get() : null) : TL_TENANT.get();
              if (seen != null) {
                leaked.incrementAndGet();
              }
              return null;
            }
            String tenant = "tenant-" + n;
            if (scoped) {
              ScopedValue.where(SV_TENANT, tenant).run(() -> {});
            } else {
              TL_TENANT.set(tenant);
              if (n % 3 != 0) {
                TL_TENANT.remove(); // the "finally" that is sometimes missing
              }
            }
            return null;
          };
      pool.submit(request).get();
    }
    return leaked.get();
  }

  @Test
  void scopedValueCannotLeakAcrossPooledRequests() throws Exception {
    int tl;
    int sv;
    try (var pool = Executors.newFixedThreadPool(2)) {
      tl = leaks(pool, false);
    }
    try (var pool = Executors.newFixedThreadPool(2)) {
      sv = leaks(pool, true);
    }
    boolean confirmed = tl > 0 && sv == 0;
    String json =
        "{\"experiment\":\"scoped-vs-threadlocal\",\"hypothesis\":\"a ThreadLocal tenant left behind by one"
            + " request is seen by a later request on the same pooled thread; a ScopedValue binding cannot"
            + " outlive its scope\",\"setup\":\"200 requests on 2 platform threads; every 10th anonymous, every"
            + " 3rd forgets ThreadLocal.remove()\",\"anonymous_requests\":20,\"threadlocal_leaks\":"
            + tl
            + ",\"scopedvalue_leaks\":"
            + sv
            + ",\"verdict\":\""
            + (confirmed ? "confirmed" : "refuted")
            + "\",\"recorded_at\":\""
            + Instant.now()
            + "\"}\n";
    Path out = Path.of("../../results/labs/scoped-vs-threadlocal.json");
    Files.writeString(out, json);
    assertThat(confirmed).as(json).isTrue();
  }
}
