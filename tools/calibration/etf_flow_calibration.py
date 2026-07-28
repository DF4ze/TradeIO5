#!/usr/bin/env python3
"""
Outil de calibration/validation pour EtfFlowConfidenceStrategy (flux ETF institutionnel vs
mouvement de prix, timeframe D1), cf. docs/prompt-calibration-etf-flow.md pour le protocole complet
et docs/calibration-rejection-zone.md / docs/calibration-movement-qualification.md pour la
méthodologie de référence (même patron : auto-test fidèle, test statistique vs groupe de contrôle,
grille de sensibilité, verdict explicite -- ici appliqué séparément à BTC et à ETH, jamais fusionné,
demande explicite de Clem).

Ce script réimplémente EXACTEMENT computeSignal() de EtfFlowConfidenceStrategy.java (2 branches
effectives -- neutre/cohérent fusionnés sous le même score=0.0, puis divergent -- cf. javadoc de
classe) pour itérer sans recompiler le projet.

Étapes :
  0. Auto-test : les 6 cas de EtfFlowConfidenceStrategyTest.java (ComputeSignalTest), pour garantir
     qu'on teste bien la formule de production, pas une approximation.
  1. Classification de chaque jour aligné (divergent / coherent_or_neutral) sur les séries réelles
     ETF flow (déjà en base, export_etf_flow_history.py) + OHLCV D1 (fetch_real_klines.py).
  2. Test statistique : taux de non-continuation (stagnation/retournement) sur les jours classés
     "divergent" à plusieurs horizons (3j/7j/14j), vs un groupe de contrôle -- échantillon ALÉATOIRE
     de jours de la même série, de taille comparable au nombre de jours divergents (demande explicite
     de Clem, cf. section "Objectif" du prompt -- pas la population complète des autres jours comme
     dans movement_qualification_calibration.py).
  3. Grille de sensibilité (3^3=27 combinaisons sur les 3 seuils les plus structurants). Note : parmi
     eux, `magnitudeScaleFactor` ne change JAMAIS la classification divergent/non-divergent (il ne
     module que la magnitude du score, pas le franchissement de seuil) -- attendu que les résultats
     du test statistique soient identiques pour les 3 valeurs de cet axe ; documenté explicitement
     plutôt que masqué.

Usage:
    python3 etf_flow_calibration.py --asset BTC \
        --etf-flow etf_flow_btc.csv --klines btc_klines_d1.csv \
        --horizons 3,7,14 --seed 42

    python3 etf_flow_calibration.py --asset ETH \
        --etf-flow etf_flow_eth.csv --klines eth_klines_d1.csv \
        --horizons 3,7,14 --seed 42
"""

import argparse
import csv
import math
import random
import statistics
import sys
from dataclasses import dataclass
from datetime import datetime, date, timezone


# ========================================================================================
# 1. Réimplémentation fidèle de EtfFlowConfidenceStrategy.computeSignal (Java)
# ========================================================================================

def clamp01(v):
    if v != v:  # NaN
        return 0.0
    return max(0.0, min(1.0, v))


def compute_signal(total, price_change_pct, flow_significance_threshold_usd,
                    magnitude_scale_factor, price_move_threshold):
    """Retourne (score, case, reason) -- miroir exact des 3 branches (2 effectives, cf. javadoc de
    classe : neutre/cohérent partagent score=0.0) de EtfFlowConfidenceStrategy.computeSignal."""
    marked_price_move = abs(price_change_pct) >= price_move_threshold
    significant_flow = abs(total) >= flow_significance_threshold_usd

    if not marked_price_move or not significant_flow:
        return 0.0, "no_signal", "pas de mouvement de prix marqué ou flux ETF non significatif"

    flow_direction = 1.0 if total > 0 else (-1.0 if total < 0 else 0.0)
    price_direction = 1.0 if price_change_pct > 0 else (-1.0 if price_change_pct < 0 else 0.0)

    if flow_direction == price_direction:
        return 0.0, "coherent", "flux ETF cohérent avec le mouvement de prix, aucune atténuation"

    magnitude = clamp01(abs(total) / (flow_significance_threshold_usd * magnitude_scale_factor))
    score = -magnitude
    return score, "divergent", "mouvement de prix non soutenu par le flux ETF institutionnel"


