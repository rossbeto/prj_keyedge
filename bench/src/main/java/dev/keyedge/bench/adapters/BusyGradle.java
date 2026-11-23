package dev.keyedge.bench.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gradle daemons that are busy right now (CPU time grows over a short window). An idle daemon is
 * harmless; a build sharing the CPUs skews a bench (ioclab lesson). Reads /proc only; signals
 * nothing.
 */
final class BusyGradle {
  private BusyGradle() {}

  static List<String> find(Duration window) {
    Map<Long, Long> before = cpuTicks();
    try {
      Thread.sleep(window);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    Map<Long, Long> after = cpuTicks();
    List<String> busy = new ArrayList<>();
    after.forEach((pid, ticks) -> {
      Long b = before.get(pid);
      if (b != null && ticks - b > 20) { // > 0.2 s CPU in the window (100 ticks/s)
        busy.add(pid + " GradleDaemon");
      }
    });
    return busy;
  }

  private static Map<Long, Long> cpuTicks() {
    Map<Long, Long> out = new HashMap<>();
    try (var dirs = Files.list(Path.of("/proc"))) {
      for (Path p : dirs.filter(d -> d.getFileName().toString().matches("\\d+")).toList()) {
        try {
          String cmd = new String(Files.readAllBytes(p.resolve("cmdline")), StandardCharsets.UTF_8);
          if (!cmd.contains("GradleDaemon")) {
            continue;
          }
          String stat = Files.readString(p.resolve("stat"));
          String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ", -1);
          out.put(
              Long.parseLong(p.getFileName().toString()),
              Long.parseLong(f[11]) + Long.parseLong(f[12]));
        } catch (IOException | RuntimeException e) {
          // the process ended meanwhile
        }
      }
    } catch (IOException e) {
      return out;
    }
    return out;
  }
}
