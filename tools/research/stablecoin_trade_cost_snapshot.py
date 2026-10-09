#!/usr/bin/env python3
"""
Snapshot public spot order books for the stablecoin -> BTC/ETH/PAXG fee study.

The script intentionally uses only the Python standard library so it can be run
from a fresh workstation:

    python tools/research/stablecoin_trade_cost_snapshot.py

It does not place orders and does not use API keys. The output is a Markdown
table with spread, depth close to mid, and theoretical buy slippage for fixed
notional sizes. Fee schedules remain documented manually because public account
fees can depend on jurisdiction, VIP tier, and exchange-specific promotions.

Use --json to emit a machine-readable snapshot for downstream tooling.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Callable, Iterable


DEFAULT_SIZES = (Decimal("100"), Decimal("500"), Decimal("1000"), Decimal("5000"))
DISPLAY_SIZES = (Decimal("1000"), Decimal("5000"))
SIZES = DEFAULT_SIZES
BAND_BPS = Decimal("10")
TIMEOUT_SECONDS = 15


@dataclass(frozen=True)
class PairProbe:
    exchange: str
    pair: str
    url: str
    parser: Callable[[object], tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]]


@dataclass(frozen=True)
class SnapshotResult:
    exchange: str
    pair: str
    spread_bps: Decimal
    ask_depth_10_bps: Decimal
    bid_depth_10_bps: Decimal
    slippage_bps_by_size: dict[Decimal, Decimal | None]
    buy_impact_bps_by_size: dict[Decimal, Decimal | None]
    sell_impact_bps_by_size: dict[Decimal, Decimal | None]


@dataclass(frozen=True)
class FeeScenario:
    exchange: str
    pair: str
    label: str
    taker_fee_bps: Decimal
    note: str


@dataclass(frozen=True)
class TwoLegRoute:
    label: str
    conversion_exchange: str
    conversion_pair: str
    conversion_taker_fee_bps: Decimal
    target_exchange: str
    target_pair: str
    target_taker_fee_bps: Decimal
    note: str


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
            "user-agent": "TradeIO5 research snapshot/1.0",
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


def levels(raw_levels: Iterable[Iterable[object]]) -> list[tuple[Decimal, Decimal]]:
    return [(decimal(price), decimal(quantity)) for price, quantity, *_ in raw_levels]


def parse_binance(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    return levels(payload.get("bids", [])), levels(payload.get("asks", []))


def parse_okx(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    books = payload.get("data") or []
    book = books[0] if books else {}
    return levels(book.get("bids", [])), levels(book.get("asks", []))


def parse_kucoin(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    book = payload.get("data") or {}
    return levels(book.get("bids", [])), levels(book.get("asks", []))


def parse_gate(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    return levels(payload.get("bids", [])), levels(payload.get("asks", []))


def parse_bitget(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    book = payload.get("data") or {}
    return levels(book.get("bids", [])), levels(book.get("asks", []))


def parse_crypto_com(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    result = payload.get("result") or {}
    books = result.get("data") or []
    book = books[0] if books else {}
    bids = book.get("bids") or book.get("b") or []
    asks = book.get("asks") or book.get("a") or []
    return levels(bids), levels(asks)


def parse_kraken(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    result = payload.get("result") or {}
    book = next(iter(result.values()), {})
    return levels(book.get("bids", [])), levels(book.get("asks", []))


def parse_coinbase(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}
    pricebook = payload.get("pricebook") or payload
    return levels(pricebook.get("bids", [])), levels(pricebook.get("asks", []))


def parse_gemini(data: object) -> tuple[list[tuple[Decimal, Decimal]], list[tuple[Decimal, Decimal]]]:
    payload = data if isinstance(data, dict) else {}

    def convert(items: Iterable[dict[str, object]]) -> list[tuple[Decimal, Decimal]]:
        return [(decimal(item["price"]), decimal(item["amount"])) for item in items]

    return convert(payload.get("bids", [])), convert(payload.get("asks", []))


def binance(symbol: str) -> str:
    return f"https://api.binance.com/api/v3/depth?symbol={symbol}&limit=5000"


def okx(inst_id: str) -> str:
    return f"https://www.okx.com/api/v5/market/books?instId={urllib.parse.quote(inst_id)}&sz=400"


def kucoin(symbol: str) -> str:
    return f"https://api.kucoin.com/api/v1/market/orderbook/level2_100?symbol={urllib.parse.quote(symbol)}"


def gate(currency_pair: str) -> str:
    return f"https://api.gateio.ws/api/v4/spot/order_book?currency_pair={currency_pair}&limit=100"


def bitget(symbol: str) -> str:
    return f"https://api.bitget.com/api/v2/spot/market/orderbook?symbol={symbol}&type=step0&limit=100"


def crypto_com(instrument: str) -> str:
    return f"https://api.crypto.com/exchange/v1/public/get-book?instrument_name={instrument}&depth=150"


def kraken(pair: str) -> str:
    return f"https://api.kraken.com/0/public/Depth?pair={pair}&count=500"


def coinbase(product_id: str) -> str:
    return f"https://api.exchange.coinbase.com/products/{product_id}/book?level=2"


def gemini(symbol: str) -> str:
    return f"https://api.gemini.com/v1/book/{symbol}?limit_bids=100&limit_asks=100"


PROBES = [
    PairProbe("Binance", "BTC/USDT", binance("BTCUSDT"), parse_binance),
    PairProbe("Binance", "BTC/USDC", binance("BTCUSDC"), parse_binance),
    PairProbe("Binance", "ETH/USDT", binance("ETHUSDT"), parse_binance),
    PairProbe("Binance", "ETH/USDC", binance("ETHUSDC"), parse_binance),
    PairProbe("Binance", "USDC/USDT", binance("USDCUSDT"), parse_binance),
    PairProbe("Binance", "PAXG/USDT", binance("PAXGUSDT"), parse_binance),
    PairProbe("Binance", "PAXG/USDC", binance("PAXGUSDC"), parse_binance),
    PairProbe("OKX", "BTC/USDT", okx("BTC-USDT"), parse_okx),
    PairProbe("OKX", "BTC/USDC", okx("BTC-USDC"), parse_okx),
    PairProbe("OKX", "ETH/USDT", okx("ETH-USDT"), parse_okx),
    PairProbe("OKX", "ETH/USDC", okx("ETH-USDC"), parse_okx),
    PairProbe("OKX", "USDC/USDT", okx("USDC-USDT"), parse_okx),
    PairProbe("OKX", "PAXG/USDT", okx("PAXG-USDT"), parse_okx),
    PairProbe("KuCoin", "USDC/USDT", kucoin("USDC-USDT"), parse_kucoin),
    PairProbe("KuCoin", "PAXG/USDT", kucoin("PAXG-USDT"), parse_kucoin),
    PairProbe("Gate.io", "USDC/USDT", gate("USDC_USDT"), parse_gate),
    PairProbe("Gate.io", "PAXG/USDT", gate("PAXG_USDT"), parse_gate),
    PairProbe("Bitget", "USDC/USDT", bitget("USDCUSDT"), parse_bitget),
    PairProbe("Bitget", "PAXG/USDT", bitget("PAXGUSDT"), parse_bitget),
    PairProbe("Crypto.com", "PAXG/USDT", crypto_com("PAXG_USDT"), parse_crypto_com),
    PairProbe("Kraken", "PAXG/USD", kraken("PAXGUSD"), parse_kraken),
    PairProbe("Kraken", "PAXG/EUR", kraken("PAXGEUR"), parse_kraken),
    PairProbe("Coinbase", "PAXG/USD", coinbase("PAXG-USD"), parse_coinbase),
    PairProbe("Gemini", "PAXG/USDC", gemini("paxgusdc"), parse_gemini),
]

FEE_SCENARIOS = [
    FeeScenario("Binance", "BTC/USDT", "Binance BTC/USDT BNB discount", Decimal("7.500"), "cost-first USDT route"),
    FeeScenario("Binance", "ETH/USDT", "Binance ETH/USDT BNB discount", Decimal("7.500"), "cost-first USDT route"),
    FeeScenario("Binance", "BTC/USDC", "Binance BTC/USDC taker + BNB", Decimal("7.125"), "EU-friendly stablecoin route"),
    FeeScenario("Binance", "ETH/USDC", "Binance ETH/USDC taker + BNB", Decimal("7.125"), "EU-friendly stablecoin route"),
    FeeScenario("Binance", "PAXG/USDT", "Binance BNB discount", Decimal("7.500"), "cost-first USDT route"),
    FeeScenario("Binance", "PAXG/USDT", "Binance regular", Decimal("10.000"), "same route without BNB fee payment"),
    FeeScenario("Binance", "PAXG/USDC", "Binance USDC taker + BNB", Decimal("7.125"), "EU-friendly stablecoin candidate"),
    FeeScenario("Binance", "PAXG/USDC", "Binance USDC taker", Decimal("9.500"), "same route without BNB fee payment"),
    FeeScenario("OKX", "BTC/USDT", "OKX BTC/USDT regular with X-Perps", Decimal("10.000"), "depends on account setup"),
    FeeScenario("OKX", "ETH/USDT", "OKX ETH/USDT regular with X-Perps", Decimal("10.000"), "depends on account setup"),
    FeeScenario("OKX", "BTC/USDC", "OKX BTC/USDC regular with X-Perps", Decimal("10.000"), "depends on account setup"),
    FeeScenario("OKX", "ETH/USDC", "OKX ETH/USDC regular with X-Perps", Decimal("10.000"), "depends on account setup"),
    FeeScenario("OKX", "PAXG/USDT", "OKX regular with X-Perps", Decimal("10.000"), "depends on account setup"),
    FeeScenario("KuCoin", "PAXG/USDT", "KuCoin VIP0 base", Decimal("10.000"), "KCS discount can reduce taker"),
    FeeScenario("Gate.io", "PAXG/USDT", "Gate.io VIP0 base", Decimal("10.000"), "public VIP0 spot fee, GT discount can reduce"),
    FeeScenario("Bitget", "PAXG/USDT", "Bitget base", Decimal("10.000"), "BGB discount can reduce taker"),
    FeeScenario("Crypto.com", "PAXG/USDT", "Crypto.com Level 1 public", Decimal("50.000"), "public entry tier, no CRO/VIP"),
    FeeScenario("Kraken", "PAXG/USD", "Kraken Pro Tier 1", Decimal("80.000"), "USD route, not stablecoin"),
    FeeScenario("Kraken", "PAXG/EUR", "Kraken Pro Tier 1", Decimal("80.000"), "EUR route, not stablecoin"),
    FeeScenario("Coinbase", "PAXG/USD", "Coinbase Advanced max public", Decimal("60.000"), "USD route, not stablecoin"),
]

TWO_LEG_ROUTES = [
    TwoLegRoute(
        "Binance USDC reserve -> USDT -> PAXG, BNB fees",
        "Binance",
        "USDC/USDT",
        Decimal("7.125"),
        "Binance",
        "PAXG/USDT",
        Decimal("7.500"),
        "tests whether USDC reserve should still execute PAXG through USDT",
    ),
    TwoLegRoute(
        "Binance USDC reserve -> USDT -> PAXG, regular fees",
        "Binance",
        "USDC/USDT",
        Decimal("9.500"),
        "Binance",
        "PAXG/USDT",
        Decimal("10.000"),
        "same route without BNB fee payment",
    ),
    TwoLegRoute(
        "OKX USDC reserve -> USDT -> PAXG, regular X-Perps",
        "OKX",
        "USDC/USDT",
        Decimal("10.000"),
        "OKX",
        "PAXG/USDT",
        Decimal("10.000"),
        "no-Binance benchmark when reserve is USDC but PAXG only has USDT",
    ),
    TwoLegRoute(
        "KuCoin USDC reserve -> USDT -> PAXG, VIP0 base",
        "KuCoin",
        "USDC/USDT",
        Decimal("10.000"),
        "KuCoin",
        "PAXG/USDT",
        Decimal("10.000"),
        "no-Binance benchmark when reserve is USDC but PAXG only has USDT",
    ),
    TwoLegRoute(
        "Gate.io USDC reserve -> USDT -> PAXG, VIP0 base",
        "Gate.io",
        "USDC/USDT",
        Decimal("10.000"),
        "Gate.io",
        "PAXG/USDT",
        Decimal("10.000"),
        "no-Binance benchmark when reserve is USDC but PAXG only has USDT",
    ),
    TwoLegRoute(
        "Bitget USDC reserve -> USDT -> PAXG, base",
        "Bitget",
        "USDC/USDT",
        Decimal("10.000"),
        "Bitget",
        "PAXG/USDT",
        Decimal("10.000"),
        "no-Binance benchmark when reserve is USDC but PAXG only has USDT",
    ),
]

PAXG_AGGREGATE_PAIRS = {
    ("Binance", "PAXG/USDT"),
    ("Binance", "PAXG/USDC"),
    ("OKX", "PAXG/USDT"),
    ("KuCoin", "PAXG/USDT"),
    ("Bitget", "PAXG/USDT"),
    ("Crypto.com", "PAXG/USDT"),
    ("Gemini", "PAXG/USDC"),
}

PROFILE_NAMES = {
    "LOWEST_COST_STABLECOIN": "Lowest cost stablecoin route",
    "EU_CONSERVATIVE_USDC": "EU conservative USDC route",
    "BINANCE_UNAVAILABLE_USDT": "USDT fallback without Binance",
}


def spread_bps(best_bid: Decimal, best_ask: Decimal) -> Decimal:
    mid = (best_bid + best_ask) / 2
    if mid <= 0:
        return Decimal("NaN")
    return (best_ask - best_bid) / mid * Decimal("10000")


def ask_depth_near_mid(asks: list[tuple[Decimal, Decimal]], mid: Decimal, band_bps: Decimal) -> Decimal:
    max_price = mid * (Decimal("1") + band_bps / Decimal("10000"))
    return sum((price * quantity for price, quantity in asks if price <= max_price), Decimal("0"))


def bid_depth_near_mid(bids: list[tuple[Decimal, Decimal]], mid: Decimal, band_bps: Decimal) -> Decimal:
    min_price = mid * (Decimal("1") - band_bps / Decimal("10000"))
    return sum((price * quantity for price, quantity in bids if price >= min_price), Decimal("0"))


def buy_average_price(asks: list[tuple[Decimal, Decimal]], quote_notional: Decimal) -> Decimal | None:
    spent = Decimal("0")
    acquired = Decimal("0")
    for price, quantity in asks:
        level_quote = price * quantity
        take_quote = min(level_quote, quote_notional - spent)
        if take_quote <= 0:
            break
        spent += take_quote
        acquired += take_quote / price
        if spent >= quote_notional:
            break
    if spent < quote_notional or acquired <= 0:
        return None
    return spent / acquired


def sell_average_price(bids: list[tuple[Decimal, Decimal]], base_quantity: Decimal) -> Decimal | None:
    sold = Decimal("0")
    received = Decimal("0")
    for price, quantity in bids:
        take_base = min(quantity, base_quantity - sold)
        if take_base <= 0:
            break
        sold += take_base
        received += take_base * price
        if sold >= base_quantity:
            break
    if sold < base_quantity or sold <= 0:
        return None
    return received / sold


def slippage_bps(asks: list[tuple[Decimal, Decimal]], best_ask: Decimal, quote_notional: Decimal) -> Decimal | None:
    average = buy_average_price(asks, quote_notional)
    if average is None:
        return None
    return (average / best_ask - Decimal("1")) * Decimal("10000")


def buy_impact_bps(asks: list[tuple[Decimal, Decimal]], mid: Decimal, quote_notional: Decimal) -> Decimal | None:
    average = buy_average_price(asks, quote_notional)
    if average is None:
        return None
    return (average / mid - Decimal("1")) * Decimal("10000")


def sell_impact_bps(bids: list[tuple[Decimal, Decimal]], mid: Decimal, base_quantity: Decimal) -> Decimal | None:
    average = sell_average_price(bids, base_quantity)
    if average is None:
        return None
    return (Decimal("1") - average / mid) * Decimal("10000")


def fmt(value: Decimal | None, places: int = 3) -> str:
    if value is None:
        return "N/A"
    if not value.is_finite():
        return "N/A"
    if value == 0:
        return "0"
    quant = Decimal("1").scaleb(-places)
    rounded = value.quantize(quant)
    if rounded == 0:
        return "0"
    if abs(rounded) >= Decimal("1000000"):
        return f"{rounded:,.0f}".replace(",", " ")
    return f"{rounded:f}"


def fmt_size(size: Decimal) -> str:
    if size == size.to_integral_value():
        return f"{size:,.0f}".replace(",", " ")
    return f"{size:f}"


def size_key(size: Decimal) -> str:
    if size == size.to_integral_value():
        return f"{size:.0f}"
    return f"{size:f}"


def decimal_json(value: Decimal | None) -> str | None:
    if value is None or not value.is_finite():
        return None
    return f"{value.normalize():f}"


def size_label(size: Decimal) -> str:
    if size == Decimal("1000"):
        return "1k"
    if size == Decimal("5000"):
        return "5k"
    return fmt_size(size)


def median(values: list[Decimal]) -> Decimal | None:
    if not values:
        return None
    sorted_values = sorted(values)
    middle = len(sorted_values) // 2
    if len(sorted_values) % 2:
        return sorted_values[middle]
    return (sorted_values[middle - 1] + sorted_values[middle]) / 2


def aggregate(values: Iterable[Decimal | None]) -> tuple[Decimal | None, Decimal | None, Decimal | None]:
    present = [value for value in values if value is not None and value.is_finite()]
    if not present:
        return None, None, None
    return min(present), median(present), max(present)


def snapshot(probe: PairProbe) -> SnapshotResult:
    data = http_json(probe.url)
    bids, asks = probe.parser(data)
    bids = sorted(bids, key=lambda item: item[0], reverse=True)
    asks = sorted(asks, key=lambda item: item[0])
    if not bids or not asks:
        raise ValueError("empty order book")
    best_bid, best_ask = bids[0][0], asks[0][0]
    mid = (best_bid + best_ask) / 2
    return SnapshotResult(
        exchange=probe.exchange,
        pair=probe.pair,
        spread_bps=spread_bps(best_bid, best_ask),
        ask_depth_10_bps=ask_depth_near_mid(asks, mid, BAND_BPS),
        bid_depth_10_bps=bid_depth_near_mid(bids, mid, BAND_BPS),
        slippage_bps_by_size={size: slippage_bps(asks, best_ask, size) for size in SIZES},
        buy_impact_bps_by_size={size: buy_impact_bps(asks, mid, size) for size in SIZES},
        sell_impact_bps_by_size={size: sell_impact_bps(bids, mid, size) for size in SIZES},
    )


def render_liquidity_row(result: SnapshotResult) -> str:
    sell_impact_1k = result.sell_impact_bps_by_size[Decimal("1000")] if result.pair == "USDC/USDT" else None
    sell_impact_5k = result.sell_impact_bps_by_size[Decimal("5000")] if result.pair == "USDC/USDT" else None
    values = [
        result.exchange,
        result.pair,
        fmt(result.spread_bps, 3),
        fmt(result.ask_depth_10_bps, 0),
        fmt(result.bid_depth_10_bps, 0),
        fmt(result.slippage_bps_by_size[Decimal("1000")], 3),
        fmt(result.slippage_bps_by_size[Decimal("5000")], 3),
        fmt(result.buy_impact_bps_by_size[Decimal("1000")], 3),
        fmt(result.buy_impact_bps_by_size[Decimal("5000")], 3),
        fmt(sell_impact_1k, 3),
        fmt(sell_impact_5k, 3),
    ]
    return "| " + " | ".join(values) + " |"


def render_cost_row(scenario: FeeScenario, result: SnapshotResult) -> str:
    impact_1k = result.buy_impact_bps_by_size[Decimal("1000")]
    impact_5k = result.buy_impact_bps_by_size[Decimal("5000")]
    cost_1k = None if impact_1k is None else scenario.taker_fee_bps + impact_1k
    cost_5k = None if impact_5k is None else scenario.taker_fee_bps + impact_5k
    values = [
        scenario.label,
        f"{scenario.exchange} {scenario.pair}",
        fmt(scenario.taker_fee_bps, 3),
        fmt(impact_1k, 3),
        fmt(cost_1k, 3),
        fmt(impact_5k, 3),
        fmt(cost_5k, 3),
        scenario.note,
    ]
    return "| " + " | ".join(values) + " |"


def render_two_leg_row(route: TwoLegRoute, results: dict[tuple[str, str], SnapshotResult]) -> str:
    conversion = results.get((route.conversion_exchange, route.conversion_pair))
    target = results.get((route.target_exchange, route.target_pair))
    if conversion is None or target is None:
        missing = "missing conversion book" if conversion is None else "missing target book"
        return (
            f"| {route.label} | {route.conversion_exchange} {route.conversion_pair} -> "
            f"{route.target_exchange} {route.target_pair} | N/A | N/A | N/A | N/A | N/A | {missing} |"
        )

    conversion_impact_1k = conversion.sell_impact_bps_by_size[Decimal("1000")]
    conversion_impact_5k = conversion.sell_impact_bps_by_size[Decimal("5000")]
    target_impact_1k = target.buy_impact_bps_by_size[Decimal("1000")]
    target_impact_5k = target.buy_impact_bps_by_size[Decimal("5000")]

    total_1k = (
        None
        if conversion_impact_1k is None or target_impact_1k is None
        else route.conversion_taker_fee_bps + conversion_impact_1k + route.target_taker_fee_bps + target_impact_1k
    )
    total_5k = (
        None
        if conversion_impact_5k is None or target_impact_5k is None
        else route.conversion_taker_fee_bps + conversion_impact_5k + route.target_taker_fee_bps + target_impact_5k
    )
    values = [
        route.label,
        f"{route.conversion_exchange} {route.conversion_pair} -> {route.target_exchange} {route.target_pair}",
        fmt(conversion_impact_1k, 3),
        fmt(target_impact_1k, 3),
        fmt(total_1k, 3),
        fmt(conversion_impact_5k, 3),
        fmt(target_impact_5k, 3),
        fmt(total_5k, 3),
        route.note,
    ]
    return "| " + " | ".join(values) + " |"


def base_quote(pair: str) -> tuple[str, str]:
    base, quote = pair.split("/", 1)
    return base, quote


def candidate_allowed(profile: str, scenario: FeeScenario) -> bool:
    _base, quote = base_quote(scenario.pair)
    if profile == "LOWEST_COST_STABLECOIN":
        return quote in {"USDT", "USDC"}
    if profile == "EU_CONSERVATIVE_USDC":
        return scenario.exchange == "Binance" and quote == "USDC"
    if profile == "BINANCE_UNAVAILABLE_USDT":
        return scenario.exchange != "Binance" and quote == "USDT"
    return False


def best_scenario(
    profile: str,
    asset: str,
    size: Decimal,
    results: dict[tuple[str, str], SnapshotResult],
) -> tuple[FeeScenario, Decimal] | None:
    candidates: list[tuple[FeeScenario, Decimal]] = []
    for scenario in FEE_SCENARIOS:
        base, _quote = base_quote(scenario.pair)
        if base != asset or not candidate_allowed(profile, scenario):
            continue
        result = results.get((scenario.exchange, scenario.pair))
        if result is None:
            continue
        cost = scenario_total_cost(result, scenario.taker_fee_bps, size)
        if cost is not None:
            candidates.append((scenario, cost))
    if not candidates:
        return None
    return min(candidates, key=lambda item: item[1])


def render_recommendations(results: dict[tuple[str, str], SnapshotResult]) -> None:
    print()
    print("Best route by profile, based on this snapshot:")
    print()
    print("| Profile | Asset | Best route 1k | Cost 1k bps | Best route 5k | Cost 5k bps |")
    print("|---|---|---|---:|---|---:|")
    for profile, profile_label in PROFILE_NAMES.items():
        for asset in ("BTC", "ETH", "PAXG"):
            best_1k = best_scenario(profile, asset, Decimal("1000"), results)
            best_5k = best_scenario(profile, asset, Decimal("5000"), results)
            route_1k = "-" if best_1k is None else f"{best_1k[0].exchange} {best_1k[0].pair} ({best_1k[0].label})"
            route_5k = "-" if best_5k is None else f"{best_5k[0].exchange} {best_5k[0].pair} ({best_5k[0].label})"
            values = [
                profile_label,
                asset,
                route_1k,
                fmt(None if best_1k is None else best_1k[1], 3),
                route_5k,
                fmt(None if best_5k is None else best_5k[1], 3),
            ]
            print("| " + " | ".join(values) + " |")

    print()
    print("PAXG best route ladder by configured size:")
    print()
    print("| Profile | " + " | ".join(size_label(size) for size in SIZES) + " |")
    print("|---|" + "|".join("---:" for _size in SIZES) + "|")
    for profile, profile_label in PROFILE_NAMES.items():
        cells = [profile_label]
        for size in SIZES:
            best = best_scenario(profile, "PAXG", size, results)
            if best is None:
                cells.append("-")
                continue
            scenario, cost = best
            cells.append(f"{scenario.exchange} {scenario.pair} ({fmt(cost, 3)} bps)")
        print("| " + " | ".join(cells) + " |")


def collect_snapshot_results() -> tuple[int, dict[tuple[str, str], SnapshotResult], list[str]]:
    exit_code = 0
    results: dict[tuple[str, str], SnapshotResult] = {}
    error_rows: list[str] = []
    for probe in PROBES:
        try:
            result = snapshot(probe)
            results[(result.exchange, result.pair)] = result
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError, IndexError, json.JSONDecodeError) as exc:
            exit_code = 1
            error_rows.append(f"| {probe.exchange} | {probe.pair} | ERROR: {type(exc).__name__} | | | | | | | | | |")
        except Exception as exc:
            exit_code = 1
            error_rows.append(f"| {probe.exchange} | {probe.pair} | ERROR: {type(exc).__name__} | | | | | | | | | |")
    return exit_code, results, error_rows


def render_snapshot_results(results: dict[tuple[str, str], SnapshotResult], error_rows: list[str]) -> None:
    print()
    print("| Exchange | Pair | Spread bps | Ask depth 10 bps | Bid depth 10 bps | Slip 1k | Slip 5k | Buy impact 1k | Buy impact 5k | Sell impact 1k | Sell impact 5k |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for probe in PROBES:
        result = results.get((probe.exchange, probe.pair))
        if result is not None:
            print(render_liquidity_row(result))
    for row in error_rows:
        print(row)

    print()
    print("Single-leg taker total cost vs mid, excluding deposit/withdrawal and prior stablecoin conversion:")
    print()
    print("| Scenario | Route | Taker fee bps | Impact 1k | Total 1k | Impact 5k | Total 5k | Note |")
    print("|---|---|---:|---:|---:|---:|---:|---|")
    for scenario in FEE_SCENARIOS:
        result = results.get((scenario.exchange, scenario.pair))
        if result is None:
            print(
                f"| {scenario.label} | {scenario.exchange} {scenario.pair} | "
                f"{fmt(scenario.taker_fee_bps, 3)} | N/A | N/A | N/A | N/A | missing order book |"
            )
            continue
        print(render_cost_row(scenario, result))

    print()
    print("Two-leg route cost, starting from USDC and executing PAXG through USDT:")
    print()
    print("| Scenario | Route | Conversion impact 1k | PAXG impact 1k | Total 1k | Conversion impact 5k | PAXG impact 5k | Total 5k | Note |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---|")
    for route in TWO_LEG_ROUTES:
        print(render_two_leg_row(route, results))
    render_recommendations(results)


def scenario_total_cost(result: SnapshotResult, taker_fee_bps: Decimal, size: Decimal) -> Decimal | None:
    impact = result.buy_impact_bps_by_size[size]
    if impact is None:
        return None
    return taker_fee_bps + impact


def snapshot_result_json(result: SnapshotResult) -> dict[str, object]:
    return {
        "exchange": result.exchange,
        "pair": result.pair,
        "spreadBps": decimal_json(result.spread_bps),
        "askDepth10BpsQuote": decimal_json(result.ask_depth_10_bps),
        "bidDepth10BpsQuote": decimal_json(result.bid_depth_10_bps),
        "slippageBpsByQuoteSize": {
            size_key(size): decimal_json(value)
            for size, value in result.slippage_bps_by_size.items()
        },
        "buyImpactBpsByQuoteSize": {
            size_key(size): decimal_json(value)
            for size, value in result.buy_impact_bps_by_size.items()
        },
        "sellImpactBpsByBaseSize": {
            size_key(size): decimal_json(value)
            for size, value in result.sell_impact_bps_by_size.items()
        },
    }


def fee_scenario_json(
    scenario: FeeScenario,
    results: dict[tuple[str, str], SnapshotResult],
) -> dict[str, object]:
    result = results.get((scenario.exchange, scenario.pair))
    return {
        "exchange": scenario.exchange,
        "pair": scenario.pair,
        "label": scenario.label,
        "takerFeeBps": decimal_json(scenario.taker_fee_bps),
        "note": scenario.note,
        "totalCostBpsByQuoteSize": {
            size_key(size): decimal_json(None if result is None else scenario_total_cost(result, scenario.taker_fee_bps, size))
            for size in SIZES
        },
        "marketImpactBpsByQuoteSize": {
            size_key(size): decimal_json(None if result is None else result.buy_impact_bps_by_size[size])
            for size in SIZES
        },
    }


def two_leg_total_cost(
    route: TwoLegRoute,
    results: dict[tuple[str, str], SnapshotResult],
    size: Decimal,
) -> Decimal | None:
    conversion = results.get((route.conversion_exchange, route.conversion_pair))
    target = results.get((route.target_exchange, route.target_pair))
    if conversion is None or target is None:
        return None
    conversion_impact = conversion.sell_impact_bps_by_size[size]
    target_impact = target.buy_impact_bps_by_size[size]
    if conversion_impact is None or target_impact is None:
        return None
    return route.conversion_taker_fee_bps + conversion_impact + route.target_taker_fee_bps + target_impact


def two_leg_route_json(
    route: TwoLegRoute,
    results: dict[tuple[str, str], SnapshotResult],
) -> dict[str, object]:
    return {
        "label": route.label,
        "conversionExchange": route.conversion_exchange,
        "conversionPair": route.conversion_pair,
        "conversionTakerFeeBps": decimal_json(route.conversion_taker_fee_bps),
        "targetExchange": route.target_exchange,
        "targetPair": route.target_pair,
        "targetTakerFeeBps": decimal_json(route.target_taker_fee_bps),
        "note": route.note,
        "totalCostBpsByQuoteSize": {
            size_key(size): decimal_json(two_leg_total_cost(route, results, size))
            for size in SIZES
        },
    }


def recommendations_json(results: dict[tuple[str, str], SnapshotResult]) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for profile, profile_label in PROFILE_NAMES.items():
        for asset in ("BTC", "ETH", "PAXG"):
            for size in SIZES:
                best = best_scenario(profile, asset, size, results)
                rows.append(
                    {
                        "profile": profile,
                        "profileLabel": profile_label,
                        "asset": asset,
                        "quoteSize": decimal_json(size),
                        "exchange": None if best is None else best[0].exchange,
                        "pair": None if best is None else best[0].pair,
                        "scenario": None if best is None else best[0].label,
                        "totalCostBps": None if best is None else decimal_json(best[1]),
                    }
                )
    return rows


def sample_json(
    timestamp_utc: str,
    results: dict[tuple[str, str], SnapshotResult],
    error_rows: list[str],
) -> dict[str, object]:
    ordered_results = [
        results[(probe.exchange, probe.pair)]
        for probe in PROBES
        if (probe.exchange, probe.pair) in results
    ]
    return {
        "snapshotUtc": timestamp_utc,
        "sizes": [decimal_json(size) for size in SIZES],
        "bandBps": decimal_json(BAND_BPS),
        "liquidity": [snapshot_result_json(result) for result in ordered_results],
        "singleLegCosts": [fee_scenario_json(scenario, results) for scenario in FEE_SCENARIOS],
        "twoLegCosts": [two_leg_route_json(route, results) for route in TWO_LEG_ROUTES],
        "bestRoutes": recommendations_json(results),
        "errors": error_rows,
    }


def render_aggregate(all_results: list[dict[tuple[str, str], SnapshotResult]]) -> None:
    print()
    print(f"Aggregate over {len(all_results)} public snapshots:")
    print()
    print("PAXG liquidity stability:")
    print()
    print("| Route | Spread bps min/median/max | Ask depth 10 bps min/median/max | Buy impact 1k min/median/max | Buy impact 5k min/median/max |")
    print("|---|---:|---:|---:|---:|")
    for exchange, pair in sorted(PAXG_AGGREGATE_PAIRS):
        samples = [results[(exchange, pair)] for results in all_results if (exchange, pair) in results]
        if not samples:
            continue
        spread = aggregate(sample.spread_bps for sample in samples)
        depth = aggregate(sample.ask_depth_10_bps for sample in samples)
        impact_1k = aggregate(sample.buy_impact_bps_by_size[Decimal("1000")] for sample in samples)
        impact_5k = aggregate(sample.buy_impact_bps_by_size[Decimal("5000")] for sample in samples)
        values = [
            f"{exchange} {pair}",
            " / ".join(fmt(value, 3) for value in spread),
            " / ".join(fmt(value, 0) for value in depth),
            " / ".join(fmt(value, 3) for value in impact_1k),
            " / ".join(fmt(value, 3) for value in impact_5k),
        ]
        print("| " + " | ".join(values) + " |")

    print()
    print("Single-leg total cost stability:")
    print()
    print("| Scenario | Route | Total 1k min/median/max | Total 5k min/median/max |")
    print("|---|---|---:|---:|")
    for scenario in FEE_SCENARIOS:
        costs_1k: list[Decimal | None] = []
        costs_5k: list[Decimal | None] = []
        for results in all_results:
            result = results.get((scenario.exchange, scenario.pair))
            if result is None:
                continue
            costs_1k.append(scenario_total_cost(result, scenario.taker_fee_bps, Decimal("1000")))
            costs_5k.append(scenario_total_cost(result, scenario.taker_fee_bps, Decimal("5000")))
        if not costs_1k and not costs_5k:
            continue
        total_1k = aggregate(costs_1k)
        total_5k = aggregate(costs_5k)
        values = [
            scenario.label,
            f"{scenario.exchange} {scenario.pair}",
            " / ".join(fmt(value, 3) for value in total_1k),
            " / ".join(fmt(value, 3) for value in total_5k),
        ]
        print("| " + " | ".join(values) + " |")

    print()
    print("Two-leg total cost stability:")
    print()
    print("| Scenario | Route | Total 1k min/median/max | Total 5k min/median/max |")
    print("|---|---|---:|---:|")
    for route in TWO_LEG_ROUTES:
        costs_1k = [two_leg_total_cost(route, results, Decimal("1000")) for results in all_results]
        costs_5k = [two_leg_total_cost(route, results, Decimal("5000")) for results in all_results]
        total_1k = aggregate(costs_1k)
        total_5k = aggregate(costs_5k)
        values = [
            route.label,
            f"{route.conversion_exchange} {route.conversion_pair} -> {route.target_exchange} {route.target_pair}",
            " / ".join(fmt(value, 3) for value in total_1k),
            " / ".join(fmt(value, 3) for value in total_5k),
        ]
        print("| " + " | ".join(values) + " |")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=1, help="number of snapshots to collect")
    parser.add_argument("--interval-seconds", type=float, default=15, help="delay between snapshots when samples > 1")
    parser.add_argument(
        "--sizes",
        default=",".join(str(size) for size in DEFAULT_SIZES),
        help="comma-separated quote notionals to simulate; 1000 and 5000 are always included for summary tables",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON instead of Markdown")
    return parser.parse_args()


def parse_sizes(raw_sizes: str) -> tuple[Decimal, ...]:
    parsed: set[Decimal] = set(DISPLAY_SIZES)
    for raw in raw_sizes.split(","):
        raw = raw.strip()
        if not raw:
            continue
        size = decimal(raw)
        if size <= 0:
            raise SystemExit("--sizes values must be > 0")
        parsed.add(size)
    return tuple(sorted(parsed))


def main() -> int:
    global SIZES
    args = parse_args()
    SIZES = parse_sizes(args.sizes)
    if args.samples < 1:
        raise SystemExit("--samples must be >= 1")
    if args.interval_seconds < 0:
        raise SystemExit("--interval-seconds must be >= 0")

    exit_code = 0
    all_results: list[dict[tuple[str, str], SnapshotResult]] = []
    json_samples: list[dict[str, object]] = []
    for index in range(args.samples):
        timestamp_utc = datetime.now(timezone.utc).isoformat(timespec="seconds")
        if not args.json:
            print(f"Snapshot UTC: {timestamp_utc}")
            if args.samples > 1:
                print(f"Sample {index + 1}/{args.samples}")
        sample_exit_code, results, error_rows = collect_snapshot_results()
        exit_code = max(exit_code, sample_exit_code)
        all_results.append(results)
        if args.json:
            json_samples.append(sample_json(timestamp_utc, results, error_rows))
        else:
            render_snapshot_results(results, error_rows)
        if index < args.samples - 1:
            time.sleep(args.interval_seconds)

    if args.json:
        print(json.dumps({"samples": json_samples}, indent=2, sort_keys=True))
    elif args.samples > 1:
        render_aggregate(all_results)

    if exit_code:
        print()
        print("At least one public endpoint failed; rerun before using the snapshot for execution decisions.", file=sys.stderr)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
