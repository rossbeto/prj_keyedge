package dev.keyedge.awstarget;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.apache.hc.client5.http.DnsResolver;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;

/**
 * The only way keyedge builds AWS clients (ArchUnit-enforced): endpoint, region, credentials and an
 * HTTP client that trusts the target's CA and, if asked, connects to another host than the endpoint
 * names.
 */
public final class AwsTargetConfigurer {
  private AwsTargetConfigurer() {}

  /** Configures any synchronous SDK v2 client builder for the target. */
  public static <B extends AwsClientBuilder<B, ?> & SdkSyncClientBuilder<B, ?>> B configure(
      B builder, AwsTarget target) {
    target.endpoint().ifPresent(builder::endpointOverride);
    builder.region(target.region()).credentialsProvider(target.credentials());
    builder.httpClient(httpClient(target));
    return builder;
  }

  /** The HTTP client for a target (one per SDK client; the SDK closes it with the client). */
  public static SdkHttpClient httpClient(AwsTarget target) {
    Apache5HttpClient.Builder http =
        Apache5HttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(5))
            .socketTimeout(Duration.ofSeconds(30))
            .maxConnections(50);
    target
        .trustStore()
        .ifPresent(store -> http.tlsTrustManagersProvider(() -> TrustStores.trustManagers(store)));
    target.connectHost().ifPresent(host -> http.dnsResolver(new FixedHostResolver(host)));
    return http.build();
  }

  /**
   * Resolves every name to one host. TLS still verifies the name from the endpoint URL, so this
   * only changes where the TCP connection goes (AWS Emulator reached from another container).
   */
  record FixedHostResolver(String host) implements DnsResolver {
    @Override
    public InetAddress[] resolve(String name) throws UnknownHostException {
      return InetAddress.getAllByName(host);
    }

    @Override
    public String resolveCanonicalHostname(String name) {
      return name;
    }
  }
}
