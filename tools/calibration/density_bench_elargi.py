#!/usr/bin/env python3
"""
Q3 (bench elargi, 2026-07-10) : retest de "plus les zones grises se chevauchent (plus c'est gris),
plus c'est fiable" sur BTC+ETH 2017-2026 (au lieu du petit echantillon BTC-seul 2024-2026).

Meme protocole que density_vs_reaction_analysis.py (densite = nb de zones, toutes TF confondues
DU MEME ACTIF, dont la bande chevauche celle de la zone testee ET dont la date d'ancrage est
<= la date de la touche -> pas de fuite). BTC et ETH sont traites separement pour le calcul de
densite (les bandes de prix ne se chevauchent jamais entre les deux actifs de toute facon), puis
les paires (densite, reaction) au niveau touche sont regroupees pour les tests statistiques finaux,
comme pour Q1/Q2/Q_volume sur le bench elargi.
"""
import sys
import numpy as np
from scipy import stats as sstats

sys.path.insert(0, ".")
from band_zone_test import load_csv, wilder_atr, to_arrays, atr_to_array, technique_consolidation, band_touch_events
from duration_vs_touches_analysis import dedup_zones

DATA_EXT = "/sessions/determined-tender-volta/mnt/TradeIO-5/target/calibration-data-extended"
HORIZON = 5
N_PERM = 1000
SEED = 42
TF_FILES = {"d1": "{a}_d1_full.csv", "h4": "{a}_h4_full.csv", "h1": "{a}_h1_ext.csv"}
ASSETS = ("btcusdt", "ethusdt")
TFS = ("d1", "h4", "h1")


def overlaps(z1, z2):
    return not (z1.band_high < z2.band_low or z1.band_low > z2.band_high)


def density_at(target_zone, touch_date, flat_zones):
    return sum(1 for (z2, d2) in flat_zones if d2 <= touch_date and overlaps(target_zone, z2))


def process_asset(asset):
    per_tf = {}
    flat = []
    for tf in TFS:
        candles = load_csv(f"{DATA_EXT}/{TF_FILES[tf].format(a=asset)}")
        atr_series = wilder_atr(candles, 14)
        arrays = to_arrays(candles)
        atr_arr = atr_to_array(atr_series)
        zones_raw = technique_consolidation(candles, lookback=len(candles), min_window=10,
                                             max_window=40, window_step=4, trim_pct=10,
                                             max_range_atr_mult=2.5)
        zones = dedup_zones(zones_raw, candles)
        per_tf[tf] = dict(candles=candles, arrays=arrays, atr_arr=atr_arr, zones=zones)
        for z in zones:
            anchor_date = candles[min(z.end_idx, len(candles) - 1)]["timestamp"]
            flat.append((z, anchor_date))

    pairs = []
    for tf in TFS:
        d = per_tf[tf]
        for z in d["zones"]:
            idx, from_above = band_touch_events(z.band_low, z.band_high, d["arrays"], min_index=0)
            if idx.size == 0:
                continue
            j = idx + HORIZON
            valid = j < d["arrays"]["close"].shape[0]
            idx, from_above, j = idx[valid], from_above[valid], j[valid]
            for k in range(idx.size):
                i = idx[k]
                atr_here = d["atr_arr"][i]
                if np.isnan(atr_here) or atr_here <= 0:
                    continue
                close_h = d["arrays"]["close"][j[k]]
                raw = (close_h - z.band_high) if from_above[k] else (z.band_low - close_h)
                reaction = raw / atr_here
                touch_date = d["candles"][i]["timestamp"]
                dens = density_at(z, touch_date, flat)
                pairs.append((dens, reaction))
    print(f"  {asset}: {sum(len(per_tf[tf]['zones']) for tf in TFS)} zones dedupliquees, "
          f"{len(pairs)} touches", flush=True)
    return pairs


print("=== Q3 bench elargi : densite de chevauchement vs reaction (BTC+ETH, 2017-2026) ===", flush=True)
all_pairs = []
for asset in ASSETS:
    all_pairs.extend(process_asset(asset))

densities = np.array([p[0] for p in all_pairs], dtype=float)
reactions = np.array([p[1] for p in all_pairs], dtype=float)
print(f"\nTotal touches (BTC+ETH) : {len(all_pairs)}")
print(f"Densite : min={densities.min():.0f} max={densities.max():.0f} moyenne={densities.mean():.2f}")

rho, p_spearman = sstats.spearmanr(densities, reactions)
print(f"\nSpearman (densite vs reaction, n={len(all_pairs)}) : rho={rho:+.3f}, p={p_spearman:.4f}")

q1, q2 = np.percentile(densities, [33.3, 66.7])
low_vals = reactions[densities <= q1]
high_vals = reactions[densities >= q2]
print(f"Tercile basse densite (<= {q1:.1f}, n={low_vals.size})  : {low_vals.mean():+.3f} ATR")
print(f"Tercile haute densite (>= {q2:.1f}, n={high_vals.size}) : {high_vals.mean():+.3f} ATR")

observed_diff = high_vals.mean() - low_vals.mean()
rng = np.random.default_rng(SEED)
pooled = np.concatenate([low_vals, high_vals])
n_low = low_vals.size
null_diffs = np.empty(N_PERM)
for i in range(N_PERM):
    perm = rng.permutation(pooled)
    null_diffs[i] = perm[n_low:].mean() - perm[:n_low].mean()
p_perm = (np.sum(np.abs(null_diffs) >= abs(observed_diff)) + 1) / (N_PERM + 1)
print(f"Ecart haute-basse densite = {observed_diff:+.3f} ATR | p (permutation) = {p_perm:.4f}")
