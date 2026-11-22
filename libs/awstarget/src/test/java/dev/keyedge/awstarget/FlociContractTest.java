package dev.keyedge.awstarget;

/** The AWS contract suite on Floci. */
class FlociContractTest extends AwsContractSuite {
  @Override
  public TargetKind kind() {
    return TargetKind.FLOCI;
  }
}
