import json
import sys
import time
import urllib.request

SYMBOLS = sys.argv[1:] or ["BTCUSDT", "ETHUSDT", "PAXGUSDT"]
INTERVAL = "1d"
LIMIT = 1000
BASE_URL = "https://api.binance.com/api/v3/klines"

def fetch_all(symbol):
    out = []
    start_time = 0  # from the beginning
    while True:
        url = f"{BASE_URL}?symbol={symbol}&interval={INTERVAL}&limit={LIMIT}&startTime={start_time}"
        with urllib.request.urlopen(url, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        if not data:
            break
        for row in data:
            out.append({
                "t": row[0],           # open time ms
                "o": float(row[1]),
                "h": float(row[2]),
                "l": float(row[3]),
                "c": float(row[4]),
                "v": float(row[5]),
            })
        last_open_time = data[-1][0]
        if len(data) < LIMIT:
            break
        start_time = last_open_time + 24 * 3600 * 1000
        time.sleep(0.2)
    return out

for sym in SYMBOLS:
    print(f"Fetching {sym}...")
    candles = fetch_all(sym)
    print(f"{sym}: {len(candles)} candles, from {candles[0]['t'] if candles else None} to {candles[-1]['t'] if candles else None}")
    out_path = f"D:\\Documents\\Spring\\TradeIO-5\\tools\\calibration\\{sym.lower()}_klines_d1_full.json"
    with open(out_path, "w") as f:
        json.dump(candles, f)
    print(f"Written to {out_path}")

print("DONE")
