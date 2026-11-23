package dev.keyedge.bench.adapters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The JSON Schema subset results/schema/*.json uses: type (incl. lists), required, properties,
 * additionalProperties false, items, enum, const, minimum. Small on purpose: the validators on the
 * classpath bring Jackson 2 next to Micronaut's Jackson 3.
 */
public final class SchemaCheck {
  private SchemaCheck() {}

  public static List<String> validate(JsonNode schema, JsonNode doc) {
    List<String> errors = new ArrayList<>();
    check(schema, doc, "$", errors);
    return errors;
  }

  public static List<String> validateAll(Path resultsRoot, Path schemaFile) {
    JsonNode schema = Json.JSON.readTree(schemaFile.toFile());
    List<String> errors = new ArrayList<>();
    if (!Files.isDirectory(resultsRoot)) {
      return errors;
    }
    try (Stream<Path> s = Files.walk(resultsRoot)) {
      for (Path f :
          s.filter(p -> p.getFileName().toString().equals("bench-result.json")).sorted().toList()) {
        validate(schema, Json.JSON.readTree(f.toFile())).forEach(e -> errors.add(f + ": " + e));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return errors;
  }

  private static void check(JsonNode s, JsonNode d, String at, List<String> errors) {
    if (s.has("const") && !s.get("const").equals(d)) {
      errors.add(at + ": expected " + s.get("const"));
    }
    if (s.has("enum")) {
      boolean found = false;
      for (JsonNode e : s.get("enum")) {
        found |= e.equals(d);
      }
      if (!found) {
        errors.add(at + ": not one of " + s.get("enum"));
      }
    }
    if (s.has("type") && !typeOk(s.get("type"), d)) {
      errors.add(at + ": expected type " + s.get("type"));
      return;
    }
    if (s.has("minimum") && d.isNumber() && d.asDouble() < s.get("minimum").asDouble()) {
      errors.add(at + ": below minimum " + s.get("minimum"));
    }
    if (d.isObject()) {
      for (JsonNode r : s.path("required")) {
        if (!d.has(r.asString())) {
          errors.add(at + ": missing " + r.asString());
        }
      }
      JsonNode props = s.path("properties");
      for (String name : d.propertyNames()) {
        if (props.has(name)) {
          check(props.get(name), d.get(name), at + "." + name, errors);
        } else if (s.path("additionalProperties").isBoolean()
            && !s.get("additionalProperties").asBoolean()) {
          errors.add(at + ": unexpected property " + name);
        }
      }
    }
    if (d.isArray() && s.has("items")) {
      for (int i = 0; i < d.size(); i++) {
        check(s.get("items"), d.get(i), at + "[" + i + "]", errors);
      }
    }
  }

  private static boolean typeOk(JsonNode type, JsonNode d) {
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (typeOk(t, d)) {
          return true;
        }
      }
      return false;
    }
    return switch (type.asString()) {
      case "object" -> d.isObject();
      case "array" -> d.isArray();
      case "string" -> d.isString();
      case "integer" -> d.isIntegralNumber();
      case "number" -> d.isNumber();
      case "boolean" -> d.isBoolean();
      case "null" -> d.isNull();
      default -> false;
    };
  }
}
