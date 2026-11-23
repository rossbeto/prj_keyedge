package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.KeysTable;
import dev.keyedge.infra.constructs.SsmContract;
import java.util.Map;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.services.dynamodb.Table;
import software.constructs.Construct;

/** The keys table and its name in the contract. */
public final class DataStack extends KeyedgeStack {
  private final Table table;

  public DataStack(Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth) {
    super(scope, id, cfg, synth, "keys table");
    var s = cfg.spec();
    table =
        new KeysTable(
                this,
                "Keys",
                new KeysTable.Props(
                    s.str("names.table"),
                    s.str("table.partitionKey"),
                    s.str("table.sortKey"),
                    s.str("table.gsi1.name"),
                    s.str("table.gsi1.partitionKey"),
                    s.str("table.gsi1.sortKey"),
                    s.str("table.ttlAttribute"),
                    cfg.prodlike()))
            .table();
    new SsmContract(this, "Contract", Map.of(cfg.ssm("tableName"), table.getTableName()));
    suppressFromSpec();
  }

  public Table table() {
    return table;
  }
}
