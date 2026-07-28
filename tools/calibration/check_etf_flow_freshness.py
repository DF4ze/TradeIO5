#!/usr/bin/env python3
"""Diagnostic ad hoc : compare fetched_at (quand EtfFlowHistorizationJob a tourné) à date (le jour
que la donnée décrit) pour mesurer le délai réel de publication SoSoValue -- répond à la question
de Clem "est-ce qu'on peut connaître la fraîcheur du flux ETF retourné par l'API ?"."""
import mysql.connector

conn = mysql.connector.connect(host="localhost", port=3306, database="tradeio5", user="klm", password="klm31")
cur = conn.cursor()
for asset in ("BTC", "ETH"):
    print(f"--- {asset} ---")
    cur.execute(
        "SELECT date, fetched_at FROM etf_flow_snapshot WHERE asset=%s ORDER BY date DESC LIMIT 20",
        (asset,),
    )
    for date, fetched_at in cur.fetchall():
        lag = (fetched_at.date() - date).days
        print(f"  date={date}  fetched_at={fetched_at}  lag(jours)={lag}")
cur.close()
conn.close()
