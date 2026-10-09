#!/usr/bin/env python3
"""
Snapshot stablecoin size, peg, and chain concentration from DefiLlama.

The script intentionally uses only the Python standard library:

    python tools/research/stablecoin_risk_snapshot.py

It does not score legal/regulatory risk. It gives live quantitative inputs for
the stablecoin part of the trading-fee study: market cap, peg deviation, recent
supply change, mechanism, and largest chains.

Use --json to emit machine-readable data for downstream tooling.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Iterable


URL = "https://stablecoins.llama.fi/stablecoins?includePrices=true"
TIMEOUT_SECONDS = 20
DEFAULT_SYMBOLS = ("USDT", "USDC", "USDS", "USDe", "DAI", "USDG", "PYUSD", "RLUSD", "TUSD", "FDUSD")


@dataclass(frozen=True)
class StablecoinSnapshot:
    symbol: str
    name: str
    peg_type: str
    mechanism: str
    market_cap_usd: Decimal
    price: Decimal | None
    peg_deviation_bps: Decimal | None
    change_day_pct: Decimal | None
    change_week_pct: Decimal | None
    change_month_pct: Decimal | None
    chains: str


def decimal(value: object) -> Decimal:
    try:
        return Decimal(str(value))
    except (InvalidOperation, TypeError) as exc:
        raise ValueError(f"Cannot parse decimal from {value!r}") from exc


def http_json(url: str) -> object:
    request = urllib.request.Request(
        url,
        headers={
            "accept": "application/json",
            "user-agent": "TradeIO5 stablecoin risk snapshot/1.0",
        },
    )
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return json.loads(response.read().decode("utf-8"))
        except (TimeoutError, urllib.error.URLError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(1)
    if last_error is not None:
        raise last_error
    raise RuntimeError("unreachable http_json state")


def amount(payload: dict[str, object], key: str) -> Decimal | None:
    value = payload.get(key)
    if not isinstance(value, dict):
        return None
    pegged_usd = value.get("peggedUSD")
    if pegged_usd is None:
        return None
    return decimal(pegged_usd)


def pct_change(current: Decimal, previous: Decimal | None) -> Decimal | None:
    if previous is None or previous == 0:
        return None
    return (current / previous - Decimal("1")) * Decimal("100")


def top_chains(payload: dict[str, object], limit: int = 3) -> str:
    chain_payload = payload.get("chainCirculating")
    if not isinstance(chain_payload, dict):
        return ""
    rows: list[tuple[str, Decimal]] = []
    for chain, values in chain_payload.items():
        if not isinstance(values, dict):
            continue
        current = values.get("current")
        if not isinstance(current, dict):
            continue
        pegged_usd = current.get("peggedUSD")
        if pegged_usd is None:
            continue
        cap = decimal(pegged_usd)
        if cap > 0:
            rows.append((str(chain), cap))
    rows.sort(key=lambda item: item[1], reverse=True)
    return ", ".join(f"{chain} {fmt_usd(cap)}" for chain, cap in rows[:limit])


def parse_stablecoin(payload: dict[str, object]) -> StablecoinSnapshot:
    circulating = amount(payload, "circulating") or Decimal("0")
    previous_day = amount(payload, "circulatingPrevDay")
    previous_week = amount(payload, "circulatingPrevWeek")
    previous_month = amount(payload, "circulatingPrevMonth")
    price = decimal(payload["price"]) if payload.get("price") is not None else None
    peg_deviation = None if price is None else (price - Decimal("1")) * Decimal("10000")
    return StablecoinSnapshot(
        symbol=str(payload.get("symbol", "")),
        name=str(payload.get("name", "")),
        peg_type=str(payload.get("pegType", "")),
        mechanism=str(payload.get("pegMechanism", "")),
        market_cap_usd=circulating,
        price=price,
        peg_deviation_bps=peg_deviation,
        change_day_pct=pct_change(circulating, previous_day),
        change_week_pct=pct_change(circulating, previous_week),
        change_month_pct=pct_change(circulating, previous_month),
        chains=top_chains(payload),
    )


def fmt_decimal(value: Decimal | None, places: int = 3) -> str:
    if value is None or not value.is_finite():
        return "N/A"
    quant = Decimal("1").scaleb(-places)
    return f"{value.quantize(quant):f}"


def decimal_json(value: Decimal | None) -> str | None:
    if value is None or not value.is_finite():
        return None
    return f"{value.normalize():f}"


def fmt_usd(value: Decimal) -> str:
    abs_value = abs(value)
    if abs_value >= Decimal("1000000000"):
        return f"{(value / Decimal('1000000000')).quantize(Decimal('0.1'))}B"
    if abs_value >= Decimal("1000000"):
        return f"{(value / Decimal('1000000')).quantize(Decimal('0.1'))}M"
    if abs_value >= Decimal("1000"):
        return f"{(value / Decimal('1000')).quantize(Decimal('0.1'))}k"
    return fmt_decimal(value, 0)


def render_table(rows: Iterable[StablecoinSnapshot]) -> None:
    print("| Symbol | Name | Cap USD | Price | Peg dev bps | 1d % | 7d % | 30d % | Mechanism | Top chains |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---|---|")
    for row in rows:
        values = [
            row.symbol,
            row.name,
            fmt_usd(row.market_cap_usd),
            fmt_decimal(row.price, 6),
            fmt_decimal(row.peg_deviation_bps, 3),
            fmt_decimal(row.change_day_pct, 3),
            fmt_decimal(row.change_week_pct, 3),
            fmt_decimal(row.change_month_pct, 3),
            row.mechanism,
            row.chains,
        ]
        print("| " + " | ".join(values) + " |")


def stablecoin_json(row: StablecoinSnapshot) -> dict[str, object]:
    return {
        "symbol": row.symbol,
        "name": row.name,
        "pegType": row.peg_type,
        "mechanism": row.mechanism,
        "marketCapUsd": decimal_json(row.market_cap_usd),
        "price": decimal_json(row.price),
        "pegDeviationBps": decimal_json(row.peg_deviation_bps),
        "changeDayPct": decimal_json(row.change_day_pct),
        "changeWeekPct": decimal_json(row.change_week_pct),
        "changeMonthPct": decimal_json(row.change_month_pct),
        "topChains": row.chains,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--symbols",
        default=",".join(DEFAULT_SYMBOLS),
        help="comma-separated stablecoin symbols to include before the top-N table",
    )
    parser.add_argument("--top", type=int, default=12, help="number of largest stablecoins to show")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON instead of Markdown")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    payload = http_json(URL)
    if not isinstance(payload, dict) or not isinstance(payload.get("peggedAssets"), list):
        raise RuntimeError("Unexpected DefiLlama stablecoin payload")

    snapshots = [
        parse_stablecoin(item)
        for item in payload["peggedAssets"]
        if isinstance(item, dict) and item.get("pegType") == "peggedUSD"
    ]
    snapshots.sort(key=lambda row: row.market_cap_usd, reverse=True)
    by_symbol: dict[str, StablecoinSnapshot] = {}
    for row in snapshots:
        by_symbol.setdefault(row.symbol.upper(), row)
    selected_symbols = [symbol.strip().upper() for symbol in args.symbols.split(",") if symbol.strip()]
    selected_rows = [by_symbol[symbol] for symbol in selected_symbols if symbol in by_symbol]

    timestamp_utc = datetime.now(timezone.utc).isoformat(timespec="seconds")
    if args.json:
        print(
            json.dumps(
                {
                    "snapshotUtc": timestamp_utc,
                    "selected": [stablecoin_json(row) for row in selected_rows],
                    "top": [stablecoin_json(row) for row in snapshots[: args.top]],
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 0

    print(f"Snapshot UTC: {timestamp_utc}")
    print()
    print("Selected stablecoins:")
    print()
    render_table(selected_rows)
    print()
    print(f"Top {args.top} USD-pegged stablecoins by DefiLlama circulating value:")
    print()
    render_table(snapshots[: args.top])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
