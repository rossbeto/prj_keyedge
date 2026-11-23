package dev.keyedge.quotes.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.fanout.CallResult;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class QuoteAssemblerTest {
  private static final CurrencyCode EUR = new CurrencyCode("EUR");
  private static final CurrencyCode USD = new CurrencyCode("USD");

  private static <T> Arbitrary<CallResult<T>> results(T ok) {
    return Arbitraries.of(
        new CallResult.Ok<>(ok),
        new CallResult.Failed<>("x", Optional.of(500)),
        new CallResult.TimedOut<>(),
        new CallResult.Skipped<>("cancelled"));
  }

  @Provide
  Arbitrary<CallResult<Upstream.Price>> prices() {
    return results(new Upstream.Price(1000, EUR));
  }

  @Provide
  Arbitrary<CallResult<Upstream.Stock>> stocks() {
    return results(new Upstream.Stock(5));
  }

  @Provide
  Arbitrary<CallResult<Upstream.FxRate>> rates() {
    return results(new Upstream.FxRate(EUR, USD, 1.5));
  }

  @Property(tries = 500)
  void requiredFailuresMeanNoQuoteAndFxIsOptional(
      @ForAll("prices") CallResult<Upstream.Price> price,
      @ForAll("stocks") CallResult<Upstream.Stock> stock,
      @ForAll("rates") CallResult<Upstream.FxRate> fx,
      @ForAll boolean convert) {
    var wanted = convert ? USD : EUR;
    QuoteOutcome out =
        QuoteAssembler.assemble(new Sku("s-1"), new Quantity(2), wanted, price, stock, fx);
    if (!price.ok() || !stock.ok()) {
      assertThat(out).isInstanceOf(QuoteOutcome.Unavailable.class);
      var u = (QuoteOutcome.Unavailable) out;
      // blame the upstream that failed, not the sibling it got cancelled
      if (u.reason() == QuoteOutcome.Reason.CANCELLED) {
        assertThat(
                price.ok()
                    || stock.ok()
                    || (price instanceof CallResult.Skipped<?>
                        && stock instanceof CallResult.Skipped<?>))
            .isTrue();
      }
      return;
    }
    var q = ((QuoteOutcome.Priced) out).quote();
    if (convert && fx.ok()) {
      assertThat(q.currency()).isEqualTo("USD");
      assertThat(q.unitPriceCents()).isEqualTo(1500);
    } else {
      assertThat(q.currency()).isEqualTo("EUR");
      assertThat(q.unitPriceCents()).isEqualTo(1000);
      assertThat(q.warnings().contains(Quote.Warning.FX_UNAVAILABLE)).isEqualTo(convert);
    }
    assertThat(q.totalCents()).isEqualTo(q.unitPriceCents() * 2);
  }

  @Property(tries = 100)
  void theFailedUpstreamIsBlamedNotItsCancelledSibling(
      @ForAll("stocks") CallResult<Upstream.Stock> stock) {
    var out =
        QuoteAssembler.assemble(
            new Sku("s-1"),
            new Quantity(1),
            EUR,
            new CallResult.Skipped<>("cancelled"),
            stock,
            new CallResult.Ok<>(new Upstream.FxRate(EUR, EUR, 1)));
    if (!stock.ok() && !(stock instanceof CallResult.Skipped<?>)) {
      assertThat(((QuoteOutcome.Unavailable) out).upstream()).isEqualTo("stock");
    }
  }

  @Property(tries = 50)
  void limitedStockIsAWarning(@ForAll("rates") CallResult<Upstream.FxRate> fx) {
    var out =
        QuoteAssembler.assemble(
            new Sku("s-1"),
            new Quantity(9),
            EUR,
            new CallResult.Ok<>(new Upstream.Price(10, EUR)),
            new CallResult.Ok<>(new Upstream.Stock(3)),
            fx);
    assertThat(((QuoteOutcome.Priced) out).quote().warnings())
        .containsExactly(Quote.Warning.LIMITED_STOCK);
  }
}
