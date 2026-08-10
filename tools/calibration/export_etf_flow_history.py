#!/usr/bin/env python3
"""
Exporte l'historique ETF_FLOW (table `etf_flow_snapshot`, remplie par le backfill du 2026-07-17,
cf. docs/etudes/etude-cache-etf-flow-historisation.md) vers un CSV par asset, pour
tools/calibration/etf_flow_calibration.py.

Contrairement aux calibrations précédentes (REJECTION_ZONE, MOVEMENT_QUALIFICATION), les données
ETF_FLOW sont déjà en base : pas besoin de retaper SoSoValue/Farside, juste d'exporter la table
MySQL locale (`tradeio5`, localhost:3306) -- doit tourner sur une machine avec accès réseau/DB
normal (pas le bac à sable Cowork, cf. docs/calibration/prompt-calibration-etf-flow.md, en-tête).

Colonnes exportées : date,total_net_inflow (triées par date croissante) -- format attendu par
etf_flow_calibration.py (load_etf_flow_series).

Usage:
    python export_etf_flow_history.py --asset BTC --out etf_flow_btc.csv
    python export_etf_flow_history.py --asset ETH --out etf_flow_eth.csv

Credentials : lues depuis --user/--password (jamais committées en clair, cf. mémoire projet
"TradeIO5 secrets are gitignored") -- valeurs par défaut alignées sur
src/main/resources/application-dev.properties (spring.datasource.username/password) pour un usage
local direct, mais surchageables en ligne de commande.
"""

import argparse
import csv
import sys

try:
    import mysql.connector
except ImportError:
    mysql = None


def fetch_rows(host, port, database, user, password, asset):
    import mysql.connector as mc
    conn = mc.connect(host=host, port=port, database=database, user=user, password=password)
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT date, total_net_inflow FROM etf_flow_snapshot WHERE asset = %s ORDER BY date",
            (asset,),
        )
        rows = cur.fetchall()
        cur.close()
        return rows
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", required=True, choices=["BTC", "ETH"])
    parser.add_argument("--out", required=True)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--database", default="tradeio5")
    parser.add_argument("--user", default="klm")
    parser.add_argument("--password", default="klm31")
    args = parser.parse_args()

    if mysql is None:
        print("[ERREUR] mysql-connector-python non installé. "
              "Installer avec: pip install mysql-connector-python", file=sys.stderr)
        sys.exit(1)

    print(f"Connexion à mysql://{args.host}:{args.port}/{args.database} (user={args.user})...")
    rows = fetch_rows(args.host, args.port, args.database, args.user, args.password, args.asset)
    print(f"{len(rows)} lignes trouvées pour asset={args.asset}.")

    with open(args.out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["date", "total_net_inflow"])
        for date, total_net_inflow in rows:
            writer.writerow([date.isoformat(), total_net_inflow])

    print(f"Écrit: {args.out}")
    if rows:
        print(f"Plage: {rows[0][0]} -> {rows[-1][0]}")


if __name__ == "__main__":
    main()
