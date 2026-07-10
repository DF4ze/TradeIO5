@echo off
cd /d "D:\Documents\Spring\TradeIO-5\tools\calibration"
set OUT=D:\Documents\Spring\TradeIO-5\target\calibration-data-extended

echo === ETH H4 (retry) === >> "%OUT%\fetch_eth_retry.log"
python fetch_real_klines.py --symbol ETHUSDT --start 2017-08-17 --end 2026-07-09 --interval 4h --out "%OUT%\ethusdt_h4_full.csv" --sleep 0.25 >> "%OUT%\fetch_eth_retry.log" 2>&1

echo === ETH H1 (retry) === >> "%OUT%\fetch_eth_retry.log"
python fetch_real_klines.py --symbol ETHUSDT --start 2020-01-01 --end 2026-07-09 --interval 1h --out "%OUT%\ethusdt_h1_ext.csv" --sleep 0.2 >> "%OUT%\fetch_eth_retry.log" 2>&1

echo ALL_DONE >> "%OUT%\fetch_eth_retry.log"
