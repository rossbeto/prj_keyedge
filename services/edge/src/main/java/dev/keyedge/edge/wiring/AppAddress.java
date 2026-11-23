package dev.keyedge.edge.wiring;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Where the sidecar forwards to. A plain URL ({@code http://quotes:15081}, compose) is used as is.
 * {@code ecs:<container>:<port>} means "that container of this task" and is resolved from the ECS
 * task metadata ({@code ECS_CONTAINER_METADATA_URI_V4/task}):
 *
 * <ul>
 *   <li>if the container's Docker id (12-char short form) resolves as a host name, the task's
 *       containers are Docker siblings on a user-defined network (Floci, parity P-04): that name;
 *   <li>otherwise they share one network namespace (Fargate {@code awsvpc}): {@code 127.0.0.1}.
 * </ul>
 *
 * The metadata's IPv4 address cannot decide this: like AWS, Floci reports the one task address for
 * every container, which on Floci belongs to the first container only (ADR 0018). Resolved lazily
 * on the first request and cached; a failure is retried on the next request (on AWS the app starts
 * only after the edge is healthy, so the edge must not wait for it at start-up).
 */
final class AppAddress implements Supplier<URI> {
  private static final int SHORT_ID = 12;
  private final String configured;
  private final @Nullable String metadataUri;
  private final HttpClient http;
  private final JsonMapper json;
  private final Predicate<String> resolvable;
  private volatile @Nullable URI resolved;

  AppAddress(String configured, @Nullable String metadataUri, HttpClient http, JsonMapper json) {
    this(configured, metadataUri, http, json, AppAddress::resolves);
  }

  AppAddress(
      String configured,
      @Nullable String metadataUri,
      HttpClient http,
      JsonMapper json,
      Predicate<String> resolvable) {
    this.configured = configured;
    this.metadataUri = metadataUri;
    this.http = http;
    this.json = json;
    this.resolvable = resolvable;
    if (!configured.startsWith("ecs:")) {
      resolved = URI.create(configured);
    }
  }

  @Override
  public URI get() {
    URI r = resolved;
    if (r != null) {
      return r;
    }
    String[] parts = configured.split(":", 3); // ecs:<container>:<port>
    if (parts.length != 3 || metadataUri == null) {
      throw new IllegalStateException(
          "app URL " + configured + " needs ECS_CONTAINER_METADATA_URI_V4");
    }
    String dockerId = dockerId(parts[1]);
    String shortId = dockerId.substring(0, Math.min(SHORT_ID, dockerId.length()));
    String host = resolvable.test(shortId) ? shortId : "127.0.0.1";
    r = URI.create("http://" + host + ":" + parts[2]);
    resolved = r;
    return r;
  }

  private static boolean resolves(String host) {
    try {
      InetAddress.getByName(host);
      return true;
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private String dockerId(String container) {
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(metadataUri + "/task"))
                  .timeout(Duration.ofSeconds(2))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      Map<String, Object> task =
          json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
      if (task != null && task.get("Containers") instanceof List<?> containers) {
        for (Object c : containers) {
          if (c instanceof Map<?, ?> m
              && container.equals(m.get("Name"))
              && m.get("DockerId") instanceof String id
              && !id.isBlank()) {
            return id;
          }
        }
      }
      throw new IllegalStateException("container " + container + " not (yet) in the task metadata");
    } catch (IOException e) {
      throw new IllegalStateException("task metadata unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
