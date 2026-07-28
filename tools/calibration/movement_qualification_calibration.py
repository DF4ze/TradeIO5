#!/usr/bin/env python3
"""
Outil de calibration/validation pour MovementQualificationStrategy (OI + Funding Rate + OBV),
cf. docs/prompt-calibration-movement-qualification.md et docs/calibration-rejection-zone.md pour
le protocole de référence (même méthodologie, adaptée à une strategy multi-indicateurs plutôt qu'à
un indicateur de niveaux de prix).

Ce script réimplémente EXACTEMENT computeSignal() de MovementQualificationStrategy.java (mêmes 3
cas mutuellement exclusifs, mêmes formules normalizeFundingSignal/clamp01) pour itérer sans
recompiler le projet, et exécute 4 étapes :

  0. Auto-test : les 4 cas de MovementQualificationStrategyTest.java (mêmes entrées -> mêmes
     scores/cas attendus), pour garantir qu'on teste bien la formule de production, pas une
     approximation.
  1. Classification de chaque point H1 (cascade / buildup / conviction / neutre) sur les séries
     réelles OHLCV + OI + funding + OBV (calculé localement, cf. compute_obv, miroir de
     ObvIndicator.java).
  2. Test statistique par cas typé : taux de "prédiction validée" à plusieurs horizons (6h/12h/24h)
     vs un groupe de contrôle (même statistique, appliquée à TOUS les points évaluables, pas
     seulement ceux classés dans ce cas).
  3. Grille de sensibilité (81 combinaisons sur les 4 seuils les plus structurants).

Usage:
    python3 movement_qualification_calibration.py \
        --klines-btc btc_klines.csv --oi-btc oi_btc.csv --funding-btc funding_btc.csv \
        --klines-eth eth_klines.csv --oi-eth oi_eth.csv --funding-eth funding_eth.csv \
        --obv-period 14 --lookback 10 --horizons 6,12,24
"""

import argparse
import csv
import math
import random
import statistics
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone


# ========================================================================================
# 1. Réimplémentation fidèle de MovementQualificationStrategy.computeSignal (Java)
# ========================================================================================

def normalize_funding_signal(funding, low_threshold, high_threshold):
    sign = 1.0 if funding > 0 else (-1.0 if funding < 0 else 0.0)
    abs_funding = abs(funding)

    if high_threshold <= low_threshold:
        return sign if abs_funding >= high_threshold else 0.0
    if abs_funding <= low_threshold:
        return 0.0
    if abs_funding >= high_threshold:
        return sign
    magnitude = (abs_funding - low_threshold) / (high_threshold - low_threshold)
    return sign * magnitude


def clamp01(v):
    if v != v:  # NaN
        return 0.0
    return max(0.0, min(1.0, v))


def compute_signal(
    oi_current, oi_previous, funding, obv, price_change_pct,
    oi_delta_cascade_threshold, oi_delta_buildup_threshold,
    funding_low_threshold, funding_high_threshold,
    funding_buildup_signal_threshold, funding_neutral_band,
    price_move_threshold,
):
    """Retourne (score, case, oi_delta, funding_signal). `case` in {"cascade","buildup",
    "conviction","neutral"} -- miroir des 3 `if` mutuellement exclusifs + fallback de
    MovementQualificationStrategy.computeSignal (même ordre de test, important : cascade avant
    buildup avant conviction)."""
    oi_delta = 0.0 if oi_previous == 0 else (oi_current - oi_previous) / oi_previous
    funding_signal = normalize_funding_signal(funding, funding_low_threshold, funding_high_threshold)
    volume_confirmation = 1.0 if obv > 0 else (-1.0 if obv < 0 else 0.0)
    marked_price_move = abs(price_change_pct) >= price_move_threshold

    # Cas 1 : cascade de liquidations
    if oi_delta <= oi_delta_cascade_threshold and marked_price_move:
        magnitude = clamp01(abs(oi_delta) / abs(oi_delta_cascade_threshold))
        return -magnitude, "cascade", oi_delta, funding_signal

    # Cas 3 (testé avant le 2 dans le Java) : sur-effet-de-levier en construction
    if (funding_signal >= funding_buildup_signal_threshold
            and oi_delta >= oi_delta_buildup_threshold
            and price_change_pct > 0):
        magnitude = clamp01(min(funding_signal, oi_delta / oi_delta_buildup_threshold))
        return -magnitude, "buildup", oi_delta, funding_signal

    # Cas 2 : conviction spot
    if oi_delta >= 0 and abs(funding_signal) <= funding_neutral_band and volume_confirmation > 0:
        oi_component = clamp01(oi_delta / max(oi_delta_buildup_threshold, 1e-9))
        score = clamp01(0.5 + 0.5 * oi_component)
        return score, "conviction", oi_delta, funding_signal

    return 0.0, "neutral", oi_delta, funding_signal


