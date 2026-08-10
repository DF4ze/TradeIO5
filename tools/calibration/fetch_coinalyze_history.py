#!/usr/bin/env python3
"""
Récupère l'historique Open Interest + Funding Rate réel depuis l'API Coinalyze
(https://api.coinalyze.net/v1), pour un symbole TradeIO5 (ex. BTCUSDT), en résolvant
dynamiquement le code de marché Coinalyze comme le fait CoinalyzeSymbolResolver.java
(GET /exchanges puis GET /future-markets, filtre exchange "Binance" + symbolOnExchange) —
le suffixe exact (ex. "BTCUSDT_PERP.A") n'est jamais codé en dur ici, cf.
docs/calibration/prompt-calibration-movement-qualification.md.

Complément à movement_qualification_calibration.py : ce script ne fetch que les 2 séries
externes (OI, funding). L'OHLCV (pour priceChangePct + OBV local) est récupéré séparément
via fetch_real_klines.py (inchangé, déjà réutilisable tel quel).

Endpoints Coinalyze utilisés :
  - GET /exchanges                : résolution du code exchange (ex. "Binance" -> code court)
  - GET /future-markets            : résolution symbole TradeIO5 -> code marché Coinalyze
  - GET /open-interest-history     : historique OI, forme "candle" {t,o,h,l,c} par point
                                      (même query params que CoinalyzeClient.fetchOpenInterestHistory)
  - GET /funding-rate-history      : historique funding rate, mêmes query params -- PAS ENCORE
                                      wrappé côté Java aujourd'hui (CoinalyzeClient.java n'expose
                                      que fetchFundingRate, valeur ponctuelle) ; appelé ici en
                                      HTTP direct. Si cet endpoint n'existe pas / renvoie une
                                      erreur claire, le script l'affiche et s'arrête sur cette
                                      série plutôt que de deviner un contournement -- à documenter
                                      tel quel dans docs/calibration/calibration-movement-qualification.md.

Rate limit Coinalyze : 40 appels/minute par clé (cf. javadoc CoinalyzeClient.java, non géré côté
client Java aujourd'hui) -- ce script pagine en fenêtres calendaires fixes et espace les appels
(--sleep, défaut 1.6s ~ 37 appels/minute, marge de sécurité).

Forme de réponse non vérifiée en amont contre un appel réel (cf. avertissement dans
OpenInterestHistoryResponse.java : "à confirmer au premier appel réel") -- ce script valide la
forme effectivement reçue (clés présentes, non vide) et log un avertissement explicite si elle
diverge de la forme attendue, plutôt que de supposer silencieusement qu'elle correspond.

Usage:
    python3 fetch_coinalyze_history.py --symbol BTCUSDT --start 2024-02-01 --end 2026-07-16 \
        --api-key <clé> --out-oi oi_btc.csv --out-funding funding_btc.csv
"""

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE_URL = "https://api.coinalyze.net/v1"
DEFAULT_EXCHANGE_HINT = "Binance"
WINDOW_DAYS = 30  # fenêtre calendaire par appel -- conservateur, ajusté si l'API tronque en dessous
INTERVAL_SECONDS = {"1hour": 3600, "1day": 86400}


