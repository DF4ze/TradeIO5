#!/usr/bin/env python3
"""
Technique "rectangle de consolidation" (idee de Clem, 2026-07-09, partagee en cours de session
autonome) : contrairement aux 6 techniques testees dans robust_zone_test.py (qui produisent toutes
un NIVEAU de prix ponctuel), celle-ci produit une BANDE de prix (rectangle [low, high]), construite
ainsi :

  1. Reperer une periode de "stagnation" (range resserre) — bornes ROBUSTES (percentile, pas
     min/max brut) pour ne pas se faire tirer par une meche isolee qui depasse un peu.
  2. Ancrer le rectangle a la FIN de cette stagnation, confirmee par une cassure franche juste
     apres (sinon ce n'est pas vraiment "la fin" d'une consolidation, juste une pause).
  3. Projeter ce rectangle horizontalement vers l'avant (il ne bouge plus, il s'etend jusqu'a la fin
     de la serie/"aujourd'hui") — differe des zones ponctuelles precedentes qui etaient retestees
     comme un niveau + tolerance fixe, ici c'est une vraie fourchette de prix.

Description originale de Clem : "quand il y a stagnation, je fais un rectangle qui englobe la fin de
la stagnation, je ne prends pas les valeurs extremes qui ressortent un peu, puis je tire
horizontalement ce rectangle jusqu'a aujourd'hui." + un raffinement adaptatif optionnel (retrecir le
cote qui a vu un rebond net) — non implemente ici (v1, cf. section finale du rapport pour la piste).

Meme framework statistique que robust_zone_test.py (magnitude continue normalisee ATR, baseline
density-matched, test de permutation + Mann-Whitney, validation walk-forward), adapte pour une bande
plutot qu'un niveau ponctuel :
  - "touche" = le prix ENTRE dans la bande en venant clairement de l'exterieur (clôture precedente
    strictement au-dessus de band_high ou strictement en-dessous de band_low — un prix deja a
    l'interieur de la bande n'est pas un "test" frais de la frontiere).
  - "reaction" = distance parcourue apres un horizon fixe, comptee comme un REJET du meme cote
    d'entree (positif = bon, la bande a tenu) ou un TRANSPERCEMENT vers l'autre cote (negatif).
  - baseline = bandes aleatoires de meme LARGEUR que la bande reelle appariee, centrees sur un
    niveau density-matched (vraie cloture historique) — controle a la fois pour la densite de prix
    ET la largeur de bande (une bande plus large est triviallement touchee differemment).

Usage:
    python3 consolidation_zone_test.py --csv <path.csv> --label H1 --lookback 3000 \
        --horizons 3,5,10 --permutations 300
    python3 consolidation_zone_test.py --walkforward --csv <path.csv> --label H1 --lookback 3000 \
        --horizons 5 --splits 0.4,0.6,0.8
"""

import argparse
import csv
import math
import statistics
import sys
from dataclasses import dataclass
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
                "open": float(row["open"]), "high": float(row["high"]),
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


def to_arrays(candles):
    return {
        "high": np.asarray([c["high"] for c in candles], dtype=np.float64),
        "low": np.asarray([c["low"] for c in candles], dtype=np.float64),
        "close": np.asarray([c["close"] for c in candles], dtype=np.float64),
    }


def atr_to_array(atr_series):
    return np.asarray([v if v is not None else np.nan for v in atr_series], dtype=np.float64)


@dataclass
class RectZone:
    band_low: float
    band_high: float
    start_idx: int
    end_idx: int  # index ou la consolidation se termine / cassure confirmee = ancrage du rectangle

    @property
    def width(self):
        return self.band_high - self.band_low

    @property
    def mid(self):
        return (self.band_high + self.band_low) / 2.0


# ============================================================================================
# Detection : scan sequentiel, extension gloutonne d'une fenetre tant que le range (robuste,
# percentile) reste sous le seuil ATR, confirmation par cassure juste apres.
# ============================================================================================

