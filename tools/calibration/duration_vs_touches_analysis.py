#!/usr/bin/env python3
"""
Analyse exploratoire (2026-07-09, demande de Clem) : la duree de stagnation qui forme une zone
'consolidation' est-elle un signal different (ou redondant) du nombre de fois qu'elle est retestee
ensuite ? Deux questions separees :

  Q1 - Une stagnation longue produit-elle une zone qui reagit mieux (magnitude de reaction plus
       forte lors des touches suivantes) qu'une stagnation courte ?
  Q2 - Une zone s'use-t-elle (chaque touche successive reagit moins bien -> les defenseurs de la
       zone s'epuisent) ou se confirme-t-elle (chaque touche reagit mieux ou pareil -> le niveau se
       renforce) ?

Garde-fou : par construction (cf. technique_consolidation), une "touche" ne peut jamais survenir
AVANT la fin de la fenetre de formation (tant que le prix reste dans la bande, ce n'est pas un
evenement de contact, c'est la formation elle-meme) -> pas de fuite de ce cote. Ceci reste une
analyse DESCRIPTIVE sur tout l'historique disponible, pas une validation walk-forward -> un
resultat net ici serait a revalider en walk-forward avant d'etre considere actionnable (meme
standard que le reste de la session).

Usage: python3 duration_vs_touches_analysis.py
"""
import math
import statistics
import sys

import numpy as np
from scipy import stats as sstats

sys.path.insert(0, ".")
from band_zone_test import (load_csv, wilder_atr, to_arrays, atr_to_array,
                             technique_consolidation, band_reaction_values)

DATA = "/sessions/determined-tender-volta/mnt/TradeIO-5/target/calibration-data"
HORIZON = 5
TF_HOURS = {"h1": 1, "h4": 4, "d1": 24}
N_PERM = 300
SEED = 42


from datetime import datetime, timedelta

def dedup_zones(zones, candles, max_gap_atr_mult=1.0, max_candles_apart_days=15):
    """Fusionne les quasi-doublons du scan glouton (meme logique que pour la page de visualisation) :
    zones proches en PRIX ET en TEMPS = la meme detection retrouvee 2x, pas 2 zones distinctes.
    Necessaire ici : sans ca, l'echantillon de 'zones' contient d'enormes chevauchements qui cassent
    l'hypothese d'independance des tests statistiques (pseudo-repetition)."""
    atr_series = wilder_atr(candles, 14)
    dated = []
    for z in zones:
        anchor_date = candles[min(z.end_idx, len(candles) - 1)]["timestamp"]
        atr_here = atr_series[min(z.end_idx, len(candles) - 1)] or 1.0
        dated.append({"z": z, "date": anchor_date, "atr": atr_here})
    dated.sort(key=lambda d: (d["date"], d["z"].band_low))
    merged = []
    for d in dated:
        match = None
        for m in merged:
            gap = m["atr"] * max_gap_atr_mult
            if abs((d["date"] - m["date"]).days) <= max_candles_apart_days and \
               d["z"].band_low <= m["z"].band_high + gap and d["z"].band_high >= m["z"].band_low - gap:
                match = m
                break
        if match:
            match["z"] = type(match["z"])(
                min(match["z"].band_low, d["z"].band_low), max(match["z"].band_high, d["z"].band_high),
                (min(match["z"].band_low, d["z"].band_low) + max(match["z"].band_high, d["z"].band_high)) / 2,
                1.0, max(match["z"].touches, d["z"].touches), max(match["z"].end_idx, d["z"].end_idx))
            if d["date"] > match["date"]:
                match["date"] = d["date"]
        else:
            merged.append(d)
    return [m["z"] for m in merged]


def per_zone_touch_series(tf_name, min_window=10, max_window=40):
    candles = load_csv(f"{DATA}/btcusdt_{tf_name}.csv")
    atr_series = wilder_atr(candles, 14)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    zones_raw = technique_consolidation(candles, lookback=len(candles), min_window=min_window,
                                         max_window=max_window, window_step=4, trim_pct=10,
                                         max_range_atr_mult=2.5)
    zones = dedup_zones(zones_raw, candles)
    results = []
    for z in zones:
        duration_candles = z.touches  # cf. technique_consolidation : touches = w_end - w_start
        duration_days = duration_candles * TF_HOURS[tf_name] / 24.0
        reactions = band_reaction_values(z.band_low, z.band_high, arrays, atr_arr, HORIZON, min_index=0)
        if reactions.size == 0:
            continue
        results.append({
            "tf": tf_name, "duration_days": duration_days, "n_touches": int(reactions.size),
            "reactions": reactions,  # ordre chronologique (touch_events scanne dans l'ordre des index croissants)
            "mean_reaction": float(reactions.mean()),
        })
    return results


import time
_t0 = time.time()
print("=== Detection des zones consolidation (H1, H4, D1, historique complet) ===", flush=True)
all_zones = []
for tf in ("h1", "h4", "d1"):
    zs = per_zone_touch_series(tf)
    print(f"  {tf}: {len(zs)} zones (deduplique) avec >=1 touche post-formation  [t={time.time()-_t0:.1f}s]", flush=True)
    all_zones.extend(zs)
print(f"Total zones utilisables: {len(all_zones)}  [t={time.time()-_t0:.1f}s]", flush=True)
print()

# ============================================================================================
# Q1 : duree de stagnation vs reaction moyenne
# ============================================================================================
print("=== Q1 : duree de stagnation (jours) vs reaction moyenne (ATR) ===")
durations = np.array([z["duration_days"] for z in all_zones])
mean_reactions = np.array([z["mean_reaction"] for z in all_zones])