def http_get(path, params, api_key, timeout=20):
    query = "&".join(f"{k}={v}" for k, v in params.items())
    url = f"{BASE_URL}{path}?{query}&api_key={api_key}"
    req = urllib.request.Request(url, headers={"User-Agent": "tradeio5-calibration/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return e.code, body
    except urllib.error.URLError as e:
        return None, str(e)


# --------------------------------------------------------------------------------------
# Résolution dynamique du symbole (miroir de CoinalyzeSymbolResolver.findCoinalyzeSymbol)
# --------------------------------------------------------------------------------------

def resolve_symbol(tradeio5_symbol, api_key, exchange_hint=DEFAULT_EXCHANGE_HINT):
    status, exchanges = http_get("/exchanges", {}, api_key)
    if status != 200 or not isinstance(exchanges, list):
        raise RuntimeError(f"GET /exchanges a échoué (status={status}): {exchanges}")

    status, markets = http_get("/future-markets", {}, api_key)
    if status != 200 or not isinstance(markets, list):
        raise RuntimeError(f"GET /future-markets a échoué (status={status}): {markets}")

    exchange_code = None
    for ex in exchanges:
        name = ex.get("name") or ""
        if exchange_hint.lower() in name.lower():
            exchange_code = ex.get("code")
            break
    if exchange_code is None:
        raise RuntimeError(f"Aucun exchange trouvé pour hint={exchange_hint!r} parmi {exchanges}")

    matches = [
        m for m in markets
        if m.get("exchange") == exchange_code
        and (m.get("symbol_on_exchange") or "").upper() == tradeio5_symbol.upper()
    ]
    if not matches:
        raise RuntimeError(
            f"Aucun marché future trouvé pour {tradeio5_symbol} sur exchange_code={exchange_code}"
        )
    if len(matches) > 1:
        print(
            f"  [WARN] {len(matches)} marchés correspondent à {tradeio5_symbol} sur {exchange_hint} "
            f"(comme CoinalyzeSymbolResolver.java, on prend le premier -- findFirst()) : "
            f"{[m.get('symbol') for m in matches]}",
            file=sys.stderr,
        )
    resolved = matches[0]["symbol"]
    print(f"  Résolu : {tradeio5_symbol} -> {resolved} (exchange_code={exchange_code})")
    return resolved


# --------------------------------------------------------------------------------------
# Fetch paginé d'un endpoint -history
# --------------------------------------------------------------------------------------

def to_epoch(date_str):
    return int(datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp())


def fetch_history(endpoint, coinalyze_symbol, start_epoch, end_epoch, api_key, interval="1hour",
                   sleep_s=1.6, window_days=WINDOW_DAYS):
    """Pagine par fenêtres calendaires fixes ; si une réponse ne couvre pas toute la fenêtre
    demandée (troncature côté API), le curseur avance seulement jusqu'au dernier point
    effectivement reçu (pas la fin de fenêtre demandée) pour ne pas sauter de données."""
    interval_s = INTERVAL_SECONDS.get(interval, 3600)
    window_s = window_days * 86400

    all_points = []
    cursor = start_epoch
    call_count = 0
    first_response_logged = False

    while cursor <= end_epoch:
        chunk_end = min(cursor + window_s, end_epoch)
        status, data = http_get(
            endpoint,
            {"symbols": coinalyze_symbol, "interval": interval, "from": cursor, "to": chunk_end},
            api_key,
        )
        call_count += 1

        if status != 200:
            raise RuntimeError(
                f"{endpoint} a échoué (status={status}) pour {coinalyze_symbol} "
                f"[{cursor}-{chunk_end}]: {data}"
            )

        if not first_response_logged:
            print(f"  [DEBUG] forme de la 1ère réponse {endpoint}: "
                  f"{json.dumps(data)[:500]}")
            first_response_logged = True

        if not isinstance(data, list) or not data:
            print(f"  [WARN] {endpoint} : réponse vide/inattendue pour "
                  f"[{datetime.fromtimestamp(cursor, tz=timezone.utc).date()} -> "
                  f"{datetime.fromtimestamp(chunk_end, tz=timezone.utc).date()}], "
                  f"on avance d'une fenêtre pleine.")
            cursor = chunk_end + interval_s
            time.sleep(sleep_s)
            continue

        entry = next((e for e in data if e.get("symbol") == coinalyze_symbol), data[0])
        history = entry.get("history") or []
        if not history:
            cursor = chunk_end + interval_s
            time.sleep(sleep_s)
            continue

        history_sorted = sorted(history, key=lambda p: p["t"])
        all_points.extend(history_sorted)
        last_t = history_sorted[-1]["t"]

        print(f"  fetched {len(history_sorted)} points up to "
              f"{datetime.fromtimestamp(last_t, tz=timezone.utc)} "
              f"({len(all_points)} points au total, {call_count} appels)")

        cursor = last_t + interval_s
        time.sleep(sleep_s)

    # dédoublonnage par timestamp (chevauchement de fenêtres possible en bord de plage)
    dedup = {}
    for p in all_points:
        dedup[p["t"]] = p
    return sorted(dedup.values(), key=lambda p: p["t"])


def write_csv(points, out_path):
    with open(out_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["timestamp", "value"])
        for p in points:
            ts_iso = datetime.fromtimestamp(p["t"], tz=timezone.utc).isoformat()
            writer.writerow([ts_iso, p["c"]])
    print(f"  Ecrit: {out_path} ({len(points)} points)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", required=True, help="Symbole TradeIO5, ex: BTCUSDT")
    parser.add_argument("--start", required=True, help="yyyy-MM-dd (UTC)")
    parser.add_argument("--end", required=True, help="yyyy-MM-dd (UTC)")
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--out-oi", required=True)
    parser.add_argument("--out-funding", required=True)
    parser.add_argument("--interval", default="1hour")
    parser.add_argument("--sleep", type=float, default=1.6)
    parser.add_argument("--window-days", type=int, default=WINDOW_DAYS)
    parser.add_argument("--skip-funding", action="store_true",
                         help="Sauter /funding-rate-history si l'endpoint s'avère indisponible "
                              "(pour ne pas re-belter l'OI déjà récupéré en le testant).")
    args = parser.parse_args()

    start_epoch = to_epoch(args.start)
    end_epoch = to_epoch(args.end)

    print(f"=== Résolution du symbole Coinalyze pour {args.symbol} ===")
    coinalyze_symbol = resolve_symbol(args.symbol, args.api_key)

    print(f"\n=== Open Interest history ({args.start} -> {args.end}, interval={args.interval}) ===")
    oi_points = fetch_history(
        "/open-interest-history", coinalyze_symbol, start_epoch, end_epoch, args.api_key,
        interval=args.interval, sleep_s=args.sleep, window_days=args.window_days,
    )
    write_csv(oi_points, args.out_oi)
    if oi_points:
        print(f"  Profondeur réelle OI: {datetime.fromtimestamp(oi_points[0]['t'], tz=timezone.utc).date()} "
              f"-> {datetime.fromtimestamp(oi_points[-1]['t'], tz=timezone.utc).date()}")

    if args.skip_funding:
        print("\n=== Funding rate history: SKIPPED (--skip-funding) ===")
        return

    print(f"\n=== Funding rate history ({args.start} -> {args.end}, interval={args.interval}) ===")
    try:
        funding_points = fetch_history(
            "/funding-rate-history", coinalyze_symbol, start_epoch, end_epoch, args.api_key,
            interval=args.interval, sleep_s=args.sleep, window_days=args.window_days,
        )
        write_csv(funding_points, args.out_funding)
        if funding_points:
            print(f"  Profondeur réelle funding: "
                  f"{datetime.fromtimestamp(funding_points[0]['t'], tz=timezone.utc).date()} -> "
                  f"{datetime.fromtimestamp(funding_points[-1]['t'], tz=timezone.utc).date()}")
    except RuntimeError as e:
        print(f"  [ERREUR] /funding-rate-history indisponible: {e}", file=sys.stderr)
        print("  -> A documenter tel quel dans docs/calibration/calibration-movement-qualification.md, "
              "pas de contournement forcé.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
