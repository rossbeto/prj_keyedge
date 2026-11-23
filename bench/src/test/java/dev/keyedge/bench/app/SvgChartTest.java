package dev.keyedge.bench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SvgChartTest {
  @Test
  void linesHaveOneAxisLegendDirectLabelsTooltipsAndBothThemes() {
    Map<String, List<SvgChart.Point>> series = new LinkedHashMap<>();
    series.put("reactor p50", List.of(new SvgChart.Point(100, 10), new SvgChart.Point(200, 12)));
    series.put("reactor p99", List.of(new SvgChart.Point(100, 30), new SvgChart.Point(200, 45)));
    String svg = SvgChart.lines("t <1>", "rate", "ms", series, List.of("structured", "reactor"));
    assertThat(svg)
        .startsWith("<svg")
        .contains("prefers-color-scheme:dark")
        .contains("stroke-dasharray")
        .contains("<title>reactor p99 at 200: 45 ms</title>")
        .contains("t &lt;1&gt;")
        .contains(">structured</text>");
    assertThat(svg).contains("class=\"s1 line\""); // colour follows the entity (slot 1 = reactor)
  }

  @Test
  void barsHaveRoundedTopsValuesAndTooltips() {
    Map<String, Map<String, Double>> g = new LinkedHashMap<>();
    g.put("start", Map.of("jvm", 100.0));
    String svg = SvgChart.bars("b", "%", g);
    assertThat(svg)
        .contains("Q")
        .contains("<title>start, jvm: 100 %</title>")
        .contains(">100</text>");
  }

  @Test
  void niceMaxAndFormatting() {
    assertThat(SvgChart.niceMax(0)).isEqualTo(1);
    assertThat(SvgChart.niceMax(37)).isEqualTo(50);
    assertThat(SvgChart.niceMax(120)).isEqualTo(200);
    assertThat(SvgChart.fmt(2.0)).isEqualTo("2");
    assertThat(SvgChart.fmt(2.25)).isEqualTo("2.3");
  }
}
