---
statut: journal
sujet: Mesure du plafond de mise ATH utilisateur (étape 2) — tableau complet BTC, ETH, PAXG
modifié_le: 2026-11-16
empreinte: 83c36738dd
---
# Étude — Mesure du plafond de mise ATH (étape 2, 2026-11-16)

Synthèse et réponses : [`etude-optimisation-mode-autonome.md`](etude-optimisation-mode-autonome.md) §11.2. Livraison : [`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md) (« Plafond de mise ATH utilisateur »).

Méthode du §11.1 : `policy-compare` (runner `AthCapMeasureManualRunnerTest`), base locale copie de prod, un lien par run (BTC / ETH wallet 1, PAXG wallet 3), départ 100 % USDC, capital BTC 2 484 / ETH 2 425 / PAXG 1 062 USDC, sans frais. 24 m = 2024-11-15 → 2026-11-15 ; max = 2018-07-12 → 2026-11-15 (BTC, ETH), 2025-04-23 → 2026-11-15 (PAXG, 19 mois). `REGIME_BAND` : bear 0–30, bull 20–90. `maxStake` = achat du signal / base avant modulation, `maxFinalStake` = achat décidé / base après modulation, « achats plafonnés » = jours où le plafond a réduit la mise du signal. Gain % = écart à un DCA fixe de même budget.

| Actif | Plage | Politique | Plafond (achat/vente) | Final | DCA fixe | Gain % | DD max % | Expo moy. % | maxStake × | maxFinalStake × | Achats plafonnés |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BTC | 24 m | CONSTANT | aucun | 2483 | 2111 | +17,6 | −6,3 | 7,2 | 12,00 | 11,77 | 0 |
| BTC | 24 m | CONSTANT | Prudent 3/1 | 2466 | 2111 | +16,8 | −5,4 | 7,0 | 3,00 | 3,00 | 414 |
| BTC | 24 m | CONSTANT | Équilibré 6/2 | 2471 | 2111 | +17,1 | −6,3 | 7,2 | 6,00 | 6,00 | 227 |
| BTC | 24 m | CONSTANT | Dynamique 10/3 | 2480 | 2111 | +17,5 | −6,3 | 7,2 | 10,00 | 10,00 | 21 |
| BTC | 24 m | CONSTANT | Full 15/5 | 2483 | 2111 | +17,6 | −6,3 | 7,2 | 12,00 | 11,77 | 0 |
| BTC | 24 m | REGIME_BAND | aucun | 2558 | 2111 | +21,2 | −14,9 | 20,9 | 12,00 | 22,81 | 0 |
| BTC | 24 m | REGIME_BAND | Prudent 3/1 | 2412 | 2111 | +14,3 | −14,8 | 17,3 | 3,00 | 6,00 | 414 |
| BTC | 24 m | REGIME_BAND | Équilibré 6/2 | 2541 | 2111 | +20,4 | −14,7 | 20,2 | 6,00 | 12,00 | 227 |
| BTC | 24 m | REGIME_BAND | Dynamique 10/3 | 2557 | 2111 | +21,1 | −14,9 | 20,9 | 10,00 | 19,89 | 21 |
| BTC | 24 m | REGIME_BAND | Full 15/5 | 2558 | 2111 | +21,2 | −14,9 | 20,9 | 12,00 | 22,81 | 0 |
| BTC | max | CONSTANT | aucun | 3369 | 17302 | −80,5 | −9,1 | 7,1 | 12,00 | 12,00 | 0 |
| BTC | max | CONSTANT | Prudent 3/1 | 3359 | 17302 | −80,6 | −7,9 | 6,6 | 3,00 | 3,00 | 1377 |
| BTC | max | CONSTANT | Équilibré 6/2 | 3360 | 17302 | −80,6 | −8,4 | 7,0 | 6,00 | 6,00 | 1023 |
| BTC | max | CONSTANT | Dynamique 10/3 | 3365 | 17302 | −80,5 | −8,9 | 7,1 | 10,00 | 10,00 | 90 |
| BTC | max | CONSTANT | Full 15/5 | 3369 | 17302 | −80,5 | −9,1 | 7,1 | 12,00 | 12,00 | 0 |
| BTC | max | REGIME_BAND | aucun | 6568 | 17302 | −62,0 | −23,8 | 19,6 | 12,00 | 22,97 | 0 |
| BTC | max | REGIME_BAND | Prudent 3/1 | 5462 | 17302 | −68,4 | −20,7 | 16,0 | 3,00 | 6,00 | 1377 |
| BTC | max | REGIME_BAND | Équilibré 6/2 | 6261 | 17302 | −63,8 | −23,0 | 18,2 | 6,00 | 12,00 | 1023 |
| BTC | max | REGIME_BAND | Dynamique 10/3 | 6548 | 17302 | −62,2 | −23,6 | 19,5 | 10,00 | 20,00 | 90 |
| BTC | max | REGIME_BAND | Full 15/5 | 6568 | 17302 | −62,0 | −23,8 | 19,6 | 12,00 | 22,97 | 0 |
| ETH | 24 m | CONSTANT | aucun | 2423 | 2052 | +18,1 | −6,4 | 7,2 | 12,00 | 11,77 | 0 |
| ETH | 24 m | CONSTANT | Prudent 3/1 | 2407 | 2052 | +17,3 | −5,4 | 7,1 | 3,00 | 3,00 | 414 |
| ETH | 24 m | CONSTANT | Équilibré 6/2 | 2411 | 2052 | +17,5 | −6,3 | 7,2 | 6,00 | 6,00 | 227 |
| ETH | 24 m | CONSTANT | Dynamique 10/3 | 2420 | 2052 | +18,0 | −6,4 | 7,3 | 10,00 | 10,00 | 21 |
| ETH | 24 m | CONSTANT | Full 15/5 | 2423 | 2052 | +18,1 | −6,4 | 7,2 | 12,00 | 11,77 | 0 |
| ETH | 24 m | REGIME_BAND | aucun | 2493 | 2052 | +21,5 | −15,0 | 21,0 | 12,00 | 22,74 | 0 |
| ETH | 24 m | REGIME_BAND | Prudent 3/1 | 2358 | 2052 | +14,9 | −14,8 | 17,4 | 3,00 | 6,00 | 414 |
| ETH | 24 m | REGIME_BAND | Équilibré 6/2 | 2478 | 2052 | +20,8 | −14,8 | 20,3 | 6,00 | 12,00 | 227 |
| ETH | 24 m | REGIME_BAND | Dynamique 10/3 | 2492 | 2052 | +21,5 | −15,0 | 21,0 | 10,00 | 19,83 | 21 |
| ETH | 24 m | REGIME_BAND | Full 15/5 | 2493 | 2052 | +21,5 | −15,0 | 21,0 | 12,00 | 22,74 | 0 |
| ETH | max | CONSTANT | aucun | 3286 | 17258 | −81,0 | −9,2 | 7,1 | 12,00 | 12,00 | 0 |
| ETH | max | CONSTANT | Prudent 3/1 | 3276 | 17258 | −81,0 | −8,0 | 6,7 | 3,00 | 3,00 | 1377 |
| ETH | max | CONSTANT | Équilibré 6/2 | 3277 | 17258 | −81,0 | −8,5 | 7,0 | 6,00 | 6,00 | 1023 |
| ETH | max | CONSTANT | Dynamique 10/3 | 3282 | 17258 | −81,0 | −9,0 | 7,1 | 10,00 | 10,00 | 90 |
| ETH | max | CONSTANT | Full 15/5 | 3286 | 17258 | −81,0 | −9,2 | 7,1 | 12,00 | 12,00 | 0 |
| ETH | max | REGIME_BAND | aucun | 6420 | 17258 | −62,8 | −23,9 | 19,7 | 12,00 | 22,97 | 0 |
| ETH | max | REGIME_BAND | Prudent 3/1 | 5354 | 17258 | −69,0 | −20,8 | 16,1 | 3,00 | 6,00 | 1377 |
| ETH | max | REGIME_BAND | Équilibré 6/2 | 6136 | 17258 | −64,4 | −23,2 | 18,4 | 6,00 | 12,00 | 1023 |
| ETH | max | REGIME_BAND | Dynamique 10/3 | 6403 | 17258 | −62,9 | −23,8 | 19,6 | 10,00 | 20,00 | 90 |
| ETH | max | REGIME_BAND | Full 15/5 | 6420 | 17258 | −62,8 | −23,9 | 19,7 | 12,00 | 22,97 | 0 |
| PAXG | max (19 m) | CONSTANT | aucun | 1087 | 918 | +18,5 | −1,8 | 7,0 | 14,96 | 12,85 | 0 |
| PAXG | max (19 m) | CONSTANT | Prudent 3/1 | 1075 | 918 | +17,2 | −2,5 | 7,5 | 3,00 | 3,00 | 192 |
| PAXG | max (19 m) | CONSTANT | Équilibré 6/2 | 1085 | 918 | +18,2 | −1,9 | 7,0 | 6,00 | 6,00 | 147 |
| PAXG | max (19 m) | CONSTANT | Dynamique 10/3 | 1088 | 918 | +18,5 | −1,8 | 7,0 | 10,00 | 10,00 | 13 |
| PAXG | max (19 m) | CONSTANT | Full 15/5 | 1087 | 918 | +18,5 | −1,8 | 7,0 | 14,96 | 12,85 | 0 |
| PAXG | max (19 m) | REGIME_BAND | aucun | 1117 | 918 | +21,7 | −4,0 | 14,4 | 14,96 | 14,27 | 0 |
| PAXG | max (19 m) | REGIME_BAND | Prudent 3/1 | 1098 | 918 | +19,6 | −3,4 | 12,7 | 3,00 | 6,00 | 192 |
| PAXG | max (19 m) | REGIME_BAND | Équilibré 6/2 | 1116 | 918 | +21,6 | −3,9 | 14,2 | 6,00 | 6,00 | 147 |
| PAXG | max (19 m) | REGIME_BAND | Dynamique 10/3 | 1118 | 918 | +21,8 | −4,0 | 14,4 | 10,00 | 10,00 | 13 |
| PAXG | max (19 m) | REGIME_BAND | Full 15/5 | 1117 | 918 | +21,7 | −4,0 | 14,4 | 14,96 | 14,27 | 0 |
