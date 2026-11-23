package dev.keyedge.infra;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.config.InfraSpec;
import dev.keyedge.infra.config.Mode;
import dev.keyedge.infra.stacks.CiStack;
import dev.keyedge.infra.stacks.ClusterStack;
import dev.keyedge.infra.stacks.ConfigStack;
import dev.keyedge.infra.stacks.DataStack;
import dev.keyedge.infra.stacks.EdgeLbStack;
import dev.keyedge.infra.stacks.KmsStack;
import dev.keyedge.infra.stacks.ObservabilityStack;
import dev.keyedge.infra.stacks.ServiceStack;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackProps;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.BootstraplessSynthesizer;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.Validations;

/**
 * The only {@code main}: config from context ({@code mode}, {@code env}) and the spec, all stacks,
 * cdk-nag on everything. Local mode synthesises bootstrap-less templates (no assets, no bootstrap
 * stack) that {@code aws cloudformation deploy} can take straight to an emulator (D-42).
 */
public final class InfraApp {
  /** The stacks deployed to the emulators in Phase 012 (the rest in Phase 014). */
  public static final List<String> LOCAL_DEPLOYED =
      List.of("KeyedgeKms", "KeyedgeData", "KeyedgeConfig");

  private InfraApp() {}

  public static void main(String[] args) {
    App app = newApp(Map.of());
    build(app, configFrom(app));
    app.synth();
  }

  /**
   * Version reporting and path metadata off: templates hold only real content (parity diff, D-47).
   */
  public static App newApp(Map<String, Object> context) {
    return newApp(context, null);
  }

  /**
   * As {@link #newApp(Map)}, synthesising into {@code outdir} (tests); null = CDK_OUTDIR or
   * default.
   */
  public static App newApp(
      Map<String, Object> context, @org.jspecify.annotations.Nullable String outdir) {
    var ctx = new java.util.HashMap<String, Object>(context);
    ctx.put("aws:cdk:version-reporting", false);
    ctx.put("aws:cdk:enable-path-metadata", false);
    var props = AppProps.builder().analyticsReporting(false).context(ctx);
    if (outdir != null) {
      props.outdir(outdir);
    }
    return new App(props.build());
  }

  public static InfraConfig configFrom(App app) {
    Object mode = app.getNode().tryGetContext("mode");
    Object env = app.getNode().tryGetContext("env");
    Mode m = Mode.parse(mode == null ? "local" : String.valueOf(mode));
    return InfraConfig.of(m, env == null ? m.key() : String.valueOf(env), InfraSpec.load());
  }

  /** All stacks for {@code cfg}; returned in dependency order. */
  public static List<software.amazon.awscdk.Stack> build(App app, InfraConfig cfg) {
    IStackSynthesizer synth =
        cfg.prodlike()
            ? DefaultStackSynthesizer.Builder.create().build()
            : new BootstraplessSynthesizer();
    var kms = new KmsStack(app, "KeyedgeKms", cfg, synth);
    var data = new DataStack(app, "KeyedgeData", cfg, synthFor(cfg));
    var config = new ConfigStack(app, "KeyedgeConfig", cfg, synthFor(cfg));
    var cluster = new ClusterStack(app, "KeyedgeCluster", cfg, synthFor(cfg));
    var keys =
        new ServiceStack(
            app,
            "KeyedgeKeysService",
            cfg,
            synthFor(cfg),
            new ServiceStack.Props("keys", false, cluster));
    var quotes =
        new ServiceStack(
            app,
            "KeyedgeQuotesService",
            cfg,
            synthFor(cfg),
            new ServiceStack.Props("quotes", true, cluster));
    keys.allowFrom(quotes.securityGroup(), cfg.spec().num("service.keysPort"));
    var lb = new EdgeLbStack(app, "KeyedgeEdgeLb", cfg, synthFor(cfg), cluster, quotes);
    var obs = new ObservabilityStack(app, "KeyedgeObservability", cfg, synthFor(cfg), lb);
    var ci = new CiStack(app, "KeyedgeCi", cfg, synthFor(cfg));
    Validations.of(app)
        .addPlugins(new AwsSolutionsChecks(app, NagPackProps.builder().verbose(true).build()));
    keys.addDependency(data); // the table and secrets exist before services that use them
    keys.addDependency(config);
    quotes.addDependency(config);
    return List.of(kms, data, config, cluster, keys, quotes, lb, obs, ci);
  }

  private static IStackSynthesizer synthFor(InfraConfig cfg) {
    return cfg.prodlike()
        ? DefaultStackSynthesizer.Builder.create().build()
        : new BootstraplessSynthesizer();
  }
}
