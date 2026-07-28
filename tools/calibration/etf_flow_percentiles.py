#!/usr/bin/env python3
"""
Calibration des seuils "haut/faible" pour le flux ETF quotidien (|total_net_inflow|, USD brut après
correction du bug d'unité, cf. docs/calibration-etf-flow.md) -- BTC et ETH séparément, jamais
fusionnés (même règle que la calibration principale).

Remplace l'approche "seuil rond devinné" (DEFAULT_FLOW_SIGNIFICANCE_THRESHOLD_USD=50_000_000, doc
source : "valeurs de bon sens jamais confrontées à des données réelles") par des seuils empiriques :
percentiles de la distribution réelle de |total_net_inflow| sur tout l'historique disponible.

Usage:
    python etf_flow_percentiles.py --asset BTC --etf-flow etf_flow_btc.csv
    python etf_flow_percentiles.py --asset ETH --etf-flow etf_flow_eth.csv
"""

import argparse
import statistics

from etf_flow_calibration import load_etf_flow_series, detect_and_fix_unit_break

PERCENTILES = [10, 25, 50, 75, 90, 95, 99]


def percentile(sorted_values, pct):
    if not sorted_values:
        return None
    n = len(sorted_values)
    idx = min(n - 1, max(0, round(pct / 100 * (n - 1))))
    return sorted_values[idx]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", required=True, choices=["BTC", "ETH"])
    parser.add_argument("--etf-flow", required=True)
    args = parser.parse_args()

    series = load_etf_flow_series(args.etf_flow)
    series, cutover_day, n_fixed = detect_and_fix_unit_break(series, args.asset)

    abs_values = sorted(abs(v) for v in series.values())
    signed_values = sorted(series.values())
    n = len(abs_values)

    print(f"\n=== {args.asset} -- distribution de |total_net_inflow| (USD brut, n={n} jours) ===")
    print(f"min={abs_values[0]:,.0f}  max={abs_values[-1]:,.0f}  "
          f"moyenne={statistics.mean(abs_values):,.0f}  médiane={abs_values[n // 2]:,.0f}")

    print("\nPercentiles de |flux| (valeur absolue, sans distinction entrée/sortie) :")
    for p in PERCENTILES:
        v = percentile(abs_values, p)
        print(f"  p{p:2d} : {v:>15,.0f} $")

    n_in = sum(1 for v in signed_values if v > 0)
    n_out = sum(1 for v in signed_values if v < 0)
    print(f"\nJours en entrée (flux>0) : {n_in} ({n_in / n:.1%})  |  "
          f"Jours en sortie (flux<0) : {n_out} ({n_out / n:.1%})")

    print("\nPercentiles côté entrée (flux>0 seulement) :")
    pos = sorted(v for v in signed_values if v > 0)
    for p in PERCENTILES:
        v = percentile(pos, p)
        print(f"  p{p:2d} : {v:>15,.0f} $" if v is not None else f"  p{p:2d} : n/a")

    print("\nPercentiles côté sortie (flux<0 seulement, valeur absolue) :")
    neg_abs = sorted(abs(v) for v in signed_values if v < 0)
    for p in PERCENTILES:
        v = percentile(neg_abs, p)
        print(f"  p{p:2d} : {v:>15,.0f} $" if v is not None else f"  p{p:2d} : n/a")

    print("\nProposition de bandes \"haut/moyen/faible\" (basées sur les tercives p33/p67 de |flux|) :")
    p33 = percentile(abs_values, 33)
    p67 = percentile(abs_values, 67)
    print(f"  faible : |flux| < {p33:,.0f} $   ({sum(1 for v in abs_values if v < p33)}/{n} jours)")
    print(f"  moyen  : {p33:,.0f} $ <= |flux| < {p67:,.0f} $   "
          f"({sum(1 for v in abs_values if p33 <= v < p67)}/{n} jours)")
    print(f"  haut   : |flux| >= {p67:,.0f} $   ({sum(1 for v in abs_values if v >= p67)}/{n} jours)")


if __name__ == "__main__":
    main()
