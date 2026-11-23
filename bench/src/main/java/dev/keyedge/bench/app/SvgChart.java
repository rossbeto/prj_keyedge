package dev.keyedge.bench.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small static SVG charts for docs/media (no dependency). Palette: the first three categorical
 * slots of the reference palette (blue, orange, aqua: validated for all pairs, light and dark),
 * switched by {@code prefers-color-scheme} inside the SVG. One y-axis, recessive grid, 2 px lines,
 * ≥ 8 px markers, legend plus direct labels at line ends, a {@code <title>} tooltip on every mark.
 */
public final class SvgChart {
  static final int W = 720;
  static final int H = 420;
  static final int LEFT = 64;
  static final int RIGHT = 120;
  static final int TOP = 84;
  static final int BOTTOM = 56;
  private static final int SLOTS = 3;

  private SvgChart() {}

  /** One bar: its group, series, value and colour slot. */
  record Mark(String group, String name, double value, int slot) {}

  /** A point of a line series. */
  public record Point(double x, double y) {}

  /**
   * Lines on one y-axis, x as given (offered load). {@code dashed} series share the colour of the
   * series whose name they start with (p99 next to p50 of the same variant).
   */
  public static String lines(
      String title,
      String xLabel,
      String yLabel,
      Map<String, List<Point>> series,
      List<String> colourKeys) {
    double xMax =
        series.values().stream().flatMap(List::stream).mapToDouble(Point::x).max().orElse(1);
    double yMax =
        niceMax(
            series.values().stream().flatMap(List::stream).mapToDouble(Point::y).max().orElse(1));
    StringBuilder b = open(title);
    axes(b, xLabel, yLabel, 0, xMax, yMax, true);
    for (var e : series.entrySet()) {
      int slot = slot(e.getKey(), colourKeys);
      boolean dashed = e.getKey().endsWith("p99");
      List<Point> pts = e.getValue();
      StringBuilder d = new StringBuilder();
      for (int i = 0; i < pts.size(); i++) {
        d.append(i == 0 ? "M" : "L")
            .append(fmt(x(pts.get(i).x(), xMax)))
            .append(' ')
            .append(fmt(y(pts.get(i).y(), yMax)));
      }
      b.append("<path class=\"s")
          .append(slot)
          .append(" line\" d=\"")
          .append(d)
          .append('"')
          .append(dashed ? " stroke-dasharray=\"6 4\"" : "")
          .append("/>\n");
      for (Point p : pts) {
        b.append("<circle class=\"f")
            .append(slot)
            .append(" ring\" r=\"4\" cx=\"")
            .append(fmt(x(p.x(), xMax)))
            .append("\" cy=\"")
            .append(fmt(y(p.y(), yMax)))
            .append("\"><title>")
            .append(esc(e.getKey()))
            .append(" at ")
            .append(fmt(p.x()))
            .append(": ")
            .append(fmt(p.y()))
            .append(' ')
            .append(esc(yLabel))
            .append("</title></circle>\n");
      }
      if (!pts.isEmpty()) {
        Point last = pts.getLast();
        b.append("<text class=\"t2\" x=\"")
            .append(fmt(x(last.x(), xMax) + 8))
            .append("\" y=\"")
            .append(fmt(y(last.y(), yMax) + 4))
            .append("\">")
            .append(esc(e.getKey()))
            .append("</text>\n");
      }
    }
    legend(b, colourKeys);
    return close(b);
  }

