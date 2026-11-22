package dev.keyedge.testkit.fixtures.awsclients.wiring;

import software.amazon.awssdk.services.sts.StsClient;

/** Allowed: composition roots build clients (through AwsTargetConfigurer in real code). */
public final class WiresClient {
  public Object client() {
    return StsClient.builder();
  }
}
