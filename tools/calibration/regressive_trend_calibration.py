#!/usr/bin/env python3
"""
Outil de calibration/validation pour la piste "Tendances régressives" proposée par Clem
(2026-09-19) : régression linéaire (OLS) sur 3 fenêtres D1 (7/14/30j par défaut), combinées en un
score de tendance continu — cf. RegressiveTrendStrategy.java / LinearRegressionCalculator.java.

Même méthodologie que movement_qualification_calibration.py / calibration-rejection-zone.md :
  0. Auto-test : réimplémentation Python vérifiée contre des cas connus en forme fermée (série
     parfaitement linéaire/plate), mêmes valeurs que LinearRegressionCalculatorTest.java, pour
     garantir qu'on calibre bien la même formule que le code Java, pas une approximation.
  1. Calcul walk-forward du score sur tout l'historique réel (BTC + ETH, D1), causal par
     construction (chaque score n'utilise que les bougies passées).
  2. Test statistique : accuracy directionnelle (sign(score) vs sign(rendement futur)) à plusieurs
     horizons, avec et sans filtre sur |score| (groupe de contrôle = tous les points évaluables),
     + corrélation de Pearson score/rendement futur, avec test de significativité (z-test/Fisher).
  3. Sensibilité légère : configuration par défaut vs. poids égaux vs. slopeScaleFactor alternatif.
  4. Export d'une page HTML (candles + les 3 courbes de régression + score combiné) façon
     zone_view_v2.html (TradingView Lightweight Charts, données embarquées, pas de backend requis).

Usage:
    python3 regressive_trend_calibration.py --btc btc_klines_d1.csv --eth eth_klines_d1.csv \
        --out-report regressive_trend_report.md --out-html regressive_trend_view.html
"""

import argparse
import csv
import math
import sys
from dataclasses import dataclass, field


# ========================================================================================
# 0. Réimplémentation fidèle de LinearRegressionCalculator.java / RegressiveTrendStrategy.java
# ========================================================================================

@dataclass
class LinRegResult:
    slope: float
    normalized_slope: float
    r2: float
    regression_value: float


def compute_linreg(closes, period):
    """Miroir exact de LinearRegressionCalculator.computeOn (Java) : OLS sur les `period`
    dernières valeurs de `closes`."""
    window = closes[-period:]
    n = len(window)
    sum_x = sum_y = sum_xy = sum_x2 = 0.0
    for i, y in enumerate(window):
        sum_x += i
        sum_y += y
        sum_xy += i * y
        sum_x2 += i * i

    mean_x = sum_x / n
    mean_y = sum_y / n

    denominator = sum_x2 - n * mean_x * mean_x
    slope = 0.0 if denominator == 0 else (sum_xy - n * mean_x * mean_y) / denominator
    intercept = mean_y - slope * mean_x

    ss_res = ss_tot = 0.0
    for i, y in enumerate(window):
        predicted = slope * i + intercept
        ss_res += (y - predicted) ** 2
        ss_tot += (y - mean_y) ** 2
    r2 = 0.0 if ss_tot == 0 else max(0.0, 1.0 - ss_res / ss_tot)

    regression_value = slope * (n - 1) + intercept
    normalized_slope = 0.0 if mean_y == 0 else slope / mean_y

    return LinRegResult(slope, normalized_slope, r2, regression_value)


DEFAULT_PERIODS = (7, 14, 30)
DEFAULT_WEIGHTS = (0.2, 0.3, 0.5)  # court, moyen, long -- cf. RegressiveTrendStrategy
DEFAULT_SLOPE_SCALE_FACTOR = 100.0


def combine_regressive_trend(window_results, weights=DEFAULT_WEIGHTS, slope_scale_factor=DEFAULT_SLOPE_SCALE_FACTOR):
    """Miroir exact de RegressiveTrendStrategy.evaluate (Java) : `window_results` triés
    court->moyen->long (même ordre que `weights`)."""
    signals = [math.tanh(w.normalized_slope * slope_scale_factor) for w in window_results]

    weighted_sum = sum(wt * sig * w.r2 for wt, sig, w in zip(weights, signals, window_results))
    weight_total = sum(weights)
    weighted_signal = 0.0 if weight_total == 0 else weighted_sum / weight_total

    pairs = [(0, 1), (0, 2), (1, 2)]
    agreements = sum(1 for i, j in pairs if math.copysign(1.0, signals[i] or 0.0) == math.copysign(1.0, signals[j] or 0.0))
    # signum(0.0) doit compter comme neutre des deux côtés -- copysign(1,0)=1 par convention Python,
    # donc on traite explicitement le cas signal==0 comme accord avec tout le monde (même esprit que
    # Math.signum(0.0)==0.0 côté Java : deux zéros s'accordent, un zéro et un non-zéro n'accordent
    # PAS dans le Java -- on réplique fidèlement ce détail ci-dessous plutôt que d'utiliser copysign.)
    agreements = 0
    for i, j in pairs:
        si = 0.0 if signals[i] == 0 else (1.0 if signals[i] > 0 else -1.0)
        sj = 0.0 if signals[j] == 0 else (1.0 if signals[j] > 0 else -1.0)
        if si == sj:
            agreements += 1
    agreement_ratio = agreements / len(pairs)
    alignment_factor = 0.4 + 0.6 * agreement_ratio

    score = max(-1.0, min(1.0, weighted_signal * alignment_factor))
    return score, signals, alignment_factor