  /** Grouped bars: for each group (x category) one bar per series, on one y-axis. */
  public static String bars(String title, String yLabel, Map<String, Map<String, Double>> groups) {
    List<String> seriesNames = new ArrayList<>();
    groups
        .values()
        .forEach(m ->
            m.keySet()
                .forEach(k -> {
                  if (!seriesNames.contains(k)) {
                    seriesNames.add(k);
                  }
                }));
    double yMax =
        niceMax(
            groups.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1));
    StringBuilder b = open(title);
    axes(b, "", yLabel, 0, 1, yMax, false);
    double plotW = W - LEFT - RIGHT;
    double groupW = plotW / Math.max(1, groups.size());
    double barW = Math.min(36, (groupW - 24) / Math.max(1, seriesNames.size()));
    int g = 0;
    for (var e : groups.entrySet()) {
      double gx = LEFT + g * groupW + (groupW - barW * seriesNames.size()) / 2;
      int s = 0;
      for (String name : seriesNames) {
        Double v = e.getValue().get(name);
        if (v != null) {
          bar(
              b,
              new Mark(e.getKey(), name, v, slot(name, seriesNames)),
              gx + s * barW + 1,
              barW - 2,
              yMax,
              yLabel);
        }
        s++;
      }
      b.append("<text class=\"t2\" text-anchor=\"middle\" x=\"")
          .append(fmt(LEFT + g * groupW + groupW / 2))
          .append("\" y=\"")
          .append(H - BOTTOM + 20)
          .append("\">")
          .append(esc(e.getKey()))
          .append("</text>\n");
      g++;
    }
    legend(b, seriesNames);
    return close(b);
  }

  private static void bar(StringBuilder b, Mark m, double bx, double w, double yMax, String unit) {
    String group = m.group();
    String name = m.name();
    double v = m.value();
    int slot = m.slot();
    double top = y(v, yMax); // bx already leaves the 2 px surface gap between adjacent bars
    b.append("<path class=\"f")
        .append(slot)
        .append("\" d=\"")
        .append(roundedTop(bx, top, w, H - BOTTOM - top))
        .append("\"><title>")
        .append(esc(group))
        .append(", ")
        .append(esc(name))
        .append(": ")
        .append(fmt(v))
        .append(' ')
        .append(esc(unit))
        .append("</title></path>\n");
    b.append("<text class=\"t2\" text-anchor=\"middle\" x=\"")
        .append(fmt(bx + w / 2))
        .append("\" y=\"")
        .append(fmt(top - 6))
        .append("\">")
        .append(fmt(v))
        .append("</text>\n");
  }

  private static StringBuilder open(String title) {
    StringBuilder b = new StringBuilder();
    b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
        .append(W)
        .append(' ')
        .append(H)
        .append("\" width=\"")
        .append(W)
        .append("\" height=\"")
        .append(H)
        .append("\" role=\"img\" aria-label=\"")
        .append(esc(title))
        .append("\">\n<title>")
        .append(esc(title))
        .append("</title>\n<style>\n")
        .append(".bg{fill:#fcfcfb}.t1{fill:#0b0b0b;font:600 15px system-ui,sans-serif}")
        .append(
            ".t2{fill:#52514e;font:12px system-ui,sans-serif}.grid{stroke:#e4e3df;stroke-width:1}")
        .append(
            ".axis{stroke:#8a8984;stroke-width:1}.line{fill:none;stroke-width:2}.ring{stroke:#fcfcfb;stroke-width:2}")
        .append(
            ".s0{stroke:#2a78d6}.s1{stroke:#eb6834}.s2{stroke:#1baf7a}.f0{fill:#2a78d6}.f1{fill:#eb6834}.f2{fill:#1baf7a}\n")
        .append(
            "@media (prefers-color-scheme:dark){.bg{fill:#1a1a19}.t1{fill:#f0efec}.t2{fill:#c3c2b7}")
        .append(".grid{stroke:#33332f}.axis{stroke:#6b6a66}.ring{stroke:#1a1a19}")
        .append(
            ".s0{stroke:#3987e5}.s1{stroke:#d95926}.s2{stroke:#199e70}.f0{fill:#3987e5}.f1{fill:#d95926}.f2{fill:#199e70}}\n")
        .append(
            "</style>\n<rect class=\"bg\" width=\"100%\" height=\"100%\"/>\n<text class=\"t1\" x=\"")
        .append(LEFT)
        .append("\" y=\"28\">")
        .append(esc(title))
        .append("</text>\n");
    return b;
  }

  private static void axes(
      StringBuilder b,
      String xLabel,
      String yLabel,
      double x0,
      double xMax,
      double yMax,
      boolean xTicks) {
    for (int i = 0; i <= 4; i++) {
      double v = yMax * i / 4;
      double yy = y(v, yMax);
      b.append("<line class=\"grid\" x1=\"")
          .append(LEFT)
          .append("\" x2=\"")
          .append(W - RIGHT)
          .append("\" y1=\"")
          .append(fmt(yy))
          .append("\" y2=\"")
          .append(fmt(yy))
          .append("\"/>")
          .append("<text class=\"t2\" text-anchor=\"end\" x=\"")
          .append(LEFT - 8)
          .append("\" y=\"")
          .append(fmt(yy + 4))
          .append("\">")
          .append(fmt(v))
          .append("</text>\n");
    }
    b.append("<line class=\"axis\" x1=\"")
        .append(LEFT)
        .append("\" x2=\"")
        .append(W - RIGHT)
        .append("\" y1=\"")
        .append(H - BOTTOM)
        .append("\" y2=\"")
        .append(H - BOTTOM)
        .append("\"/>\n");
    if (xTicks) {
      for (int i = 0; i <= 4; i++) {
        double v = x0 + (xMax - x0) * i / 4;
        b.append("<text class=\"t2\" text-anchor=\"middle\" x=\"")
            .append(fmt(x(v, xMax)))
            .append("\" y=\"")
            .append(H - BOTTOM + 18)
            .append("\">")
            .append(fmt(v))
            .append("</text>\n");
      }
      b.append("<text class=\"t2\" text-anchor=\"middle\" x=\"")
          .append((LEFT + W - RIGHT) / 2)
          .append("\" y=\"")
          .append(H - 12)
          .append("\">")
          .append(esc(xLabel))
          .append("</text>\n");
    }
    b.append("<text class=\"t2\" x=\"")
        .append(LEFT)
        .append("\" y=\"")
        .append(TOP - 30)
        .append("\">")
        .append(esc(yLabel))
        .append("</text>\n");
  }

  private static void legend(StringBuilder b, List<String> names) {
    int i = 0;
    for (String n : names) {
      int yy = TOP + 8 + i * 20;
      b.append("<rect class=\"f")
          .append(slot(n, names))
          .append("\" x=\"")
          .append(W - RIGHT + 12)
          .append("\" y=\"")
          .append(yy - 9)
          .append("\" width=\"10\" height=\"10\" rx=\"2\"/><text class=\"t2\" x=\"")
          .append(W - RIGHT + 28)
          .append("\" y=\"")
          .append(yy)
          .append("\">")
          .append(esc(n))
          .append("</text>\n");
      i++;
    }
  }

  private static String close(StringBuilder b) {
    return b.append("</svg>\n").toString();
  }

  /** Colour slot by the entity (the key the name starts with), never by rank on screen. */
  static int slot(String name, List<String> keys) {
    for (int i = 0; i < keys.size(); i++) {
      if (name.startsWith(keys.get(i))) {
        return i % SLOTS;
      }
    }
    return 0;
  }

  private static String roundedTop(double x, double top, double w, double h) {
    double r = Math.min(4, Math.min(w / 2, h));
    return "M"
        + fmt(x)
        + " "
        + fmt(top + h)
        + "V"
        + fmt(top + r)
        + "Q"
        + fmt(x)
        + " "
        + fmt(top)
        + " "
        + fmt(x + r)
        + " "
        + fmt(top)
        + "H"
        + fmt(x + w - r)
        + "Q"
        + fmt(x + w)
        + " "
        + fmt(top)
        + " "
        + fmt(x + w)
        + " "
        + fmt(top + r)
        + "V"
        + fmt(top + h)
        + "Z";
  }

  private static double x(double v, double xMax) {
    return LEFT + v / xMax * (W - LEFT - RIGHT);
  }

  private static double y(double v, double yMax) {
    return H - BOTTOM - v / yMax * (H - TOP - BOTTOM);
  }

  static double niceMax(double v) {
    if (v <= 0) {
      return 1;
    }
    double mag = Math.pow(10, Math.floor(Math.log10(v)));
    for (double step : new double[] {1, 2, 2.5, 5, 10}) {
      if (step * mag >= v) {
        return step * mag;
      }
    }
    return 10 * mag;
  }

  static String fmt(double v) {
    return v == Math.rint(v) && Math.abs(v) < 1e9
        ? String.valueOf((long) v)
        : String.format(Locale.ROOT, "%.1f", v);
  }

  static String esc(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