DEFAULT_THRESHOLDS = dict(
    flow_significance_threshold_usd=50_000_000.0,
    magnitude_scale_factor=3.0,
    price_move_threshold=0.02,
)


def self_test():
    """Mêmes 6 cas que EtfFlowConfidenceStrategyTest.java (ComputeSignalTest) -- si l'un d'eux
    échoue, le script s'arrête : on ne veut pas calibrer une approximation de la formule."""
    t = DEFAULT_THRESHOLDS

    # 1. Divergence : prix en hausse marquée mais flux ETF en sortie significative -> score négatif
    score, case, _ = compute_signal(-100_000_000.0, 0.05, **t)
    assert score < 0 and case == "divergent", f"test 1 failed: score={score} case={case}"

    # 2. Divergence : prix en baisse marquée mais flux ETF en entrée significative -> score négatif
    score, case, _ = compute_signal(100_000_000.0, -0.05, **t)
    assert score < 0 and case == "divergent", f"test 2 failed: score={score} case={case}"

    # 3. Cohérence : prix en hausse et flux ETF en entrée -> score neutre (jamais de bonus)
    score, case, _ = compute_signal(100_000_000.0, 0.05, **t)
    assert abs(score) < 1e-4 and case == "coherent", f"test 3 failed: score={score} case={case}"

    # 4. Flux ETF sous le seuil de significativité -> neutre malgré un mouvement de prix marqué
    score, case, _ = compute_signal(-10_000_000.0, 0.05, **t)
    assert abs(score) < 1e-4 and case == "no_signal", f"test 4 failed: score={score} case={case}"

    # 5. Pas de mouvement de prix marqué -> neutre malgré un flux ETF significatif
    score, case, _ = compute_signal(-100_000_000.0, 0.005, **t)
    assert abs(score) < 1e-4 and case == "no_signal", f"test 5 failed: score={score} case={case}"

    # 6. Divergence maximale (3x le seuil) -> score plafonné à -1.0, jamais au-delà
    score, case, _ = compute_signal(-1_000_000_000.0, 0.05, **t)
    assert abs(score - (-1.0)) < 1e-4 and case == "divergent", f"test 6 failed: score={score} case={case}"

    print("[self-test] OK -- les 6 cas de EtfFlowConfidenceStrategyTest.ComputeSignalTest sont "
          "reproduits fidèlement.")


# ========================================================================================
# 2. Chargement des données réelles
# ========================================================================================

def parse_date(s):
    s = str(s).strip()
    if "T" in s or " " in s:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).date()
    return date.fromisoformat(s)


def load_klines_daily(path):
    """OHLCV D1, format fetch_real_klines.py (timestamp,open,high,low,close,volume). Indexé par
    date UTC (les klines D1 crypto existent 7j/7, cf. étape 1c du prompt)."""
    rows = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            ts = datetime.fromisoformat(row["timestamp"].replace("Z", "+00:00"))
            rows.append(dict(date=ts.date(), close=float(row["close"])))
    rows.sort(key=lambda r: r["date"])
    return rows


def load_etf_flow_series(path):
    """Format export_etf_flow_history.py (date,total_net_inflow)."""
    out = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            out[parse_date(row["date"])] = float(row["total_net_inflow"])
    return out


