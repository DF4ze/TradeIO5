#!/usr/bin/env python3
"""Snapshot France/MiCA des couts BTC/ETH/PAXG hors USDT.

Ce script reprend les fonctions de simulation de carnet de
stablecoin_trade_cost_snapshot.py, mais limite volontairement l'univers aux
venues et quotes plausibles pour un resident France : USDC, EUR et USD.

Limite importante : certaines APIs publiques ne sont pas separees par entite
reglementaire. Les lignes Gate/Crypto.com/Bybit restent donc indicatives tant
que le compte France n'a pas confirme les memes paires via API authentifiee.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.parse
from dataclasses import dataclass, asdict
from datetime import UTC, datetime
from decimal import Decimal
from typing import Iterable

import stablecoin_trade_cost_snapshot as common


@dataclass(frozen=True)
class FranceScenario:
    exchange: str
    pair: str
    label: str
    asset: str
    taker_fee_bps: Decimal | None
    status: str
    note: str


def bybit_eu(symbol: str) -> str:
    return f"https://api.bybit.eu/v5/market/orderbook?category=spot&symbol={symbol}&limit=200"


def bitstamp(symbol: str) -> str:
    return f"https://www.bitstamp.net/api/v2/order_book/{urllib.parse.quote(symbol)}/"


def bitvavo(market: str) -> str:
    return f"https://api.bitvavo.com/v2/{urllib.parse.quote(market)}/book?depth=100"


def parse_bybit(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    result = payload.get("result") or {}
    return common.levels(result.get("b", [])), common.levels(result.get("a", []))


def parse_bitstamp(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    return common.levels(payload.get("bids", [])), common.levels(payload.get("asks", []))


def parse_bitvavo(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    return common.levels(payload.get("bids", [])), common.levels(payload.get("asks", []))


PROBES = [
    # OKX Europe: public books, USDT excluded.
    common.PairProbe("OKX Europe*", "BTC/USDC", common.okx("BTC-USDC"), common.parse_okx),
    common.PairProbe("OKX Europe*", "ETH/USDC", common.okx("ETH-USDC"), common.parse_okx),
    common.PairProbe("OKX Europe*", "BTC/EUR", common.okx("BTC-EUR"), common.parse_okx),
    common.PairProbe("OKX Europe*", "ETH/EUR", common.okx("ETH-EUR"), common.parse_okx),
    common.PairProbe("OKX Europe*", "PAXG/USDC", common.okx("PAXG-USDC"), common.parse_okx),
    common.PairProbe("OKX Europe*", "PAXG/EUR", common.okx("PAXG-EUR"), common.parse_okx),
    # Bitvavo: MiCA/EEA venue, public books with low USDC-pair fees.
    common.PairProbe("Bitvavo", "BTC/USDC", bitvavo("BTC-USDC"), parse_bitvavo),
    common.PairProbe("Bitvavo", "ETH/USDC", bitvavo("ETH-USDC"), parse_bitvavo),
    common.PairProbe("Bitvavo", "BTC/EUR", bitvavo("BTC-EUR"), parse_bitvavo),
    common.PairProbe("Bitvavo", "ETH/EUR", bitvavo("ETH-EUR"), parse_bitvavo),
    common.PairProbe("Bitvavo", "PAXG/USDC", bitvavo("PAXG-USDC"), parse_bitvavo),
    common.PairProbe("Bitvavo", "PAXG/EUR", bitvavo("PAXG-EUR"), parse_bitvavo),
    # Kraken France/EEA, USDT excluded.
    common.PairProbe("Kraken", "BTC/USDC", common.kraken("XBTUSDC"), common.parse_kraken),
    common.PairProbe("Kraken", "ETH/USDC", common.kraken("ETHUSDC"), common.parse_kraken),
    common.PairProbe("Kraken", "BTC/EUR", common.kraken("XXBTZEUR"), common.parse_kraken),
    common.PairProbe("Kraken", "ETH/EUR", common.kraken("XETHZEUR"), common.parse_kraken),
    common.PairProbe("Kraken", "PAXG/EUR", common.kraken("PAXGEUR"), common.parse_kraken),
    common.PairProbe("Kraken", "PAXG/USD", common.kraken("PAXGUSD"), common.parse_kraken),
    # Coinbase exchange public books. Coinbase has no BTC/USDC book on this API.
    common.PairProbe("Coinbase", "BTC/USD", common.coinbase("BTC-USD"), common.parse_coinbase),
    common.PairProbe("Coinbase", "ETH/USD", common.coinbase("ETH-USD"), common.parse_coinbase),
    common.PairProbe("Coinbase", "BTC/EUR", common.coinbase("BTC-EUR"), common.parse_coinbase),
    common.PairProbe("Coinbase", "ETH/EUR", common.coinbase("ETH-EUR"), common.parse_coinbase),
    common.PairProbe("Coinbase", "PAXG/USD", common.coinbase("PAXG-USD"), common.parse_coinbase),
    # Bitstamp Europe.
    common.PairProbe("Bitstamp", "BTC/EUR", bitstamp("btceur"), parse_bitstamp),
    common.PairProbe("Bitstamp", "ETH/EUR", bitstamp("etheur"), parse_bitstamp),
    common.PairProbe("Bitstamp", "PAXG/EUR", bitstamp("paxgeur"), parse_bitstamp),
    common.PairProbe("Bitstamp", "PAXG/USD", bitstamp("paxgusd"), parse_bitstamp),
    # Bybit EU.
    common.PairProbe("Bybit EU*", "BTC/USDC", bybit_eu("BTCUSDC"), parse_bybit),
    common.PairProbe("Bybit EU*", "ETH/USDC", bybit_eu("ETHUSDC"), parse_bybit),
    common.PairProbe("Bybit EU*", "PAXG/USDC", bybit_eu("PAXGUSDC"), parse_bybit),
    # Gate Europe: global public API is used as an indicative proxy.
    common.PairProbe("Gate Europe*", "BTC/USDC", common.gate("BTC_USDC"), common.parse_gate),
    common.PairProbe("Gate Europe*", "ETH/USDC", common.gate("ETH_USDC"), common.parse_gate),
    common.PairProbe("Gate Europe*", "PAXG/USDC", common.gate("PAXG_USDC"), common.parse_gate),
    # Crypto.com, indicative until France account confirms exchange availability.
    common.PairProbe("Crypto.com*", "BTC/USDC", common.crypto_com("BTC_USDC"), common.parse_crypto_com),
    common.PairProbe("Crypto.com*", "ETH/USDC", common.crypto_com("ETH_USDC"), common.parse_crypto_com),
    common.PairProbe("Crypto.com*", "PAXG/USD", common.crypto_com("PAXG_USD"), common.parse_crypto_com),
    # Gemini EU, useful mostly to falsify PAXG/USDC cost.
    common.PairProbe("Gemini", "BTC/USD", common.gemini("btcusd"), common.parse_gemini),
    common.PairProbe("Gemini", "ETH/USD", common.gemini("ethusd"), common.parse_gemini),
    common.PairProbe("Gemini", "PAXG/USDC", common.gemini("paxgusdc"), common.parse_gemini),
    common.PairProbe("Gemini", "PAXG/USD", common.gemini("paxgusd"), common.parse_gemini),
]


SCENARIOS = [
    FranceScenario("OKX Europe*", "BTC/USDC", "OKX Europe BTC/USDC regular spot-only", "BTC", Decimal("20.000"), "france_watch", "OKX Europe, conservative public EEA spot-only taker"),
    FranceScenario("OKX Europe*", "ETH/USDC", "OKX Europe ETH/USDC regular spot-only", "ETH", Decimal("20.000"), "france_watch", "OKX Europe, conservative public EEA spot-only taker"),
    FranceScenario("OKX Europe*", "BTC/EUR", "OKX Europe BTC/EUR regular spot-only", "BTC", Decimal("20.000"), "france_watch", "OKX Europe, conservative public EEA spot-only taker"),
    FranceScenario("OKX Europe*", "ETH/EUR", "OKX Europe ETH/EUR regular spot-only", "ETH", Decimal("20.000"), "france_watch", "OKX Europe, conservative public EEA spot-only taker"),
    FranceScenario("OKX Europe*", "PAXG/USDC", "OKX Europe PAXG/USDC probe", "PAXG", Decimal("20.000"), "france_watch", "probe usually errors/empty if pair unavailable"),
    FranceScenario("OKX Europe*", "PAXG/EUR", "OKX Europe PAXG/EUR probe", "PAXG", Decimal("20.000"), "france_watch", "probe usually errors/empty if pair unavailable"),
    FranceScenario("Bitvavo", "BTC/USDC", "Bitvavo BTC/USDC base tier", "BTC", Decimal("5.000"), "france_allowed", "USDC-pair taker fee, no PAXG market found"),
    FranceScenario("Bitvavo", "ETH/USDC", "Bitvavo ETH/USDC base tier", "ETH", Decimal("5.000"), "france_allowed", "USDC-pair taker fee, no PAXG market found"),
    FranceScenario("Bitvavo", "BTC/EUR", "Bitvavo BTC/EUR base tier", "BTC", Decimal("25.000"), "france_allowed", "EUR-pair taker fee"),
    FranceScenario("Bitvavo", "ETH/EUR", "Bitvavo ETH/EUR base tier", "ETH", Decimal("25.000"), "france_allowed", "EUR-pair taker fee"),
    FranceScenario("Bitvavo", "PAXG/USDC", "Bitvavo PAXG/USDC probe", "PAXG", Decimal("5.000"), "france_allowed", "missing market in public API at snapshot"),
    FranceScenario("Bitvavo", "PAXG/EUR", "Bitvavo PAXG/EUR probe", "PAXG", Decimal("25.000"), "france_allowed", "missing market in public API at snapshot"),
    FranceScenario("Kraken", "BTC/USDC", "Kraken BTC/USDC Tier 1", "BTC", Decimal("8.000"), "france_allowed", "Kraken Pro base taker fee"),
    FranceScenario("Kraken", "ETH/USDC", "Kraken ETH/USDC Tier 1", "ETH", Decimal("8.000"), "france_allowed", "Kraken Pro base taker fee"),
    FranceScenario("Kraken", "BTC/EUR", "Kraken BTC/EUR Tier 1", "BTC", Decimal("8.000"), "france_allowed", "Kraken Pro base taker fee"),
    FranceScenario("Kraken", "ETH/EUR", "Kraken ETH/EUR Tier 1", "ETH", Decimal("8.000"), "france_allowed", "Kraken Pro base taker fee"),
    FranceScenario("Kraken", "PAXG/EUR", "Kraken PAXG/EUR Tier 1", "PAXG", Decimal("8.000"), "france_allowed", "useful for existing PAXG and now competitive after fee correction"),
    FranceScenario("Kraken", "PAXG/USD", "Kraken PAXG/USD Tier 1", "PAXG", Decimal("8.000"), "france_allowed", "USD route, competitive after fee correction"),
    FranceScenario("Coinbase", "BTC/USD", "Coinbase BTC/USD max public", "BTC", Decimal("60.000"), "france_allowed", "public max retail Advanced fee"),
    FranceScenario("Coinbase", "ETH/USD", "Coinbase ETH/USD max public", "ETH", Decimal("60.000"), "france_allowed", "public max retail Advanced fee"),
    FranceScenario("Coinbase", "BTC/EUR", "Coinbase BTC/EUR max public", "BTC", Decimal("60.000"), "france_allowed", "public max retail Advanced fee"),
    FranceScenario("Coinbase", "ETH/EUR", "Coinbase ETH/EUR max public", "ETH", Decimal("60.000"), "france_allowed", "public max retail Advanced fee"),
    FranceScenario("Coinbase", "PAXG/USD", "Coinbase PAXG/USD max public", "PAXG", Decimal("60.000"), "france_allowed", "USD route, no stablecoin direct"),
    FranceScenario("Bitstamp", "BTC/EUR", "Bitstamp BTC/EUR public", "BTC", Decimal("16.000"), "france_allowed", "public example fee from API docs, verify account tier"),
    FranceScenario("Bitstamp", "ETH/EUR", "Bitstamp ETH/EUR public", "ETH", Decimal("16.000"), "france_allowed", "public example fee from API docs, verify account tier"),
    FranceScenario("Bitstamp", "PAXG/EUR", "Bitstamp PAXG/EUR public", "PAXG", Decimal("16.000"), "france_allowed", "liquidity historically weak"),
    FranceScenario("Bitstamp", "PAXG/USD", "Bitstamp PAXG/USD public", "PAXG", Decimal("16.000"), "france_allowed", "liquidity historically weak"),
    FranceScenario("Bybit EU*", "BTC/USDC", "Bybit EU BTC/USDC non-VIP", "BTC", Decimal("25.000"), "france_watch", "Bybit EU public non-VIP taker"),
    FranceScenario("Bybit EU*", "ETH/USDC", "Bybit EU ETH/USDC non-VIP", "ETH", Decimal("25.000"), "france_watch", "Bybit EU public non-VIP taker"),
    FranceScenario("Bybit EU*", "PAXG/USDC", "Bybit EU PAXG/USDC probe", "PAXG", Decimal("25.000"), "france_watch", "probe validates whether pair exists"),
    FranceScenario("Gate Europe*", "BTC/USDC", "Gate Europe BTC/USDC VIP0", "BTC", Decimal("10.000"), "france_watch", "Gate Europe fee page VIP0; public book proxy"),
    FranceScenario("Gate Europe*", "ETH/USDC", "Gate Europe ETH/USDC VIP0", "ETH", Decimal("10.000"), "france_watch", "Gate Europe fee page VIP0; public book proxy"),
    FranceScenario("Gate Europe*", "PAXG/USDC", "Gate Europe PAXG/USDC probe", "PAXG", Decimal("10.000"), "france_watch", "probe validates whether pair exists"),
    FranceScenario("Crypto.com*", "BTC/USDC", "Crypto.com BTC/USDC Level 1", "BTC", Decimal("50.000"), "france_watch", "France exchange availability/account fee to confirm"),
    FranceScenario("Crypto.com*", "ETH/USDC", "Crypto.com ETH/USDC Level 1", "ETH", Decimal("50.000"), "france_watch", "France exchange availability/account fee to confirm"),
    FranceScenario("Crypto.com*", "PAXG/USD", "Crypto.com PAXG/USD Level 1", "PAXG", Decimal("50.000"), "france_watch", "USD route if available to account"),
    FranceScenario("Gemini", "BTC/USD", "Gemini BTC/USD fee not final", "BTC", Decimal("120.000"), "france_allowed", "base fee appears high; verify account"),
    FranceScenario("Gemini", "ETH/USD", "Gemini ETH/USD fee not final", "ETH", Decimal("120.000"), "france_allowed", "base fee appears high; verify account"),
    FranceScenario("Gemini", "PAXG/USDC", "Gemini PAXG/USDC fee not final", "PAXG", Decimal("120.000"), "france_allowed", "direct stablecoin route but spread/liquidity poor"),
    FranceScenario("Gemini", "PAXG/USD", "Gemini PAXG/USD fee not final", "PAXG", Decimal("120.000"), "france_allowed", "USD route, fee to verify"),
]


def total_cost_bps(result: common.SnapshotResult, fee_bps: Decimal | None, size: Decimal) -> Decimal | None:
    if fee_bps is None:
        return None
    impact = result.buy_impact_bps_by_size.get(size)
    if impact is None:
        return None
    return fee_bps + impact


def size_key(size: Decimal) -> str:
    value = format(size, "f")
    if "." in value:
        value = value.rstrip("0").rstrip(".")
    return value


def fmt(value: Decimal | None, places: int = 3) -> str:
    return common.fmt(value, places)


def collect(probes: Iterable[common.PairProbe]) -> tuple[dict[tuple[str, str], common.SnapshotResult], list[str]]:
    results: dict[tuple[str, str], common.SnapshotResult] = {}
    errors: list[str] = []
    for probe in probes:
        try:
            result = common.snapshot(probe)
            results[(result.exchange, result.pair)] = result
        except Exception as exc:  # noqa: BLE001 - research script reports all probe failures.
            errors.append(f"{probe.exchange} {probe.pair}: {type(exc).__name__}: {exc}")
    return results, errors


def best_by_asset(results: dict[tuple[str, str], common.SnapshotResult], size: Decimal, include_watch: bool) -> dict[str, tuple[FranceScenario, Decimal]]:
    best: dict[str, tuple[FranceScenario, Decimal]] = {}
    for scenario in SCENARIOS:
        if not include_watch and scenario.status != "france_allowed":
            continue
        result = results.get((scenario.exchange, scenario.pair))
        if result is None:
            continue
        cost = total_cost_bps(result, scenario.taker_fee_bps, size)
        if cost is None:
            continue
        previous = best.get(scenario.asset)
        if previous is None or cost < previous[1]:
            best[scenario.asset] = (scenario, cost)
    return best


def print_markdown(
    results: dict[tuple[str, str], common.SnapshotResult],
    errors: list[str],
    sizes: tuple[Decimal, ...],
    generated_at: str,
) -> None:
    print(f"# France/MiCA trade cost snapshot ({generated_at})")
    print()
    print("USDT, Binance global, Bitget et KuCoin global sont exclus de ce snapshot.")
    print("Les lignes marquees `*` doivent etre confirmees par compte/API France avant execution.")
    print()
    print("| Venue | Pair | Spread bps | Depth ask 10 bps | Impact 1k | Impact 5k |")
    print("|---|---|---:|---:|---:|---:|")
    for probe in PROBES:
        result = results.get((probe.exchange, probe.pair))
        if result is None:
            continue
        print(
            "| "
            + " | ".join(
                [
                    result.exchange,
                    result.pair,
                    fmt(result.spread_bps, 3),
                    fmt(result.ask_depth_10_bps, 0),
                    fmt(result.buy_impact_bps_by_size.get(Decimal("1000")), 3),
                    fmt(result.buy_impact_bps_by_size.get(Decimal("5000")), 3),
                ]
            )
            + " |"
        )

    print()
    print("| Asset | Scenario | Route | Status | Taker fee bps | Total 100 | Total 500 | Total 1k | Total 5k | Note |")
    print("|---|---|---|---|---:|---:|---:|---:|---:|---|")
    for scenario in SCENARIOS:
        result = results.get((scenario.exchange, scenario.pair))
        totals = []
        for size in (Decimal("100"), Decimal("500"), Decimal("1000"), Decimal("5000")):
            totals.append(fmt(None if result is None else total_cost_bps(result, scenario.taker_fee_bps, size), 3))
        print(
            "| "
            + " | ".join(
                [
                    scenario.asset,
                    scenario.label,
                    f"{scenario.exchange} {scenario.pair}",
                    scenario.status,
                    fmt(scenario.taker_fee_bps, 3),
                    *totals,
                    scenario.note if result is not None else f"missing book; {scenario.note}",
                ]
            )
            + " |"
        )

    print()
    print("Best route, France allowed only:")
    print()
    print("| Size | BTC | ETH | PAXG |")
    print("|---:|---|---|---|")
    for size in sizes:
        best = best_by_asset(results, size, include_watch=False)
        cells = [size_key(size)]
        for asset in ("BTC", "ETH", "PAXG"):
            item = best.get(asset)
            cells.append("-" if item is None else f"{item[0].exchange} {item[0].pair} ({fmt(item[1], 3)} bps)")
        print("| " + " | ".join(cells) + " |")

    print()
    print("Best route, including watchlist/proxy venues:")
    print()
    print("| Size | BTC | ETH | PAXG |")
    print("|---:|---|---|---|")
    for size in sizes:
        best = best_by_asset(results, size, include_watch=True)
        cells = [size_key(size)]
        for asset in ("BTC", "ETH", "PAXG"):
            item = best.get(asset)
            cells.append("-" if item is None else f"{item[0].exchange} {item[0].pair} ({fmt(item[1], 3)} bps)")
        print("| " + " | ".join(cells) + " |")

    if errors:
        print()
        print("Probe errors / unavailable pairs:")
        for error in errors:
            print(f"- {error}")


def json_payload(
    results: dict[tuple[str, str], common.SnapshotResult],
    errors: list[str],
    generated_at: str,
) -> dict[str, object]:
    scenario_rows = []
    for scenario in SCENARIOS:
        result = results.get((scenario.exchange, scenario.pair))
        scenario_rows.append(
            {
                **asdict(scenario),
                "taker_fee_bps": None if scenario.taker_fee_bps is None else str(scenario.taker_fee_bps),
                "cost_bps_by_size": {
                    size_key(size): None if result is None else str(total_cost_bps(result, scenario.taker_fee_bps, size))
                    for size in common.SIZES
                },
            }
        )
    return {
        "generated_at": generated_at,
        "probes": [common.snapshot_result_json(result) for result in results.values()],
        "scenarios": scenario_rows,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sizes", default="100,500,1000,5000")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--strict", action="store_true", help="return non-zero when a probe is unavailable")
    args = parser.parse_args()

    sizes = tuple(Decimal(raw.strip()) for raw in args.sizes.split(",") if raw.strip())
    common.SIZES = sizes
    generated_at = datetime.now(UTC).replace(microsecond=0).isoformat()
    results, errors = collect(PROBES)
    if args.json:
        print(json.dumps(json_payload(results, errors, generated_at), indent=2, default=str))
    else:
        print_markdown(results, errors, sizes, generated_at)
    return 1 if args.strict and errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
