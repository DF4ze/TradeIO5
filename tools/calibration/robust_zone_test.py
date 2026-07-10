#!/usr/bin/env python3
"""
Refonte du protocole de calibration RejectionZone (v2, 2026-07-09) — demande explicite de Clem :
le test binaire "tient/casse" de rejection_zone_calibration.py (v1) souffre de plusieurs faiblesses
identifiées après le premier run réel (H1/H4/D1/W1, fev 2024 -> juil 2026) :

  1. Test BINAIRE (tient/casse dans une fenêtre) -> perd toute l'info de magnitude du rebond.
  2. Marge de "cassure" (hold_margin) FIXE (0.5x range moyen de toute la série) -> pas d'ajustement
     au régime de volatilité local au moment du test.
  3. Baseline aléatoire = niveau de prix UNIFORME entre min/max de la série -> biais de densité :
     les prix réels ne se répartissent pas uniformément sur toute leur plage (zones de prix très
     visitées vs peu visitées), donc un niveau au hasard dans une zone dense a naturellement plus
     de "touches" et semble mieux "tenir" même sans aucun vrai support/résistance.
  4. Comparaison à UN SEUL tirage aléatoire -> pas de vraie significativité statistique (pas de
     distribution nulle, pas de p-value).
  5. Fenêtre de "tenue" par scan (chercher une cassure dans les N bougies suivantes) -> peut être
     vide selon le TF (cf. l'artefact W1 100% du run précédent : hold_days < espacement des
     bougies -> fenêtre toujours vide -> "tient" par construction).
  6. `was_above` déterminé sur la clôture de la bougie de contact elle-même -> fuite d'info
     (la bougie qui touche peut déjà avoir clôturé de l'autre côté du niveau).

Ce script corrige les 6 points :
  1. Magnitude continue : distance parcourue APRES un horizon fixe de N bougies, normalisée par
     l'ATR local au moment du contact (pas un binaire tient/casse).
  2. Normalisation ATR locale -> le "combien de bruit c'est normal" s'ajuste au régime courant.
  3. Baseline "density-matched" : niveaux tirés de VRAIES clôtures historiques (pas uniforme sur
     [min,max]) -> corrige le biais de densité.
  4. Test de permutation : M tirages baseline (défaut 300) -> distribution nulle -> p-value
     empirique + test de Mann-Whitney U sur les valeurs de réaction "touche par touche".
  5. Horizon fixe en nombre de bougies (pas de scan de fenêtre) -> jamais de fenêtre vide, testable
     à horizons identiques ou proportionnels pour tous les TF.
  6. `was_above` basé sur la clôture de la DERNIÈRE bougie avant le contact (pas la bougie de
     contact elle-même) -> plus de fuite intra-bougie.

En prime (demande de Clem : "j'aimerais que tu en trouves plus, histoire de tourner le temps que je
m'absente") : SIX techniques de détection de zone au total, testées avec exactement le même
framework statistique pour une comparaison à méthodologie égale :
  A. RejectionZoneIndicator (formule mèche/corps/ATR/position de clôture — la formule existante)
  B. Volume Profile approximatif (HVN) — Binance klines n'exposent que le volume agrégé par
     bougie (pas de volume par tick/prix réel) -> approximation standard (volume distribué au
     prorata du recouvrement sur des bins de prix). Limite à documenter.
  C. Pivot/fractal clustering — swing highs/lows classiques (extremum local sur 2N+1 bougies),
     sans condition de mèche/corps, puis même clustering par distance.
  D. Wick-extreme density clustering — TOUS les extremes de mèche (pas de filtre fractal ni de
     formule de rejet), seul un seuil de densité (min_samples) décide. Equivalent 1D d'un DBSCAN.
  E. Niveaux ronds psychologiques — hypothèse "discrétionnaire classique" (multiples de 1000$ pour
     BTC), zones déterministes, pas de détection — le test statistique seul tranche.
  F. Densité temporelle (KDE) — zones basées sur le TEMPS passé à chaque prix (pas le volume),
     estimation de densité par noyau gaussien pondérée par récence.

Usage:
    python3 robust_zone_test.py --csv <path.csv> --label H1 --technique all \
        --horizons 3,5,10 --lookback 200 --permutations 300 --seed 42
"""