# Seuil de détection du "saut d'unité" documenté dans EtfFlowBackfillService/SosoValueEtfFlowClient
# (javadoc de classe) : le backfill combine Farside (historique profond, valeurs en MILLIONS USD,
# ex. "172.0" = 172 M$, FarsideEtfFlowClient.parseFlowNumber ne fait aucune conversion) et SoSoValue
# (dernier mois seulement, USD BRUT, ex. "-55066297.0" = -55,07 M$) dans la MEME colonne
# `total_net_inflow`, sans distinguer la source -- EtfFlowConfidenceStrategy attend du USD brut
# partout (cf. javadoc SosoValueEtfFlowClient : "Tout seuil futur sur ETF_FLOW devra être calibré en
# USD brut, pas en millions"). Un flux BTC/ETH réel en USD brut ne descend jamais sous ~1M$ (constat
# empirique sur ce jeu de données : le dernier point "millions" observé est de l'ordre de quelques
# centaines, le premier point "USD brut" de l'ordre de la dizaine de millions) -- un seuil de
# détection à 1 000 000 sépare donc proprement les deux régimes sans ambiguïté sur ces données.
UNIT_BREAK_THRESHOLD_USD = 1_000_000.0


def detect_and_fix_unit_break(series, label=""):
    """Détecte la date de bascule Farside(millions)->SoSoValue(USD brut) et reconvertit toutes les
    lignes antérieures en USD brut (x 1_000_000) pour que compute_signal (qui attend du USD brut,
    cf. DEFAULT_FLOW_SIGNIFICANCE_THRESHOLD_USD=50_000_000.0 dans EtfFlowConfidenceStrategy.java)
    reçoive une série homogène. Correction appliquée seulement pour CETTE calibration (lecture
    seule, aucune écriture en base) -- la vraie correction du bug côté production (normaliser les
    unités au moment du backfill/upsert) reste à faire séparément, cf. verdict."""
    if not series:
        return series, None, 0

    sorted_days = sorted(series.keys())
    cutover_day = None
    for day in sorted_days:
        if abs(series[day]) >= UNIT_BREAK_THRESHOLD_USD:
            cutover_day = day
            break

    if cutover_day is None:
        print(f"[{label}] Aucune bascule d'unité détectée (toutes les valeurs sont sous "
              f"{UNIT_BREAK_THRESHOLD_USD:,.0f}) -- pas de correction appliquée.")
        return series, None, 0

    fixed = dict(series)
    n_fixed = 0
    for day in sorted_days:
        if day < cutover_day:
            fixed[day] = series[day] * 1_000_000.0
            n_fixed += 1

    print(f"[{label}] Bascule d'unité Farside(millions)->SoSoValue(USD brut) détectée au "
          f"{cutover_day} -- {n_fixed}/{len(series)} lignes antérieures reconverties x1e6 "
          f"(bug de mélange d'unités dans etf_flow_snapshot.total_net_inflow, cf. javadoc "
          f"SosoValueEtfFlowClient/EtfFlowBackfillService).")
    return fixed, cutover_day, n_fixed


def percentile(sorted_values, pct):
    """Percentile simple par interpolation la plus proche (même patron que
    etf_flow_percentiles.py, dupliqué ici sciemment pour ne pas créer de dépendance croisée entre
    les deux scripts -- cf. convention déjà en usage dans ce projet pour ce type de petite
    duplication, ex. FarsideEtfFlowClient/SosoValueEtfFlowClient.parseHistory)."""
    if not sorted_values:
        return None
    n = len(sorted_values)
    idx = min(n - 1, max(0, round(pct / 100 * (n - 1))))
    return sorted_values[idx]


def compute_percentile_flow_threshold(etf_flow_series, pct):
    """Seuil de significativité du flux ETF dérivé de la distribution RÉELLE de |total_net_inflow|
    pour CET actif (percentile `pct`), plutôt que d'un seuil rond identique pour BTC et ETH --
    demande explicite de Clem : calibrer "haut/faible" empiriquement au lieu de deviner un montant
    en dollars, cf. tools/calibration/etf_flow_percentiles.py pour l'exploration complète des
    percentiles (p10/p25/p50/p75/p90/p95/p99) qui a motivé ce choix."""
    abs_values = sorted(abs(v) for v in etf_flow_series.values())
    return percentile(abs_values, pct)


