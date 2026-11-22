package dev.keyedge.testkit.fixtures.awsclients.adapters;

import software.amazon.awssdk.services.sts.StsClient;

/** Breaks awsClientsOnlyInWiring: an adapter building its own client bypasses the target switch. */
public final class BuildsClient {
  public Object client() {
    return StsClient.builder();
  }
}
