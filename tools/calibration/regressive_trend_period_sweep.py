#!/usr/bin/env python3
"""
Bench de sensibilite sur les durees de fenetres (et scale/poids) de la piste "Tendances
regressives" (RegressiveTrendStrategy) -- repond a la question de Clem (2026-09-21) :
gagne-t-on ou perd-on en accuracy en changeant 7/14/30j pour d'autres durees, et est-ce que
d'autres parametres (slopeScaleFactor, poids court/moyen/long) ont plus d'impact que les
durees elles-memes.

Reutilise telles quelles les fonctions deja validees (self-test) de
regressive_trend_calibration.py -- meme definition de score, meme walk-forward causal, meme
test statistique (binomial z-test vs 50%, Pearson + Fisher). Ce script ne fait QUE varier les
parametres d'entree, jamais la formule.

ATTENTION multi-tests : ~7 jeux de fenetres x 2 actifs x 3 horizons + sweep scale (5 valeurs)
+ sweep poids (3 configs) = plusieurs dizaines de tests statistiques independants. Un p<0.05
isole au milieu de ce balayage n'est PAS une preuve d'edge -- seul un resultat qui ressort de
facon coherente sur BTC ET ETH, sur plusieurs horizons, a la fois en accuracy ET en
correlation, merite d'etre retenu.

Usage:
    python3 regressive_trend_period_sweep.py --btc btc_klines_d1.csv --eth eth_klines_d1.csv \
        --out-report period_sweep_report.md
"""
import argparse

import regressive_trend_calibration as rtc


PERIOD_SETS = [
    (5, 10, 20),
    (7, 14, 30),    # baseline actuel (RegressiveTrendStrategy.defaults)
    (10, 20, 40),
    (14, 30, 60),
    (5, 14, 30),
    (7, 21, 50),
    (10, 30, 90),
]

SCALE_SWEEP = [50.0, 100.0, 150.0, 200.0, 300.0]

WEIGHT_SWEEP = {
    "défaut (0.2/0.3/0.5, favorise long)": (0.2, 0.3, 0.5),
    "égaux (1/3 chacun)": (1 / 3, 1 / 3, 1 / 3),
    "favorise court (0.5/0.3/0.2)": (0.5, 0.3, 0.2),
}

HORIZONS = (3, 7, 14)
SCORE_THRESHOLD = 0.2


def eval_config(dates, closes, periods, weights, scale, horizons):
    points = rtc.compute_timeline(dates, closes, periods=periods, weights=weights, slope_scale_factor=scale)
    out = {}
    for h in horizons:
        pairs = rtc.forward_return(None, points, h)
        if not pairs:
            continue
        scores = [s for s, _ in pairs]
        rets = [r for _, r in pairs]
        r, p_corr = rtc.pearson_correlation(scores, rets)
        control_hits = sum(1 for s, ret in pairs if ret != 0 and ((s > 0) == (ret > 0)))
        control_n = sum(1 for _, ret in pairs if ret != 0)
        control_acc, control_p = rtc.binomial_z_test(control_hits, control_n)
        filtered = [(s, ret) for s, ret in pairs if abs(s) >= SCORE_THRESHOLD and ret != 0]
        filt_hits = sum(1 for s, ret in filtered if (s > 0) == (ret > 0))
        filt_n = len(filtered)
        filt_acc, filt_p = rtc.binomial_z_test(filt_hits, filt_n)
        out[h] = dict(control_acc=control_acc, control_p=control_p, control_n=control_n,
                      filt_acc=filt_acc, filt_p=filt_p, filt_n=filt_n, r=r, p_corr=p_corr)
    return out