# ========================================================================================
# 3. Construction du dataset évaluable (alignement sur les jours où ETF_FLOW est publié, étape 1c)
# ========================================================================================

@dataclass
class EvalPoint:
    idx: int  # index dans la série klines COMPLETE (7j/7), pas dans la série ETF-only
    day: date
    case: str
    score: float
    price_change_pct: float
    total: float


def build_eval_points(klines, etf_flow_series, lookback, thresholds):
    """Un point évaluable par jour où (a) ETF_FLOW est publié (b) le kline D1 de ce jour et celui
    d'il y a `lookback` jours existent tous les deux dans la série klines. Un jour sans flux ETF
    publié n'est PAS exclu du prix (les klines couvrent 7j/7), mais n'est pas un point évaluable
    (rien à comparer) -- cf. étape 1c du prompt."""
    klines_idx_by_date = {k["date"]: i for i, k in enumerate(klines)}
    points = []

    for day, total in sorted(etf_flow_series.items()):
        idx = klines_idx_by_date.get(day)
        if idx is None or idx - lookback < 0:
            continue
        ref_close = klines[idx - lookback]["close"]
        last_close = klines[idx]["close"]
        if ref_close == 0:
            continue
        price_change_pct = (last_close - ref_close) / ref_close

        score, case, _ = compute_signal(total, price_change_pct, **thresholds)
        points.append(EvalPoint(idx, day, case, score, price_change_pct, total))

    return points


# ========================================================================================
# 4. Test statistique : taux de non-continuation sur "divergent" vs groupe de contrôle ALEATOIRE
# ========================================================================================

def non_continuation_outcome(klines, idx, horizon, price_change_pct):
    """Prédiction de la strategy : un mouvement de prix non soutenu par le flux ETF institutionnel
    est MOINS DURABLE qu'un mouvement quelconque -> succès si le prix, `horizon` jours plus tard,
    NE continue PAS dans le sens initial (stagnation ou retournement). Miroir de cascade_outcome
    dans movement_qualification_calibration.py, horizons en JOURS ici (pas en heures), cohérent
    avec la granularité D1 d'ETF_FLOW (étape 3 du prompt)."""
    direction = 1.0 if price_change_pct > 0 else (-1.0 if price_change_pct < 0 else 0.0)
    if direction == 0.0 or idx + horizon >= len(klines):
        return None
    future_move = klines[idx + horizon]["close"] - klines[idx]["close"]
    continued = (future_move > 0 and direction > 0) or (future_move < 0 and direction < 0)
    return 0 if continued else 1


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


def run_statistical_test(klines, points, horizons, rng):
    """Jours "divergent" (treatment) vs échantillon ALEATOIRE de jours de la même série, de taille
    comparable (control) -- demande explicite de Clem, cf. section Objectif du prompt. Le pool de
    contrôle est tiré parmi TOUS les points évaluables (divergent inclus n'est pas exclu du pool de
    tirage, cf. remarque ci-dessous) sans remise, taille = min(len(divergent), len(points)-1) pour
    rester tirable même si "divergent" est presque tout l'échantillon (cas dégénéré improbable mais
    gardé explicite)."""
    divergent_points = [p for p in points if p.case == "divergent"]
    n_divergent = len(divergent_points)

    # Population de tirage pour le contrôle : tous les points évaluables. Ne pas exclure les jours
    # "divergent" eux-mêmes serait plus simple à défendre statistiquement (population de référence =
    # la série entière) que de tirer uniquement parmi les jours non-divergents, qui biaiserait le
    # contrôle vers "jours calmes" par construction.
    control_sample_size = min(n_divergent, len(points))
    control_points = rng.sample(points, control_sample_size) if control_sample_size > 0 else []

    results = {}
    for h in horizons:
        treat_successes, treat_n = 0, 0
        for p in divergent_points:
            o = non_continuation_outcome(klines, p.idx, h, p.price_change_pct)
            if o is not None:
                treat_successes += o
                treat_n += 1

        ctrl_successes, ctrl_n = 0, 0
        for p in control_points:
            o = non_continuation_outcome(klines, p.idx, h, p.price_change_pct)
            if o is not None:
                ctrl_successes += o
                ctrl_n += 1

        treat_rate = treat_successes / treat_n if treat_n else None
        ctrl_rate = ctrl_successes / ctrl_n if ctrl_n else None
        z, pval = two_proportion_ztest(treat_successes, treat_n, ctrl_successes, ctrl_n) \
            if treat_n and ctrl_n else (None, None)

        results[h] = dict(
            treat_rate=treat_rate, treat_n=treat_n,
            ctrl_rate=ctrl_rate, ctrl_n=ctrl_n,
            diff_pts=(treat_rate - ctrl_rate) * 100 if treat_rate is not None and ctrl_rate is not None else None,
            p_value=pval,
        )

    return results, n_divergent


