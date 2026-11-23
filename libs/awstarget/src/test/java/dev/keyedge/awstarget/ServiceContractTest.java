package dev.keyedge.awstarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ServiceContractTest {
  @Test
  void withoutAContractEveryNameFallsBackToConfig() {
    var none = ServiceContract.none();
    assertThat(none.name(ServiceContract.TABLE_NAME, "from-config")).isEqualTo("from-config");
    assertThat(none.source()).isEqualTo("config");
    assertThat(ServiceContract.READ_BY_SERVICES).contains(ServiceContract.TABLE_NAME).hasSize(4);
  }

  @Test
  void aContractWithoutTheKeyIsAnError() throws Exception {
    var ctor = ServiceContract.class.getDeclaredConstructor(String.class, java.util.Map.class);
    ctor.setAccessible(true);
    var c = ctor.newInstance("ssm:/keyedge/local", java.util.Map.of("table-name", "t"));
    assertThat(c.name(ServiceContract.TABLE_NAME, "x")).isEqualTo("t");
    assertThatThrownBy(() -> c.name(ServiceContract.PEPPER_SECRET, "x"))
        .hasMessageContaining("pepper-secret-name");
  }
}
