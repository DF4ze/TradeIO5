#!/usr/bin/env python3
"""Snapshot public des pistes wrapped/on-chain pour BTC, ETH et PAXG.

Cet outil reste volontairement read-only : il interroge DexScreener pour la
liquidite observable et Jupiter pour des quotes Solana quand l'API publique
renvoie un devis exploitable.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, asdict
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import Any


USER_AGENT = "TradeIO5 research/0.1"

USDC_SOLANA = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
PAXG_SOLANA = "5GgRAEmv8ZxF2PR5hY72Qs5x1bnQ6UK2RbTPoqJ3wSwW"
CBBTC_SOLANA = "cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij"


DEXSCREENER_TARGETS = [
    {
        "label": "cbBTC/USDC Base Aerodrome",
        "kind": "pair",
        "chain": "base",
        "address": "0x160D7E9d948B16c163332a277b393c288408eb12",
        "route": "USDC -> cbBTC on Base",
        "risk": "Coinbase wrapped BTC issuer/custody + Base smart-contract risk.",
    },
    {
        "label": "cbBTC/USDC Base Uniswap",
        "kind": "pair",
        "chain": "base",
        "address": "0xfBB6Eed8e7aa03B138556eeDaF5D271A5E1e43ef",
        "route": "USDC -> cbBTC on Base",
        "risk": "Coinbase wrapped BTC issuer/custody + Base smart-contract risk.",
    },
    {
        "label": "cbBTC/USDC Base Aerodrome Slipstream",
        "kind": "pair",
        "chain": "base",
        "address": "0x4e962BB3889Bf030368F56810A9c96B83CB3E778",
        "route": "USDC -> cbBTC on Base",
        "risk": "Coinbase wrapped BTC issuer/custody + Base smart-contract risk.",
    },
    {
        "label": "cbBTC/USDC Solana Orca",
        "kind": "pair",
        "chain": "solana",
        "address": "HxA6SKW5qA4o12fjVgTpXdq2YnZ5Zv1s7SB4FFomsyLM",
        "route": "USDC -> cbBTC on Solana",
        "risk": "Coinbase wrapped BTC issuer/custody + Solana program/network risk.",
    },
    {
        "label": "WETH/USDC Base token scan",
        "kind": "token",
        "chain": "base",
        "address": "0x4200000000000000000000000000000000000006",
        "route": "USDC -> WETH/ETH exposure on Base",
        "risk": "L2 custody/bridge and smart-contract risk; native ETH withdrawal still needs a supported path.",
        "base_symbols": {"WETH"},
        "quote_symbols": {"USDC", "USDbC"},
    },
    {
        "label": "PAXG/USDC Solana token scan",
        "kind": "token",
        "chain": "solana",
        "address": PAXG_SOLANA,
        "route": "USDC -> official PAXG on Solana",
        "risk": "Paxos issuer/gold custody + Solana token/program risk; bridge/redeem path must be validated.",
        "base_symbols": {"PAXG"},
        "quote_symbols": {"USDC"},
    },
]


JUPITER_QUOTES = [
    {
        "label": "USDC -> PAXG Solana via Jupiter",
        "input_mint": USDC_SOLANA,
        "output_mint": PAXG_SOLANA,
        "input_decimals": 6,
        "output_decimals": 6,
    },
    {
        "label": "USDC -> cbBTC Solana via Jupiter",
        "input_mint": USDC_SOLANA,
        "output_mint": CBBTC_SOLANA,
        "input_decimals": 6,
        "output_decimals": 8,
    },
]


@dataclass
class DexPair:
    label: str
    route: str
    chain: str
    dex: str
    base: str
    quote: str
    liquidity_usd: Decimal | None
    volume_24h_usd: Decimal | None
    price_usd: Decimal | None
    pair_address: str
    url: str
    risk: str


@dataclass
class JupiterQuote:
    label: str
    input_usdc: Decimal
    out_amount: Decimal | None
    price_impact_pct: Decimal | None
    route: str
    swap_usd_value: Decimal | None
    error: str | None = None


def http_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def dec(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def fetch_pair_target(target: dict[str, Any]) -> list[DexPair]:
    if target["kind"] == "pair":
        url = f"https://api.dexscreener.com/latest/dex/pairs/{target['chain']}/{target['address']}"
        payload = http_json(url)
        pair = payload.get("pair")
        return [pair_to_record(target, pair)] if pair else []

    url = f"https://api.dexscreener.com/tokens/v1/{target['chain']}/{target['address']}"
    payload = http_json(url)
    base_symbols = target.get("base_symbols")
    quote_symbols = target.get("quote_symbols")
    records: list[DexPair] = []
    for pair in payload if isinstance(payload, list) else []:
        base = pair.get("baseToken", {}).get("symbol", "")
        quote = pair.get("quoteToken", {}).get("symbol", "")
        if base_symbols and base not in base_symbols:
            continue
        if quote_symbols and quote not in quote_symbols:
            continue
        records.append(pair_to_record(target, pair))
    records.sort(key=lambda item: item.liquidity_usd or Decimal("-1"), reverse=True)
    return records[:3]


def pair_to_record(target: dict[str, Any], pair: dict[str, Any]) -> DexPair:
    return DexPair(
        label=target["label"],
        route=target["route"],
        chain=pair.get("chainId", ""),
        dex=pair.get("dexId", ""),
        base=pair.get("baseToken", {}).get("symbol", ""),
        quote=pair.get("quoteToken", {}).get("symbol", ""),
        liquidity_usd=dec(pair.get("liquidity", {}).get("usd")),
        volume_24h_usd=dec(pair.get("volume", {}).get("h24")),
        price_usd=dec(pair.get("priceUsd")),
        pair_address=pair.get("pairAddress", ""),
        url=pair.get("url", ""),
        risk=target["risk"],
    )


def fetch_jupiter_quote(target: dict[str, Any], size_usdc: Decimal) -> JupiterQuote:
    amount = int(size_usdc * (Decimal(10) ** target["input_decimals"]))
    query = urllib.parse.urlencode(
        {
            "inputMint": target["input_mint"],
            "outputMint": target["output_mint"],
            "amount": str(amount),
            "slippageBps": "50",
        }
    )
    url = f"https://api.jup.ag/swap/v1/quote?{query}"
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            payload = http_json(url)
            break
        except Exception as exc:  # noqa: BLE001 - research script reports API failures.
            last_error = exc
            time.sleep(0.75 * (attempt + 1))
    else:
        return JupiterQuote(target["label"], size_usdc, None, None, "", None, str(last_error))

    out_amount = dec(payload.get("outAmount"))
    if out_amount is not None:
        out_amount = out_amount / (Decimal(10) ** target["output_decimals"])
    route = " -> ".join(
        step.get("swapInfo", {}).get("label", "")
        for step in payload.get("routePlan", [])
        if step.get("swapInfo")
    )
    return JupiterQuote(
        label=target["label"],
        input_usdc=size_usdc,
        out_amount=out_amount,
        price_impact_pct=dec(payload.get("priceImpactPct")),
        route=route,
        swap_usd_value=dec(payload.get("swapUsdValue")),
    )


def fmt(value: Decimal | None, places: int = 2) -> str:
    if value is None:
        return "N/A"
    quant = Decimal(10) ** -places
    return f"{value.quantize(quant):,}".replace(",", " ")


def print_markdown(pairs: list[DexPair], quotes: list[JupiterQuote], generated_at: str) -> None:
    print(f"# Wrapped/on-chain liquidity snapshot ({generated_at})")
    print()
    print("## Dex liquidity")
    print()
    print("| Label | Chain | DEX | Pair | Liquidity USD | Volume 24h USD | Route | Risk note |")
    print("|---|---|---|---|---:|---:|---|---|")
    for item in pairs:
        pair = f"{item.base}/{item.quote}"
        print(
            "| "
            + " | ".join(
                [
                    item.label,
                    item.chain,
                    item.dex,
                    pair,
                    fmt(item.liquidity_usd),
                    fmt(item.volume_24h_usd),
                    item.route,
                    item.risk,
                ]
            )
            + " |"
        )

    print()
    print("## Jupiter Solana quotes")
    print()
    print("| Route | Input USDC | Output asset amount | Jupiter priceImpactPct | AMM route | Swap USD value |")
    print("|---|---:|---:|---:|---|---:|")
    for item in quotes:
        if item.error:
            print(f"| {item.label} | {fmt(item.input_usdc)} | ERROR | ERROR | {item.error} | ERROR |")
            continue
        print(
            "| "
            + " | ".join(
                [
                    item.label,
                    fmt(item.input_usdc),
                    fmt(item.out_amount, 8),
                    str(item.price_impact_pct) if item.price_impact_pct is not None else "N/A",
                    item.route or "N/A",
                    fmt(item.swap_usd_value, 6),
                ]
            )
            + " |"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sizes", default="50,100,500,1000,5000")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    generated_at = datetime.now(UTC).replace(microsecond=0).isoformat()
    sizes = [Decimal(raw.strip()) for raw in args.sizes.split(",") if raw.strip()]

    pairs: list[DexPair] = []
    for target in DEXSCREENER_TARGETS:
        try:
            pairs.extend(fetch_pair_target(target))
        except Exception as exc:  # noqa: BLE001 - research script reports API failures.
            print(f"warning: failed to fetch {target['label']}: {exc}", file=sys.stderr)

    quotes: list[JupiterQuote] = []
    for target in JUPITER_QUOTES:
        for size in sizes:
            quotes.append(fetch_jupiter_quote(target, size))
            time.sleep(0.8)

    if args.json:
        print(
            json.dumps(
                {
                    "generatedAt": generated_at,
                    "dexPairs": [asdict(item) for item in pairs],
                    "jupiterQuotes": [asdict(item) for item in quotes],
                },
                default=str,
                indent=2,
            )
        )
    else:
        print_markdown(pairs, quotes, generated_at)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