DEFAULT_THRESHOLDS = dict(
    oi_delta_cascade_threshold=-0.10,
    oi_delta_buildup_threshold=0.10,
    funding_low_threshold=0.0005,
    funding_high_threshold=0.01,
    funding_buildup_signal_threshold=0.6,
    funding_neutral_band=0.3,
    price_move_threshold=0.02,
)


def self_test():
    """Mêmes 4 cas que MovementQualificationStrategyTest.java (computeSignal only) -- si l'un
    d'eux échoue, le script s'arrête : on ne veut pas calibrer une approximation de la formule."""
    t = DEFAULT_THRESHOLDS

    # 1. Cascade de liquidations
    score, case, _, _ = compute_signal(80.0, 100.0, 0.0001, -500.0, -0.05, **t)
    assert score < 0 and case == "cascade", f"cascade test failed: score={score} case={case}"

    # 2. Conviction spot
    score, case, _, _ = compute_signal(105.0, 100.0, 0.0001, 1000.0, 0.001, **t)
    assert score > 0 and case == "conviction", f"conviction test failed: score={score} case={case}"

    # 3. Sur-effet-de-levier en construction
    score, case, _, _ = compute_signal(115.0, 100.0, 0.009, 200.0, 0.03, **t)
    assert score < 0 and case == "buildup", f"buildup test failed: score={score} case={case}"

    # 4. Aucun pattern
    score, case, _, _ = compute_signal(100.5, 100.0, 0.003, -50.0, 0.001, **t)
    assert abs(score) < 1e-9 and case == "neutral", f"neutral test failed: score={score} case={case}"

    print("[self-test] OK -- les 4 cas de MovementQualificationStrategyTest.java sont reproduits fidèlement.")


# ========================================================================================
# 2. Chargement des données réelles
# ========================================================================================

def parse_iso(ts_str):
    return datetime.fromisoformat(str(ts_str).replace("Z", "+00:00"))


def load_klines(path):
    rows = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            rows.append(dict(
                timestamp=parse_iso(row["timestamp"]),
                open=float(row["open"]), high=float(row["high"]),
                low=float(row["low"]), close=float(row["close"]),
                volume=float(row["volume"]),
            ))
    rows.sort(key=lambda r: r["timestamp"])
    return rows


def load_value_series(path):
    """Format timestamp,value -- fetch_coinalyze_history.py (OI ou funding)."""
    out = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            out[parse_iso(row["timestamp"])] = float(row["value"])
    return out


# ========================================================================================
# 3. OBV local (miroir de ObvIndicator.java) + priceChangePct (miroir de computePriceChangePct)
# ========================================================================================

def compute_obv(window):
    """window = period+1 bougies, cf. ObvIndicator.compute -- valeur initiale = volume de la 1ère
    bougie de la fenêtre, puis +/- volume selon le sens de chaque clôture successive. Seul le
    SIGNE est exploité par la Strategy (Math.signum(obv)) -- cf. avertissement dans ObvIndicator.java
    sur la non-comparabilité de la magnitude absolue entre deux fenêtres."""
    obv = window[0]["volume"]
    for i in range(1, len(window)):
        curr, prev = window[i], window[i - 1]
        if curr["close"] > prev["close"]:
            obv += curr["volume"]
        elif curr["close"] < prev["close"]:
            obv -= curr["volume"]
    return obv


def compute_price_change_pct(klines, idx, lookback):
    """Miroir de MovementQualificationStrategy.computePriceChangePct : (close[idx] -
    close[idx-lookback]) / close[idx-lookback]. Retourne 0.0 si pas assez d'historique (même
    dégradation propre que le Java, cf. commentaire de la méthode source)."""
    if idx - lookback < 0:
        return 0.0
    ref = klines[idx - lookback]["close"]
    if ref == 0:
        return 0.0
    return (klines[idx]["close"] - ref) / ref


