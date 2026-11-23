package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.SidecarTaskDefinition;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableAttributes;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.CloudMapOptions;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.DeploymentCircuitBreaker;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.HealthCheck;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

/**
 * One Fargate service: quotes with its edge sidecar, or keys alone. Deployment circuit breaker with
 * rollback, 100 % minimum healthy (no capacity dip during deploys), 200 % maximum. The task role
 * can read the secrets and (keys) use the table; the services find their names through the
 * contract.
 */
public final class ServiceStack extends KeyedgeStack {
  private final FargateService service;
  private final SecurityGroup securityGroup;

  /** What this service is. */
  public record Props(String name, boolean withSidecar, ClusterStack cluster) {}

  public ServiceStack(
      Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth, Props p) {
    super(scope, id, cfg, synth, p.name() + " service");
    var s = cfg.spec();
    // every container: where the contract is, which AWS target, bind on the task address
    Map<String, String> env = new java.util.TreeMap<>();
    env.put("KEYEDGE_CONTRACT_PATH", cfg.ssmPath());
    env.put("KEYEDGE_AWS_TARGET", cfg.prodlike() ? "aws" : "floci");
    env.put("MICRONAUT_SERVER_HOST", s.str("service.bindHost"));
    TaskDefinition task = p.withSidecar() ? sidecar(cfg, p, env) : single(cfg, p, env);
    // Imported by name (spec), not referenced across stacks: no exports between stacks that deploy
    // in different phases, and ARNs that read the same in every template and in both CDK apps.
    for (String name : List.of("peppers", "callers", "principal")) {
      Secret.fromSecretNameV2(this, "Secret-" + name, s.str("names.secrets." + name))
          .grantRead(task.getTaskRole());
    }
    if (!p.withSidecar()) {
      String tableName = s.str("names.table");
      ITable table =
          Table.fromTableAttributes(
              this,
              "Keys",
              TableAttributes.builder()
                  .tableName(tableName)
                  .globalIndexes(List.of(s.str("table.gsi1.name")))
                  .build());
      table.grantReadWriteData(task.getTaskRole()); // includes <table>/index/* for GSI1 queries
      acknowledgeGranular(
          task.getTaskRole(),
          "AwsSolutions-IAM5",
          "Resource::arn:<AWS::Partition>:dynamodb:"
              + cfg.region()
              + ":"
              + cfg.account()
              + ":table/"
              + tableName
              + "/index/*");
    }
    securityGroup =
        SecurityGroup.Builder.create(this, "Sg")
            .vpc(p.cluster().vpc())
            .description(p.name() + " tasks")
            .allowAllOutbound(true)
            .build();
    service =
        FargateService.Builder.create(this, "Service")
            .cluster(p.cluster().cluster())
            .serviceName(p.name())
            .taskDefinition(task)
            .desiredCount(s.num("service.desiredCount", cfg.mode()))
            .minHealthyPercent(100)
            .maxHealthyPercent(200)
            .circuitBreaker(DeploymentCircuitBreaker.builder().enable(true).rollback(true).build())
            .assignPublicIp(!cfg.prodlike()) // local: public subnets, no NAT
            .securityGroups(List.of(securityGroup))
            .cloudMapOptions(CloudMapOptions.builder().name(p.name()).build())
            .healthCheckGracePeriod(p.withSidecar() ? Duration.seconds(30) : null)
            .build();
    suppressFromSpec();
  }

  private TaskDefinition sidecar(InfraConfig cfg, Props p, Map<String, String> env) {
    var s = cfg.spec();
    // the edge: its app (task metadata) and the control plane; the app: its upstreams (ADR 0018)
    var edgeEnv = new java.util.TreeMap<>(env);
    edgeEnv.put("KEYEDGE_APP_URL", s.str("service.edgeAppUrl"));
    edgeEnv.put("KEYEDGE_KEYS_URL", s.str("service.verifyUrl"));
    var appEnv = new java.util.TreeMap<>(env);
    var upstreams = s.map("service.upstreams." + cfg.mode().key());
    appEnv.put("KEYEDGE_QUOTES_PRICING_URL", upstreams.get("pricing"));
    appEnv.put("KEYEDGE_QUOTES_STOCK_URL", upstreams.get("stock"));
    appEnv.put("KEYEDGE_QUOTES_FX_URL", upstreams.get("fx"));
    appEnv.put("KEYEDGE_QUOTES_TOKEN_URL", upstreams.get("token"));
    return new SidecarTaskDefinition(
            this,
            "Task",
            new SidecarTaskDefinition.Props(
                s.num("service.cpu"),
                s.num("service.memoryMiB"),
                s.str("images.edge"),
                s.str("images." + p.name()),
                p.name(),
                s.num("service.edgePort"),
                s.num("service.appPort"),
                s.num("service.edgeStopTimeoutSeconds"),
                s.num("service.appStopTimeoutSeconds"),
                s.str("service.user"),
                edgeEnv,
                appEnv))
        .task();
  }

  private TaskDefinition single(InfraConfig cfg, Props p, Map<String, String> env) {
    var s = cfg.spec();
    int port = s.num("service." + p.name() + "Port");
    var task =
        FargateTaskDefinition.Builder.create(this, "Task")
            .cpu(s.num("service.cpu"))
            .memoryLimitMiB(s.num("service.memoryMiB"))
            .build();
    task.addContainer(
        p.name(),
        ContainerDefinitionOptions.builder()
            .image(ContainerImage.fromRegistry(s.str("images." + p.name())))
            .readonlyRootFilesystem(true)
            .user(s.str("service.user"))
            .stopTimeout(Duration.seconds(s.num("service.appStopTimeoutSeconds")))
            .environment(env)
            .portMappings(List.of(PortMapping.builder().containerPort(port).build()))
            .healthCheck(
                HealthCheck.builder()
                    .command(
                        List.of(
                            "CMD",
                            "/app/application",
                            "--health-check",
                            "http://127.0.0.1:" + port + "/health"))
                    .build())
            .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder().streamPrefix(p.name()).build()))
            .build());
    return task;
  }

  /** Lets {@code other} reach this service's port (e.g. the quotes task's edge calling keys). */
  public void allowFrom(SecurityGroup other, int port) {
    securityGroup.addIngressRule(other, Port.tcp(port), "from " + other.getNode().getId());
  }

  public FargateService service() {
    return service;
  }

  public SecurityGroup securityGroup() {
    return securityGroup;
  }
}
