package dev.keyedge.awstarget;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * The names a service needs, written by the CDK stacks as SSM parameters under {@code
 * /keyedge/<env>/} (D-34) and read once at start-up with one paginated {@code GetParametersByPath}.
 * The key constants are the contract: infra's {@code SsmContractTest} checks that every key a
 * service reads is written by some stack.
 */
public final class ServiceContract {
  public static final String TABLE_NAME = "table-name";
  public static final String PEPPER_SECRET = "pepper-secret-name";
  public static final String CALLER_SECRET = "caller-secret-name";
  public static final String PRINCIPAL_SECRET = "principal-secret-name";
  public static final String VERIFY_URL = "verify-url";
  public static final String SOPS_KMS_ARN = "sops-kms-arn";

  /** Every key the services read. */
  public static final List<String> READ_BY_SERVICES =
      List.of(TABLE_NAME, PEPPER_SECRET, CALLER_SECRET, PRINCIPAL_SECRET);

  private final String source;
  private final Map<String, String> values;

  private ServiceContract(String source, Map<String, String> values) {
    this.source = source;
    this.values = Map.copyOf(values);
  }

  /** No contract (tests, or a run without the CDK stacks): every name falls back to config. */
  public static ServiceContract none() {
    return new ServiceContract("config", Map.of());
  }

  public static ServiceContract load(SsmClient ssm, String path) {
    var values = new TreeMap<String, String>();
    ssm.getParametersByPathPaginator(b -> b.path(path).recursive(false)).stream()
        .flatMap(page -> page.parameters().stream())
        .forEach(p -> values.put(p.name().substring(p.name().lastIndexOf('/') + 1), p.value()));
    if (values.isEmpty()) {
      throw new IllegalStateException(
          "no SSM parameters under " + path + ": deploy the stacks (make cdk-deploy)");
    }
    return new ServiceContract("ssm:" + path, values);
  }

  /** The contract's value for {@code key}, or {@code fallback} when there is no contract. */
  public String name(String key, String fallback) {
    if (values.isEmpty()) {
      return fallback;
    }
    String v = values.get(key);
    if (v == null) {
      throw new IllegalStateException("contract " + source + " has no '" + key + "'");
    }
    return v;
  }

  /**
   * Where the names came from ({@code config} or {@code ssm:/keyedge/<env>}), for the start-up log.
   */
  public String source() {
    return source;
  }
}
