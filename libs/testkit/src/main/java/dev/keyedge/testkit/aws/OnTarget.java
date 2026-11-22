package dev.keyedge.testkit.aws;

import dev.keyedge.awstarget.TargetKind;

/** Implemented by contract test classes so conditions know which emulator a class runs against. */
public interface OnTarget {
  TargetKind kind();
}
