package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.SsmContract;
import dev.keyedge.infra.constructs.VersionedSecret;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

/**
 * The three secrets (peppers, internal callers, sidecar principal key) and the names in the
 * contract.
 */
public final class ConfigStack extends KeyedgeStack {
  private final Map<String, ISecret> secrets = new LinkedHashMap<>();

  public ConfigStack(Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth) {
    super(scope, id, cfg, synth, "secrets and service contract");
    var s = cfg.spec();
    var contract = new LinkedHashMap<String, String>();
    for (String name : List.of("peppers", "callers", "principal")) {
      var secret =
          new VersionedSecret(
                  this,
                  Character.toUpperCase(name.charAt(0)) + name.substring(1),
                  new VersionedSecret.Props(
                      s.str("names.secrets." + name),
                      s.str("secrets.generated." + name + ".template"),
                      s.str("secrets.generated." + name + ".key"),
                      s.num("secrets.length"),
                      cfg.prodlike()))
              .secret();
      secrets.put(name, secret);
    }
    contract.put(cfg.ssm("pepperSecret"), s.str("names.secrets.peppers"));
    contract.put(cfg.ssm("callerSecret"), s.str("names.secrets.callers"));
    contract.put(cfg.ssm("principalSecret"), s.str("names.secrets.principal"));
    contract.put(cfg.ssm("verifyUrl"), s.str("service.verifyUrl"));
    new SsmContract(this, "Contract", contract);
    suppressFromSpec();
  }

  public Map<String, ISecret> secrets() {
    return Map.copyOf(secrets);
  }
}
