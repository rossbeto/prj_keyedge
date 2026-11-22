package dev.keyedge.keys.adapters;

import dev.keyedge.awstarget.TargetKind;

class FlociKeyStoreTest extends DynamoKeyStoreContract {
  @Override
  public TargetKind kind() {
    return TargetKind.FLOCI;
  }
}
