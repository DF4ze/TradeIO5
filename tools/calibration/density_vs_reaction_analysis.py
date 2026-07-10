#!/usr/bin/env python3
"""
Q3 (2026-07-09, demande de Clem apres avoir vu la page web) : "plus les zones grises se
chevauchent (plus c'est gris), plus c'est fiable" -- teste directement l'hypothese que la DENSITE
de recouvrement des zones 'consolidation' a un prix donne est correlee a une meilleure reaction.

Definition de la densite pour une touche donnee (zone Z, a la date t_i) : nombre de zones
(dedupliquees, toutes TF confondues, y compris Z elle-meme) dont la bande [band_low, band_high]
chevauche celle de Z, ET dont la date d'ancrage est <= t_i (seules les zones deja "formees" a ce
moment comptent -> pas de fuite : une zone qui apparait plus tard sur le graphique ne peut pas
contribuer a l'epaisseur de gris VUE a un instant passe).

Meme protocole statistique que Q1 (correlation de Spearman + comparaison par tercile + test de
permutation), applique a densite au lieu de duree.
"""
import sys
import numpy as np
from scipy import stats as sstats

sys.path.insert(0, ".")
from band_zone_test import load_csv, wilder_atr, to_arrays, atr_to_array, technique_consolidation, band_touch_events
from duration_vs_touches_analysis import dedup_zones, DATA, HORIZON, N_PERM, SEED

TFS = ("h1", "h4", "d1")


def load_all_dedup_zones():
    """Retourne, pour chaque TF, les zones dedupliquees + leurs candles/atr/arrays, et une liste
    plate (zone, anchor_date) pour le calcul de densite cross-TF."""
    per_tf = {}
    flat = []
    for tf in TFS:
        candles = load_csv(f"{DATA}/btcusdt_{tf}.csv")
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
    return per_tf, flat


def overlaps(z1, z2):
    return not (z1.band_high < z2.band_low or z1.band_low > z2.band_high)


def density_at(target_zone, touch_date, flat_zones):
    return sum(1 for (z2, d2) in flat_zones if d2 <= touch_date and overlaps(target_zone, z2))


print("=== Chargement + detection (H1/H4/D1) ===", flush=True)
per_tf, flat_zones = load_all_dedup_zones()
for tf in TFS:
    print(f"  {tf}: {len(per_tf[tf]['zones'])} zones dedupliquees")
print(f"Total zones (toutes TF) : {len(flat_zones)}")
print()

print("=== Calcul densite + reaction par touche ===", flush=True)
pairs = []  # (density, reaction)
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
            dens = density_at(z, touch_date, flat_zones)
            pairs.append((dens, reaction))

print(f"Total touches analysees : {len(pairs)}")
densities = np.array([p[0] for p in pairs], dtype=float)
reactions = np.array([p[1] for p in pairs], dtype=float)
print(f"Densite : min={densities.min():.0f} max={densities.max():.0f} moyenne={densities.mean():.2f}")
print()

rho, p_spearman = sstats.spearmanr(densities, reactions)
print(f"Correlation de Spearman (densite vs reaction, n={len(pairs)}) : rho={rho:+.3f}, p={p_spearman:.4f}")

q1, q2 = np.percentile(densities, [33.3, 66.7])
low_mask = densities <= q1
high_mask = densities >= q2
low_vals = reactions[low_mask]
high_vals = reactions[high_mask]
print(f"Tercile basse densite (<= {q1:.1f}, n={low_vals.size})  : reaction moyenne = {low_vals.mean():+.3f} ATR")
print(f"Tercile haute densite (>= {q2:.1f}, n={high_vals.size}) : reaction moyenne = {high_vals.mean():+.3f} ATR")

observed_diff = high_vals.mean() - low_vals.mean()
rng = np.random.default_rng(SEED)
pooled = np.concatenate([low_vals, high_vals])
n_low = low_vals.size
null_diffs = []
for _ in range(N_PERM):
    perm = rng.permutation(pooled)
    null_diffs.append(perm[n_low:].mean() - perm[:n_low].mean())
null_diffs = np.array(null_diffs)
p_perm = (np.sum(np.abs(null_diffs) >= abs(observed_diff)) + 1) / (N_PERM + 1)
print(f"Ecart haute-basse densite = {observed_diff:+.3f} ATR | p (permutation, bilateral) = {p_perm:.4f}")
