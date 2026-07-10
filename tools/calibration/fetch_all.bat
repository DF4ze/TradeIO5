@echo off
cd /d "D:\Documents\Spring\TradeIO-5\tools\calibration"
set OUT=D:\Documents\Spring\TradeIO-5\target\calibration-data-extended

echo === BTC D1 === >> "%OUT%\fetch_all.log"
python fetch_real_klines.py --symbol BTCUSDT --start 2017-08-17 --end 2026-07-09 --interval 1d --out "%OUT%\btcusdt_d1_full.csv" --sleep 0.2 >> "%OUT%\fetch_all.log" 2>&1

echo === BTC H4 === >> "%OUT%\fetch_all.log"
python fetch_real_klines.py --symbol BTCUSDT --start 2017-08-17 --end 2026-07-09 --interval 4h --out "%OUT%\btcusdt_h4_full.csv" --sleep 0.2 >> "%OUT%\fetch_all.log" 2>&1

echo === BTC H1 (2020+) === >> "%OUT%\fetch_all.log"
python fetch_real_klines.py --symbol BTCUSDT --start 2020-01-01 --end 2026-07-09 --interval 1h --out "%OUT%\btcusdt_h1_ext.csv" --sleep 0.15 >> "%OUT%\fetch_all.log" 2>&1

echo === ETH D1 === >> "%OUT%\fetch_all.log"
python fetch_real_klines.py --symbol ETHUSDT --start 2017-08-17 --end 2026-07-09 --interval 1d --out "%OUT%\ethusdt_d1_full.csv" --sleep 0.2 >> "%OUT%\fetch_all.log" 2>&1

echo === ETH H4 === >> "%OUT%\fetch_all.log"
python fetch_real_klines.py --symbol ETHUSDT --start 2017-08-17 --end 2026-07-09 --interval 4h --out "%OUT%\ethusdt_h4_full.csv" --sleep 0.2 >> "%OUT%\fetch_all.log" 2>&1

echo === ETH H1 (2020+) === >> "%OUT%\fetch_all.log"
python fetch_real_klines.py --symbol ETHUSDT --start 2020-01-01 --end 2026-07-09 --interval 1h --out "%OUT%\ethusdt_h1_ext.csv" --sleep 0.15 >> "%OUT%\fetch_all.log" 2>&1

echo ALL_DONE >> "%OUT%\fetch_all.log"