# ========================================================================================
# 5. Orchestration par actif
# ========================================================================================

def run_asset(label, etf_flow_path, klines_path, lookback, horizons, thresholds, rng, fix_units=True):
    print(f"\n{'=' * 90}\n=== {label} ===\n{'=' * 90}")
    klines = load_klines_daily(klines_path)
    etf_flow_series = load_etf_flow_series(etf_flow_path)

    if fix_units:
        etf_flow_series, cutover_day, n_fixed = detect_and_fix_unit_break(etf_flow_series, label)

    print(f"Bougies D1 chargées: {len(klines)} ({klines[0]['date']} -> {klines[-1]['date']})")
    print(f"Jours ETF_FLOW chargés: {len(etf_flow_series)} "
          f"({min(etf_flow_series)} -> {max(etf_flow_series)})" if etf_flow_series
          else "Jours ETF_FLOW chargés: 0")

    points = build_eval_points(klines, etf_flow_series, lookback, thresholds)
    print(f"Points évaluables (ETF_FLOW publié + klines D1 alignés, lookback={lookback}j): {len(points)}")

    if not points:
        print("[WARN] Aucun point évaluable -- vérifier l'alignement des dates ETF_FLOW/klines.")
        return None

    counts = {"divergent": 0, "coherent": 0, "no_signal": 0}
    for p in points:
        counts[p.case] += 1
    total = len(points)
    print("\n--- FREQUENCE DES CAS ---")
    for case in ("divergent", "coherent", "no_signal"):
        print(f"  {case:12s}: {counts[case]:6d} / {total} ({counts[case] / total:.2%})")

    if counts["divergent"] < 20:
        print(f"\n[ATTENTION] Seulement {counts['divergent']} jours 'divergent' -- en dessous du "
              f"seuil de 20-30 occurrences signalé dans le prompt comme trop faible pour un test "
              f"statistique solide. Le verdict devra le mentionner explicitement.")

    stats, n_divergent = run_statistical_test(klines, points, horizons, rng)
    print(f"\n--- TEST STATISTIQUE (divergent, n={n_divergent}, vs échantillon aléatoire "
          f"comparable) ---")
    for h in horizons:
        r = stats[h]
        if r["treat_rate"] is None:
            print(f"  horizon={h}j : pas assez de points testables")
            continue
        ctrl_str = f"{r['ctrl_rate']:.1%} (n={r['ctrl_n']})" if r["ctrl_rate"] is not None else "n/a"
        diff_str = f"{r['diff_pts']:+.1f} pts" if r["diff_pts"] is not None else "n/a"
        p_str = f"p={r['p_value']:.4f}" if r["p_value"] is not None else "p=n/a"
        print(f"  horizon={h:3d}j : divergent={r['treat_rate']:.1%} (n={r['treat_n']:4d})  "
              f"vs contrôle={ctrl_str}  écart={diff_str}  {p_str}")

    return dict(klines=klines, etf_flow_series=etf_flow_series, points=points, counts=counts, stats=stats)


