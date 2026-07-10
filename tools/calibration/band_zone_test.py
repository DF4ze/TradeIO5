#!/usr/bin/env python3
"""
Unification "bande" (idee de Clem, 2026-07-09) : "je ne cherche pas LE prix de rebond/rejet exact,
je cherche un indicateur plus ou moins pondere". Les 6 techniques de robust_zone_test.py produisaient
un NIVEAU ponctuel + une tolerance de contact FIXE et globale (0.25x range moyen de toute la serie),
independante de la technique. Ici, chaque zone porte sa PROPRE largeur, derivee de sa construction :

  - rejection / pivot / wickdensity (techniques a clustering) : largeur = etendue reelle des
    membres du cluster (min a max des niveaux qui l'ont forme) — une zone formee par des touches
    dispersees est naturellement plus large qu'une zone tres precise. Plancher a 0.5x ATR pour
    eviter une largeur nulle sur un cluster a 1 seul membre.
  - volprofile : largeur = etendue du bin (ou des bins fusionnes) qui a defini le HVN.
  - roundnumbers / kde : pas de largeur naturelle -> marge fixe de 0.5x ATR de chaque cote (defaut,
    parametrable), pour rester dans le meme ordre de grandeur que les autres techniques.
  - consolidation (rectangle de Clem) : deja une bande native, reprise telle quelle.

Meme framework statistique "bande" que consolidation_zone_test.py (touche = entree depuis
l'exterieur, reaction = rejet du meme cote vs transpercement, normalise ATR, baseline
density-matched de meme largeur, permutation + Mann-Whitney, walk-forward).

Usage:
    python3 band_zone_test.py --csv <path.csv> --label H1 --technique all --lookback 800 \
        --horizons 3,5,10 --permutations 300
    python3 band_zone_test.py --walkforward --csv <path.csv> --label H1 --technique rejection \
        --lookback 800 --horizons 5 --splits 0.3,0.5,0.7,0.9
"""

import argparse
import csv
import math
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
                "timestamp": ts, "open": float(row["open"]), "high": float(row["high"]),
                "low": float(row["low"]), "close": float(row["close"]),
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
class Band:
    band_low: float
    band_high: float
    level: float       # centre / niveau "de reference" (pour affichage)
    strength: float
    touches: int
    end_idx: int        # index d'ancrage (derniere bougie vue par la detection) -> pour l'ATR de largeur


W_TOUCHES, W_VOLUME, W_RECENCY = 1.0, 0.5, 1.0
RECENCY_HALF_LIFE, STRENGTH_SATURATION = 50.0, 5.0
MIN_WIDTH_ATR_MULT = 0.5  # plancher de largeur pour les clusters a 1 membre / techniques sans largeur naturelle


def _cluster_to_band(members, direction, window_avg_volume, atr_value, end_idx):
    touches = len(members)
    level = sum(m.level for m in members) / touches
    recency_sum = sum(0.5 ** (m.age / RECENCY_HALF_LIFE) for m in members)
    avg_zone_volume = sum(m.volume for m in members) / touches
    volume_score = (avg_zone_volume / window_avg_volume) if window_avg_volume > 1e-9 else 0.0
    raw = W_TOUCHES * touches + W_VOLUME * volume_score + W_RECENCY * recency_sum
    strength = raw / (raw + STRENGTH_SATURATION)
    lo = min(m.level for m in members)
    hi = max(m.level for m in members)
    min_width = MIN_WIDTH_ATR_MULT * atr_value
    if hi - lo < min_width:
        pad = (min_width - (hi - lo)) / 2.0
        lo, hi = lo - pad, hi + pad
    return Band(lo, hi, level, strength, touches, end_idx)


def cluster_to_bands(points, cluster_distance, window_avg_volume, atr_value, end_idx):
    bands = []
    for direction in ("support", "resistance"):
        pts = sorted([r for r in points if r.direction == direction], key=lambda r: r.level)
        if not pts:
            continue
        current = [pts[0]]
        for r in pts[1:]:
            if r.level - current[-1].level <= cluster_distance:
                current.append(r)
            else:
                bands.append(_cluster_to_band(current, direction, window_avg_volume, atr_value, end_idx))
                current = [r]
        bands.append(_cluster_to_band(current, direction, window_avg_volume, atr_value, end_idx))
    return bands


