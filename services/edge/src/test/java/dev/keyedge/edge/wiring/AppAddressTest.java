package dev.keyedge.edge.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sidecar's app address: plain URL as is; {@code ecs:<container>:<port>} via task metadata. */
class AppAddressTest {
  private static final String TASK =
      """
      {"Cluster":"keyedge-local","Containers":[
        {"Name":"edge","DockerId":"03b2a13f2be809ed01e5cfcb50043d10",
         "Networks":[{"NetworkMode":"awsvpc","IPv4Addresses":["10.40.0.59"]}]},
        {"Name":"quotes","DockerId":"24892d9328cb0f70cda6b009edf7684a",
         "Networks":[{"NetworkMode":"awsvpc","IPv4Addresses":["10.40.0.59"]}]}]}
      """;

  private final JsonMapper json = JsonMapper.createDefault();
  private final HttpClient http = HttpClient.newHttpClient();
  private final AtomicInteger calls = new AtomicInteger();
  private HttpServer metadata;
  private String metadataUri;

  @BeforeEach
  void start() throws Exception {
    metadata = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    metadata.createContext(
        "/v4/abc/task",
        ex -> {
          calls.incrementAndGet();
          byte[] body = TASK.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });
    metadata.start();
    metadataUri = "http://127.0.0.1:" + metadata.getAddress().getPort() + "/v4/abc";
  }

  @AfterEach
  void stop() {
    metadata.stop(0);
  }

  @Test
  void aPlainUrlIsUsedAsIsWithoutMetadata() {
    var address = new AppAddress("http://127.0.0.1:15081", null, http, json);
    assertThat(address.get()).isEqualTo(URI.create("http://127.0.0.1:15081"));
  }

  @Test
  void dockerSiblingsTheShortDockerIdResolvesSoItIsUsedOnceAndCached() {
    var address =
        new AppAddress("ecs:quotes:15081", metadataUri, http, json, "24892d9328cb"::equals);
    assertThat(address.get()).isEqualTo(URI.create("http://24892d9328cb:15081"));
    assertThat(address.get()).isEqualTo(URI.create("http://24892d9328cb:15081"));
    assertThat(calls).hasValue(1);
  }

  @Test
  void sharedNamespaceTheDockerIdDoesNotResolveSoLoopbackNotTheTaskAddress() {
    var address = new AppAddress("ecs:quotes:15081", metadataUri, http, json, host -> false);
    assertThat(address.get()).isEqualTo(URI.create("http://127.0.0.1:15081"));
  }

  @Test
  void anUnknownContainerFailsAndIsRetriedOnTheNextRequest() {
    var address = new AppAddress("ecs:pricing:15081", metadataUri, http, json, host -> true);
    assertThatThrownBy(address::get).hasMessageContaining("pricing");
    assertThatThrownBy(address::get).hasMessageContaining("pricing");
    assertThat(calls).hasValue(2);
  }

  @Test
  void anEcsAddressWithoutTheMetadataVariableFailsClearly() {
    var address = new AppAddress("ecs:quotes:15081", null, http, json);
    assertThatThrownBy(address::get).hasMessageContaining("ECS_CONTAINER_METADATA_URI_V4");
  }
}