import argparse
import csv
import math
import random
import statistics
import sys
from dataclasses import dataclass, field
from datetime import datetime

import numpy as np
from scipy import stats as sstats


def load_csv(path):
    candles = []
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            raw_ts = row.get("timestamp", len(candles))
            try:
                ts = datetime.fromisoformat(str(raw_ts).replace("Z", "+00:00"))
            except ValueError:
                ts = raw_ts
            candles.append({
                "timestamp": ts,
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
                "volume": float(row.get("volume", 0.0)),
            })
    return candles


def wilder_atr(candles, period):
    n = len(candles)
    atr = [None] * n
    if n < period + 1:
        return atr
    tr = [None] * n
    for i in range(1, n):
        c, p = candles[i], candles[i - 1]
        tr[i] = max(c["high"] - c["low"], abs(c["high"] - p["close"]), abs(c["low"] - p["close"]))
    first = sum(tr[1:period + 1]) / period
    atr[period] = first
    for i in range(period + 1, n):
        atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period
    return atr


@dataclass
class Rejection:
    level: float
    age: int
    volume: float
    direction: str


@dataclass
class Zone:
    level: float
    touches: int
    strength: float
    direction: str
    members: list = field(default_factory=list)


W_TOUCHES, W_VOLUME, W_RECENCY = 1.0, 0.5, 1.0
RECENCY_HALF_LIFE, STRENGTH_SATURATION = 50.0, 5.0


def _build_zone(members, direction, window_avg_volume):
    touches = len(members)
    level = sum(m.level for m in members) / touches
    recency_sum = sum(0.5 ** (m.age / RECENCY_HALF_LIFE) for m in members)
    avg_zone_volume = sum(m.volume for m in members) / touches
    volume_score = (avg_zone_volume / window_avg_volume) if window_avg_volume > 1e-9 else 0.0
    raw = W_TOUCHES * touches + W_VOLUME * volume_score + W_RECENCY * recency_sum
    strength = raw / (raw + STRENGTH_SATURATION)
    return Zone(level, touches, strength, direction, members)


def cluster_points(points, cluster_distance, window_avg_volume):
    zones = []
    for direction in ("support", "resistance"):
        pts = sorted([r for r in points if r.direction == direction], key=lambda r: r.level)
        if not pts:
            continue
        current = [pts[0]]
        for r in pts[1:]:
            if r.level - current[-1].level <= cluster_distance:
                current.append(r)
            else:
                zones.append(_build_zone(current, direction, window_avg_volume))
                current = [r]
        zones.append(_build_zone(current, direction, window_avg_volume))
    return zones


def detect_rejections(window, atr_value, k1, k2, p):
    n = len(window)
    rejections = []
    for i, c in enumerate(window):
        o, h, l, cl = c["open"], c["high"], c["low"], c["close"]
        rng = h - l
        if rng <= 0:
            continue
        body = abs(cl - o)
        lower_wick = min(o, cl) - l
        upper_wick = h - max(o, cl)
        age = n - 1 - i
        volume = c["volume"]
        if lower_wick > k1 * body and lower_wick > k2 * atr_value and cl > l + p * rng:
            rejections.append(Rejection(l, age, volume, "support"))
        if upper_wick > k1 * body and upper_wick > k2 * atr_value and cl < h - p * rng:
            rejections.append(Rejection(h, age, volume, "resistance"))
    return rejections


def technique_rejection_zone(candles, lookback, k1=1.5, k2=0.5, p=0.66, cluster_distance_mult=0.5, atr_period=14):
    atr_series = wilder_atr(candles, atr_period)
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    atr_value = atr_series[n - 1]
    if atr_value is None or atr_value <= 0:
        return []
    cluster_distance = cluster_distance_mult * atr_value
    window_avg_volume = sum(c["volume"] for c in window) / len(window)
    rejections = detect_rejections(window, atr_value, k1, k2, p)
    return cluster_points(rejections, cluster_distance, window_avg_volume)


