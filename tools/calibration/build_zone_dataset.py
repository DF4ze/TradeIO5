#!/usr/bin/env python3
"""
Etape 1/2 (bench elargi, 2026-07-09) : detecte + deduplique les zones 'consolidation' pour UN actif
sur H1/H4/D1 (historique etendu : BTC/ETH depuis 2017-08-17 en D1/H4, depuis 2020-01-01 en H1),
calcule pour chacune :
  - duration_days (duree de la stagnation qui l'a formee, en jours calendaires)
  - volume_strength (volume moyen PENDANT la formation / volume moyen de TOUT l'historique
    ANTERIEUR au debut de la formation -> pas de fuite : la baseline ne voit jamais le futur)
  - reactions : liste chronologique des magnitudes de reaction (normalisees ATR, horizon=5) a
    chaque touche post-formation
et ecrit tout ca dans un JSON (une ligne par TF) pour que l'analyse finale (Q1/Q2/Q_volume/Q3)
n'ait plus qu'a charger des donnees deja calculees, sans refaire la detection (couteuse en temps
sur H1 etendu, ~57k bougies).

Usage: python3 build_zone_dataset.py --asset BTCUSDT
"""
import argparse
import json
import sys
import time

import numpy as np

sys.path.insert(0, ".")
from band_zone_test import load_csv, wilder_atr, to_arrays, atr_to_array, technique_consolidation, band_touch_events
from duration_vs_touches_analysis import dedup_zones

DATA_EXT = "/sessions/determined-tender-volta/mnt/TradeIO-5/target/calibration-data-extended"
HORIZON = 5
TF_FILES = {
    "d1": "{asset}_d1_full.csv",
    "h4": "{asset}_h4_full.csv",
    "h1": "{asset}_h1_ext.csv",
}
TF_HOURS = {"h1": 1, "h4": 4, "d1": 24}


def process_tf(asset_lower, tf):
    path = f"{DATA_EXT}/{TF_FILES[tf].format(asset=asset_lower)}"
    candles = load_csv(path)
    atr_series = wilder_atr(candles, 14)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    volumes = np.array([c["volume"] for c in candles], dtype=np.float64)

    zones_raw = technique_consolidation(candles, lookback=len(candles), min_window=10, max_window=40,
                                         window_step=4, trim_pct=10, max_range_atr_mult=2.5)
    zones = dedup_zones(zones_raw, candles)

    out = []
    for z in zones:
        start_idx = max(0, z.end_idx - z.touches)  # touches = duree de formation (cf. technique_consolidation)
        end_idx = min(z.end_idx, len(candles) - 1)
        if start_idx <= 0:
            continue  # pas de baseline anterieure -> zone ignoree (evite tout biais de debut de serie)
        formation_vol = volumes[start_idx:end_idx].mean() if end_idx > start_idx else np.nan
        baseline_vol = volumes[:start_idx].mean()
        if np.isnan(formation_vol) or baseline_vol <= 0:
            continue
        volume_strength = float(formation_vol / baseline_vol)

        idx, from_above = band_touch_events(z.band_low, z.band_high, arrays, min_index=0)
        if idx.size == 0:
            continue
        j = idx + HORIZON
        valid = j < arrays["close"].shape[0]
        idx, from_above, j = idx[valid], from_above[valid], j[valid]
        reactions = []
        for k in range(idx.size):
            i = idx[k]
            atr_here = atr_arr[i]
            if np.isnan(atr_here) or atr_here <= 0:
                continue
            close_h = arrays["close"][j[k]]
            raw = (close_h - z.band_high) if from_above[k] else (z.band_low - close_h)
            reactions.append(float(raw / atr_here))
        if not reactions:
            continue

        out.append({
            "duration_days": z.touches * TF_HOURS[tf] / 24.0,
            "volume_strength": volume_strength,
            "reactions": reactions,
            "band_low": z.band_low, "band_high": z.band_high,
            "anchor_date": candles[end_idx]["timestamp"].isoformat(),
        })
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", required=True, help="ex: BTCUSDT")
    args = parser.parse_args()
    asset_lower = args.asset.lower()

    t0 = time.time()
    result = {"asset": args.asset}
    for tf in ("d1", "h4", "h1"):
        zs = process_tf(asset_lower, tf)
        result[tf] = zs
        print(f"{args.asset} {tf}: {len(zs)} zones utilisables  [t={time.time()-t0:.1f}s]", flush=True)

    out_path = f"{DATA_EXT}/zones_{asset_lower}.json"
    with open(out_path, "w") as f:
        json.dump(result, f)
    print(f"Ecrit: {out_path}  [t={time.time()-t0:.1f}s]")


if __name__ == "__main__":
    main()
