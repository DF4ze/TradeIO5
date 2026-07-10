#!/usr/bin/env python3
"""
Prepare les donnees pour le POC page web (zone editor) : OHLCV multi-TF + zones candidates
initiales, generees avec la technique 'consolidation' de Clem (la plus solide de la calibration,
cf. docs/calibration-rejection-zone.md, section "Bandes plutot que niveaux ponctuels").

Deux categories, conformement a la convention visuelle de Clem :
  - "historical" (zone grise, rectangle) : consolidation detectee sur D1 avec tout l'historique
    disponible, ancree il y a plus de HIST_CUTOFF_DAYS jours -> zone "mature", testee sur plusieurs
    regimes de marche.
  - "recent" (ligne pointillee verte) : consolidation detectee sur H1 (plus reactif) sur les
    derniers HIST_CUTOFF_DAYS jours -> zone fraichement formee, pas encore "eprouvee".

Sortie : tools/calibration/zones_seed.json
"""
import json
import sys
from datetime import timedelta

sys.path.insert(0, ".")
from band_zone_test import load_csv, technique_consolidation

DATA = "/sessions/determined-tender-volta/mnt/TradeIO-5/target/calibration-data"
HIST_CUTOFF_DAYS = 60

d1 = load_csv(f"{DATA}/btcusdt_d1.csv")
h1 = load_csv(f"{DATA}/btcusdt_h1.csv")

today = d1[-1]["timestamp"]
cutoff_date = today - timedelta(days=HIST_CUTOFF_DAYS)

# Historique : consolidation sur D1, plein historique
hist_bands = technique_consolidation(d1, lookback=len(d1), min_window=8, max_window=40,
                                      window_step=4, trim_pct=10, max_range_atr_mult=2.5)
historical = []
for b in hist_bands:
    anchor_date = d1[min(b.end_idx, len(d1) - 1)]["timestamp"]
    if anchor_date <= cutoff_date:
        historical.append({
            "type": "historical", "band_low": round(b.band_low, 2), "band_high": round(b.band_high, 2),
            "anchor_date": anchor_date.isoformat(), "touches": b.touches,
            "label": f"Consolidation D1 ({anchor_date.date()})",
        })

# Recent : consolidation sur H1, derniers ~ HIST_CUTOFF_DAYS jours seulement
recent_lookback_candles = HIST_CUTOFF_DAYS * 24 + 200
recent_bands_all = technique_consolidation(h1, lookback=min(recent_lookback_candles, len(h1)),
                                            min_window=15, max_window=60, window_step=5,
                                            trim_pct=10, max_range_atr_mult=2.5)
recent = []
for b in recent_bands_all:
    anchor_date = h1[min(b.end_idx, len(h1) - 1)]["timestamp"]
    if anchor_date > cutoff_date:
        recent.append({
            "type": "recent", "band_low": round(b.band_low, 2), "band_high": round(b.band_high, 2),
            "level": round((b.band_low + b.band_high) / 2, 2),
            "anchor_date": anchor_date.isoformat(), "touches": b.touches,
            "label": f"Consolidation H1 ({anchor_date.date()})",
        })

print(f"Historique (D1, > {HIST_CUTOFF_DAYS}j) : {len(historical)} zones")
print(f"Recent (H1, <= {HIST_CUTOFF_DAYS}j)     : {len(recent)} zones")

zones_seed = {"generated_at": today.isoformat(), "hist_cutoff_days": HIST_CUTOFF_DAYS,
              "historical": historical, "recent": recent}
with open("zones_seed.json", "w") as f:
    json.dump(zones_seed, f, indent=2)
print("Ecrit: zones_seed.json")