def run_sensitivity_grid(klines, etf_flow_series, lookback, horizon, rng):
    print(f"\n{'=' * 90}\n=== SENSIBILITE AUX PARAMETRES (grille 3^3=27, horizon={horizon}j) ===\n{'=' * 90}")
    print("Note : magnitudeScaleFactor ne module que la magnitude du score, jamais la "
          "classification divergent/non-divergent -- attendu que les 3 valeurs de cet axe "
          "donnent des résultats identiques (documenté, pas un bug du script).")

    grid_values = dict(
        flow_significance_threshold_usd=(25_000_000.0, 50_000_000.0, 100_000_000.0),
        magnitude_scale_factor=(2.0, 3.0, 4.0),
        price_move_threshold=(0.01, 0.02, 0.03),
    )

    rows = []
    for flow_t in grid_values["flow_significance_threshold_usd"]:
        for scale_t in grid_values["magnitude_scale_factor"]:
            for price_t in grid_values["price_move_threshold"]:
                thresholds = dict(
                    flow_significance_threshold_usd=flow_t,
                    magnitude_scale_factor=scale_t,
                    price_move_threshold=price_t,
                )
                points = build_eval_points(klines, etf_flow_series, lookback, thresholds)
                if not points:
                    continue
                stats, n_divergent = run_statistical_test(klines, points, [horizon], rng)
                r = stats[horizon]
                total = len(points)
                rows.append(dict(
                    flow_t=flow_t, scale_t=scale_t, price_t=price_t,
                    n_divergent=n_divergent, freq=n_divergent / total if total else 0.0,
                    treat_rate=r["treat_rate"], ctrl_rate=r["ctrl_rate"],
                    diff_pts=r["diff_pts"], p_value=r["p_value"],
                ))

    testable = [r for r in rows if r["treat_rate"] is not None]
    if not testable:
        print("  Aucune combinaison n'a produit de point testable.")
        return rows

    rates = [r["treat_rate"] for r in testable]
    freqs = [r["freq"] for r in testable]
    diffs = [r["diff_pts"] for r in testable if r["diff_pts"] is not None]
    print(f"  {len(testable)}/27 combinaisons testables -- "
          f"taux non-continuation: min={min(rates):.1%} max={max(rates):.1%} "
          f"moyenne={statistics.mean(rates):.1%} écart-type={statistics.pstdev(rates):.1%}  |  "
          f"fréquence divergent: min={min(freqs):.2%} max={max(freqs):.2%} moyenne={statistics.mean(freqs):.2%}")
    if diffs:
        print(f"  écart vs contrôle (pts): min={min(diffs):+.1f} max={max(diffs):+.1f} "
              f"moyenne={statistics.mean(diffs):+.1f}")

    print("\n  Détail par combinaison (flow_threshold_usd, magnitude_scale, price_move_threshold):")
    for r in rows:
        if r["treat_rate"] is None:
            print(f"    {r['flow_t']:>12,.0f} / {r['scale_t']:.1f} / {r['price_t']:.2f} : "
                  f"n_divergent={r['n_divergent']:4d} -- pas de point testable")
            continue
        p_str = f"p={r['p_value']:.4f}" if r["p_value"] is not None else "p=n/a"
        diff_str = f"{r['diff_pts']:+.1f}pts" if r["diff_pts"] is not None else "n/a"
        print(f"    {r['flow_t']:>12,.0f} / {r['scale_t']:.1f} / {r['price_t']:.2f} : "
              f"n_divergent={r['n_divergent']:4d} ({r['freq']:.2%})  "
              f"taux={r['treat_rate']:.1%}  vs_ctrl={r['ctrl_rate']:.1%}  écart={diff_str}  {p_str}")

    return rows