def technique_volume_profile(candles, lookback, n_bins=80, top_percentile=80, min_gap_bins=2, atr_period=14):
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    price_min = min(c["low"] for c in window)
    price_max = max(c["high"] for c in window)
    if price_max <= price_min:
        return []
    bin_width = (price_max - price_min) / n_bins
    vol_bins = [0.0] * n_bins
    age_weighted_bins = [0.0] * n_bins

    for idx, c in enumerate(window):
        lo, hi, vol = c["low"], c["high"], c["volume"]
        age = len(window) - 1 - idx
        span = hi - lo
        recency_w = 0.5 ** (age / RECENCY_HALF_LIFE)
        if span <= 0:
            b = min(max(int((c["close"] - price_min) / bin_width), 0), n_bins - 1)
            vol_bins[b] += vol
            age_weighted_bins[b] += recency_w
            continue
        start_b = max(0, int((lo - price_min) / bin_width))
        end_b = min(n_bins - 1, int((hi - price_min) / bin_width))
        for b in range(start_b, end_b + 1):
            b_lo = price_min + b * bin_width
            b_hi = b_lo + bin_width
            overlap = max(0.0, min(hi, b_hi) - max(lo, b_lo))
            frac = overlap / span
            vol_bins[b] += vol * frac
            age_weighted_bins[b] += recency_w * frac

    threshold = np.percentile(vol_bins, top_percentile)
    peaks = []
    for i in range(1, n_bins - 1):
        if vol_bins[i] >= threshold and vol_bins[i] >= vol_bins[i - 1] and vol_bins[i] >= vol_bins[i + 1]:
            level = price_min + (i + 0.5) * bin_width
            peaks.append((level, vol_bins[i], age_weighted_bins[i]))

    peaks.sort(key=lambda x: x[0])
    merged = []
    for level, vol, rec in peaks:
        if merged and (level - merged[-1][0]) < min_gap_bins * bin_width:
            if vol > merged[-1][1]:
                merged[-1] = (level, vol, rec)
        else:
            merged.append((level, vol, rec))

    if not merged:
        return []
    max_vol = max(v for _, v, _ in merged)
    max_rec = max(r for _, _, r in merged) or 1.0
    zones = []
    for level, vol, rec in merged:
        strength = 0.5 * (vol / max_vol) + 0.5 * (rec / max_rec)
        zones.append(Zone(level=level, touches=0, strength=strength, direction="both", members=[]))
    return zones


def technique_pivot_cluster(candles, lookback, fractal_n=3, cluster_distance_mult=0.5, atr_period=14):
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    atr_series = wilder_atr(candles, atr_period)
    atr_value = atr_series[n - 1]
    if atr_value is None or atr_value <= 0:
        return []

    swings = []
    wn = len(window)
    for i in range(fractal_n, wn - fractal_n):
        c = window[i]
        neighborhood = window[i - fractal_n:i] + window[i + 1:i + 1 + fractal_n]
        age = wn - 1 - i
        if all(c["high"] >= o["high"] for o in neighborhood):
            swings.append(Rejection(c["high"], age, c["volume"], "resistance"))
        if all(c["low"] <= o["low"] for o in neighborhood):
            swings.append(Rejection(c["low"], age, c["volume"], "support"))

    window_avg_volume = sum(c["volume"] for c in window) / len(window)
    cluster_distance = cluster_distance_mult * atr_value
    return cluster_points(swings, cluster_distance, window_avg_volume)