# ---- Technique A : rejection ----
def detect_rejections(window, atr_value, k1, k2, p):
    n = len(window)
    rejections = []
    for i, c in enumerate(window):
        o, h, l, cl = c["open"], c["high"], c["low"], c["close"]
        rng = h - l
        if rng <= 0:
            continue
        body = abs(cl - o)
        lower_wick, upper_wick = min(o, cl) - l, h - max(o, cl)
        age, volume = n - 1 - i, c["volume"]
        if lower_wick > k1 * body and lower_wick > k2 * atr_value and cl > l + p * rng:
            rejections.append(Rejection(l, age, volume, "support"))
        if upper_wick > k1 * body and upper_wick > k2 * atr_value and cl < h - p * rng:
            rejections.append(Rejection(h, age, volume, "resistance"))
    return rejections


def technique_rejection(candles, lookback, k1=1.5, k2=0.5, p=0.66, cluster_distance_mult=0.5, atr_period=14):
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
    return cluster_to_bands(rejections, cluster_distance, window_avg_volume, atr_value, n - 1)


# ---- Technique C : pivot ----
def technique_pivot(candles, lookback, fractal_n=3, cluster_distance_mult=0.5, atr_period=14):
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
        neigh = window[i - fractal_n:i] + window[i + 1:i + 1 + fractal_n]
        age = wn - 1 - i
        if all(c["high"] >= o["high"] for o in neigh):
            swings.append(Rejection(c["high"], age, c["volume"], "resistance"))
        if all(c["low"] <= o["low"] for o in neigh):
            swings.append(Rejection(c["low"], age, c["volume"], "support"))
    window_avg_volume = sum(c["volume"] for c in window) / len(window)
    cluster_distance = cluster_distance_mult * atr_value
    return cluster_to_bands(swings, cluster_distance, window_avg_volume, atr_value, n - 1)


# ---- Technique D : wickdensity ----
def technique_wickdensity(candles, lookback, atr_period=14, cluster_distance_mult=0.35, min_samples=4):
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
    bands = cluster_to_bands(points, cluster_distance, window_avg_volume, atr_value, n - 1)
    return [b for b in bands if b.touches >= min_samples]


# ---- Technique B : volprofile ----
def technique_volprofile(candles, lookback, n_bins=80, top_percentile=80, min_gap_bins=2, atr_period=14):
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
    age_w_bins = [0.0] * n_bins
    for idx, c in enumerate(window):
        lo, hi, vol = c["low"], c["high"], c["volume"]
        age = len(window) - 1 - idx
        span = hi - lo
        recw = 0.5 ** (age / RECENCY_HALF_LIFE)
        if span <= 0:
            b = min(max(int((c["close"] - price_min) / bin_width), 0), n_bins - 1)
            vol_bins[b] += vol
            age_w_bins[b] += recw
            continue
        start_b = max(0, int((lo - price_min) / bin_width))
        end_b = min(n_bins - 1, int((hi - price_min) / bin_width))
        for b in range(start_b, end_b + 1):
            b_lo = price_min + b * bin_width
            b_hi = b_lo + bin_width
            overlap = max(0.0, min(hi, b_hi) - max(lo, b_lo))
            frac = overlap / span
            vol_bins[b] += vol * frac
            age_w_bins[b] += recw * frac
    threshold = np.percentile(vol_bins, top_percentile)
    peaks = []
    for i in range(1, n_bins - 1):
        if vol_bins[i] >= threshold and vol_bins[i] >= vol_bins[i - 1] and vol_bins[i] >= vol_bins[i + 1]:
            peaks.append((i, vol_bins[i], age_w_bins[i]))
    peaks.sort(key=lambda x: x[0])
    merged = []  # (bin_start, bin_end, vol, rec)
    for i, vol, rec in peaks:
        if merged and (i - merged[-1][1]) < min_gap_bins:
            if vol > merged[-1][2]:
                merged[-1] = (merged[-1][0], i, vol, rec)
            else:
                merged[-1] = (merged[-1][0], i, merged[-1][2], merged[-1][3])
        else:
            merged.append((i, i, vol, rec))
    if not merged:
        return []
    max_vol = max(v for _, _, v, _ in merged)
    max_rec = max(r for _, _, _, r in merged) or 1.0
    bands = []
    for b_start, b_end, vol, rec in merged:
        lo = price_min + b_start * bin_width
        hi = price_min + (b_end + 1) * bin_width
        strength = 0.5 * (vol / max_vol) + 0.5 * (rec / max_rec)
        bands.append(Band(lo, hi, (lo + hi) / 2, strength, 0, n - 1))
    return bands