rho, p_spearman = sstats.spearmanr(durations, mean_reactions)
print(f"Correlation de Spearman (toutes zones, n={len(all_zones)}) : rho={rho:+.3f}, p={p_spearman:.4f}")

# Terciles de duree
q1, q2 = np.percentile(durations, [33.3, 66.7])
short_mask = durations <= q1
long_mask = durations >= q2
short_vals = mean_reactions[short_mask]
long_vals = mean_reactions[long_mask]
print(f"Tercile court (<= {q1:.1f}j, n={short_vals.size}) : reaction moyenne = {short_vals.mean():+.3f} ATR")
print(f"Tercile long  (>= {q2:.1f}j, n={long_vals.size}) : reaction moyenne = {long_vals.mean():+.3f} ATR")

observed_diff = long_vals.mean() - short_vals.mean()
rng = np.random.default_rng(SEED)
pooled = np.concatenate([short_vals, long_vals])
n_short = short_vals.size
null_diffs = []
for _ in range(N_PERM):
    perm = rng.permutation(pooled)
    null_diffs.append(perm[n_short:].mean() - perm[:n_short].mean())
null_diffs = np.array(null_diffs)
p_perm = (np.sum(np.abs(null_diffs) >= abs(observed_diff)) + 1) / (N_PERM + 1)
print(f"Ecart long-court = {observed_diff:+.3f} ATR | p (permutation, bilateral) = {p_perm:.4f}")
print()

# ============================================================================================
# Q2 : usure vs confirmation au fil des touches successives
# ============================================================================================
print("=== Q2 : tendance de la reaction au fil des touches successives (par zone, n>=3 touches) ===")
multi_touch_zones = [z for z in all_zones if z["n_touches"] >= 3]
print(f"Zones avec >= 3 touches post-formation : {len(multi_touch_zones)} / {len(all_zones)}  [t={time.time()-_t0:.1f}s]", flush=True)

def zone_slope(reactions):
    """Pente de la regression lineaire reaction ~ rang_de_touche (1..n), normalisee par l'ecart-type
    des reactions de la zone pour rendre les pentes comparables entre zones d'amplitude differente.
    Formule fermee (pas scipy.linregress) : appelee des centaines de milliers de fois dans la boucle
    de permutation Q2, le seul overhead d'appel de linregress dominait le temps d'execution total."""
    n = reactions.shape[0] if hasattr(reactions, "shape") else len(reactions)
    y = np.asarray(reactions, dtype=np.float64)
    std = y.std()
    if std < 1e-9:
        return 0.0
    x = np.arange(1, n + 1, dtype=np.float64)
    x_mean, y_mean = x.mean(), y.mean()
    denom = np.sum((x - x_mean) ** 2)
    if denom < 1e-12:
        return 0.0
    slope = np.sum((x - x_mean) * (y - y_mean)) / denom
    return slope / std

print(f"  debut calcul pentes observees  [t={time.time()-_t0:.1f}s]", flush=True)
observed_slopes = [zone_slope(z["reactions"]) for z in multi_touch_zones]
print(f"  pentes observees calculees  [t={time.time()-_t0:.1f}s]", flush=True)
mean_slope = float(np.mean(observed_slopes))
n_neg = sum(1 for s in observed_slopes if s < 0)
n_pos = sum(1 for s in observed_slopes if s > 0)
print(f"Pente moyenne (normalisee) sur {len(multi_touch_zones)} zones : {mean_slope:+.4f}")
print(f"Zones a pente negative (usure) : {n_neg} | positive (confirmation) : {n_pos} | nulle : {len(multi_touch_zones)-n_neg-n_pos}")

# test de signe binomial (H0: 50/50 entre pente positive et negative)
if n_neg + n_pos > 0:
    binom_p = sstats.binomtest(min(n_neg, n_pos), n_neg + n_pos, 0.5).pvalue
    print(f"Test du signe (binomial) : p={binom_p:.4f}")

# test de permutation : mélange l'ordre des touches DANS chaque zone (préserve les valeurs, casse
# l'ordre chronologique), recalcule la pente moyenne agregee -> distribution nulle.
rng2 = np.random.default_rng(SEED)
null_mean_slopes = []
for _ in range(N_PERM):
    slopes = []
    for z in multi_touch_zones:
        shuffled = rng2.permutation(z["reactions"])
        slopes.append(zone_slope(shuffled))
    null_mean_slopes.append(float(np.mean(slopes)))
null_mean_slopes = np.array(null_mean_slopes)
print(f"  permutation Q2 terminee  [t={time.time()-_t0:.1f}s]", flush=True)
p_perm_q2 = (np.sum(np.abs(null_mean_slopes) >= abs(mean_slope)) + 1) / (N_PERM + 1)
print(f"p (permutation, bilateral, ordre chronologique vs ordre aleatoire) = {p_perm_q2:.4f}")
print()

# Detail : reaction moyenne a la 1ere, 2eme, 3eme+ touche (toutes zones confondues, poids egal par zone)
print("--- Detail : reaction moyenne par rang de touche (poids egal par zone) ---")
max_rank_report = 4
for rank in range(1, max_rank_report + 1):
    vals = [z["reactions"][rank - 1] for z in multi_touch_zones if len(z["reactions"]) >= rank]
    if vals:
        print(f"  Touche #{rank} : reaction moyenne = {statistics.mean(vals):+.3f} ATR (n={len(vals)} zones)")