def technique_wick_density(candles, lookback, atr_period=14, cluster_distance_mult=0.35, min_samples=4):
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    atr_series = wilder_atr(candles, atr_period)
    atr_value = atr_series[n - 1]
    if atr_value is None or atr_value <= 0:
        return []
    points = []
    wn = len(window)
    for i, c in enumerate(window):
        age = wn - 1 - i
        points.append(Rejection(c["high"], age, c["volume"], "resistance"))
        points.append(Rejection(c["low"], age, c["volume"], "support"))
    window_avg_volume = sum(c["volume"] for c in window) / len(window)
    cluster_distance = cluster_distance_mult * atr_value
    zones = cluster_points(points, cluster_distance, window_avg_volume)
    return [z for z in zones if z.touches >= min_samples]


def technique_round_numbers(candles, lookback, step=1000.0, atr_period=14):
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    price_min = min(c["low"] for c in window)
    price_max = max(c["high"] for c in window)
    zones = []
    level = math.floor(price_min / step) * step
    while level <= price_max:
        if price_min <= level <= price_max:
            zones.append(Zone(level=level, touches=0, strength=1.0, direction="both", members=[]))
        level += step
    return zones


def technique_time_density_kde(candles, lookback, atr_period=14, bandwidth=None, n_peaks=25, min_gap_mult=0.5):
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    atr_series = wilder_atr(candles, atr_period)
    atr_value = atr_series[n - 1]
    if atr_value is None or atr_value <= 0:
        return []
    closes = np.array([c["close"] for c in window])
    wn = len(window)
    weights = np.array([0.5 ** ((wn - 1 - i) / RECENCY_HALF_LIFE) for i in range(wn)])
    try:
        kde = sstats.gaussian_kde(closes, weights=weights, bw_method=bandwidth)
    except Exception:
        return []
    grid = np.linspace(closes.min(), closes.max(), 400)
    density = kde(grid)
    peaks_idx = []
    for i in range(1, len(grid) - 1):
        if density[i] >= density[i - 1] and density[i] >= density[i + 1]:
            peaks_idx.append(i)
    peaks_idx.sort(key=lambda i: -density[i])
    selected = []
    min_gap = min_gap_mult * atr_value
    for i in peaks_idx:
        lvl = grid[i]
        if all(abs(lvl - s) >= min_gap for s in selected):
            selected.append(lvl)
        if len(selected) >= n_peaks:
            break
    if not selected:
        return []
    densities_selected = [float(kde(lvl)[0]) for lvl in selected]
    max_density = max(densities_selected) if densities_selected else 1.0
    zones = []
    for lvl, d in zip(selected, densities_selected):
        zones.append(Zone(level=float(lvl), touches=0,
                           strength=min(d / max_density, 1.0) if max_density > 0 else 0.0,
                           direction="both", members=[]))
    return zones


def to_arrays(candles):
    return {
        "high": np.asarray([c["high"] for c in candles], dtype=np.float64),
        "low": np.asarray([c["low"] for c in candles], dtype=np.float64),
        "close": np.asarray([c["close"] for c in candles], dtype=np.float64),
    }


def atr_to_array(atr_series):
    return np.asarray([v if v is not None else np.nan for v in atr_series], dtype=np.float64)


def reaction_values(level, arrays, atr_arr, touch_tolerance, horizon):
    highs, lows, closes = arrays["high"], arrays["low"], arrays["close"]
    n = closes.shape[0]
    touching = (lows - touch_tolerance <= level) & (highs + touch_tolerance >= level)
    onset = np.zeros(n, dtype=bool)
    onset[1:] = touching[1:] & ~touching[:-1]
    idx = np.nonzero(onset)[0]
    if idx.size == 0:
        return np.empty(0)

    was_above = closes[idx - 1] >= level
    j = idx + horizon
    valid = j < n
    idx, was_above, j = idx[valid], was_above[valid], j[valid]
    if idx.size == 0:
        return np.empty(0)

    atr_at_touch = atr_arr[idx]
    valid_atr = ~np.isnan(atr_at_touch) & (atr_at_touch > 0)
    if not np.any(valid_atr):
        return np.empty(0)
    idx, was_above, j, atr_at_touch = idx[valid_atr], was_above[valid_atr], j[valid_atr], atr_at_touch[valid_atr]

    close_h = closes[j]
    raw = np.where(was_above, close_h - level, level - close_h)
    return raw / atr_at_touch