def self_test():
    """Cas connus en forme fermée, identiques à LinearRegressionCalculatorTest.java --
    si l'un d'eux échoue, le script s'arrête : on ne veut pas calibrer une approximation."""
    # y = 10 + 2x sur x=0..4
    r = compute_linreg([10, 12, 14, 16, 18], 5)
    assert abs(r.slope - 2.0) < 1e-9, r
    assert abs(r.normalized_slope - 2.0 / 14.0) < 1e-9, r
    assert abs(r.r2 - 1.0) < 1e-9, r
    assert abs(r.regression_value - 18.0) < 1e-9, r

    # y = 20 - 5x sur x=0..4
    r = compute_linreg([20, 15, 10, 5, 0], 5)
    assert abs(r.slope - (-5.0)) < 1e-9, r
    assert abs(r.r2 - 1.0) < 1e-9, r

    # plat -> r2 = 0 par convention
    r = compute_linreg([10, 10, 10, 10, 10], 5)
    assert abs(r.slope - 0.0) < 1e-9, r
    assert abs(r.r2 - 0.0) < 1e-9, r

    print("[self-test] OK -- réimplémentation Python conforme à LinearRegressionCalculator.java")


# ========================================================================================
# 1. Chargement des données réelles
# ========================================================================================

def load_d1_closes(path):
    dates, closes = [], []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            dates.append(row["timestamp"][:10])
            closes.append(float(row["close"]))
    return dates, closes


@dataclass
class DailyPoint:
    date: str
    close: float
    score: float
    signals: tuple
    r2s: tuple
    regression_values: tuple  # (short, medium, long) valeur de la droite ajustée


def compute_timeline(dates, closes, periods=DEFAULT_PERIODS, weights=DEFAULT_WEIGHTS,
                      slope_scale_factor=DEFAULT_SLOPE_SCALE_FACTOR):
    """Walk-forward : un point par bougie dès que l'historique suffit pour la plus longue
    fenêtre (causal -- n'utilise jamais de donnée future, même construction que
    LinearRegressionCalculator.computeTimeline côté Java)."""
    max_period = max(periods)
    points = []
    for i in range(len(closes)):
        if i + 1 < max_period:
            continue
        window_results = [compute_linreg(closes[: i + 1], p) for p in periods]
        score, signals, _ = combine_regressive_trend(window_results, weights, slope_scale_factor)
        points.append(DailyPoint(
            date=dates[i],
            close=closes[i],
            score=score,
            signals=tuple(signals),
            r2s=tuple(w.r2 for w in window_results),
            regression_values=tuple(w.regression_value for w in window_results),
        ))
    return points


# ========================================================================================
# 2. Statistiques : accuracy directionnelle + corrélation, avec test de significativité
# ========================================================================================

