package dev.keyedge.keys.adapters;

import dev.keyedge.awstarget.TargetKind;

class FlociSecretsAndUsageTest extends SecretsAndUsageContract {
  @Override
  public TargetKind kind() {
    return TargetKind.FLOCI;
  }
}