def fmt_row(label, res, horizons):
    cells = [label]
    for h in horizons:
        d = res.get(h)
        if not d:
            cells.append("--")
            continue
        cells.append(f"{d['control_acc']:.1%} (p={d['control_p']:.3f}) / r={d['r']:+.3f} (p={d['p_corr']:.3f})")
    return cells


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc", default="btc_klines_d1.csv")
    parser.add_argument("--eth", default="eth_klines_d1.csv")
    parser.add_argument("--out-report", default="period_sweep_report.md")
    args = parser.parse_args()

    rtc.self_test()

    assets = {}
    for symbol, path in (("BTCUSDT", args.btc), ("ETHUSDT", args.eth)):
        dates, closes = rtc.load_d1_closes(path)
        assets[symbol] = (dates, closes)

    lines = []
    lines.append("# Bench -- sensibilité durées de fenêtres (+ scale/poids) -- Tendances régressives")
    lines.append("")
    lines.append(
        "Répond à la question de Clem (2026-09-21) : est-ce qu'on gagne/perd en accuracy en changeant "
        "les durées 7/14/30j, et est-ce que scale/poids comptent plus que les durées. Même moteur que "
        "regressive_trend_calibration.py (walk-forward causal, self-test validé) -- seuls les paramètres "
        "d'entrée varient, jamais la formule."
    )
    lines.append("")
    lines.append(
        "**Attention multi-tests** : chaque cellule ci-dessous est un test statistique indépendant. Sur "
        "~7 jeux de fenêtres x 2 actifs x 3 horizons (+ sweep scale/poids), quelques p<0.05 isolés sont "
        "attendus par hasard seul. Seul un résultat cohérent BTC+ETH, sur plusieurs horizons, à la fois "
        "en accuracy ET en corrélation, doit être retenu comme un signal réel."
    )
    lines.append("")

    # ------------------------------------------------------------------
    # 1. Sweep des durées de fenêtres
    # ------------------------------------------------------------------
    lines.append("## 1. Durées de fenêtres (poids 0.2/0.3/0.5, scale=100 -- défaut)")
    lines.append("")
    period_results = {}  # periods -> {symbol: res}
    for symbol, (dates, closes) in assets.items():
        lines.append(f"### {symbol}")
        header = ["Fenêtres (court/moy/long)"] + [f"h={h}j" for h in HORIZONS]
        lines.append("| " + " | ".join(header) + " |")
        lines.append("|" + "---|" * len(header))
        for periods in PERIOD_SETS:
            res = eval_config(dates, closes, periods, rtc.DEFAULT_WEIGHTS, rtc.DEFAULT_SLOPE_SCALE_FACTOR, HORIZONS)
            period_results.setdefault(periods, {})[symbol] = res
            label = "/".join(str(p) for p in periods) + ("  *(baseline)*" if periods == (7, 14, 30) else "")
            row = fmt_row(label, res, HORIZONS)
            lines.append("| " + " | ".join(row) + " |")
        lines.append("")

    lines.append("### Synthèse -- accuracy moyenne BTC+ETH par horizon (tous points, %)")
    lines.append("")
    header = ["Fenêtres"] + [f"h={h}j moyenne" for h in HORIZONS]
    lines.append("| " + " | ".join(header) + " |")
    lines.append("|" + "---|" * len(header))
    summary_rows = []
    for periods in PERIOD_SETS:
        avgs = []
        for h in HORIZONS:
            accs = [period_results[periods][s][h]["control_acc"] for s in assets]
            avgs.append(sum(accs) / len(accs))
        summary_rows.append((periods, avgs))
    summary_rows.sort(key=lambda pr: -pr[1][HORIZONS.index(7)])
    for periods, avgs in summary_rows:
        label = "/".join(str(p) for p in periods) + ("  *(baseline)*" if periods == (7, 14, 30) else "")
        lines.append("| " + " | ".join([label] + [f"{a:.1%}" for a in avgs]) + " |")
    lines.append("")
    lines.append(f"Trié par h=7j décroissant. Baseline actuel = {(7,14,30)}.")
    lines.append("")

    # ------------------------------------------------------------------
    # 2. Sweep du slopeScaleFactor (fenêtres par défaut)
    # ------------------------------------------------------------------
    lines.append("## 2. slopeScaleFactor (fenêtres 7/14/30j, poids par défaut)")
    lines.append("")
    for symbol, (dates, closes) in assets.items():
        lines.append(f"### {symbol}")
        header = ["scale"] + [f"h={h}j" for h in HORIZONS]
        lines.append("| " + " | ".join(header) + " |")
        lines.append("|" + "---|" * len(header))
        for scale in SCALE_SWEEP:
            res = eval_config(dates, closes, rtc.DEFAULT_PERIODS, rtc.DEFAULT_WEIGHTS, scale, HORIZONS)
            label = f"{scale:.0f}" + ("  *(baseline)*" if scale == rtc.DEFAULT_SLOPE_SCALE_FACTOR else "")
            row = fmt_row(label, res, HORIZONS)
            lines.append("| " + " | ".join(row) + " |")
        lines.append("")

    # ------------------------------------------------------------------
    # 3. Sweep des poids court/moyen/long (fenêtres par défaut)
    # ------------------------------------------------------------------
    lines.append("## 3. Poids court/moyen/long (fenêtres 7/14/30j, scale=100)")
    lines.append("")
    for symbol, (dates, closes) in assets.items():
        lines.append(f"### {symbol}")
        header = ["Poids"] + [f"h={h}j" for h in HORIZONS]
        lines.append("| " + " | ".join(header) + " |")
        lines.append("|" + "---|" * len(header))
        for label, weights in WEIGHT_SWEEP.items():
            res = eval_config(dates, closes, rtc.DEFAULT_PERIODS, weights, rtc.DEFAULT_SLOPE_SCALE_FACTOR, HORIZONS)
            row = fmt_row(label, res, HORIZONS)
            lines.append("| " + " | ".join(row) + " |")
        lines.append("")

    # ------------------------------------------------------------------
    # 4. Meilleure config de durées (étape 1) x meilleur scale (validation croisée grossière)
    # ------------------------------------------------------------------
    best_periods = summary_rows[0][0]
    if best_periods != rtc.DEFAULT_PERIODS:
        lines.append(f"## 4. Meilleures durées trouvées ({'/'.join(str(p) for p in best_periods)}) x sweep scale")
        lines.append("")
        lines.append(
            "Vérifie si le gain observé en étape 1 tient aussi en faisant varier le scale, "
            "ou s'il ne tenait qu'à la combinaison précise durées+scale=100 par défaut."
        )
        lines.append("")
        for symbol, (dates, closes) in assets.items():
            lines.append(f"### {symbol}")
            header = ["scale"] + [f"h={h}j" for h in HORIZONS]
            lines.append("| " + " | ".join(header) + " |")
            lines.append("|" + "---|" * len(header))
            for scale in SCALE_SWEEP:
                res = eval_config(dates, closes, best_periods, rtc.DEFAULT_WEIGHTS, scale, HORIZONS)
                label = f"{scale:.0f}" + ("  *(baseline scale)*" if scale == rtc.DEFAULT_SLOPE_SCALE_FACTOR else "")
                row = fmt_row(label, res, HORIZONS)
                lines.append("| " + " | ".join(row) + " |")
            lines.append("")

    report = "\n".join(lines)
    with open(args.out_report, "w", encoding="utf-8") as f:
        f.write(report)
    print(report)
    print(f"\n[report] -> {args.out_report}")


if __name__ == "__main__":
    main()