# ========================================================================================
# 4. Construction du dataset évaluable (jointure klines / OI / funding sur les mêmes timestamps H1)
# ========================================================================================

@dataclass
class EvalPoint:
    idx: int
    timestamp: datetime
    case: str
    score: float
    oi_delta: float
    funding_signal: float
    price_change_pct: float


def build_eval_points(klines, oi_series, funding_series, obv_period, lookback, thresholds,
                       obv_roc_window=None):
    """`obv_roc_window` (diagnostic optionnel, cf. section "Limites" du prompt de calibration) :
    si fourni, substitue à l'OBV brut (compute_signal ne consomme que Math.signum(obv), donc
    passer une autre grandeur au signe significatif est équivalent côté classification) le DELTA
    d'OBV entre `idx` et `idx - obv_roc_window` -- teste si le cas 'conviction' (seul consommateur
    de volumeConfirmation) se comporte différemment avec un OBV "en mouvement" plutôt qu'un simple
    signe instantané. Diagnostic exploratoire uniquement, ne change pas compute_signal (fidèle à
    MovementQualificationStrategy.java), ne remonte jamais dans le code de prod."""
    klines_by_ts = {k["timestamp"]: i for i, k in enumerate(klines)}
    points = []
    warmup = max(obv_period, lookback)
    if obv_roc_window:
        warmup = max(warmup, obv_period + obv_roc_window)

    matched_oi = 0
    matched_funding = 0

    for idx in range(warmup, len(klines)):
        ts = klines[idx]["timestamp"]
        ts_prev = ts - timedelta(hours=1)

        if ts not in oi_series or ts_prev not in oi_series:
            continue
        matched_oi += 1
        if ts not in funding_series:
            continue
        matched_funding += 1

        oi_current = oi_series[ts]
        oi_previous = oi_series[ts_prev]
        funding = funding_series[ts]

        window = klines[idx - obv_period: idx + 1]
        obv = compute_obv(window)
        if obv_roc_window:
            past_window = klines[idx - obv_roc_window - obv_period: idx - obv_roc_window + 1]
            obv_past = compute_obv(past_window)
            obv = obv - obv_past  # substitué : seul le signe compte dans compute_signal
        price_change_pct = compute_price_change_pct(klines, idx, lookback)

        score, case, oi_delta, funding_signal = compute_signal(
            oi_current, oi_previous, funding, obv, price_change_pct, **thresholds
        )
        points.append(EvalPoint(idx, ts, case, score, oi_delta, funding_signal, price_change_pct))

    return points, matched_oi, matched_funding


# ========================================================================================
# 5. Test statistique : taux de "prédiction validée" par cas, vs groupe de contrôle
# ========================================================================================

def cascade_outcome(klines, idx, horizon, price_change_pct):
    """Prédiction : mouvement peu durable -> succès si le prix NE continue PAS dans le sens de
    price_change_pct sur l'horizon (stagnation ou retournement)."""
    direction = 1.0 if price_change_pct > 0 else (-1.0 if price_change_pct < 0 else 0.0)
    if direction == 0.0 or idx + horizon >= len(klines):
        return None
    future_move = klines[idx + horizon]["close"] - klines[idx]["close"]
    continued = (future_move > 0 and direction > 0) or (future_move < 0 and direction < 0)
    return 0 if continued else 1


def buildup_outcome(klines, idx, horizon, price_change_pct=None):
    """Prédiction : risque de retournement violent A LA BAISSE -> succès si le prix baisse sur
    l'horizon. Fonction générique (indépendante du cas) pour pouvoir s'appliquer au groupe de
    contrôle (points aléatoires) exactement comme aux points classés 'buildup'."""
    if idx + horizon >= len(klines):
        return None
    return 1 if klines[idx + horizon]["close"] < klines[idx]["close"] else 0


def conviction_outcome(klines, idx, horizon, price_change_pct=None):
    """Prédiction : mouvement de qualité (conviction acheteuse, OBV positif) -> succès si le prix
    continue A LA HAUSSE sur l'horizon. Fonction générique, même remarque que buildup_outcome."""
    if idx + horizon >= len(klines):
        return None
    return 1 if klines[idx + horizon]["close"] > klines[idx]["close"] else 0


OUTCOME_FUNCS = {
    "cascade": cascade_outcome,
    "buildup": buildup_outcome,
    "conviction": conviction_outcome,
}

