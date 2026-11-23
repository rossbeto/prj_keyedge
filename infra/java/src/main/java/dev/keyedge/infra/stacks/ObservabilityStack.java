package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.SeverityAlarms;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.services.cloudwatch.MathExpression;
import software.amazon.awscdk.services.cloudwatch.MathExpressionProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HttpCodeElb;
import software.amazon.awscdk.services.elasticloadbalancingv2.HttpCodeTarget;
import software.constructs.Construct;

/**
 * Severity topics and the first alarms (Phase 011's runbook §1): 5xx percentage at the ALB and from
 * the edge sidecars. Thresholds from the spec.
 */
public final class ObservabilityStack extends KeyedgeStack {
  public ObservabilityStack(
      Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth, EdgeLbStack lb) {
    super(scope, id, cfg, synth, "alarms");
    var s = cfg.spec();
    var alarms =
        new SeverityAlarms(this, "Severity", "keyedge-" + cfg.env(), s.list("names.alarmTopics"));
    var metrics = lb.alb().getMetrics();
    var edge5xx =
        new MathExpression(
            MathExpressionProps.builder()
                .expression("100 * (elb + target) / MAX([requests, 1])")
                .usingMetrics(
                    new java.util.TreeMap<>(
                        java.util.Map.of(
                            "elb", metrics.httpCodeElb(HttpCodeElb.ELB_5XX_COUNT),
                            "target", metrics.httpCodeTarget(HttpCodeTarget.TARGET_5XX_COUNT),
                            "requests", metrics.requestCount())))
                .period(Duration.minutes(1))
                .label("5xx %")
                .build());
    alarms.alarm(
        "Edge5xx",
        "critical",
        edge5xx,
        s.num("alarms.edge5xxPercent"),
        s.num("alarms.evaluationPeriods"),
        "edge 5xx above " + s.num("alarms.edge5xxPercent") + " % (runbook §1)");
    alarms.alarm(
        "EdgeLatency",
        "high",
        metrics
            .targetResponseTime()
            .with(
                software.amazon.awscdk.services.cloudwatch.MetricOptions.builder()
                    .statistic("p99")
                    .period(Duration.minutes(1))
                    .build()),
        0.5,
        s.num("alarms.evaluationPeriods"),
        "edge p99 above 500 ms");
    suppressFromSpec();
  }
}
