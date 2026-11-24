#!/usr/bin/env python3
"""Etude D1a : bougies D1 Binance en USDT, usage bench / etude UNIQUEMENT (jamais l'execution, jamais le live).

Stockage dedie (hors DB, hors tables de bougies lues par le live) : tools/rainbow-replay/data-bench/
  d1_<ACTIF>.csv   : ts_ms,open,high,low,close,volume (jours UTC clos uniquement)
  manifest.json    : etiquette source (BINANCE/USDT), symbole, nb de bougies, premier / dernier jour, sha256 du CSV

Pagination douce : pages de 1000, pause entre pages, aucune requete en parallele, arret sur page courte.
Usage : python fetch_binance_d1.py <dossier_sortie> [ACTIF ...]
"""
import hashlib
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone

URL = "https://api.binance.com/api/v3/klines?symbol={sym}&interval=1d&limit=1000&startTime={start}"
ASSETS = ["BNB", "SOL", "XRP", "ADA", "LINK", "LTC", "AVAX", "DOT", "DOGE"]
QUOTE = "USDT"
LABEL = "BINANCE/USDT"
PAUSE_S = 1.0
START_MS = int(datetime(2017, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
DAY_MS = 86_400_000


def fetch(asset):
    sym = asset + QUOTE
    now_ms = int(time.time() * 1000)
    rows, start = [], START_MS
    while True:
        with urllib.request.urlopen(URL.format(sym=sym, start=start), timeout=30) as r:
            page = json.loads(r.read())
        rows += page
        if len(page) < 1000:
            break
        start = page[-1][0] + DAY_MS
        time.sleep(PAUSE_S)
    seen, out = set(), []
    for k in rows:
        ts = int(k[0])
        if ts in seen or int(k[6]) >= now_ms:  # doublon ou bougie du jour non close
            continue
        seen.add(ts)
        out.append((ts, k[1], k[2], k[3], k[4], k[5]))
    out.sort()
    return sym, out


def main():
    outdir = sys.argv[1]
    assets = sys.argv[2:] or ASSETS
    manifest = []
    for a in assets:
        sym, rows = fetch(a)
        text = "".join(",".join(map(str, r)) + "\n" for r in rows)
        with open(f"{outdir}/d1_{a}.csv", "w", newline="\n") as f:
            f.write(text)
        day = lambda ts: datetime.fromtimestamp(ts / 1000, timezone.utc).strftime("%Y-%m-%d")
        manifest.append({"asset": a, "symbol": sym, "quote": QUOTE, "source": LABEL, "rows": len(rows),
                         "firstDay": day(rows[0][0]), "lastDay": day(rows[-1][0]),
                         "csvSha256": hashlib.sha256(text.encode()).hexdigest()})
        print(a, sym, len(rows), manifest[-1]["firstDay"], manifest[-1]["lastDay"], flush=True)
        time.sleep(PAUSE_S)
    with open(f"{outdir}/manifest.json", "w", newline="\n") as f:
        json.dump({"usage": "bench / etude uniquement : jamais l'execution ni le live", "assets": manifest}, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
