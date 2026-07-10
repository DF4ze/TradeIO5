#!/usr/bin/env python3
"""
Etape 2/2 (bench elargi, 2026-07-09) : charge les zones precalculees (BTC + ETH, D1/H4/H1,
2017-08-17 -> 2026-07-09 pour D1/H4, 2020-01-01 -> 2026-07-09 pour H1) et rejoue :
  Q1 - duree de stagnation vs reaction
  Q2 - tendance de la reaction au fil des touches successives (usure vs confirmation)
  Q_volume - "force de la zone" = volume moyen pendant la formation / volume moyen de tout
             l'historique ANTERIEUR (pas de fuite), vs reaction

Usage: python3 final_analysis_extended.py
"""
import json
import numpy as np
from scipy import stats as sstats

DATA_EXT = "/sessions/determined-tender-volta/mnt/TradeIO-5/target/calibration-data-extended"
N_PERM = 1000
SEED = 42


def load_all():
    zones = []
    for asset in ("btcusdt", "ethusdt"):
        d = json.load(open(f"{DATA_EXT}/zones_{asset}.json"))
        for tf in ("d1", "h4", "h1"):
            for z in d[tf]:
                z["asset"] = d["asset"]
                z["tf"] = tf
                zones.append(z)
    return zones


def zone_slope(reactions):
    y = np.asarray(reactions, dtype=np.float64)
    n = y.shape[0]
    std = y.std()
    if std < 1e-9:
        return 0.0
    x = np.arange(1, n + 1, dtype=np.float64)
    x_mean, y_mean = x.mean(), y.mean()
    denom = np.sum((x - x_mean) ** 2)
    if denom < 1e-12:
        return 0.0
    return (np.sum((x - x_mean) * (y - y_mean)) / denom) / std


def tercile_perm_test(values, reactions, rng, n_perm=N_PERM):
    q1, q2 = np.percentile(values, [33.3, 66.7])
    low = reactions[values <= q1]
    high = reactions[values >= q2]
    observed = high.mean() - low.mean()
    pooled = np.concatenate([low, high])
    n_low = low.size
    null_diffs = np.empty(n_perm)
    for i in range(n_perm):
        perm = rng.permutation(pooled)
        null_diffs[i] = perm[n_low:].mean() - perm[:n_low].mean()
    p_perm = (np.sum(np.abs(null_diffs) >= abs(observed)) + 1) / (n_perm + 1)
    return q1, q2, low, high, observed, p_perm


zones = load_all()
print(f"=== Bench elargi : {len(zones)} zones (BTC+ETH, D1/H4/H1, 2017-2026) ===")
by_asset = {}
for z in zones:
    by_asset.setdefault(z["asset"], 0)
    by_asset[z["asset"]] += 1
print("Par actif:", by_asset)
by_tf = {}
for z in zones:
    by_tf.setdefault(z["tf"], 0)
    by_tf[z["tf"]] += 1
print("Par TF:", by_tf)
print()

durations = np.array([z["duration_days"] for z in zones])
vol_strengths = np.array([z["volume_strength"] for z in zones])
mean_reactions = np.array([np.mean(z["reactions"]) for z in zones])
n_touches = np.array([len(z["reactions"]) for z in zones])

# ============================================================================================
# Q1 : duree vs reaction (bench elargi)
# ============================================================================================
print("=== Q1 (bench elargi) : duree de stagnation vs reaction moyenne ===")
rho, p = sstats.spearmanr(durations, mean_reactions)
print(f"Spearman (n={len(zones)}) : rho={rho:+.3f}, p={p:.4f}")
rng = np.random.default_rng(SEED)
q1, q2, low, high, obs, p_perm = tercile_perm_test(durations, mean_reactions, rng)
print(f"Tercile court (<= {q1:.1f}j, n={low.size}) : {low.mean():+.3f} ATR")
print(f"Tercile long  (>= {q2:.1f}j, n={high.size}) : {high.mean():+.3f} ATR")
print(f"Ecart = {obs:+.3f} ATR | p (permutation) = {p_perm:.4f}")
print()

# ============================================================================================
# Q2 : usure vs confirmation (bench elargi)
# ============================================================================================
print("=== Q2 (bench elargi) : tendance de la reaction au fil des touches ===")
multi = [z for z in zones if len(z["reactions"]) >= 3]
print(f"Zones avec >= 3 touches : {len(multi)} / {len(zones)}")
slopes = [zone_slope(z["reactions"]) for z in multi]
mean_slope = float(np.mean(slopes))
n_neg = sum(1 for s in slopes if s < 0)
n_pos = sum(1 for s in slopes if s > 0)
print(f"Pente moyenne : {mean_slope:+.4f} | negatives={n_neg} positives={n_pos}")
if n_neg + n_pos > 0:
    print(f"Test du signe : p={sstats.binomtest(min(n_neg, n_pos), n_neg + n_pos, 0.5).pvalue:.4f}")

rng2 = np.random.default_rng(SEED)
null_slopes = np.empty(N_PERM)
for i in range(N_PERM):
    s = [zone_slope(rng2.permutation(z["reactions"])) for z in multi]
    null_slopes[i] = np.mean(s)
p_perm_q2 = (np.sum(np.abs(null_slopes) >= abs(mean_slope)) + 1) / (N_PERM + 1)
print(f"p (permutation, ordre chronologique vs aleatoire) = {p_perm_q2:.4f}")
print()
print("--- Detail par rang de touche (bench elargi) ---")
for rank in range(1, 6):
    vals = [z["reactions"][rank - 1] for z in multi if len(z["reactions"]) >= rank]
    if vals:
        print(f"  Touche #{rank} : {np.mean(vals):+.3f} ATR (n={len(vals)} zones)")
print()

# ============================================================================================
# Q_volume : force de la zone (volume pendant formation / volume anterieur) vs reaction
# ============================================================================================
print("=== Q_volume : force de la zone (volume relatif pendant la formation) vs reaction ===")
print(f"volume_strength : min={vol_strengths.min():.2f} max={vol_strengths.max():.2f} "
      f"median={np.median(vol_strengths):.2f} (1.0 = volume moyen identique a l'historique anterieur)")
rho_v, p_v = sstats.spearmanr(vol_strengths, mean_reactions)
print(f"Spearman (n={len(zones)}) : rho={rho_v:+.3f}, p={p_v:.4f}")
rng3 = np.random.default_rng(SEED)
q1v, q2v, lowv, highv, obsv, p_permv = tercile_perm_test(vol_strengths, mean_reactions, rng3)
print(f"Tercile faible volume (<= {q1v:.2f}x, n={lowv.size})  : {lowv.mean():+.3f} ATR")
print(f"Tercile fort volume   (>= {q2v:.2f}x, n={highv.size}) : {highv.mean():+.3f} ATR")
print(f"Ecart = {obsv:+.3f} ATR | p (permutation) = {p_permv:.4f}")
