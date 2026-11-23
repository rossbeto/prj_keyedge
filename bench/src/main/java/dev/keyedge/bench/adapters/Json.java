package dev.keyedge.bench.adapters;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The one JSON and YAML configuration of the bench (Jackson 3: java.time built in). */
public final class Json {
  public static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(
              SerializationFeature.INDENT_OUTPUT, SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();
  public static final YAMLMapper YAML = YAMLMapper.builder().build();

  private Json() {}
}