def sample_density_matched_levels(arrays, n, rng):
    closes = arrays["close"]
    picks = rng.integers(0, closes.shape[0], size=n) if hasattr(rng, "integers") else \
        [rng.randrange(closes.shape[0]) for _ in range(n)]
    return closes[picks] if hasattr(rng, "integers") else closes[np.asarray(picks)]


def evaluate_technique(arrays, zones, atr_arr, touch_tolerance, horizon, n_permutations, rng):
    if not zones:
        return None

    zone_reaction_lists = [reaction_values(z.level, arrays, atr_arr, touch_tolerance, horizon) for z in zones]
    all_zone_values = np.concatenate(zone_reaction_lists) if zone_reaction_lists else np.empty(0)
    if all_zone_values.size == 0:
        return None

    zone_means = [lst.mean() for lst in zone_reaction_lists if lst.size > 0]
    touch_pooled_mean = float(all_zone_values.mean())
    zone_level_mean = float(np.mean(zone_means)) if zone_means else None
    n_touches_total = int(all_zone_values.size)
    n_zones_tested = len(zone_means)

    null_means = []
    baseline_chunks = []
    baseline_pool_size = 0
    n_levels = len(zones)
    for _ in range(n_permutations):
        random_levels = sample_density_matched_levels(arrays, n_levels, rng)
        draw_values = np.concatenate([reaction_values(lvl, arrays, atr_arr, touch_tolerance, horizon)
                                       for lvl in random_levels]) if n_levels > 0 else np.empty(0)
        if draw_values.size > 0:
            null_means.append(float(draw_values.mean()))
            if baseline_pool_size < 5000:
                baseline_chunks.append(draw_values)
                baseline_pool_size += draw_values.size

    if not null_means:
        return None

    baseline_pool_for_mw = np.concatenate(baseline_chunks) if baseline_chunks else np.empty(0)
    null_means_arr = np.asarray(null_means)
    null_mean_avg = float(null_means_arr.mean())
    null_std = float(null_means_arr.std(ddof=0)) if null_means_arr.size > 1 else 0.0
    ge = int(np.sum(null_means_arr >= touch_pooled_mean))
    p_value_perm = (ge + 1) / (null_means_arr.size + 1)
    effect_size_perm = (touch_pooled_mean - null_mean_avg) / null_std if null_std > 1e-9 else float("nan")

    mw_p, mw_effect = None, None
    if all_zone_values.size >= 5 and baseline_pool_for_mw.size >= 5:
        try:
            u_stat, mw_p = sstats.mannwhitneyu(all_zone_values, baseline_pool_for_mw, alternative="greater")
            n1, n2 = all_zone_values.size, baseline_pool_for_mw.size
            mean_u = n1 * n2 / 2
            std_u = math.sqrt(n1 * n2 * (n1 + n2 + 1) / 12)
            z = (u_stat - mean_u) / std_u if std_u > 0 else 0.0
            mw_effect = z / math.sqrt(n1 + n2)
        except ValueError:
            pass

    return {
        "n_zones": len(zones),
        "n_zones_tested": n_zones_tested,
        "n_touches_total": n_touches_total,
        "touch_pooled_mean_atr": touch_pooled_mean,
        "zone_level_mean_atr": zone_level_mean,
        "null_mean_avg_atr": null_mean_avg,
        "null_std_atr": null_std,
        "p_value_permutation": p_value_perm,
        "effect_size_permutation": effect_size_perm,
        "mannwhitney_p": mw_p,
        "mannwhitney_effect_r": mw_effect,
        "baseline_pool_size": int(baseline_pool_for_mw.size),
    }