def normal_cdf(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def binomial_z_test(successes, n, p0=0.5):
    if n == 0:
        return None, None
    p_hat = successes / n
    se = math.sqrt(p0 * (1 - p0) / n)
    z = 0.0 if se == 0 else (p_hat - p0) / se
    p_value = 2.0 * (1.0 - normal_cdf(abs(z)))
    return p_hat, p_value


def pearson_correlation(xs, ys):
    n = len(xs)
    if n < 3:
        return 0.0, 1.0
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    var_x = sum((x - mean_x) ** 2 for x in xs)
    var_y = sum((y - mean_y) ** 2 for y in ys)
    if var_x == 0 or var_y == 0:
        return 0.0, 1.0
    r = cov / math.sqrt(var_x * var_y)
    r_clamped = max(-0.999999, min(0.999999, r))
    z = math.atanh(r_clamped) * math.sqrt(n - 3)
    p_value = 2.0 * (1.0 - normal_cdf(abs(z)))
    return r, p_value


def forward_return(closes_by_date, points, horizon):
    """(score, rendement futur à `horizon` jours) pour chaque point où l'horizon est disponible."""
    pairs = []
    index_of_date = {p.date: i for i, p in enumerate(points)}
    for i, p in enumerate(points):
        j = i + horizon
        if j >= len(points):
            break
        fwd = (points[j].close - p.close) / p.close
        pairs.append((p.score, fwd))
    return pairs


def evaluate_asset(name, points, horizons, score_threshold=0.2):
    report_lines = [f"### {name} ({len(points)} points évaluables, {points[0].date} -> {points[-1].date})"]
    for h in horizons:
        pairs = forward_return(None, points, h)
        if not pairs:
            continue
        scores = [s for s, _ in pairs]
        rets = [r for _, r in pairs]

        r, p_corr = pearson_correlation(scores, rets)

        # Accuracy directionnelle -- groupe de contrôle = TOUS les points évaluables (pas
        # seulement ceux au-dessus du seuil), même principe que movement_qualification_calibration.py
        control_hits = sum(1 for s, ret in pairs if ret != 0 and ((s > 0) == (ret > 0)))
        control_n = sum(1 for _, ret in pairs if ret != 0)
        control_acc, control_p = binomial_z_test(control_hits, control_n)

        filtered = [(s, ret) for s, ret in pairs if abs(s) >= score_threshold and ret != 0]
        filt_hits = sum(1 for s, ret in filtered if (s > 0) == (ret > 0))
        filt_n = len(filtered)
        filt_acc, filt_p = binomial_z_test(filt_hits, filt_n)

        report_lines.append(
            f"- horizon {h}j (n={len(pairs)}) : corrélation score/rendement r={r:+.3f} (p={p_corr:.4f}) ; "
            f"accuracy directionnelle tous points={control_acc:.1%} (n={control_n}, p={control_p:.4f}) ; "
            f"accuracy |score|>={score_threshold} : {filt_acc if filt_n else float('nan'):.1%} "
            f"(n={filt_n}, p={filt_p if filt_n else float('nan')})"
        )
    return "\n".join(report_lines)


# ========================================================================================
# 3. Sensibilité légère (3 configurations, pas la grille complète des autres études)
# ========================================================================================

SENSITIVITY_CONFIGS = {
    "défaut (poids 0.2/0.3/0.5, scale=100)": (DEFAULT_WEIGHTS, DEFAULT_SLOPE_SCALE_FACTOR),
    "poids égaux (1/3 chacun)": ((1 / 3, 1 / 3, 1 / 3), DEFAULT_SLOPE_SCALE_FACTOR),
    "scale=50 (moins sensible)": (DEFAULT_WEIGHTS, 50.0),
    "scale=200 (plus sensible)": (DEFAULT_WEIGHTS, 200.0),
}


def sensitivity_report(dates, closes, horizon=7, score_threshold=0.2):
    lines = [f"### Sensibilité (horizon {horizon}j, accuracy directionnelle tous points confondus)"]
    for label, (weights, scale) in SENSITIVITY_CONFIGS.items():
        points = compute_timeline(dates, closes, weights=weights, slope_scale_factor=scale)
        pairs = forward_return(None, points, horizon)
        hits = sum(1 for s, ret in pairs if ret != 0 and ((s > 0) == (ret > 0)))
        n = sum(1 for _, ret in pairs if ret != 0)
        acc, p = binomial_z_test(hits, n)
        lines.append(f"- {label} : accuracy={acc:.1%} (n={n}, p={p:.4f})")
    return "\n".join(lines)


# ========================================================================================
# 4. Export HTML (façon zone_view_v2.html -- TradingView Lightweight Charts, données embarquées)
# ========================================================================================

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<title>Tendances régressives -- {symbol}</title>
<script src="https://unpkg.com/lightweight-charts@4.2.3/dist/lightweight-charts.standalone.production.js"></script>
<style>
  body {{ margin:0; background:#131722; color:#d1d4dc; font-family: -apple-system, Segoe UI, Roboto, sans-serif; }}
  #header {{ padding: 14px 20px; border-bottom: 1px solid #2a2e39; }}
  #header h1 {{ font-size: 16px; margin: 0 0 4px 0; font-weight: 600; }}
  #header p {{ font-size: 12px; color: #787b86; margin: 3px 0; max-width: 980px; }}
  .legend {{ display:flex; flex-wrap:wrap; gap:18px; margin-top:10px; font-size:12px; }}
  .legend span {{ display:flex; align-items:center; gap:6px; }}
  .swatch {{ width:16px; height:3px; display:inline-block; }}
  #price-chart {{ width:100%; height:520px; }}
  #score-chart {{ width:100%; height:160px; border-top: 1px solid #2a2e39; }}
</style>
</head>
<body>
<div id="header">
  <h1>{symbol} -- D1 -- Tendances régressives (régression OLS, fenêtres {periods}j)</h1>
  <p>Piste étudiée à la demande de Clem (2026-09-19), en complément de SWING_STRUCTURE -- pas encore calibrée en prod.
     Historique réel {date_start} -&gt; {date_end} ({n_points} bougies). Score combiné = moyenne pondérée de
     tanh(pente_normalisée x facteur) x r², atténuée si les 3 fenêtres se contredisent.</p>
  <div class="legend">
    <span><i class="swatch" style="background:#42a5f5"></i> Régression courte ({p_short}j)</span>
    <span><i class="swatch" style="background:#ffa726"></i> Régression moyenne ({p_medium}j)</span>
    <span><i class="swatch" style="background:#ab47bc"></i> Régression longue ({p_long}j)</span>
    <span><i class="swatch" style="background:#26a69a"></i> Score combiné &gt; 0 (haussier)</span>
    <span><i class="swatch" style="background:#ef5350"></i> Score combiné &lt; 0 (baissier)</span>
  </div>
</div>
<div id="price-chart"></div>
<div id="score-chart"></div>
<script>
const CANDLES = {candles_json};
const LINE_SHORT = {line_short_json};
const LINE_MEDIUM = {line_medium_json};
const LINE_LONG = {line_long_json};
const SCORE = {score_json};

const chartOptions = {{
  layout: {{ background: {{ color: '#131722' }}, textColor: '#d1d4dc' }},
  grid: {{ vertLines: {{ color: '#1e222d' }}, horzLines: {{ color: '#1e222d' }} }},
  timeScale: {{ borderColor: '#2a2e39' }},
  rightPriceScale: {{ borderColor: '#2a2e39' }},
  crosshair: {{ mode: LightweightCharts.CrosshairMode.Normal }},
}};

const priceChart = LightweightCharts.createChart(document.getElementById('price-chart'), chartOptions);
const candleSeries = priceChart.addCandlestickSeries({{ upColor: '#26a69a', downColor: '#ef5350', borderVisible: false, wickUpColor: '#26a69a', wickDownColor: '#ef5350' }});
candleSeries.setData(CANDLES);

const lineShortSeries = priceChart.addLineSeries({{ color: '#42a5f5', lineWidth: 1 }});
lineShortSeries.setData(LINE_SHORT);
const lineMediumSeries = priceChart.addLineSeries({{ color: '#ffa726', lineWidth: 1 }});
lineMediumSeries.setData(LINE_MEDIUM);
const lineLongSeries = priceChart.addLineSeries({{ color: '#ab47bc', lineWidth: 2 }});
lineLongSeries.setData(LINE_LONG);
priceChart.timeScale().fitContent();

const scoreChart = LightweightCharts.createChart(document.getElementById('score-chart'), {{
  ...chartOptions,
  timeScale: {{ borderColor: '#2a2e39', visible: true }},
}});
const scoreSeries = scoreChart.addHistogramSeries({{ }});
scoreSeries.setData(SCORE);
scoreChart.timeScale().fitContent();

// Les 2 charts sont 2 instances indépendantes, chacune avec sa propre échelle de prix à droite,
// dont la largeur s'auto-ajuste au texte affiché (prix BTC/ETH ~8 caractères vs score ~5
// caractères) -- sans ce forçage, la zone de tracé ne démarre pas au même pixel sur les 2 charts
// et les mêmes dates apparaissent décalées horizontalement entre les 2, même avec la plage
// temporelle synchronisée (cf. LightweightCharts.PriceScaleOptions.minimumWidth).
function alignPriceScaleWidths() {{
  const width = Math.max(priceChart.priceScale('right').width(), scoreChart.priceScale('right').width());
  if (width > 0) {{
    priceChart.priceScale('right').applyOptions({{ minimumWidth: width }});
    scoreChart.priceScale('right').applyOptions({{ minimumWidth: width }});
  }}
}}
// LWC ne calcule la largeur reelle de l'echelle de prix (basee sur le texte des labels)
// qu'apres le premier rendu -- l'appeler juste apres setData()/fitContent() renvoie encore
// width()===0 des 2 cotes, donc l'appel initial precedent n'avait aucun effet. On attend
// 2 frames pour laisser le layout se stabiliser avant de forcer une largeur commune.
requestAnimationFrame(() => requestAnimationFrame(alignPriceScaleWidths));

function syncRange(source, target) {{
  source.timeScale().subscribeVisibleLogicalRangeChange(range => {{
    if (range) target.timeScale().setVisibleLogicalRange(range);
  }});
}}
syncRange(priceChart, scoreChart);
syncRange(scoreChart, priceChart);

window.addEventListener('resize', () => {{
  priceChart.applyOptions({{ width: document.getElementById('price-chart').clientWidth }});
  scoreChart.applyOptions({{ width: document.getElementById('score-chart').clientWidth }});
  alignPriceScaleWidths();
}});
</script>
</body>
</html>
"""


def build_html_from_ohlc(symbol, ohlc_rows, points, periods=DEFAULT_PERIODS):
    import json as _json

    candles = [
        {"time": r["date"], "open": r["open"], "high": r["high"], "low": r["low"], "close": r["close"]}
        for r in ohlc_rows
    ]
    line_short = [{"time": p.date, "value": p.regression_values[0]} for p in points]
    line_medium = [{"time": p.date, "value": p.regression_values[1]} for p in points]
    line_long = [{"time": p.date, "value": p.regression_values[2]} for p in points]
    score = [
        {"time": p.date, "value": p.score, "color": "#26a69a" if p.score >= 0 else "#ef5350"}
        for p in points
    ]

    html = HTML_TEMPLATE.format(
        symbol=symbol,
        periods="/".join(str(p) for p in periods),
        p_short=periods[0], p_medium=periods[1], p_long=periods[2],
        date_start=ohlc_rows[0]["date"], date_end=ohlc_rows[-1]["date"],
        n_points=len(ohlc_rows),
        candles_json=_json.dumps(candles),
        line_short_json=_json.dumps(line_short),
        line_medium_json=_json.dumps(line_medium),
        line_long_json=_json.dumps(line_long),
        score_json=_json.dumps(score),
    )
    return html


def load_d1_ohlc(path):
    rows = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            rows.append({
                "date": row["timestamp"][:10],
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
            })
    return rows


# ========================================================================================
# main
# ========================================================================================

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc", default="btc_klines_d1.csv")
    parser.add_argument("--eth", default="eth_klines_d1.csv")
    parser.add_argument("--horizons", default="3,7,14")
    parser.add_argument("--score-threshold", type=float, default=0.2)
    parser.add_argument("--out-report", default="regressive_trend_report.md")
    parser.add_argument("--out-html-btc", default="regressive_trend_view_btc.html")
    parser.add_argument("--out-html-eth", default="regressive_trend_view_eth.html")
    args = parser.parse_args()

    self_test()

    horizons = [int(h) for h in args.horizons.split(",")]

    report_sections = [
        "# Calibration -- Tendances régressives (régression OLS multi-fenêtres)",
        "",
        "Piste proposée par Clem (2026-09-19) en complément de SWING_STRUCTURE. Méthodologie : "
        "walk-forward causal sur historique réel D1 (Binance), score combiné (RegressiveTrendStrategy, "
        "config par défaut 7/14/30j) comparé au rendement futur à plusieurs horizons.",
        "",
        "**Non calibré / point de départ, à discuter avec Clem avant tout branchement en prod** -- "
        "même réserve que MovementQualification/RejectionZone/EtfFlow à leur introduction.",
        "",
    ]

    for symbol, path, out_html in (("BTCUSDT", args.btc, args.out_html_btc), ("ETHUSDT", args.eth, args.out_html_eth)):
        ohlc_rows = load_d1_ohlc(path)
        dates = [r["date"] for r in ohlc_rows]
        closes = [r["close"] for r in ohlc_rows]

        points = compute_timeline(dates, closes)
        report_sections.append(evaluate_asset(symbol, points, horizons, args.score_threshold))
        report_sections.append("")

        html = build_html_from_ohlc(symbol, ohlc_rows, points)
        with open(out_html, "w", encoding="utf-8") as f:
            f.write(html)
        print(f"[html] {symbol} -> {out_html}")

        report_sections.append(sensitivity_report(dates, closes, horizon=7 if 7 in horizons else horizons[0],
                                                    score_threshold=args.score_threshold))
        report_sections.append("")

    report = "\n".join(report_sections)
    with open(args.out_report, "w", encoding="utf-8") as f:
        f.write(report)

    print()
    print(report)
    print(f"\n[report] -> {args.out_report}")


if __name__ == "__main__":
    main()
