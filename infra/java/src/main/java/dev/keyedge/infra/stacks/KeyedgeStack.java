package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.Validations;
import software.constructs.Construct;

/**
 * Base of every keyedge stack: environment from the config, the chosen synthesizer, tags, and the
 * cdk-nag acknowledgments of this mode — each with the reason written in the spec (none in code).
 * cdk-nag 3 runs as a CDK policy-validation plugin; acknowledgments use CDK's own {@code
 * Validations}.
 */
public abstract class KeyedgeStack extends Stack {
  protected final InfraConfig cfg;

  protected KeyedgeStack(
      Construct scope, String id, InfraConfig cfg, IStackSynthesizer synthesizer, String what) {
    super(
        scope,
        id,
        StackProps.builder()
            .env(Environment.builder().account(cfg.account()).region(cfg.region()).build())
            .synthesizer(synthesizer)
            .description("keyedge " + cfg.mode().key() + ": " + what)
            .build());
    this.cfg = cfg;
    Tags.of(this).add("project", "keyedge");
    Tags.of(this).add("env", cfg.env());
  }

  /**
   * Acknowledges one rule on one construct with the spec's reason. Granular findings (e.g. IAM5 per
   * resource) are not covered by a stack-wide acknowledgment: they need the construct.
   */
  protected void acknowledgeOn(software.constructs.IConstruct construct, String rule) {
    String reason = cfg.spec().map("nag." + cfg.mode().key()).get(rule);
    if (reason == null) {
      throw new IllegalStateException(
          "no reason for " + rule + " in nag." + cfg.mode().key() + " of the spec");
    }
    Validations.of(construct)
        .acknowledge(Acknowledgment.builder().id("AwsSolutions::" + rule).reason(reason).build());
  }

  /** Acknowledges one granular finding (e.g. IAM5 for one resource) with the rule's spec reason. */
  protected void acknowledgeGranular(
      software.constructs.IConstruct construct, String rule, String finding) {
    String reason = cfg.spec().map("nag." + cfg.mode().key()).get(rule);
    if (reason == null) {
      throw new IllegalStateException(
          "no reason for " + rule + " in nag." + cfg.mode().key() + " of the spec");
    }
    Validations.of(construct)
        .acknowledge(
            Acknowledgment.builder()
                .id("AwsSolutions::" + rule + "[" + finding + "]")
                .reason(reason)
                .build());
  }

  /** Acknowledges this mode's rules from the spec, stack-wide (called once the resources exist). */
  protected void suppressFromSpec() {
    cfg.spec()
        .map("nag." + cfg.mode().key())
        .forEach((rule, reason) ->
            Validations.of(this)
                .acknowledge(
                    Acknowledgment.builder().id("AwsSolutions::" + rule).reason(reason).build()));
  }
}