def run_sensitivity_grid_percentile(label, klines, etf_flow_series, lookback, horizon, rng,
                                     percentiles=(50, 67, 75, 90)):
    """Variante de run_sensitivity_grid : au lieu d'un seuil de significativité fixe en dollars
    (identique pour BTC et ETH, ce qui n'a pas le même sens pour les deux -- cf.
    etf_flow_percentiles.py, la médiane BTC est ~4x celle d'ETH), l'axe flowSignificanceThresholdUsd
    est dérivé des percentiles RÉELS de |flow| pour CET actif (demande explicite de Clem : calibrer
    haut/faible empiriquement plutôt que deviner un montant rond). magnitudeScaleFactor/
    priceMoveThreshold restent sur la même grille 3 valeurs que run_sensitivity_grid (pas de raison
    de les rendre relatifs à la distribution -- priceMoveThreshold est déjà une fraction, donc déjà
    comparable d'un actif à l'autre)."""
    flow_thresholds_usd = [compute_percentile_flow_threshold(etf_flow_series, p) for p in percentiles]
    print(f"\n{'=' * 90}\n=== {label} -- SENSIBILITE PERCENTILE (seuil de significativité dérivé de "
          f"la distribution réelle, horizon={horizon}j) ===\n{'=' * 90}")
    for p, v in zip(percentiles, flow_thresholds_usd):
        print(f"  p{p} de |flow| -> {v:,.0f} $")

    grid_values = dict(
        magnitude_scale_factor=(2.0, 3.0, 4.0),
        price_move_threshold=(0.01, 0.02, 0.03),
    )

    rows = []
    for pct, flow_t in zip(percentiles, flow_thresholds_usd):
        for scale_t in grid_values["magnitude_scale_factor"]:
            for price_t in grid_values["price_move_threshold"]:
                thresholds = dict(
                    flow_significance_threshold_usd=flow_t,
                    magnitude_scale_factor=scale_t,
                    price_move_threshold=price_t,
                )
                points = build_eval_points(klines, etf_flow_series, lookback, thresholds)
                if not points:
                    continue
                stats, n_divergent = run_statistical_test(klines, points, [horizon], rng)
                r = stats[horizon]
                total = len(points)
                rows.append(dict(
                    pct=pct, flow_t=flow_t, scale_t=scale_t, price_t=price_t,
                    n_divergent=n_divergent, freq=n_divergent / total if total else 0.0,
                    treat_rate=r["treat_rate"], ctrl_rate=r["ctrl_rate"],
                    diff_pts=r["diff_pts"], p_value=r["p_value"],
                ))

    testable = [r for r in rows if r["treat_rate"] is not None]
    if not testable:
        print("  Aucune combinaison n'a produit de point testable.")
        return rows

    print(f"\n  Détail par combinaison (percentile_flow / magnitude_scale / price_move_threshold):")
    for r in rows:
        if r["treat_rate"] is None:
            print(f"    p{r['pct']:2d}={r['flow_t']:>12,.0f} / {r['scale_t']:.1f} / {r['price_t']:.2f} : "
                  f"n_divergent={r['n_divergent']:4d} -- pas de point testable")
            continue
        p_str = f"p={r['p_value']:.4f}" if r["p_value"] is not None else "p=n/a"
        diff_str = f"{r['diff_pts']:+.1f}pts" if r["diff_pts"] is not None else "n/a"
        print(f"    p{r['pct']:2d}={r['flow_t']:>12,.0f} / {r['scale_t']:.1f} / {r['price_t']:.2f} : "
              f"n_divergent={r['n_divergent']:4d} ({r['freq']:.2%})  "
              f"taux={r['treat_rate']:.1%}  vs_ctrl={r['ctrl_rate']:.1%}  écart={diff_str}  {p_str}")

    by_pct = {}
    for r in testable:
        by_pct.setdefault(r["pct"], []).append(r)
    print(f"\n  Résumé par percentile (agrégé sur les 9 combinaisons scale x price_move) :")
    for pct in percentiles:
        group = by_pct.get(pct, [])
        if not group:
            continue
        rates = [r["treat_rate"] for r in group]
        diffs = [r["diff_pts"] for r in group if r["diff_pts"] is not None]
        n_sig = sum(1 for r in group if r["p_value"] is not None and r["p_value"] < 0.05)
        print(f"    p{pct:2d} (seuil={group[0]['flow_t']:,.0f}$) : "
              f"taux moyen={statistics.mean(rates):.1%}  "
              f"écart moyen vs contrôle={statistics.mean(diffs) if diffs else float('nan'):+.1f}pts  "
              f"cellules p<0.05: {n_sig}/{len(group)}  "
              f"fréquence divergent moyenne={statistics.mean(r['freq'] for r in group):.2%}")

    return rows


