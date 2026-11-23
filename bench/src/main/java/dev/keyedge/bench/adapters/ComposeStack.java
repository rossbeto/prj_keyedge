package dev.keyedge.bench.adapters;

import dev.keyedge.bench.app.Auth;
import dev.keyedge.bench.app.StackControl;
import dev.keyedge.bench.domain.Scenario;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The compose stack of `make up`. Variants recreate edge + quotes (compose may keep old containers:
 * always --force-recreate) with the variant's flavor and env, then apply CPU/memory limits and the
 * CPU pinning to the edge with {@code docker update}. CPU time is read from the containers' cgroup
 * ({@code cpu.stat usage_usec}, cgroup v2).
 */
public final class ComposeStack implements StackControl {
  private static final String EDGE = "keyedge-edge-1";
  private static final String QUOTES = "keyedge-quotes-1";
  private final Map<String, String> containerEnv;
  private final String sutCpuset;
  private final String supportCpuset;
  private final String awsTarget;
  private final HttpClient http = HttpClient.newHttpClient();

  /**
   * @param containerEnv what `make container-env` exports for the target (endpoint, uid, ...)
   * @param sutCpuset CPUs for edge + quotes ("" = no pinning)
   * @param supportCpuset CPUs for the simulator, Toxiproxy, keys and issuer ("" = no pinning)
   */
  public ComposeStack(
      Map<String, String> containerEnv, String sutCpuset, String supportCpuset, String awsTarget) {
    this.containerEnv = Map.copyOf(containerEnv);
    this.sutCpuset = sutCpuset;
    this.supportCpuset = supportCpuset;
    this.awsTarget = awsTarget;
  }

  @Override
  public void prepare(Scenario.Variant v) {
    List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", "deploy/compose.yaml"));
    if (v.flavor().equals("native")) {
      cmd.addAll(List.of("-f", "deploy/compose.native.yaml"));
    }
    cmd.addAll(List.of("up", "-d", "--wait", "--force-recreate", "edge", "quotes"));
    Map<String, String> env = new HashMap<>(containerEnv);
    env.putAll(v.env());
    Proc.run(cmd, env, Duration.ofMinutes(5));
    List<String> update = new ArrayList<>(List.of("docker", "update"));
    if (v.cpus() != null) {
      update.addAll(List.of("--cpus", String.valueOf(v.cpus())));
    }
    if (v.memory() != null) {
      update.addAll(List.of("--memory", v.memory(), "--memory-swap", v.memory()));
    }
    if (!sutCpuset.isBlank()) {
      update.addAll(List.of("--cpuset-cpus", sutCpuset));
    }
    if (update.size() > 2) {
      update.add(EDGE);
      Proc.run(update, Map.of(), Duration.ofMinutes(1));
      if (!sutCpuset.isBlank()) {
        Proc.run("docker", "update", "--cpuset-cpus", sutCpuset, QUOTES);
      }
    }
    if (!supportCpuset.isBlank()) { // upstream-sim, Toxiproxy, keys, issuer off the SUT's CPUs
      Proc.run(
          "docker",
          "update",
          "--cpuset-cpus",
          supportCpuset,
          "keyedge-upstream-sim-1",
          "keyedge-toxiproxy-1",
          "keyedge-keys-1",
          "keyedge-issuer-1");
    }
    waitHealthy();
  }

  private void waitHealthy() {
    for (int i = 0; i < 60; i++) {
      String s = Proc.run("docker", "inspect", "-f", "{{.State.Health.Status}}", EDGE);
      if (s.equals("healthy")) {
        return;
      }
      sleep(Duration.ofSeconds(1));
    }
    throw new IllegalStateException("edge not healthy after the variant was applied");
  }

  @Override
  public void simulate(String simScenario) {
    if (simScenario.startsWith("file:")) { // a scenario of the bench itself, sent as the YAML body
      try {
        String yaml = Files.readString(Path.of(simScenario.substring("file:".length())));
        send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:15201/__chaos"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(yaml)));
      } catch (IOException e) {
        throw new IllegalStateException("cannot read " + simScenario, e);
      }
      return;
    }
    send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:15201/__chaos?name=" + simScenario))
            .PUT(HttpRequest.BodyPublishers.noBody()));
  }

  @Override
  public Auth credentials(int maxRate) {
    String token =
        post(
                "http://127.0.0.1:15110/token",
                "{\"tenant\":\"bench\",\"scopes\":[\"keys:write\"]}",
                Map.of())
            .path("accessToken")
            .asString();
    int n = Math.max(2, (int) Math.ceil(maxRate / 40.0) + 1); // default plan: 50 req/s per key
    List<String> keys = new ArrayList<>();
    String keyId = "";
    for (int i = 0; i < n; i++) {
      JsonNode created =
          post(
              "http://127.0.0.1:15100/v1/keys",
              "{\"name\":\"bench-" + i + "\",\"scopes\":[\"quotes:read\"]}",
              Map.of(
                  "Authorization",
                  "Bearer " + token,
                  "Idempotency-Key",
                  "bench-" + UUID.randomUUID()));
      keys.add(created.path("key").asString());
      keyId = created.path("keyId").asString(created.path("id").asString(""));
    }
    String principal =
        Proc.run(
            List.of(
                "bash",
                "-c",
                ". <(scripts/aws-env.sh \"$T\") && aws secretsmanager get-secret-value --secret-id keyedge/principal"
                    + " --query SecretString --output text"),
            Map.of("T", awsTarget),
            Duration.ofMinutes(1));
    String key = Json.JSON.readTree(principal).path("key").asString();
    return new Auth(keys, "bench|" + keyId + "|quotes:read", key);
  }

  @Override
  public long sutCpuMicros() {
    long total = 0;
    for (String c : List.of(EDGE, QUOTES)) {
      String id = Proc.run("docker", "inspect", "-f", "{{.Id}}", c);
      Path stat = Path.of("/sys/fs/cgroup/system.slice/docker-" + id + ".scope/cpu.stat");
      try {
        for (String line : Files.readAllLines(stat)) {
          if (line.startsWith("usage_usec ")) {
            total += Long.parseLong(line.substring("usage_usec ".length()).strip());
          }
        }
      } catch (IOException e) {
        return -1; // not readable here: cpuPerReqMs stays empty
      }
    }
    return total;
  }

  @Override
  public List<String> competingBuilds() {
    return BusyGradle.find(Duration.ofSeconds(1));
  }

  private JsonNode post(String url, String body, Map<String, String> headers) {
    var b =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    return Json.JSON.readTree(send(b));
  }

  private String send(HttpRequest.Builder b) {
    try {
      var r =
          http.send(
              b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() >= 300) {
        throw new IllegalStateException("HTTP " + r.statusCode() + " from " + r.uri().getPath());
      }
      return r.body();
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