def detect_consolidation_rectangles(candles, lookback, atr_period=14, min_window=10, max_window=40,
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

    zones = []
    i = start
    window_sizes = list(range(min_window, max_window + 1, window_step))
    while i < n - min_window:
        atr_probe = atr_series[i + min_window] if i + min_window < n else None
        if atr_probe is None or atr_probe <= 0:
            i += 1
            continue
        # rejet rapide : la fenetre minimale doit deja etre relativement resserree
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
                break  # au-dela, la fenetre s'est trop elargie -> on garde le dernier "best" valide
            best = (i, end, band_low, band_high, atr_here)

        if best is None:
            i += 1
            continue

        w_start, w_end, band_low, band_high, atr_here = best
        broke = False
        for k in range(w_end, min(w_end + breakout_confirm_candles, n)):
            c = closes_all[k]
            if c > band_high + breakout_margin_atr_mult * atr_here or \
               c < band_low - breakout_margin_atr_mult * atr_here:
                broke = True
                break

        if broke:
            zones.append(RectZone(band_low, band_high, w_start, w_end))
            i = w_end + breakout_confirm_candles
        else:
            i += 1

    return zones


# ============================================================================================
# Framework statistique adapte aux bandes.
# ============================================================================================

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
    valid = from_above | from_below  # exclut les cas ou on etait deja "dans" la bande avant
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
    # positif = rejete du meme cote qu'entre (la bande a "tenu") ; negatif = transperce de l'autre cote
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


def evaluate_bands(arrays, zones, atr_arr, horizon, n_permutations, rng, min_index=0):
    if not zones:
        return None
    per_zone = [band_reaction_values(z.band_low, z.band_high, arrays, atr_arr, horizon, min_index) for z in zones]
    all_values = np.concatenate(per_zone) if per_zone else np.empty(0)
    if all_values.size == 0:
        return None
    touch_pooled_mean = float(all_values.mean())
    n_touches = int(all_values.size)
    n_zones_tested = sum(1 for v in per_zone if v.size > 0)
    widths = [z.width for z in zones]
    # largeur normalisee ATR (ATR au moment ou le rectangle est ancre) : purement informatif,
    # n'affecte pas le calcul de reaction (deja normalise cote band_reaction_values).
    widths_atr_norm = [z.width / atr_arr[min(z.end_idx, atr_arr.shape[0]-1)]
                        for z in zones if not np.isnan(atr_arr[min(z.end_idx, atr_arr.shape[0]-1)])
                        and atr_arr[min(z.end_idx, atr_arr.shape[0]-1)] > 0]

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

    return dict(n_zones=len(zones), n_zones_tested=n_zones_tested, n_touches=n_touches,
                reaction_mean_atr=touch_pooled_mean, baseline_mean_atr=null_mean_avg,
                null_std_atr=null_std, effect_size_perm=effect, p_value_perm=p_perm,
                mw_p=mw_p, mw_effect_r=mw_effect,
                avg_width_atr=float(np.mean(widths_atr_norm)) if widths_atr_norm else None,
                avg_width_price=float(np.mean(widths)) if widths else None)


# ============================================================================================
# Orchestration
# ============================================================================================

def print_result(h, result, atr_period_note=""):
    if result is None:
        print(f"  horizon={h:3d}  -> pas assez de touches testables")
        return
    sig = "***" if result["p_value_perm"] < 0.05 else ("*" if result["p_value_perm"] < 0.10 else "")
    mw_str = f"MW p={result['mw_p']:.3f}" if result["mw_p"] is not None else "MW n/a"
    print(f"  horizon={h:3d} | touches={result['n_touches']:4d} ({result['n_zones_tested']}/{result['n_zones']} zones) | "
          f"largeur moy={result['avg_width_atr']:.2f} ATR | "
          f"reaction={result['reaction_mean_atr']:+.3f} ATR (baseline={result['baseline_mean_atr']:+.3f}) | "
          f"effect={result['effect_size_perm']:+.2f} | p_perm={result['p_value_perm']:.3f}{sig} | {mw_str}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--lookback", type=int, default=3000)
    parser.add_argument("--horizons", default="3,5,10")
    parser.add_argument("--permutations", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--atr-period", type=int, default=14)
    parser.add_argument("--min-window", type=int, default=10)
    parser.add_argument("--max-window", type=int, default=40)
    parser.add_argument("--trim-pct", type=float, default=10)
    parser.add_argument("--max-range-atr", type=float, default=2.5)
    args = parser.parse_args()

    candles = load_csv(args.csv)
    atr_series = wilder_atr(candles, args.atr_period)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    horizons = [int(h) for h in args.horizons.split(",")]

    zones = detect_consolidation_rectangles(candles, args.lookback, atr_period=args.atr_period,
                                             min_window=args.min_window, max_window=args.max_window,
                                             trim_pct=args.trim_pct, max_range_atr_mult=args.max_range_atr)
    print(f"=== CONSOLIDATION RECTANGLE - {args.label or args.csv} ===")
    print(f"Bougies: {len(candles)} | lookback={args.lookback} | {len(zones)} rectangles detectes")
    for z in sorted(zones, key=lambda z: -z.width)[:15]:
        print(f"  [{z.band_low:.2f} - {z.band_high:.2f}]  largeur={z.width:.2f}  "
              f"fenetre=[{z.start_idx}:{z.end_idx}] ({z.end_idx - z.start_idx} bougies)")
    print()
    if not zones:
        return
    print("--- TEST STATISTIQUE (meme fenetre, exploratoire) ---")
    for h in horizons:
        rng = np.random.default_rng(args.seed)
        result = evaluate_bands(arrays, zones, atr_arr, h, args.permutations, rng, min_index=0)
        print_result(h, result)


def walkforward_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--lookback", type=int, default=3000)
    parser.add_argument("--horizons", default="5")
    parser.add_argument("--permutations", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--atr-period", type=int, default=14)
    parser.add_argument("--min-window", type=int, default=10)
    parser.add_argument("--max-window", type=int, default=40)
    parser.add_argument("--trim-pct", type=float, default=10)
    parser.add_argument("--max-range-atr", type=float, default=2.5)
    parser.add_argument("--splits", default="0.3,0.4,0.5,0.6,0.7,0.8,0.9")
    args = parser.parse_args()

    candles = load_csv(args.csv)
    atr_series = wilder_atr(candles, args.atr_period)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    horizons = [int(h) for h in args.horizons.split(",")]
    splits = [float(s) for s in args.splits.split(",")]

    print(f"=== CONSOLIDATION RECTANGLE - WALK-FORWARD - {args.label or args.csv} ===")
    print(f"Bougies totales: {len(candles)} | lookback detection={args.lookback}")
    for split in splits:
        cutoff = int(len(candles) * split)
        candles_train = candles[:cutoff]
        zones = detect_consolidation_rectangles(candles_train, args.lookback, atr_period=args.atr_period,
                                                  min_window=args.min_window, max_window=args.max_window,
                                                  trim_pct=args.trim_pct, max_range_atr_mult=args.max_range_atr)
        print(f"\n--- Coupure a {split:.0%} (bougie {cutoff}/{len(candles)}) : {len(zones)} rectangles ---")
        if not zones:
            continue
        for h in horizons:
            rng = np.random.default_rng(args.seed)
            result = evaluate_bands(arrays, zones, atr_arr, h, args.permutations, rng, min_index=cutoff)
            print_result(h, result)


if __name__ == "__main__":
    if "--walkforward" in sys.argv:
        sys.argv.remove("--walkforward")
        walkforward_main()
    else:
        main()
