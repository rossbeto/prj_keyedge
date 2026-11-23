package dev.keyedge.lab.pinning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.consumer.RecordingStream;

/**
 * DELIBERATELY WRONG CODE (lab pitfall): blocking while holding a monitor. 1 000 virtual threads,
 * each with its own lock, each {@code synchronized (lock) { sleep 50 ms }} on 4 carrier threads.
 * On Java 21 the virtual thread cannot unmount inside {@code synchronized}: the carrier is pinned,
 * only 4 sleeps run at a time. On Java 25 (JEP 491) monitors no longer pin. Counts
 * {@code jdk.VirtualThreadPinned} events in-process and writes one JSON object.
 */
public final class PinningExperiment {
  private static final int TASKS = 1_000;
  private static final long SLEEP_MS = 50;

  public static void main(String[] args) throws Exception {
    var pinned = new AtomicInteger();
    long wallNanos;
    try (var rs = new RecordingStream()) {
      rs.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO);
      rs.onEvent("jdk.VirtualThreadPinned", e -> pinned.incrementAndGet());
      rs.startAsync();
      wallNanos = run();
      Thread.sleep(1_500); // let the stream deliver the last events
    }
    double seconds = wallNanos / 1e9;
    String json =
        String.format(
            java.util.Locale.ROOT,
            "{\"java\":\"%s\",\"tasks\":%d,\"sleep_ms\":%d,\"carriers\":4,\"wall_s\":%.3f,"
                + "\"throughput_tasks_per_s\":%.1f,\"pinned_events\":%d}",
            System.getProperty("java.version"),
            TASKS,
            SLEEP_MS,
            seconds,
            TASKS / seconds,
            pinned.get());
    System.out.println(json);
    Path out = Path.of(args[0]);
    Files.createDirectories(out.getParent());
    Files.writeString(out, json + "\n");
  }

  private static long run() throws Exception {
    List<Object> locks = new ArrayList<>();
    for (int i = 0; i < TASKS; i++) {
      locks.add(new Object());
    }
    long start = System.nanoTime();
    try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Object lock : locks) {
        vt.submit(() -> {
          synchronized (lock) { // the pitfall: blocking inside a monitor
            Thread.sleep(SLEEP_MS);
          }
          return null;
        });
      }
    }
    return System.nanoTime() - start;
  }
}