TECHNIQUES = {
    "rejection": technique_rejection_zone,
    "volprofile": technique_volume_profile,
    "pivot": technique_pivot_cluster,
    "wickdensity": technique_wick_density,
    "roundnumbers": technique_round_numbers,
    "kde": technique_time_density_kde,
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--technique", default="all", help="rejection|volprofile|pivot|wickdensity|roundnumbers|kde|all")
    parser.add_argument("--lookback", type=int, default=800)
    parser.add_argument("--horizons", default="3,5,10", help="Liste d'horizons (nb bougies) séparés par virgule")
    parser.add_argument("--permutations", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--atr-period", type=int, default=14)
    parser.add_argument("--csv-out", default=None)
    args = parser.parse_args()

    candles = load_csv(args.csv)
    atr_series = wilder_atr(candles, args.atr_period)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    avg_range = statistics.mean(c["high"] - c["low"] for c in candles)
    touch_tolerance = avg_range * 0.25

    horizons = [int(h) for h in args.horizons.split(",")]
    techniques = list(TECHNIQUES.keys()) if args.technique == "all" else [args.technique]

    print(f"=== ROBUST ZONE TEST v2 - {args.label or args.csv} ===")
    print(f"Bougies: {len(candles)}  |  lookback={args.lookback}  |  touch_tolerance={touch_tolerance:.2f}")
    print(f"Horizons testes (bougies): {horizons}  |  permutations={args.permutations}")
    print()

    csv_rows = []
    for tech_name in techniques:
        detect_fn = TECHNIQUES[tech_name]
        zones = detect_fn(candles, args.lookback)
        print(f"--- Technique: {tech_name} ({len(zones)} zones detectees) ---")
        if not zones:
            print("  (aucune zone, skip)")
            print()
            continue
        for h in horizons:
            rng = np.random.default_rng(args.seed)
            result = evaluate_technique(arrays, zones, atr_arr, touch_tolerance, h, args.permutations, rng)
            if result is None:
                print(f"  horizon={h:3d}  -> pas assez de touches testables")
                continue
            sig = "***" if (result["p_value_permutation"] < 0.05) else ("*" if result["p_value_permutation"] < 0.10 else "")
            mw_str = (f"MW p={result['mannwhitney_p']:.3f} r={result['mannwhitney_effect_r']:+.3f}"
                      if result['mannwhitney_p'] is not None else "MW n/a")
            print(f"  horizon={h:3d} bougies | touches={result['n_touches_total']:4d} "
                  f"({result['n_zones_tested']}/{result['n_zones']} zones) | "
                  f"reaction moy={result['touch_pooled_mean_atr']:+.3f} ATR "
                  f"(baseline={result['null_mean_avg_atr']:+.3f} ATR, sigma={result['null_std_atr']:.3f}) | "
                  f"effect={result['effect_size_permutation']:+.2f} | "
                  f"p_perm={result['p_value_permutation']:.3f}{sig} | {mw_str}")
            csv_rows.append({
                "label": args.label, "technique": tech_name, "horizon": h,
                "n_zones": result["n_zones"], "n_touches": result["n_touches_total"],
                "reaction_mean_atr": result["touch_pooled_mean_atr"],
                "baseline_mean_atr": result["null_mean_avg_atr"],
                "effect_size_perm": result["effect_size_permutation"],
                "p_value_perm": result["p_value_permutation"],
                "mw_p": result["mannwhitney_p"], "mw_effect_r": result["mannwhitney_effect_r"],
            })
        print()

    if args.csv_out and csv_rows:
        import os
        write_header = not os.path.exists(args.csv_out)
        with open(args.csv_out, "a", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(csv_rows[0].keys()))
            if write_header:
                writer.writeheader()
            writer.writerows(csv_rows)
        print(f"[csv-out] {len(csv_rows)} lignes ajoutees a {args.csv_out}")


# ============================================================================================
# 9. Validation hors-echantillon (walk-forward) : les zones sont detectees UNIQUEMENT sur les
#    donnees anterieures a une date de coupure, et la reaction n'est mesuree QUE sur les bougies
#    STRICTEMENT posterieures a cette coupure (jamais vues par la detection). Corrige un risque de
#    fuite du test "meme fenetre" (evaluate_technique) : une bougie de rejet qui sert a CONSTRUIRE
#    une zone (par definition, une bougie de rejet montre deja un mouvement de prix qui repousse le
#    niveau) peut aussi etre comptee comme une "touche" testee dans reaction_values -> risque de
#    circularite partielle. Le split walk-forward elimine ce risque : detection et test n'ont
#    JAMAIS acces aux memes bougies.
# ============================================================================================

def reaction_values_min_index(level, arrays, atr_arr, touch_tolerance, horizon, min_index):
    highs, lows, closes = arrays["high"], arrays["low"], arrays["close"]
    n = closes.shape[0]
    touching = (lows - touch_tolerance <= level) & (highs + touch_tolerance >= level)
    onset = np.zeros(n, dtype=bool)
    onset[1:] = touching[1:] & ~touching[:-1]
    onset[:min_index] = False
    idx = np.nonzero(onset)[0]
    if idx.size == 0:
        return np.empty(0)
    was_above = closes[idx - 1] >= level
    j = idx + horizon
    valid = j < n
    idx, was_above, j = idx[valid], was_above[valid], j[valid]
    if idx.size == 0:
        return np.empty(0)
    atr_at_touch = atr_arr[idx]
    valid_atr = ~np.isnan(atr_at_touch) & (atr_at_touch > 0)
    if not np.any(valid_atr):
        return np.empty(0)
    idx, was_above, j, atr_at_touch = idx[valid_atr], was_above[valid_atr], j[valid_atr], atr_at_touch[valid_atr]
    close_h = closes[j]
    raw = np.where(was_above, close_h - level, level - close_h)
    return raw / atr_at_touch


def evaluate_technique_oos(arrays, zones, atr_arr, touch_tolerance, horizon, n_permutations, rng, min_index):
    """Identique a evaluate_technique, mais toutes les 'touches' (zones ET baseline) sont
    restreintes a index >= min_index -> validation walk-forward, zero chevauchement entre les
    donnees utilisees pour detecter les zones et celles utilisees pour tester la reaction."""
    if not zones:
        return None
    zone_reaction_lists = [reaction_values_min_index(z.level, arrays, atr_arr, touch_tolerance, horizon, min_index)
                            for z in zones]
    all_zone_values = np.concatenate(zone_reaction_lists) if zone_reaction_lists else np.empty(0)
    if all_zone_values.size == 0:
        return None
    touch_pooled_mean = float(all_zone_values.mean())
    n_touches_total = int(all_zone_values.size)
    n_zones_tested = sum(1 for lst in zone_reaction_lists if lst.size > 0)

    null_means = []
    baseline_chunks = []
    baseline_pool_size = 0
    n_levels = len(zones)
    for _ in range(n_permutations):
        random_levels = sample_density_matched_levels(arrays, n_levels, rng)
        draw_values = np.concatenate([reaction_values_min_index(lvl, arrays, atr_arr, touch_tolerance, horizon, min_index)
                                       for lvl in random_levels]) if n_levels > 0 else np.empty(0)
        if draw_values.size > 0:
            null_means.append(float(draw_values.mean()))
            if baseline_pool_size < 5000:
                baseline_chunks.append(draw_values)
                baseline_pool_size += draw_values.size
    if not null_means:
        return None
    baseline_pool_for_mw = np.concatenate(baseline_chunks) if baseline_chunks else np.empty(0)
    null_means_arr = np.asarray(null_means)
    null_mean_avg = float(null_means_arr.mean())
    null_std = float(null_means_arr.std(ddof=0)) if null_means_arr.size > 1 else 0.0
    ge = int(np.sum(null_means_arr >= touch_pooled_mean))
    p_value_perm = (ge + 1) / (null_means_arr.size + 1)
    effect_size_perm = (touch_pooled_mean - null_mean_avg) / null_std if null_std > 1e-9 else float("nan")

    mw_p, mw_effect = None, None
    if all_zone_values.size >= 5 and baseline_pool_for_mw.size >= 5:
        try:
            u_stat, mw_p = sstats.mannwhitneyu(all_zone_values, baseline_pool_for_mw, alternative="greater")
            n1, n2 = all_zone_values.size, baseline_pool_for_mw.size
            mean_u = n1 * n2 / 2
            std_u = math.sqrt(n1 * n2 * (n1 + n2 + 1) / 12)
            z = (u_stat - mean_u) / std_u if std_u > 0 else 0.0
            mw_effect = z / math.sqrt(n1 + n2)
        except ValueError:
            pass

    return {
        "n_zones": len(zones), "n_zones_tested": n_zones_tested, "n_touches_total": n_touches_total,
        "touch_pooled_mean_atr": touch_pooled_mean, "null_mean_avg_atr": null_mean_avg,
        "null_std_atr": null_std, "p_value_permutation": p_value_perm,
        "effect_size_permutation": effect_size_perm, "mannwhitney_p": mw_p, "mannwhitney_effect_r": mw_effect,
    }


def walkforward_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--technique", default="rejection")
    parser.add_argument("--lookback", type=int, default=800)
    parser.add_argument("--horizons", default="3,5,10")
    parser.add_argument("--permutations", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--atr-period", type=int, default=14)
    parser.add_argument("--splits", default="0.5,0.6,0.7,0.8",
                         help="Fractions de la serie utilisees comme date de coupure (detection sur "
                              "[0:cutoff], test sur [cutoff:fin] uniquement)")
    args = parser.parse_args()

    candles = load_csv(args.csv)
    atr_series = wilder_atr(candles, args.atr_period)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    avg_range = statistics.mean(c["high"] - c["low"] for c in candles)
    touch_tolerance = avg_range * 0.25
    horizons = [int(h) for h in args.horizons.split(",")]
    splits = [float(s) for s in args.splits.split(",")]
    detect_fn = TECHNIQUES[args.technique]

    print(f"=== WALK-FORWARD (hors-echantillon) - {args.label or args.csv} - technique={args.technique} ===")
    print(f"Bougies totales: {len(candles)}  |  lookback detection={args.lookback}")
    for split in splits:
        cutoff = int(len(candles) * split)
        candles_train = candles[:cutoff]
        zones = detect_fn(candles_train, args.lookback)
        print(f"\n--- Coupure a {split:.0%} (bougie {cutoff}/{len(candles)}) : {len(zones)} zones detectees sur "
              f"les {min(args.lookback, cutoff)} dernieres bougies AVANT la coupure ---")
        if not zones:
            print("  (aucune zone)")
            continue
        for h in horizons:
            rng = np.random.default_rng(args.seed)
            result = evaluate_technique_oos(arrays, zones, atr_arr, touch_tolerance, h, args.permutations, rng, cutoff)
            if result is None:
                print(f"  horizon={h:3d} -> pas assez de touches HORS-ECHANTILLON testables (donnees post-coupure insuffisantes)")
                continue
            sig = "***" if result["p_value_permutation"] < 0.05 else ("*" if result["p_value_permutation"] < 0.10 else "")
            mw_str = (f"MW p={result['mannwhitney_p']:.3f}" if result['mannwhitney_p'] is not None else "MW n/a")
            print(f"  horizon={h:3d} | touches OOS={result['n_touches_total']:4d} ({result['n_zones_tested']}/{result['n_zones']} zones testables) | "
                  f"reaction={result['touch_pooled_mean_atr']:+.3f} ATR (baseline={result['null_mean_avg_atr']:+.3f}) | "
                  f"effect={result['effect_size_permutation']:+.2f} | p_perm={result['p_value_permutation']:.3f}{sig} | {mw_str}")


if __name__ == "__main__":
    if "--walkforward" in sys.argv:
        sys.argv.remove("--walkforward")
        walkforward_main()
    else:
        main()
