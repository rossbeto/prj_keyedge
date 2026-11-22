package dev.keyedge.keys.api;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.app.RecordUsage;
import dev.keyedge.keys.app.VerifyKey;
import dev.keyedge.keys.domain.UsageWindow;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.io.IOException;
import java.util.List;

/**
 * Endpoints for sidecars only, authenticated by an HMAC request signature instead of a partner JWT.
 * The body is read raw so the signature covers exactly the bytes sent. The secret in a verify
 * request is never logged.
 */
@Controller("/internal/v1")
@Secured(SecurityRule.IS_ANONYMOUS) // authenticated by InternalAuth (signature), not by a JWT
@ExecuteOn(TaskExecutors.VIRTUAL)
public class InternalController {
  private final InternalAuth auth;
  private final JsonMapper json;
  private final VerifyKey verify;
  private final RecordUsage usage;

  InternalController(InternalAuth auth, JsonMapper json, VerifyKey verify, RecordUsage usage) {
    this.auth = auth;
    this.json = json;
    this.verify = verify;
    this.usage = usage;
  }

  @Post("/verify")
  @Consumes(MediaType.APPLICATION_JSON)
  public Dtos.Verdict verify(HttpRequest<?> request, @Body String body) {
    auth.check(request, body);
    Dtos.VerifyRequest req = read(body, Argument.of(Dtos.VerifyRequest.class));
    KeyId keyId = KeyId.parse(req.keyId()).orElseThrow(() -> invalid("keyId"));
    KeySecret secret = KeySecret.parse(req.secret()).orElseThrow(() -> invalid("secret"));
    return switch (verify.verify(keyId, secret)) {
      case KeyCheck.Valid(TenantId tenant, KeyId id, var scopes, var plan, var ttl) ->
          new Dtos.Verdict(
              "VALID",
              tenant.value(),
              id.toString(),
              scopes.stream().map(Scope::value).sorted().toList(),
              new Dtos.RateLimit(plan.permitsPerSecond(), plan.burst()),
              ttl.toSeconds(),
              null);
      case KeyCheck.Unknown u -> rejection("UNKNOWN", keyId, null);
      case KeyCheck.Revoked r -> rejection("REVOKED", keyId, r.at());
      case KeyCheck.Expired e -> rejection("EXPIRED", keyId, e.at());
      case KeyCheck.RotatedOut r -> rejection("ROTATED_OUT", keyId, r.retiredAt());
      case KeyCheck.Malformed m -> rejection("MALFORMED", keyId, null);
    };
  }

  @Post("/usage")
  @Consumes(MediaType.APPLICATION_JSON)
  public HttpResponse<Void> usage(HttpRequest<?> request, @Body String body) {
    auth.check(request, body);
    List<Dtos.UsageEntry> entries = read(body, Argument.listOf(Dtos.UsageEntry.class));
    if (entries.size() > 10_000) {
      throw invalid("batch too large");
    }
    usage.record(
        entries.stream()
            .map(e ->
                new UsageWindow(
                    new TenantId(e.tenant()),
                    KeyId.parse(e.keyId()).orElseThrow(() -> invalid("keyId")),
                    e.minute(),
                    e.allowed(),
                    e.rejected()))
            .toList());
    return HttpResponse.accepted();
  }

  private static Dtos.Verdict rejection(
      String verdict, KeyId keyId, java.time.@org.jspecify.annotations.Nullable Instant at) {
    // Short negative TTL: a sidecar should not keep asking for a garbage key, nor cache it for
    // long.
    return new Dtos.Verdict(verdict, null, keyId.toString(), List.of(), null, 5, at);
  }

  private <T> T read(String body, Argument<T> type) {
    try {
      T value = json.readValue(body, type);
      if (value == null) {
        throw invalid("empty body");
      }
      return value;
    } catch (IOException e) {
      throw invalid("malformed JSON");
    }
  }

  private static ProblemException invalid(String what) {
    return new ProblemException(Problem.of(ProblemType.VALIDATION, "invalid " + what));
  }
}