OUTCOME_LABELS = {
    "cascade": "taux de non-continuation (stagnation/retournement) -- valide la prédiction 'peu durable'",
    "buildup": "taux de retournement à la baisse -- valide la prédiction 'risque de retournement violent'",
    "conviction": "taux de continuation à la hausse -- valide la prédiction 'mouvement de qualité'",
}


def two_proportion_ztest(successes1, n1, successes2, n2):
    """Test z de différence de deux proportions (approx normale) -- pas de dépendance scipy.
    Retourne (z, p_two_sided) ou (None, None) si non calculable (n trop petit)."""
    if n1 == 0 or n2 == 0:
        return None, None
    p1, p2 = successes1 / n1, successes2 / n2
    p_pool = (successes1 + successes2) / (n1 + n2)
    denom = math.sqrt(p_pool * (1 - p_pool) * (1 / n1 + 1 / n2))
    if denom == 0:
        return None, None
    z = (p1 - p2) / denom
    p_value = 2 * (1 - 0.5 * (1 + math.erf(abs(z) / math.sqrt(2))))
    return z, p_value


def run_statistical_test(klines, points, horizons, rng, label=""):
    """Pour chaque cas typé et chaque horizon : taux de succès sur les points classés dans ce cas
    (treatment) vs sur TOUS les points évaluables autres que ce cas (control, même fonction
    d'outcome). Retourne un dict imbriqué {case: {horizon: {...}}}."""
    by_case = {"cascade": [], "buildup": [], "conviction": [], "neutral": []}
    for p in points:
        by_case[p.case].append(p)

    results = {}
    for case in ("cascade", "buildup", "conviction"):
        outcome_fn = OUTCOME_FUNCS[case]
        case_points = by_case[case]
        control_pool = [p for p in points if p.case != case]  # tous les autres points évaluables

        results[case] = {}
        for h in horizons:
            treat_successes, treat_n = 0, 0
            for p in case_points:
                o = outcome_fn(klines, p.idx, h, p.price_change_pct)
                if o is not None:
                    treat_successes += o
                    treat_n += 1

            ctrl_successes, ctrl_n = 0, 0
            for p in control_pool:
                o = outcome_fn(klines, p.idx, h, p.price_change_pct)
                if o is not None:
                    ctrl_successes += o
                    ctrl_n += 1

            treat_rate = treat_successes / treat_n if treat_n else None
            ctrl_rate = ctrl_successes / ctrl_n if ctrl_n else None
            z, pval = two_proportion_ztest(treat_successes, treat_n, ctrl_successes, ctrl_n) \
                if treat_n and ctrl_n else (None, None)

            results[case][h] = dict(
                treat_rate=treat_rate, treat_n=treat_n,
                ctrl_rate=ctrl_rate, ctrl_n=ctrl_n,
                diff_pts=(treat_rate - ctrl_rate) * 100 if treat_rate is not None and ctrl_rate is not None else None,
                p_value=pval,
            )

    return results, by_case


# ========================================================================================
# 6. Orchestration par actif + agrégation multi-actifs
# ========================================================================================

