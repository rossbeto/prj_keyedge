package dev.keyedge.keys.adapters;

import dev.keyedge.awstarget.TargetKind;

class AwsEmulatorSecretsAndUsageTest extends SecretsAndUsageContract {
  @Override
  public TargetKind kind() {
    return TargetKind.AWS_EMULATOR;
  }
}