# ---- Technique E : roundnumbers ----
def technique_roundnumbers(candles, lookback, step=1000.0, atr_period=14, margin_atr_mult=0.5):
    n = len(candles)
    if n < lookback:
        return []
    window = candles[n - lookback:]
    atr_series = wilder_atr(candles, atr_period)
    atr_value = atr_series[n - 1]
    if atr_value is None or atr_value <= 0:
        return []
    price_min = min(c["low"] for c in window)
    price_max = max(c["high"] for c in window)
    margin = margin_atr_mult * atr_value
    bands = []
    level = math.floor(price_min / step) * step
    while level <= price_max:
        if price_min <= level <= price_max:
            bands.append(Band(level - margin, level + margin, level, 1.0, 0, n - 1))
        level += step
    return bands


# ---- Technique F : kde ----
def technique_kde(candles, lookback, atr_period=14, bandwidth=None, n_peaks=25, min_gap_mult=0.5, margin_atr_mult=0.5):
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
    peaks_idx = [i for i in range(1, len(grid) - 1) if density[i] >= density[i - 1] and density[i] >= density[i + 1]]
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
    dens_sel = [float(kde(lvl)[0]) for lvl in selected]
    max_d = max(dens_sel) if dens_sel else 1.0
    margin = margin_atr_mult * atr_value
    bands = []
    for lvl, d in zip(selected, dens_sel):
        strength = min(d / max_d, 1.0) if max_d > 0 else 0.0
        bands.append(Band(float(lvl) - margin, float(lvl) + margin, float(lvl), strength, 0, n - 1))
    return bands


# ---- Technique G : consolidation rectangle (Clem) ----
def technique_consolidation(candles, lookback, atr_period=14, min_window=10, max_window=40,
                             window_step=5, trim_pct=10, max_range_atr_mult=2.5,
                             breakout_confirm_candles=3, breakout_margin_atr_mult=0.5):
    n = len(candles)
    if n < lookback:
        lookback = n
    start = max(0, n - lookback)
    atr_series = wilder_atr(candles, atr_period)
    highs_all = [c["high"] for c in candles]
    lows_all = [c["low"] for c in candles]
    closes_all = [c["close"] for c in candles]
    bands = []
    i = start
    window_sizes = list(range(min_window, max_window + 1, window_step))
    while i < n - min_window:
        atr_probe = atr_series[i + min_window] if i + min_window < n else None
        if atr_probe is None or atr_probe <= 0:
            i += 1
            continue
        probe_high = np.percentile(highs_all[i:i + min_window], 100 - trim_pct)
        probe_low = np.percentile(lows_all[i:i + min_window], trim_pct)
        if probe_high - probe_low > max_range_atr_mult * atr_probe * 1.3:
            i += 1
            continue
        best = None
        for w in window_sizes:
            end = i + w
            if end >= n:
                break
            atr_here = atr_series[end]
            if atr_here is None or atr_here <= 0:
                continue
            band_high = np.percentile(highs_all[i:end], 100 - trim_pct)
            band_low = np.percentile(lows_all[i:end], trim_pct)
            if band_high - band_low > max_range_atr_mult * atr_here:
                break
            best = (i, end, band_low, band_high, atr_here)
        if best is None:
            i += 1
            continue
        w_start, w_end, band_low, band_high, atr_here = best
        broke = False
        for k in range(w_end, min(w_end + breakout_confirm_candles, n)):
            c = closes_all[k]
            if c > band_high + breakout_margin_atr_mult * atr_here or c < band_low - breakout_margin_atr_mult * atr_here:
                broke = True
                break
        if broke:
            bands.append(Band(band_low, band_high, (band_low + band_high) / 2, 1.0, w_end - w_start, w_end))
            i = w_end + breakout_confirm_candles
        else:
            i += 1
    return bands


TECHNIQUES = {
    "rejection": technique_rejection, "volprofile": technique_volprofile, "pivot": technique_pivot,
    "wickdensity": technique_wickdensity, "roundnumbers": technique_roundnumbers, "kde": technique_kde,
    "consolidation": technique_consolidation,
}