def run_asset(label, klines_path, oi_path, funding_path, obv_period, lookback, horizons, thresholds, rng):
    print(f"\n{'=' * 90}\n=== {label} ===\n{'=' * 90}")
    klines = load_klines(klines_path)
    oi_series = load_value_series(oi_path)
    funding_series = load_value_series(funding_path)

    print(f"Bougies H1 chargées: {len(klines)} "
          f"({klines[0]['timestamp'].date()} -> {klines[-1]['timestamp'].date()})")
    print(f"Points OI chargés: {len(oi_series)} "
          f"({min(oi_series).date()} -> {max(oi_series).date()})" if oi_series else "Points OI chargés: 0")
    print(f"Points funding chargés: {len(funding_series)} "
          f"({min(funding_series).date()} -> {max(funding_series).date()})" if funding_series else "Points funding chargés: 0")

    points, matched_oi, matched_funding = build_eval_points(
        klines, oi_series, funding_series, obv_period, lookback, thresholds
    )
    print(f"Points évaluables (klines+OI+OI_previous+funding alignés): {len(points)} "
          f"(intersection OI: {matched_oi}, intersection OI+funding: {matched_funding})")

    if not points:
        print("[WARN] Aucun point évaluable -- vérifier l'alignement des timestamps entre klines/OI/funding.")
        return None

    counts = {"cascade": 0, "buildup": 0, "conviction": 0, "neutral": 0}
    for p in points:
        counts[p.case] += 1
    total = len(points)
    print("\n--- FREQUENCE DES CAS ---")
    for case in ("cascade", "buildup", "conviction", "neutral"):
        print(f"  {case:12s}: {counts[case]:6d} / {total} ({counts[case] / total:.2%})")

    stats, by_case = run_statistical_test(klines, points, horizons, rng, label=label)
    print("\n--- TEST STATISTIQUE (par cas typé, vs groupe de contrôle) ---")
    for case in ("cascade", "buildup", "conviction"):
        print(f"\n  [{case}] {OUTCOME_LABELS[case]}")
        for h in horizons:
            r = stats[case][h]
            if r["treat_rate"] is None:
                print(f"    horizon={h}h : pas assez de points testables")
                continue
            ctrl_str = f"{r['ctrl_rate']:.1%} (n={r['ctrl_n']})" if r["ctrl_rate"] is not None else "n/a"
            diff_str = f"{r['diff_pts']:+.1f} pts" if r["diff_pts"] is not None else "n/a"
            p_str = f"p={r['p_value']:.4f}" if r["p_value"] is not None else "p=n/a"
            print(f"    horizon={h:3d}h : cas={r['treat_rate']:.1%} (n={r['treat_n']:4d})  "
                  f"vs contrôle={ctrl_str}  écart={diff_str}  {p_str}")

    return dict(klines=klines, oi_series=oi_series, funding_series=funding_series,
                points=points, counts=counts, stats=stats)


def run_sensitivity_grid(klines, oi_series, funding_series, obv_period, lookback, horizon, rng):
    print(f"\n{'=' * 90}\n=== SENSIBILITE AUX PARAMETRES (grille 3^4=81, horizon={horizon}h) ===\n{'=' * 90}")

    grid_values = dict(
        oi_delta_cascade_threshold=(-0.05, -0.10, -0.15),
        oi_delta_buildup_threshold=(0.05, 0.10, 0.15),
        funding_buildup_signal_threshold=(0.4, 0.6, 0.8),
        price_move_threshold=(0.01, 0.02, 0.03),
    )

    per_case_rates = {"cascade": [], "buildup": [], "conviction": []}
    per_case_freq = {"cascade": [], "buildup": [], "conviction": []}

    for cascade_t in grid_values["oi_delta_cascade_threshold"]:
        for buildup_t in grid_values["oi_delta_buildup_threshold"]:
            for funding_t in grid_values["funding_buildup_signal_threshold"]:
                for price_t in grid_values["price_move_threshold"]:
                    thresholds = dict(DEFAULT_THRESHOLDS)
                    thresholds.update(
                        oi_delta_cascade_threshold=cascade_t,
                        oi_delta_buildup_threshold=buildup_t,
                        funding_buildup_signal_threshold=funding_t,
                        price_move_threshold=price_t,
                    )
                    points, _, _ = build_eval_points(klines, oi_series, funding_series, obv_period, lookback, thresholds)
                    if not points:
                        continue
                    stats, by_case = run_statistical_test(klines, points, [horizon], rng)
                    total = len(points)
                    for case in ("cascade", "buildup", "conviction"):
                        r = stats[case][horizon]
                        if r["treat_rate"] is not None:
                            per_case_rates[case].append(r["treat_rate"])
                            per_case_freq[case].append(len(by_case[case]) / total)

    for case in ("cascade", "buildup", "conviction"):
        rates = per_case_rates[case]
        freqs = per_case_freq[case]
        if not rates:
            print(f"  [{case}] Aucune combinaison n'a produit de point testable.")
            continue
        print(f"  [{case}] {len(rates)}/81 combinaisons testables -- "
              f"taux succès: min={min(rates):.1%} max={max(rates):.1%} "
              f"moyenne={statistics.mean(rates):.1%} écart-type={statistics.pstdev(rates):.1%}  |  "
              f"fréquence du cas: min={min(freqs):.2%} max={max(freqs):.2%} moyenne={statistics.mean(freqs):.2%}")

    return per_case_rates, per_case_freq


