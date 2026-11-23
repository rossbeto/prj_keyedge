package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.DrainingTargetGroup;
import java.util.List;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerCertificate;
import software.constructs.Construct;

/**
 * The ALB in front of the edge sidecars. Local: internal, HTTP 80. Prodlike: internet-facing, HTTPS
 * 443 with the certificate from the spec and an 80 → 443 redirect. Only the ALB may reach the edge.
 */
public final class EdgeLbStack extends KeyedgeStack {
  private final ApplicationLoadBalancer alb;

  public EdgeLbStack(
      Construct scope,
      String id,
      InfraConfig cfg,
      IStackSynthesizer synth,
      ClusterStack cluster,
      ServiceStack quotes) {
    super(scope, id, cfg, synth, "load balancer for the edge sidecars");
    var s = cfg.spec();
    int listenerPort = s.num("loadBalancer.listenerPort", cfg.mode());
    var albSg = albSecurityGroup(cluster, listenerPort);
    alb =
        ApplicationLoadBalancer.Builder.create(this, "Alb")
            .vpc(cluster.vpc())
            .internetFacing(cfg.prodlike())
            .securityGroup(albSg)
            .build();
    var tg =
        new DrainingTargetGroup(
                this,
                "Edge",
                new DrainingTargetGroup.Props(
                    cluster.vpc(),
                    s.num("service.edgePort"),
                    s.str("loadBalancer.healthCheckPath"),
                    s.num("loadBalancer.deregistrationDelaySeconds", cfg.mode())))
            .group();
    ApplicationListener listener = listener(tg, listenerPort);
    if (cfg.prodlike()) {
      redirectAndAccessLogs();
    }
    quotes.service().attachToApplicationTargetGroup(tg);
    quotes.allowFrom(albSg, s.num("service.edgePort"));
    listener.getNode(); // the listener is wired through the target group
    suppressFromSpec();
  }

  private SecurityGroup albSecurityGroup(ClusterStack cluster, int listenerPort) {
    var s = cfg.spec();
    var albSg =
        SecurityGroup.Builder.create(this, "AlbSg")
            .vpc(cluster.vpc())
            .description("edge ALB")
            .allowAllOutbound(true)
            .build();
    if (cfg.prodlike()) {
      albSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS from the internet");
      albSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "redirect to HTTPS");
    } else {
      albSg.addIngressRule(
          Peer.ipv4(s.str("cluster.vpcCidr")), Port.tcp(listenerPort), "inside the VPC");
    }
    return albSg;
  }

  private ApplicationListener listener(
      software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup tg,
      int listenerPort) {
    var s = cfg.spec();
    return cfg.prodlike()
        ? alb.addListener(
            "Https",
            software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps
                .builder()
                .port(443)
                .open(false) // ingress is the security group's explicit rules, not CDK's
                // 0.0.0.0/0
                .protocol(ApplicationProtocol.HTTPS)
                .certificates(
                    List.of(ListenerCertificate.fromArn(s.str("certificate.prodlikeArn"))))
                .defaultAction(ListenerAction.forward(List.of(tg)))
                .build())
        : alb.addListener(
            "Http",
            software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps
                .builder()
                .port(listenerPort)
                .open(false) // CDK's default opens the listener to 0.0.0.0/0 (found by a test)
                .protocol(ApplicationProtocol.HTTP)
                .defaultAction(ListenerAction.forward(List.of(tg)))
                .build());
  }

  /** Prodlike only: 80 → 443 redirect and access logs to an encrypted bucket. */
  private void redirectAndAccessLogs() {
    alb.addRedirect();
    var logs =
        software.amazon.awscdk.services.s3.Bucket.Builder.create(this, "AccessLogs")
            .encryption(software.amazon.awscdk.services.s3.BucketEncryption.S3_MANAGED)
            .blockPublicAccess(software.amazon.awscdk.services.s3.BlockPublicAccess.BLOCK_ALL)
            .enforceSsl(true)
            .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
            .build();
    alb.logAccessLogs(logs, "alb");
    acknowledgeOn(logs, "AwsSolutions-S1"); // the access-log bucket does not log itself
  }

  public ApplicationLoadBalancer alb() {
    return alb;
  }

  static LoadBalancerTargetOptions edgeTarget(int port) {
    return LoadBalancerTargetOptions.builder().containerName("edge").containerPort(port).build();
  }
}
