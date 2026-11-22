package dev.keyedge.keys.api;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keys.app.IssueKey;
import dev.keyedge.keys.app.ManageKeys;
import dev.keyedge.keys.app.RecordUsage;
import dev.keyedge.keys.app.RequestContext;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Partner-facing keys API. Each method: parse → one use case → map the sealed result to HTTP. */
@Controller("/v1")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
public class KeysController {
  private final Callers callers;
  private final IssueKey issue;
  private final ManageKeys manage;
  private final RecordUsage usage;
  private final Duration defaultGrace;

  KeysController(
      Callers callers, IssueKey issue, ManageKeys manage, RecordUsage usage, ApiSettings settings) {
    this.callers = callers;
    this.issue = issue;
    this.manage = manage;
    this.usage = usage;
    this.defaultGrace = settings.defaultGrace();
  }

  @Post("/keys")
  public HttpResponse<Dtos.KeyWithSecret> create(
      HttpRequest<?> request,
      Authentication auth,
      @Header("Idempotency-Key") @Nullable String idempotencyKey,
      @Body @Valid Dtos.CreateKey body) {
    RequestContext ctx = callers.context(request, auth, "keys:write");
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
      throw Problems.validation("an Idempotency-Key header (1-100 characters) is required");
    }
    var cmd =
        new IssueKey.Command(
            body.name(), scopes(body.scopes()), Optional.ofNullable(body.expiresAt()));
    return switch (Callers.run(ctx, () -> issue.run(ctx, cmd, idempotencyKey))) {
      case IssueKey.Result.Issued(var key, var full) ->
          HttpResponse.created(
                  new Dtos.KeyWithSecret(full, true, Dtos.KeyView.of(key), key.keyId().toString()))
              .header(HttpHeaders.LOCATION, "/v1/keys/" + key.keyId())
              .header(HttpHeaders.ETAG, etag(key.version()));
      case IssueKey.Result.Replayed(var r) ->
          HttpResponse.<Dtos.KeyWithSecret>status(HttpStatus.valueOf(r.status()))
              .body(new Dtos.KeyWithSecret(null, true, null, r.keyId()))
              .header(HttpHeaders.LOCATION, r.location())
              .header("Idempotent-Replayed", "true");
      case IssueKey.Result.InFlight f ->
          throw new ProblemException(Problem.of(ProblemType.IDEMPOTENCY_CONFLICT, null));
      case IssueKey.Result.Mismatch m ->
          throw new ProblemException(Problem.of(ProblemType.IDEMPOTENCY_MISMATCH, null));
      case IssueKey.Result.Failed(var error) -> throw Problems.of(error);
    };
  }

  @Get("/keys")
  public Dtos.KeyPage list(
      HttpRequest<?> request,
      Authentication auth,
      @QueryValue @Nullable String cursor,
      @QueryValue @Nullable Integer limit) {
    RequestContext ctx = callers.context(request, auth, "keys:read");
    var page =
        Callers.run(
            ctx, () -> manage.list(ctx, Optional.ofNullable(cursor), limit == null ? 20 : limit));
    return new Dtos.KeyPage(
        page.keys().stream().map(Dtos.KeyView::of).toList(), page.next().orElse(null));
  }

  @Get("/keys/{keyId}")
  public HttpResponse<Dtos.KeyView> get(
      HttpRequest<?> request,
      Authentication auth,
      @PathVariable String keyId,
      @Header(HttpHeaders.IF_NONE_MATCH) @Nullable String ifNoneMatch) {
    RequestContext ctx = callers.context(request, auth, "keys:read");
    var key =
        Callers.run(ctx, () -> manage.get(ctx, id(keyId)))
            .orElseThrow(() -> new ProblemException(Problem.of(ProblemType.NOT_FOUND, null)));
    String etag = etag(key.version());
    if (etag.equals(ifNoneMatch)) {
      return HttpResponse.<Dtos.KeyView>notModified().header(HttpHeaders.ETAG, etag);
    }
    return HttpResponse.ok(Dtos.KeyView.of(key)).header(HttpHeaders.ETAG, etag);
  }

  @Post("/keys/{keyId}/rotate")
  public HttpResponse<Dtos.KeyWithSecret> rotate(
      HttpRequest<?> request,
      Authentication auth,
      @PathVariable String keyId,
      @Body Dtos.@Nullable Rotate body) {
    RequestContext ctx = callers.context(request, auth, "keys:write");
    Duration grace =
        body == null || body.graceSeconds() == null
            ? defaultGrace
            : Duration.ofSeconds(body.graceSeconds());
    return switch (Callers.run(ctx, () -> manage.rotate(ctx, id(keyId), grace))) {
      case ManageKeys.Change.Changed(var key, var full) ->
          HttpResponse.ok(
                  new Dtos.KeyWithSecret(
                      full.orElse(null), true, Dtos.KeyView.of(key), key.keyId().toString()))
              .header(HttpHeaders.ETAG, etag(key.version()));
      case ManageKeys.Change.Failed(var error) -> throw Problems.of(error);
    };
  }

  @Post("/keys/{keyId}/revoke")
  public HttpResponse<Dtos.KeyView> revoke(
      HttpRequest<?> request, Authentication auth, @PathVariable String keyId) {
    RequestContext ctx = callers.context(request, auth, "keys:write");
    return switch (Callers.run(ctx, () -> manage.revoke(ctx, id(keyId)))) {
      case ManageKeys.Change.Changed(var key, var full) ->
          HttpResponse.ok(Dtos.KeyView.of(key)).header(HttpHeaders.ETAG, etag(key.version()));
      case ManageKeys.Change.Failed(var error) -> throw Problems.of(error);
    };
  }

  @Get("/usage")
  public List<Dtos.Usage> usage(
      HttpRequest<?> request,
      Authentication auth,
      @QueryValue String keyId,
      @QueryValue Instant from,
      @QueryValue Instant to) {
    RequestContext ctx = callers.context(request, auth, "keys:read");
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
      throw Problems.validation("from must be before to, at most 31 days apart");
    }
    return Callers.run(ctx, () -> usage.query(ctx, id(keyId), from, to))
        .orElseThrow(() -> new ProblemException(Problem.of(ProblemType.NOT_FOUND, null)))
        .stream()
        .map(Dtos.Usage::of)
        .toList();
  }

  private static KeyId id(String text) {
    return KeyId.parse(text)
        .orElseThrow(() -> new ProblemException(Problem.of(ProblemType.NOT_FOUND, null)));
  }

  private static java.util.Set<Scope> scopes(@Nullable List<String> raw) {
    try {
      return raw == null
          ? java.util.Set.of()
          : raw.stream().map(Scope::new).collect(Collectors.toSet());
    } catch (IllegalArgumentException e) {
      throw Problems.validation("scopes look like 'quotes:read'");
    }
  }

  static String etag(long version) {
    return "\"v" + version + "\"";
  }
}
