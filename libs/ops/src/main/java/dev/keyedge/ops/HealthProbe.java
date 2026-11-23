package dev.keyedge.ops;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Container health check for distroless images (no shell, no curl): {@code java -cp /app/lib/*
 * dev.keyedge.ops.HealthProbe http://127.0.0.1:15100/health}. Exit 0 on HTTP 200, 1 otherwise.
 */
public final class HealthProbe {
  private HealthProbe() {}

  public static void main(String[] args) {
    System.exit(check(args.length > 0 ? args[0] : "http://127.0.0.1:8080/health") ? 0 : 1);
  }

  /**
   * For native images (no JVM to run {@link #main}): {@code <binary> --health-check <url>} probes
   * and exits; any other arguments return normally so the application starts.
   */
  public static void exitIfRequested(String[] args) {
    if (args.length == 2 && args[0].equals("--health-check")) {
      System.exit(check(args[1]) ? 0 : 1);
    }
  }

  static boolean check(String url) {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException | IllegalArgumentException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
