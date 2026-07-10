#!/usr/bin/env python3
"""
Prepare les donnees pour zone_view_v2.html (nouvelle page, ne remplace pas zone_view.html) :
  - candles D1 BTCUSDT (historique complet 2017-2026)
  - zones 'consolidation' dedupliquees, avec pour chacune les VRAIES metriques calibrees
    (duree de formation, volume_strength causal, liste chronologique des touches avec
    date/prix/reaction ATR-normalisee, reaction moyenne) -- pas de "force" fabriquee.
  - segments de regime de marche (tendance / range / neutre) via ADX(14) de Wilder, compresses
    en plages contigues pour rester leger.

Design pense suite a la discussion du 2026-07-10 : aucune des metriques testees (duree, volume,
densite de chevauchement) ne justifie un encodage visuel "plus fonce/plus gros = plus fiable" --
donc ce script n'exporte PAS de score de force. Le seul signal robuste trouve est l'effet de
1ere touche (plus faible que les suivantes), d'ou le marqueur dedie ; et le regime de marche
(tendance vs range) explique mieux la reussite de la technique que n'importe quelle metrique par
zone (walk-forward), d'ou le bandeau de fond.

Usage: python3 export_zones_v2.py
"""
import json
import sys
import numpy as np

sys.path.insert(0, ".")
from band_zone_test import load_csv, wilder_atr, to_arrays, atr_to_array, technique_consolidation, band_touch_events
from duration_vs_touches_analysis import dedup_zones

DATA_EXT = "/sessions/determined-tender-volta/mnt/TradeIO-5/target/calibration-data-extended"
OUT = "/sessions/determined-tender-volta/mnt/TradeIO-5/tools/calibration/zone_view_v2_data.json"
HORIZON = 5
ADX_PERIOD = 14
ADX_TREND_TH = 25.0
ADX_RANGE_TH = 20.0


def compute_adx(candles, period=ADX_PERIOD):
    n = len(candles)
    highs = np.array([c["high"] for c in candles])
    lows = np.array([c["low"] for c in candles])
    closes = np.array([c["close"] for c in candles])

    up_move = np.zeros(n)
    down_move = np.zeros(n)
    tr = np.zeros(n)
    up_move[1:] = highs[1:] - highs[:-1]
    down_move[1:] = lows[:-1] - lows[1:]
    plus_dm = np.where((up_move > down_move) & (up_move > 0), up_move, 0.0)
    minus_dm = np.where((down_move > up_move) & (down_move > 0), down_move, 0.0)
    tr[1:] = np.maximum(highs[1:] - lows[1:],
                         np.maximum(np.abs(highs[1:] - closes[:-1]), np.abs(lows[1:] - closes[:-1])))

    def wilder_smooth(x):
        s = np.full(n, np.nan)
        if n <= period:
            return s
        s[period] = x[1:period + 1].sum()
        for i in range(period + 1, n):
            s[i] = s[i - 1] - s[i - 1] / period + x[i]
        return s

    tr_s = wilder_smooth(tr)
    plus_dm_s = wilder_smooth(plus_dm)
    minus_dm_s = wilder_smooth(minus_dm)

    with np.errstate(divide="ignore", invalid="ignore"):
        plus_di = 100.0 * plus_dm_s / tr_s
        minus_di = 100.0 * minus_dm_s / tr_s
        dx = 100.0 * np.abs(plus_di - minus_di) / (plus_di + minus_di)

    adx = np.full(n, np.nan)
    first_valid = period * 2
    if first_valid < n:
        adx[first_valid] = np.nanmean(dx[period + 1:first_valid + 1])
        for i in range(first_valid + 1, n):
            prev = adx[i - 1]
            adx[i] = prev if np.isnan(dx[i]) else (prev * (period - 1) + dx[i]) / period
    return adx


def regime_segments(candles, adx):
    n = len(candles)
    labels = []
    for i in range(n):
        a = adx[i]
        if np.isnan(a):
            labels.append(None)
        elif a >= ADX_TREND_TH:
            labels.append("trend")
        elif a < ADX_RANGE_TH:
            labels.append("range")
        else:
            labels.append("neutral")

    segments = []
    cur_label, cur_start = None, None
    for i in range(n):
        lab = labels[i]
        if lab != cur_label:
            if cur_label is not None:
                segments.append({
                    "start": candles[cur_start]["timestamp"].date().isoformat(),
                    "end": candles[i - 1]["timestamp"].date().isoformat(),
                    "regime": cur_label,
                })
            cur_label, cur_start = lab, i
    if cur_label is not None:
        segments.append({
            "start": candles[cur_start]["timestamp"].date().isoformat(),
            "end": candles[n - 1]["timestamp"].date().isoformat(),
            "regime": cur_label,
        })
    return segments


def main():
    candles = load_csv(f"{DATA_EXT}/btcusdt_d1_full.csv")
    atr_series = wilder_atr(candles, 14)
    arrays = to_arrays(candles)
    atr_arr = atr_to_array(atr_series)
    volumes = np.array([c["volume"] for c in candles], dtype=np.float64)

    zones_raw = technique_consolidation(candles, lookback=len(candles), min_window=10, max_window=40,
                                         window_step=4, trim_pct=10, max_range_atr_mult=2.5)
    zones = dedup_zones(zones_raw, candles)
    print(f"Zones D1 detectees (dedupliquees) : {len(zones)}")

    out_zones = []
    for z in zones:
        start_idx = max(0, z.end_idx - z.touches)
        end_idx = min(z.end_idx, len(candles) - 1)
        if start_idx <= 0:
            continue
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

        touches = []
        for k in range(idx.size):
            i = idx[k]
            atr_here = atr_arr[i]
            if np.isnan(atr_here) or atr_here <= 0:
                continue
            close_h = arrays["close"][j[k]]
            edge = z.band_high if from_above[k] else z.band_low
            raw = (close_h - z.band_high) if from_above[k] else (z.band_low - close_h)
            reaction = float(raw / atr_here)
            touches.append({
                "date": candles[i]["timestamp"].date().isoformat(),
                "price": float(edge),
                "reaction": reaction,
            })
        if not touches:
            continue

        mean_reaction = float(np.mean([t["reaction"] for t in touches]))
        out_zones.append({
            "band_low": float(z.band_low), "band_high": float(z.band_high),
            "anchor_date": candles[end_idx]["timestamp"].date().isoformat(),
            "duration_days": float(z.touches),
            "volume_strength": volume_strength,
            "touches": touches,
            "n_touches": len(touches),
            "mean_reaction": mean_reaction,
            "first_touch": touches[0],
        })
    print(f"Zones exploitables (>=1 touche) : {len(out_zones)}")

    print("Calcul ADX(14)...")
    adx = compute_adx(candles, ADX_PERIOD)
    segments = regime_segments(candles, adx)
    segments = [s for s in segments if s["regime"] is not None]
    print(f"Segments de regime : {len(segments)} "
          f"(trend={sum(1 for s in segments if s['regime']=='trend')}, "
          f"range={sum(1 for s in segments if s['regime']=='range')}, "
          f"neutral={sum(1 for s in segments if s['regime']=='neutral')})")

    chart_candles = [{"time": c["timestamp"].date().isoformat(), "open": c["open"], "high": c["high"],
                       "low": c["low"], "close": c["close"]} for c in candles]

    result = {
        "candles": chart_candles,
        "zones": out_zones,
        "regime_segments": segments,
        "generated_at": "2026-07-10",
    }
    with open(OUT, "w") as f:
        json.dump(result, f)
    print(f"Ecrit: {OUT}")


if __name__ == "__main__":
    main()