def run_obv_roc_diagnostic(label, klines, oi_series, funding_series, obv_period, lookback,
                            horizons, thresholds, rng, roc_window):
    """Diagnostic exploratoire (cf. section "Limites déjà identifiées" du prompt de calibration) :
    remplace le signe brut de l'OBV (Math.signum(obv), seule information exploitée par
    volumeConfirmation dans le cas 'conviction') par le signe de sa VARIATION sur `roc_window`
    bougies -- teste si "OBV qui accélère" est un meilleur filtre que "OBV positif à l'instant T".
    N'affecte que le cas 'conviction' (seul consommateur de volumeConfirmation dans
    compute_signal) ; cascade/buildup ne changent pas et ne sont pas réaffichés ici."""
    print(f"\n{'-' * 90}\n--- DIAGNOSTIC OBV rate-of-change ({roc_window} bougies) -- {label}, cas 'conviction' seul ---\n{'-' * 90}")
    points, _, _ = build_eval_points(klines, oi_series, funding_series, obv_period, lookback,
                                      thresholds, obv_roc_window=roc_window)
    if not points:
        print("  Aucun point évaluable avec ce warmup étendu.")
        return
    stats, by_case = run_statistical_test(klines, points, horizons, rng)
    n_conviction = len(by_case["conviction"])
    total = len(points)
    print(f"  Fréquence 'conviction' (OBV RoC) : {n_conviction}/{total} ({n_conviction / total:.2%}) "
          f"-- vs signe brut plus haut")
    for h in horizons:
        r = stats["conviction"][h]
        if r["treat_rate"] is None:
            print(f"    horizon={h}h : pas assez de points testables")
            continue
        ctrl_str = f"{r['ctrl_rate']:.1%} (n={r['ctrl_n']})" if r["ctrl_rate"] is not None else "n/a"
        diff_str = f"{r['diff_pts']:+.1f} pts" if r["diff_pts"] is not None else "n/a"
        p_str = f"p={r['p_value']:.4f}" if r["p_value"] is not None else "p=n/a"
        print(f"    horizon={h:3d}h : cas={r['treat_rate']:.1%} (n={r['treat_n']:4d})  "
              f"vs contrôle={ctrl_str}  écart={diff_str}  {p_str}")


# ========================================================================================
# 7. CLI
# ========================================================================================

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--klines-btc", required=True)
    parser.add_argument("--oi-btc", required=True)
    parser.add_argument("--funding-btc", required=True)
    parser.add_argument("--klines-eth", required=True)
    parser.add_argument("--oi-eth", required=True)
    parser.add_argument("--funding-eth", required=True)
    parser.add_argument("--obv-period", type=float, default=14.0,
                         help="Défaut aligné sur MovementQualificationParam.defaults() utilisé dans "
                              "les tests Java (MarketOpinionParametersFactoryMovementQualificationTest).")
    parser.add_argument("--lookback", type=int, default=10, help="priceLookbackCandles (défaut Strategy)")
    parser.add_argument("--horizons", default="6,12,24")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--skip-sensitivity", action="store_true")
    parser.add_argument("--obv-roc-diagnostic", type=int, default=None,
                         help="Active le diagnostic OBV rate-of-change (cf. section Limites du "
                              "prompt) avec cette fenêtre en bougies, ex: 3.")
    args = parser.parse_args()

    obv_period = int(args.obv_period)
    horizons = [int(h) for h in args.horizons.split(",")]
    rng = random.Random(args.seed)

    self_test()

    assets = [
        ("BTCUSDT", args.klines_btc, args.oi_btc, args.funding_btc),
        ("ETHUSDT", args.klines_eth, args.oi_eth, args.funding_eth),
    ]

    all_results = {}
    for label, klines_path, oi_path, funding_path in assets:
        result = run_asset(label, klines_path, oi_path, funding_path, obv_period, args.lookback,
                            horizons, DEFAULT_THRESHOLDS, rng)
        if result:
            all_results[label] = result

    if not all_results:
        print("\n[ERREUR] Aucun actif n'a produit de résultat exploitable.")
        sys.exit(1)

    if not args.skip_sensitivity:
        for label, result in all_results.items():
            run_sensitivity_grid(result["klines"], result["oi_series"], result["funding_series"],
                                  obv_period, args.lookback, horizons[len(horizons) // 2], rng)

    if args.obv_roc_diagnostic:
        for label, result in all_results.items():
            run_obv_roc_diagnostic(label, result["klines"], result["oi_series"], result["funding_series"],
                                    obv_period, args.lookback, horizons, DEFAULT_THRESHOLDS, rng,
                                    args.obv_roc_diagnostic)


if __name__ == "__main__":
    main()
