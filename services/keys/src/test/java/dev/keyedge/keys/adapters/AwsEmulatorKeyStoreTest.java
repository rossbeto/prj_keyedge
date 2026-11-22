package dev.keyedge.keys.adapters;

import dev.keyedge.awstarget.TargetKind;

class AwsEmulatorKeyStoreTest extends DynamoKeyStoreContract {
  @Override
  public TargetKind kind() {
    return TargetKind.AWS_EMULATOR;
  }
}
