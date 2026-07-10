#!/usr/bin/env python3
"""
Récupère un historique H1 réel depuis l'API publique Binance (klines, pas d'authentification
nécessaire) et l'écrit au format CSV attendu par rejection_zone_calibration.py
(timestamp,open,high,low,close,volume).

Complément à docs/calibration-rejection-zone.md : la première exécution du protocole avait tourné
sur des données synthétiques faute d'accès réseau sortant vers un exchange depuis l'environnement
de build. Ce script est prévu pour être exécuté depuis une machine avec un accès réseau normal
(pas l'environnement de build) — voir le run réel documenté dans le même fichier.

Usage:
    python fetch_real_klines.py --symbol BTCUSDT --start 2024-02-01 --end 2026-07-09 --out out.csv
"""

import argparse
import csv
import json
import time
import urllib.request
from datetime import datetime, timezone

BINANCE_KLINES_URL = "https://api.binance.com/api/v3/klines"
CHUNK_LIMIT = 1000  # max klines par appel Binance

# Correctif 2026-07-09 : la version precedente calculait step_ms/l'increment du curseur en dur
# pour du H1 (3600_000 ms), meme quand --interval valait "4h" ou "1d" -> chaque appel ne couvrait
# alors qu'une fenetre de temps dimensionnee pour du H1 (999h), donc ~24x trop courte pour du H1
# reel en D1/H4, un facteur ~24-96x plus d'appels que necessaire. Table de conversion pour calculer
# la bonne fenetre par requete quel que soit l'intervalle demande.
INTERVAL_MS = {
    "1m": 60_000, "5m": 300_000, "15m": 900_000, "30m": 1_800_000,
    "1h": 3_600_000, "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
    "12h": 43_200_000, "1d": 86_400_000, "1w": 604_800_000,
}


def to_millis(date_str):
    dt = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def fetch_chunk(symbol, interval, start_ms, end_ms, limit):
    params = f"symbol={symbol}&interval={interval}&startTime={start_ms}&endTime={end_ms}&limit={limit}"
    url = f"{BINANCE_KLINES_URL}?{params}"
    req = urllib.request.Request(url, headers={"User-Agent": "tradeio5-calibration/1.0"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_all(symbol, interval, start_ms, end_ms, sleep_s=0.25):
    all_rows = []
    cursor = start_ms
    interval_ms = INTERVAL_MS.get(interval, 3_600_000)
    step_ms = interval_ms * (CHUNK_LIMIT - 1)

    while cursor <= end_ms:
        chunk_end = min(cursor + step_ms, end_ms)
        rows = fetch_chunk(symbol, interval, cursor, chunk_end, CHUNK_LIMIT)
        if not rows:
            cursor = chunk_end + interval_ms
            continue
        all_rows.extend(rows)
        last_open_time = rows[-1][0]
        cursor = last_open_time + interval_ms
        print(f"  fetched up to {datetime.fromtimestamp(last_open_time / 1000, tz=timezone.utc)} "
              f"({len(all_rows)} bougies au total)")
        time.sleep(sleep_s)  # rate-limit poli

    return all_rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--start", required=True, help="yyyy-MM-dd (UTC)")
    parser.add_argument("--end", required=True, help="yyyy-MM-dd (UTC)")
    parser.add_argument("--interval", default="1h")
    parser.add_argument("--out", required=True)
    parser.add_argument("--sleep", type=float, default=0.25, help="Pause entre appels (secondes), poli envers l'API publique")
    args = parser.parse_args()

    start_ms = to_millis(args.start)
    end_ms = to_millis(args.end)

    print(f"Fetching {args.symbol} {args.interval} from {args.start} to {args.end} (Binance public klines)...")
    rows = fetch_all(args.symbol, args.interval, start_ms, end_ms, sleep_s=args.sleep)
    print(f"Total: {len(rows)} bougies.")

    with open(args.out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["timestamp", "open", "high", "low", "close", "volume"])
        for r in rows:
            # kline format Binance: [openTime, open, high, low, close, volume, closeTime, ...]
            open_time_iso = datetime.fromtimestamp(r[0] / 1000, tz=timezone.utc).isoformat()
            writer.writerow([open_time_iso, r[1], r[2], r[3], r[4], r[5]])

    print(f"Ecrit: {args.out}")


if __name__ == "__main__":
    main()