# ============================================================================================
# Framework statistique "bande" (identique a consolidation_zone_test.py)
# ============================================================================================

def to_arrays(candles):
    return {
        "high": np.asarray([c["high"] for c in candles], dtype=np.float64),
        "low": np.asarray([c["low"] for c in candles], dtype=np.float64),
        "close": np.asarray([c["close"] for c in candles], dtype=np.float64),
    }


def atr_to_array(atr_series):
    return np.asarray([v if v is not None else np.nan for v in atr_series], dtype=np.float64)


def band_touch_events(band_low, band_high, arrays, min_index):
    highs, lows, closes = arrays["high"], arrays["low"], arrays["close"]
    n = closes.shape[0]
    inside = (lows <= band_high) & (highs >= band_low)
    onset = np.zeros(n, dtype=bool)
    onset[1:] = inside[1:] & ~inside[:-1]
    lo = max(min_index, 1)
    onset[:lo] = False
    idx = np.nonzero(onset)[0]
    if idx.size == 0:
        return np.empty(0, dtype=int), np.empty(0, dtype=bool)
    prev_close = closes[idx - 1]
    from_above = prev_close > band_high
    from_below = prev_close < band_low
    valid = from_above | from_below
    return idx[valid], from_above[valid]


def band_reaction_values(band_low, band_high, arrays, atr_arr, horizon, min_index=0):
    idx, from_above = band_touch_events(band_low, band_high, arrays, min_index)
    if idx.size == 0:
        return np.empty(0)
    closes = arrays["close"]
    n = closes.shape[0]
    j = idx + horizon
    valid = j < n
    idx, from_above, j = idx[valid], from_above[valid], j[valid]
    if idx.size == 0:
        return np.empty(0)
    atr_at_touch = atr_arr[idx]
    valid_atr = ~np.isnan(atr_at_touch) & (atr_at_touch > 0)
    if not np.any(valid_atr):
        return np.empty(0)
    idx, from_above, j, atr_at_touch = idx[valid_atr], from_above[valid_atr], j[valid_atr], atr_at_touch[valid_atr]
    close_h = closes[j]
    raw = np.where(from_above, close_h - band_high, band_low - close_h)
    return raw / atr_at_touch


def sample_density_matched_bands(arrays, widths, rng):
    closes = arrays["close"]
    n = closes.shape[0]
    picks = rng.integers(0, n, size=len(widths))
    centers = closes[picks]
    lows = centers - np.asarray(widths) / 2.0
    highs = centers + np.asarray(widths) / 2.0
    return lows, highs


def evaluate_bands(arrays, bands, atr_arr, horizon, n_permutations, rng, min_index=0):
    if not bands:
        return None
    per_zone = [band_reaction_values(b.band_low, b.band_high, arrays, atr_arr, horizon, min_index) for b in bands]
    all_values = np.concatenate(per_zone) if per_zone else np.empty(0)
    if all_values.size == 0:
        return None
    touch_pooled_mean = float(all_values.mean())
    n_touches = int(all_values.size)
    n_zones_tested = sum(1 for v in per_zone if v.size > 0)
    widths = [b.band_high - b.band_low for b in bands]

    null_means, baseline_chunks, baseline_size = [], [], 0
    for _ in range(n_permutations):
        lows, highs = sample_density_matched_bands(arrays, widths, rng)
        draw = np.concatenate([band_reaction_values(lo, hi, arrays, atr_arr, horizon, min_index)
                                for lo, hi in zip(lows, highs)]) if len(widths) > 0 else np.empty(0)
        if draw.size > 0:
            null_means.append(float(draw.mean()))
            if baseline_size < 5000:
                baseline_chunks.append(draw)
                baseline_size += draw.size
    if not null_means:
        return None
    baseline_pool = np.concatenate(baseline_chunks) if baseline_chunks else np.empty(0)
    null_arr = np.asarray(null_means)
    null_mean_avg = float(null_arr.mean())
    null_std = float(null_arr.std(ddof=0)) if null_arr.size > 1 else 0.0
    ge = int(np.sum(null_arr >= touch_pooled_mean))
    p_perm = (ge + 1) / (null_arr.size + 1)
    effect = (touch_pooled_mean - null_mean_avg) / null_std if null_std > 1e-9 else float("nan")

    mw_p, mw_effect = None, None
    if all_values.size >= 5 and baseline_pool.size >= 5:
        try:
            u, mw_p = sstats.mannwhitneyu(all_values, baseline_pool, alternative="greater")
            n1, n2 = all_values.size, baseline_pool.size
            mean_u, std_u = n1 * n2 / 2, math.sqrt(n1 * n2 * (n1 + n2 + 1) / 12)
            z = (u - mean_u) / std_u if std_u > 0 else 0.0
            mw_effect = z / math.sqrt(n1 + n2)
        except ValueError:
            pass

    return dict(n_zones=len(bands), n_zones_tested=n_zones_tested, n_touches=n_touches,
                reaction_mean_atr=touch_pooled_mean, baseline_mean_atr=null_mean_avg,
                effect_size_perm=effect, p_value_perm=p_perm, mw_p=mw_p, mw_effect_r=mw_effect)


