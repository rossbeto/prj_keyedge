package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.SopsKey;
import dev.keyedge.infra.constructs.SsmContract;
import java.util.Map;
import software.amazon.awscdk.IStackSynthesizer;
import software.constructs.Construct;

/** The SOPS KMS key and its ARN in the contract (D-44). */
public final class KmsStack extends KeyedgeStack {
  public KmsStack(Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth) {
    super(scope, id, cfg, synth, "KMS key for SOPS");
    var key =
        new SopsKey(
            this, "Sops", cfg.spec().str("names.sopsKeyAlias") + "-" + cfg.env(), cfg.prodlike());
    new SsmContract(this, "Contract", Map.of(cfg.ssm("sopsKmsArn"), key.key().getKeyArn()));
    suppressFromSpec();
  }
}
