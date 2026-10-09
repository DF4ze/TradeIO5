#!/usr/bin/env python3
"""
Inventory public spot pairs for BTC/ETH/PAXG across candidate exchanges.

The goal is not to measure liquidity or fees. It answers a simpler but critical
question for the fee study: which exchanges currently list all three target
assets, and against which stablecoin/fiat quotes?

Use --json to emit machine-readable inventory data for downstream tooling.
"""

from __future__ import annotations

import json
import argparse
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable


TARGET_ASSETS = ("BTC", "ETH", "PAXG")
STABLE_QUOTES = ("USDT", "USDC", "FDUSD", "TUSD", "DAI", "USDS", "USDE", "PYUSD", "USDG", "RLUSD", "GUSD", "EURC")
FIAT_QUOTES = ("USD", "EUR", "CHF", "GBP", "TRY")
RELEVANT_QUOTES = set(STABLE_QUOTES) | set(FIAT_QUOTES) | {"BTC", "ETH"}
TIMEOUT_SECONDS = 20


@dataclass(frozen=True)
class Pair:
    exchange: str
    base: str
    quote: str
    symbol: str
    status: str


@dataclass(frozen=True)
class ExchangeSource:
    name: str
    url: str
    parser: Callable[[object], list[Pair]]


def http_json(url: str) -> object:
    request = urllib.request.Request(
        url,
        headers={
            "accept": "application/json",
            "user-agent": "TradeIO5 exchange pair inventory/1.0",
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


def normalize_asset(asset: object) -> str:
    value = str(asset).upper()
    aliases = {
        "XBT": "BTC",
        "XXBT": "BTC",
        "XETH": "ETH",
        "ZUSD": "USD",
        "ZEUR": "EUR",
        "ZGBP": "GBP",
        "ZCHF": "CHF",
    }
    return aliases.get(value, value)


def active(status: object) -> bool:
    return str(status).lower() in {"trading", "tradable", "online", "live", "enabled", "1", "true"}


def only_relevant(pairs: list[Pair]) -> list[Pair]:
    return [
        pair
        for pair in pairs
        if pair.base in TARGET_ASSETS and pair.quote in RELEVANT_QUOTES and active(pair.status)
    ]


def parse_binance(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    rows = []
    for item in payload.get("symbols", []):
        rows.append(
            Pair(
                "Binance",
                normalize_asset(item.get("baseAsset")),
                normalize_asset(item.get("quoteAsset")),
                str(item.get("symbol", "")),
                str(item.get("status", "")),
            )
        )
    return only_relevant(rows)


def parse_okx(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    rows = []
    for item in payload.get("data", []):
        rows.append(
            Pair(
                "OKX",
                normalize_asset(item.get("baseCcy")),
                normalize_asset(item.get("quoteCcy")),
                str(item.get("instId", "")),
                str(item.get("state", "")),
            )
        )
    return only_relevant(rows)


def parse_kucoin(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    rows = []
    for item in payload.get("data", []):
        rows.append(
            Pair(
                "KuCoin",
                normalize_asset(item.get("baseCurrency")),
                normalize_asset(item.get("quoteCurrency")),
                str(item.get("symbol", "")),
                "enabled" if item.get("enableTrading") else "disabled",
            )
        )
    return only_relevant(rows)


def parse_gate(data: object) -> list[Pair]:
    items = data if isinstance(data, list) else []
    rows = []
    for item in items:
        rows.append(
            Pair(
                "Gate.io",
                normalize_asset(item.get("base")),
                normalize_asset(item.get("quote")),
                str(item.get("id", "")),
                str(item.get("trade_status", "")),
            )
        )
    return only_relevant(rows)


def parse_bitget(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    rows = []
    for item in payload.get("data", []):
        rows.append(
            Pair(
                "Bitget",
                normalize_asset(item.get("baseCoin")),
                normalize_asset(item.get("quoteCoin")),
                str(item.get("symbol", "")),
                str(item.get("status", "")),
            )
        )
    return only_relevant(rows)


def parse_bybit(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    result = payload.get("result") or {}
    rows = []
    for item in result.get("list", []):
        rows.append(
            Pair(
                "Bybit",
                normalize_asset(item.get("baseCoin")),
                normalize_asset(item.get("quoteCoin")),
                str(item.get("symbol", "")),
                str(item.get("status", "")),
            )
        )
    return only_relevant(rows)


def parse_mexc(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    rows = []
    for item in payload.get("symbols", []):
        rows.append(
            Pair(
                "MEXC",
                normalize_asset(item.get("baseAsset")),
                normalize_asset(item.get("quoteAsset")),
                str(item.get("symbol", "")),
                str(item.get("status", "")),
            )
        )
    return only_relevant(rows)


def parse_crypto_com(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    result = payload.get("result") or {}
    rows = []
    for item in result.get("data", []):
        if item.get("inst_type") != "CCY_PAIR":
            continue
        rows.append(
            Pair(
                "Crypto.com",
                normalize_asset(item.get("base_ccy")),
                normalize_asset(item.get("quote_ccy")),
                str(item.get("symbol", "")),
                "true" if item.get("tradable") else "false",
            )
        )
    return only_relevant(rows)


def parse_coinbase(data: object) -> list[Pair]:
    items = data if isinstance(data, list) else []
    rows = []
    for item in items:
        disabled = item.get("trading_disabled") or item.get("cancel_only") or item.get("post_only")
        rows.append(
            Pair(
                "Coinbase Exchange",
                normalize_asset(item.get("base_currency")),
                normalize_asset(item.get("quote_currency")),
                str(item.get("id", "")),
                "online" if item.get("status") == "online" and not disabled else "disabled",
            )
        )
    return only_relevant(rows)


def parse_kraken(data: object) -> list[Pair]:
    payload = data if isinstance(data, dict) else {}
    result = payload.get("result") or {}
    rows = []
    for item in result.values():
        wsname = str(item.get("wsname", ""))
        if "/" in wsname:
            base, quote = wsname.split("/", 1)
        else:
            base, quote = item.get("base"), item.get("quote")
        rows.append(
            Pair(
                "Kraken",
                normalize_asset(base),
                normalize_asset(quote),
                str(item.get("altname", "")),
                str(item.get("status", "")),
            )
        )
    return only_relevant(rows)


def parse_gemini(data: object) -> list[Pair]:
    items = data if isinstance(data, list) else []
    rows = []
    sorted_quotes = sorted(RELEVANT_QUOTES, key=len, reverse=True)
    for raw_symbol in items:
        symbol = str(raw_symbol).upper()
        base = ""
        quote = ""
        for candidate_base in TARGET_ASSETS:
            if not symbol.startswith(candidate_base):
                continue
            suffix = symbol[len(candidate_base) :]
            for candidate_quote in sorted_quotes:
                if suffix == candidate_quote:
                    base = candidate_base
                    quote = candidate_quote
                    break
            if quote:
                break
        if base and quote:
            rows.append(Pair("Gemini", base, quote, symbol, "online"))
    return only_relevant(rows)


def parse_bitstamp(data: object) -> list[Pair]:
    items = data if isinstance(data, list) else []
    rows = []
    for item in items:
        name = str(item.get("name", ""))
        if "/" in name:
            base, quote = name.split("/", 1)
        else:
            continue
        rows.append(
            Pair(
                "Bitstamp",
                normalize_asset(base),
                normalize_asset(quote),
                str(item.get("url_symbol", "")),
                str(item.get("trading", "")),
            )
        )
    return only_relevant(rows)


SOURCES = [
    ExchangeSource("Binance", "https://api.binance.com/api/v3/exchangeInfo", parse_binance),
    ExchangeSource("OKX", "https://www.okx.com/api/v5/public/instruments?instType=SPOT", parse_okx),
    ExchangeSource("KuCoin", "https://api.kucoin.com/api/v2/symbols", parse_kucoin),
    ExchangeSource("Gate.io", "https://api.gateio.ws/api/v4/spot/currency_pairs", parse_gate),
    ExchangeSource("Bitget", "https://api.bitget.com/api/v2/spot/public/symbols", parse_bitget),
    ExchangeSource("Bybit", "https://api.bybit.com/v5/market/instruments-info?category=spot", parse_bybit),
    ExchangeSource("MEXC", "https://api.mexc.com/api/v3/exchangeInfo", parse_mexc),
    ExchangeSource("Crypto.com", "https://api.crypto.com/exchange/v1/public/get-instruments", parse_crypto_com),
    ExchangeSource("Coinbase Exchange", "https://api.exchange.coinbase.com/products", parse_coinbase),
    ExchangeSource("Kraken", "https://api.kraken.com/0/public/AssetPairs", parse_kraken),
    ExchangeSource("Gemini", "https://api.gemini.com/v1/symbols", parse_gemini),
    ExchangeSource("Bitstamp", "https://www.bitstamp.net/api/v2/trading-pairs-info/", parse_bitstamp),
]


def collect() -> tuple[dict[str, list[Pair]], list[str]]:
    inventory: dict[str, list[Pair]] = {}
    errors: list[str] = []
    for source in SOURCES:
        try:
            data = http_json(source.url)
            inventory[source.name] = source.parser(data)
        except Exception as exc:
            inventory[source.name] = []
            errors.append(f"{source.name}: {type(exc).__name__}")
    return inventory, errors


def quotes_for(pairs: list[Pair], base: str, include_fiat: bool = True) -> list[str]:
    allowed = set(STABLE_QUOTES) | (set(FIAT_QUOTES) if include_fiat else set())
    return sorted({pair.quote for pair in pairs if pair.base == base and pair.quote in allowed})


def symbols_for(pairs: list[Pair], base: str) -> list[str]:
    return sorted(pair.symbol for pair in pairs if pair.base == base)


def fmt_list(values: list[str]) -> str:
    return ", ".join(values) if values else "-"


def exchange_inventory_json(exchange: str, pairs: list[Pair]) -> dict[str, object]:
    btc_quotes = quotes_for(pairs, "BTC")
    eth_quotes = quotes_for(pairs, "ETH")
    paxg_quotes = quotes_for(pairs, "PAXG")
    common_stables = sorted(set(btc_quotes) & set(eth_quotes) & set(paxg_quotes) & set(STABLE_QUOTES))
    return {
        "exchange": exchange,
        "btcQuotes": btc_quotes,
        "ethQuotes": eth_quotes,
        "paxgQuotes": paxg_quotes,
        "stableQuotesCommonToAllTargets": common_stables,
        "paxgSymbols": symbols_for(pairs, "PAXG"),
        "pairs": [
            {
                "base": pair.base,
                "quote": pair.quote,
                "symbol": pair.symbol,
                "status": pair.status,
            }
            for pair in pairs
        ],
    }


def render(inventory: dict[str, list[Pair]]) -> None:
    print("| Exchange | BTC quotes | ETH quotes | PAXG quotes | Stable quote common to all 3 | PAXG symbols |")
    print("|---|---|---|---|---|---|")
    for source in SOURCES:
        pairs = inventory[source.name]
        btc_quotes = quotes_for(pairs, "BTC")
        eth_quotes = quotes_for(pairs, "ETH")
        paxg_quotes = quotes_for(pairs, "PAXG")
        common_stables = sorted(set(btc_quotes) & set(eth_quotes) & set(paxg_quotes) & set(STABLE_QUOTES))
        values = [
            source.name,
            fmt_list(btc_quotes),
            fmt_list(eth_quotes),
            fmt_list(paxg_quotes),
            fmt_list(common_stables),
            fmt_list(symbols_for(pairs, "PAXG")),
        ]
        print("| " + " | ".join(values) + " |")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON instead of Markdown")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inventory, errors = collect()
    timestamp_utc = datetime.now(timezone.utc).isoformat(timespec="seconds")
    if args.json:
        print(
            json.dumps(
                {
                    "snapshotUtc": timestamp_utc,
                    "inventory": [
                        exchange_inventory_json(source.name, inventory[source.name])
                        for source in SOURCES
                    ],
                    "errors": errors,
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 1 if errors else 0

    print(f"Snapshot UTC: {timestamp_utc}")
    print()
    render(inventory)
    if errors:
        print()
        print("Errors:")
        for error in errors:
            print(f"- {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