def print_result(h, result):
    if result is None:
        print(f"    horizon={h:3d}  -> pas assez de touches testables")
        return
    sig = "***" if result["p_value_perm"] < 0.05 else ("*" if result["p_value_perm"] < 0.10 else "")
    mw_str = f"MW p={result['mw_p']:.3f}" if result["mw_p"] is not None else "MW n/a"
    print(f"    horizon={h:3d} | touches={result['n_touches']:4d} ({result['n_zones_tested']}/{result['n_zones']} zones) | "
          f"reaction={result['reaction_mean_atr']:+.3f} ATR (baseline={result['baseline_mean_atr']:+.3f}) | "
          f"effect={result['effect_size_perm']:+.2f} | p_perm={result['p_value_perm']:.3f}{sig} | {mw_str}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--technique", default="all")
    parser.add_argument("--lookback", type=int, default=800)
    parser.add_argument("--horizons", default="3,5,10")
    parser.add_argument("--permutations", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--atr-period", type=int, default=14)
    args = parser.parse_args()

    candles = load_csv(args.csv)
    atr_series = wilder_atr(candles, args.atr_period)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    horizons = [int(h) for h in args.horizons.split(",")]
    techniques = list(TECHNIQUES.keys()) if args.technique == "all" else [args.technique]

    print(f"=== BAND ZONE TEST - {args.label or args.csv} ===")
    print(f"Bougies: {len(candles)} | lookback={args.lookback}")
    for tech in techniques:
        bands = TECHNIQUES[tech](candles, args.lookback)
        print(f"\n--- {tech} ({len(bands)} bandes) ---")
        if not bands:
            continue
        for h in horizons:
            rng = np.random.default_rng(args.seed)
            result = evaluate_bands(arrays, bands, atr_arr, h, args.permutations, rng, min_index=0)
            print_result(h, result)


def walkforward_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--technique", default="rejection")
    parser.add_argument("--lookback", type=int, default=800)
    parser.add_argument("--horizons", default="5")
    parser.add_argument("--permutations", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--splits", default="0.3,0.4,0.5,0.6,0.7,0.8,0.9")
    args = parser.parse_args()

    candles = load_csv(args.csv)
    atr_series = wilder_atr(candles, 14)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    horizons = [int(h) for h in args.horizons.split(",")]
    splits = [float(s) for s in args.splits.split(",")]
    techniques = list(TECHNIQUES.keys()) if args.technique == "all" else [args.technique]

    print(f"=== BAND ZONE TEST - WALK-FORWARD - {args.label or args.csv} ===")
    print(f"Bougies totales: {len(candles)} | lookback detection={args.lookback}")
    for tech in techniques:
        print(f"\n########## {tech} ##########")
        for split in splits:
            cutoff = int(len(candles) * split)
            bands = TECHNIQUES[tech](candles[:cutoff], args.lookback)
            print(f"  -- coupure {split:.0%} (bougie {cutoff}) : {len(bands)} bandes --")
            if not bands:
                continue
            for h in horizons:
                rng = np.random.default_rng(args.seed)
                result = evaluate_bands(arrays, bands, atr_arr, h, args.permutations, rng, min_index=cutoff)
                print_result(h, result)


if __name__ == "__main__":
    if "--walkforward" in sys.argv:
        sys.argv.remove("--walkforward")
        walkforward_main()
    else:
        main()
