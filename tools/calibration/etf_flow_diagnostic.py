#!/usr/bin/env python3
"""Diagnostic ad hoc (pas un livrable de calibration) : décompose pourquoi le cas "divergent" est
si rare -- mesure séparément la fréquence de markedPriceMove (|priceChangePct|>=2%) et de
significantFlow (|total|>=50M) sur les mêmes points évaluables que etf_flow_calibration.py, pour
savoir laquelle des deux conditions jointes est le goulot d'étranglement (ou si c'est la conjonction
des deux qui est rare alors que chacune prise seule ne l'est pas)."""

import argparse
from etf_flow_calibration import load_klines_daily, load_etf_flow_series, build_eval_points, DEFAULT_THRESHOLDS


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", required=True)
    parser.add_argument("--etf-flow", required=True)
    parser.add_argument("--klines", required=True)
    parser.add_argument("--lookback", type=int, default=1)
    args = parser.parse_args()

    klines = load_klines_daily(args.klines)
    etf_flow_series = load_etf_flow_series(args.etf_flow)
    klines_idx_by_date = {k["date"]: i for i, k in enumerate(klines)}

    total_days = 0
    marked_price_move = 0
    significant_flow = 0
    both = 0
    flow_abs_values = []
    price_abs_values = []

    for day, total in sorted(etf_flow_series.items()):
        idx = klines_idx_by_date.get(day)
        if idx is None or idx - args.lookback < 0:
            continue
        ref_close = klines[idx - args.lookback]["close"]
        last_close = klines[idx]["close"]
        if ref_close == 0:
            continue
        price_change_pct = (last_close - ref_close) / ref_close

        total_days += 1
        flow_abs_values.append(abs(total))
        price_abs_values.append(abs(price_change_pct))
        mpm = abs(price_change_pct) >= DEFAULT_THRESHOLDS["price_move_threshold"]
        sf = abs(total) >= DEFAULT_THRESHOLDS["flow_significance_threshold_usd"]
        if mpm:
            marked_price_move += 1
        if sf:
            significant_flow += 1
        if mpm and sf:
            both += 1

    flow_abs_values.sort()
    price_abs_values.sort()

    def pct(x, n):
        return f"{x}/{n} ({x / n:.2%})" if n else "n/a"

    def median(vals):
        n = len(vals)
        return vals[n // 2] if n else None

    print(f"=== {args.asset} -- diagnostic marginal (n={total_days} jours alignés) ===")
    print(f"markedPriceMove (|priceChangePct| >= 2%)   seul : {pct(marked_price_move, total_days)}")
    print(f"significantFlow (|total| >= 50M USD)        seul : {pct(significant_flow, total_days)}")
    print(f"conjonction des deux (= divergent + coherent)    : {pct(both, total_days)}")
    print(f"médiane |priceChangePct| sur la série              : {median(price_abs_values):.4f}")
    print(f"médiane |total| (flux ETF USD)                     : {median(flow_abs_values):,.0f}")
    print(f"90e percentile |total|                              : {flow_abs_values[int(0.9 * len(flow_abs_values))]:,.0f}")
    print(f"90e percentile |priceChangePct|                     : {price_abs_values[int(0.9 * len(price_abs_values))]:.4f}")


if __name__ == "__main__":
    main()
