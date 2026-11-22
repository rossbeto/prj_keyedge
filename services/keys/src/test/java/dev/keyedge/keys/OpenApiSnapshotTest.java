package dev.keyedge.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The OpenAPI document generated at compile time must match the committed contract
 * (contracts/openapi/keys.yml). Intentional API changes: run with -Dkeyedge.updateSnapshots=true
 * and review the diff.
 */
class OpenApiSnapshotTest {
  private static final Path CONTRACT = Path.of("../../contracts/openapi/keys.yml");

  @Test
  void generatedSpecMatchesTheCommittedContract() throws IOException {
    String generated;
    try (InputStream in = getClass().getResourceAsStream("/META-INF/swagger/keyedge-keys-1.yml")) {
      assertThat(in).as("generated OpenAPI document").isNotNull();
      generated = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (Boolean.getBoolean("keyedge.updateSnapshots") || !Files.exists(CONTRACT)) {
      Files.createDirectories(CONTRACT.getParent());
      Files.writeString(CONTRACT, generated);
    }
    assertThat(generated).isEqualTo(Files.readString(CONTRACT));
  }
}