# ========================================================================================
# 6. CLI
# ========================================================================================

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", required=True, choices=["BTC", "ETH"])
    parser.add_argument("--etf-flow", required=True, help="CSV export_etf_flow_history.py (date,total_net_inflow)")
    parser.add_argument("--klines", required=True, help="CSV fetch_real_klines.py --interval 1d")
    parser.add_argument("--lookback", type=int, default=1, help="priceLookbackCandles (défaut Strategy: 1 bougie D1)")
    parser.add_argument("--horizons", default="3,7,14", help="Horizons en JOURS (granularité D1)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--skip-sensitivity", action="store_true")
    parser.add_argument("--no-unit-fix", action="store_true",
                         help="Désactive la correction du saut d'unité Farside(millions)/SoSoValue"
                              "(USD brut) -- cf. detect_and_fix_unit_break. Utile pour comparer "
                              "avant/après et documenter l'ampleur du bug dans le verdict.")
    parser.add_argument("--flow-threshold-percentile", type=float, default=None,
                         help="Si fourni (ex: 67), remplace flowSignificanceThresholdUsd par le "
                              "percentile correspondant de |flow| pour CET actif (calibrage "
                              "empirique haut/faible, demande explicite de Clem) au lieu du seuil "
                              "fixe par défaut (50M$, identique BTC/ETH). Affecte le test "
                              "statistique principal ET remplace la grille de sensibilité fixe par "
                              "run_sensitivity_grid_percentile (percentiles 50/67/75/90).")
    args = parser.parse_args()

    horizons = [int(h) for h in args.horizons.split(",")]
    rng = random.Random(args.seed)

    self_test()

    label = f"{args.asset}USDT"
    thresholds = dict(DEFAULT_THRESHOLDS)

    if args.flow_threshold_percentile is not None:
        # Calcul du seuil percentile AVANT run_asset : nécessite la série déjà corrigée des unités,
        # donc on charge/corrige une première fois ici (léger recalcul, acceptable vu la taille des
        # séries -- quelques centaines de lignes).
        raw_series = load_etf_flow_series(args.etf_flow)
        fixed_series, _, _ = detect_and_fix_unit_break(raw_series, label) if not args.no_unit_fix \
            else (raw_series, None, 0)
        pct_threshold = compute_percentile_flow_threshold(fixed_series, args.flow_threshold_percentile)
        print(f"\n[{label}] Seuil de significativité calibré empiriquement : p{args.flow_threshold_percentile:.0f} "
              f"de |flow| = {pct_threshold:,.0f} $ (au lieu du défaut fixe "
              f"{DEFAULT_THRESHOLDS['flow_significance_threshold_usd']:,.0f} $)")
        thresholds["flow_significance_threshold_usd"] = pct_threshold

    result = run_asset(label, args.etf_flow, args.klines, args.lookback, horizons, thresholds, rng,
                        fix_units=not args.no_unit_fix)

    if not result:
        print("\n[ERREUR] Aucun résultat exploitable.")
        sys.exit(1)

    if not args.skip_sensitivity:
        mid_horizon = horizons[len(horizons) // 2]
        if args.flow_threshold_percentile is not None:
            run_sensitivity_grid_percentile(label, result["klines"], result["etf_flow_series"],
                                             args.lookback, mid_horizon, rng)
        else:
            run_sensitivity_grid(result["klines"], result["etf_flow_series"], args.lookback, mid_horizon, rng)


if __name__ == "__main__":
    main()
