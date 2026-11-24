---
statut: journal
sujet: Tableaux complets de D1a (actifs, empreintes, blocs, dérive, notes) ; synthèse dans etude-transfert-presets.md
modifié_le: 2026-11-23
empreinte: 123b068a0c
---
# D1a — tableaux complets (2026-11-23)

Synthèse : [`etude-transfert-presets.md`](etude-transfert-presets.md). Sortie brute de `run-optimizer-manual.bat transfer`.


Δ = gain du preset pur − gain du DCA fixe calé à exposition moyenne égale (dichotomie de B'), en points du capital, par bloc de 24 mois ; exposition = valeur de la position / valeur totale, moyenne sur les jours. Trend = Params.defaults() + KEEP_PREVIOUS (« Trend de base » du live pour un actif sans réglage dédié). Aucun réglage par actif.

## Actifs
| Actif | Rôle | Source | Bougies | Premier | Dernier | Empreinte | ATR % méd. |
|---|---|---|---|---|---|---|---|
| BTC | CONTROLE | OKX | 3238 | 2018-01-11 | 2026-11-22 | 952c6a2742424853 | 4.25 |
| ETH | CONTROLE | OKX | 3238 | 2018-01-11 | 2026-11-22 | fad3b6456cd9303a | 5.80 |
| PAXG | CONTROLE | KRAKEN | 761 | 2024-10-23 | 2026-11-22 | 1aaf2158eb6aaeeb | 1.66 |
| BNB | NOUVEAU | BINANCE/USDT | 3304 | 2017-11-06 | 2026-11-22 | a119932e089fe01a | 5.34 |
| SOL | NOUVEAU | BINANCE/USDT | 2295 | 2020-08-11 | 2026-11-22 | 0f107cb8d9805613 | 7.68 |
| XRP | NOUVEAU | BINANCE/USDT | 3125 | 2018-05-04 | 2026-11-22 | c22ad96e623e7974 | 6.10 |
| ADA | NOUVEAU | BINANCE/USDT | 3142 | 2018-04-17 | 2026-11-22 | 3e00dc33fa610b0c | 7.09 |
| LINK | NOUVEAU | BINANCE/USDT | 2868 | 2019-01-16 | 2026-11-22 | da8a762b3ea2c00a | 7.45 |
| LTC | NOUVEAU | BINANCE/USDT | 3267 | 2017-12-13 | 2026-11-22 | a81f832243236198 | 6.57 |
| AVAX | NOUVEAU | BINANCE/USDT | 2253 | 2020-09-22 | 2026-11-22 | 4fde7e1e622c2842 | 7.68 |
| DOT | NOUVEAU | BINANCE/USDT | 2288 | 2020-08-18 | 2026-11-22 | be06c4093cadbf50 | 7.09 |
| DOGE | NOUVEAU | BINANCE/USDT | 2698 | 2019-07-05 | 2026-11-22 | ff2fc9af5a514e8f | 6.92 |

## Preset × actif : Δ médian (pts de capital) (blocs gagnés / blocs valides) TRANSFERE
`[échant.]` = preset réglé pour cet actif : rapporté, exclu du verdict. `prov.` = moins de 2 blocs : signe seul, hors verdict.

| Actif | BTC | ETH | PAXG |
|---|---|---|---|
| BTC | [échant.] 0.39 (3/4) OUI | 1.54 (4/4) OUI | 3.03 (4/4) OUI |
| ETH | 0.94 (3/4) OUI | [échant.] 1.87 (3/4) OUI | 1.40 (2/4) non |
| PAXG | n/d (0/0) prov. + | n/d (0/0) prov. + | [échant.] n/d (0/0) prov. + |
| BNB | 1.45 (3/4) OUI | 1.12 (4/4) OUI | 2.18 (3/4) OUI |
| SOL | 6.08 (2/2) OUI | 2.32 (2/2) OUI | 2.50 (2/2) OUI |
| XRP | 0.88 (2/4) non | 1.53 (2/4) non | 5.80 (3/4) OUI |
| ADA | 1.24 (3/4) OUI | -1.68 (1/4) non | -1.69 (2/4) non |
| LINK | 3.00 (3/3) OUI | 1.32 (3/3) OUI | 3.96 (3/3) OUI |
| LTC | 1.44 (4/4) OUI | 1.55 (4/4) OUI | 0.99 (2/4) non |
| AVAX | 2.25 (2/2) OUI | 2.35 (2/2) OUI | 1.77 (1/2) non |
| DOT | 3.01 (2/2) OUI | 2.83 (2/2) OUI | 1.89 (1/2) non |
| DOGE | 2.03 (2/3) OUI | 4.11 (2/3) OUI | 4.25 (2/3) OUI |

## Verdict global : PRESET_GENERIQUE
Plusieurs presets GENERIQUE : BTC, ETH ; aucun n'est choisi a posteriori (meilleur Δ médian poolé, rapporté seulement : PAXG).

Règle 2 (GENERIQUE si TRANSFERE sur ≥ 70 % des actifs hors échantillon conclus) :

| Preset | Part TRANSFERE | Δ médian poolé (blocs) | GENERIQUE |
|---|---|---|---|
| BTC | 90 % | 1.95 | oui |
| ETH | 80 % | 1.61 | oui |
| PAXG | 55 % | 2.61 | non |

Règle 3 (choix par volatilité, ATR % médian le plus proche ; actifs hors sources conclus : 9) : TRANSFERE sur 78 % (seuil 70 %), bat la moyenne des deux autres sur 44 % (seuil 60 %) => ne tient pas.

| Actif | Preset choisi |
|---|---|
| ADA | ETH |
| AVAX | ETH |
| BNB | ETH |
| DOGE | ETH |
| DOT | ETH |
| LINK | ETH |
| LTC | ETH |
| SOL | ETH |
| XRP | ETH |

## Actifs « provisoire » (hors verdict)
- PAXG × BTC : PROVISOIRE (0 bloc(s)) moins de 2 blocs de 24 mois rejouables : provisoire, aucun verdict
- PAXG × ETH : PROVISOIRE (0 bloc(s)) moins de 2 blocs de 24 mois rejouables : provisoire, aucun verdict
- PAXG × PAXG : PROVISOIRE (0 bloc(s)) moins de 2 blocs de 24 mois rejouables : provisoire, aucun verdict

## A posteriori / dans l'échantillon (hors verdict) : meilleur preset par actif (Δ médian)
| Actif | Meilleur preset | Δ médian |
|---|---|---|
| BTC | PAXG | 3.03 |
| ETH | ETH (échant.) | 1.87 |
| BNB | PAXG | 2.18 |
| SOL | BTC | 6.08 |
| XRP | PAXG | 5.80 |
| ADA | BTC | 1.24 |
| LINK | PAXG | 3.96 |
| LTC | ETH | 1.55 |
| AVAX | ETH | 2.35 |
| DOT | BTC | 3.01 |
| DOGE | PAXG | 4.25 |

## Détail des blocs
| Actif | Preset | Bloc | Preset % | DCA égal % | Δ | Bande | Expo % | DD ok |
|---|---|---|---|---|---|---|---|---|
| BTC | BTC | 2024-11-23 → 2026-11-22 | 0.29 | -2.41 | +2.70 | 0.21 | 3.7 | oui |
| BTC | BTC | 2022-11-23 → 2024-11-22 | 9.18 | 8.59 | +0.60 | 0.00 | 4.1 | oui |
| BTC | BTC | 2020-11-23 → 2022-11-22 | -3.76 | -3.95 | +0.19 | 0.14 | 3.6 | oui |
| BTC | BTC | 2018-11-23 → 2020-11-22 | 9.37 | 9.76 | -0.39 | 0.00 | 4.6 | oui |
| BTC | ETH | 2024-11-23 → 2026-11-22 | 0.03 | -1.93 | +1.96 | 0.16 | 3.0 | oui |
| BTC | ETH | 2022-11-23 → 2024-11-22 | 4.59 | 3.53 | +1.06 | 0.00 | 1.8 | oui |
| BTC | ETH | 2020-11-23 → 2022-11-22 | -1.57 | -3.81 | +2.24 | 0.14 | 3.4 | oui |
| BTC | ETH | 2018-11-23 → 2020-11-22 | 7.77 | 6.65 | +1.12 | 0.00 | 3.3 | oui |
| BTC | PAXG | 2024-11-23 → 2026-11-22 | -3.02 | -5.54 | +2.52 | 0.47 | 8.6 | oui |
| BTC | PAXG | 2022-11-23 → 2024-11-22 | 15.16 | 11.62 | +3.54 | 0.00 | 5.6 | oui |
| BTC | PAXG | 2020-11-23 → 2022-11-22 | -3.55 | -8.18 | +4.63 | 0.29 | 7.4 | oui |
| BTC | PAXG | 2018-11-23 → 2020-11-22 | 15.11 | 15.08 | +0.03 | 0.00 | 7.2 | oui |
| ETH | BTC | 2024-11-23 → 2026-11-22 | -1.78 | -3.65 | +1.88 | 0.40 | 5.6 | oui |
| ETH | BTC | 2022-11-23 → 2024-11-22 | 7.42 | 4.45 | +2.97 | 0.00 | 5.2 | oui |
| ETH | BTC | 2020-11-23 → 2022-11-22 | -1.59 | -1.59 | +0.00 | 0.00 | 2.9 | oui |
| ETH | BTC | 2018-11-23 → 2020-11-22 | 7.20 | 7.79 | -0.59 | 0.00 | 2.9 | oui |
| ETH | ETH | 2024-11-23 → 2026-11-22 | 0.44 | -3.74 | +4.18 | 0.41 | 5.7 | oui |
| ETH | ETH | 2022-11-23 → 2024-11-22 | 3.91 | 2.47 | +1.44 | 0.00 | 2.9 | oui |
| ETH | ETH | 2020-11-23 → 2022-11-22 | 0.81 | -1.48 | +2.30 | 0.00 | 2.7 | oui |
| ETH | ETH | 2018-11-23 → 2020-11-22 | 6.46 | 7.23 | -0.78 | 0.00 | 2.8 | oui |
| ETH | PAXG | 2024-11-23 → 2026-11-22 | 4.16 | -7.14 | +11.30 | 0.78 | 10.9 | oui |
| ETH | PAXG | 2022-11-23 → 2024-11-22 | 11.75 | 11.88 | -0.13 | 0.00 | 13.3 | oui |
| ETH | PAXG | 2020-11-23 → 2022-11-22 | -1.73 | -4.67 | +2.94 | 0.00 | 8.1 | oui |
| ETH | PAXG | 2018-11-23 → 2020-11-22 | 20.13 | 31.16 | -11.02 | 0.00 | 11.0 | non |
| BNB | BTC | 2024-11-23 → 2026-11-22 | 0.49 | -1.35 | +1.85 | 0.09 | 3.4 | oui |
| BNB | BTC | 2022-11-23 → 2024-11-22 | 6.34 | 5.28 | +1.06 | 0.00 | 4.3 | oui |
| BNB | BTC | 2020-11-23 → 2022-11-22 | 0.46 | 1.45 | -0.99 | 0.00 | 4.0 | oui |
| BNB | BTC | 2018-11-23 → 2020-11-22 | 5.25 | 3.22 | +2.03 | 0.00 | 2.7 | oui |
| BNB | ETH | 2024-11-23 → 2026-11-22 | 0.50 | -1.07 | +1.57 | 0.07 | 2.7 | oui |
| BNB | ETH | 2022-11-23 → 2024-11-22 | 6.07 | 4.28 | +1.79 | 0.00 | 3.6 | oui |
| BNB | ETH | 2020-11-23 → 2022-11-22 | 1.85 | 1.19 | +0.66 | 0.00 | 3.4 | oui |
| BNB | ETH | 2018-11-23 → 2020-11-22 | 4.03 | 3.76 | +0.27 | 0.00 | 3.2 | oui |
| BNB | PAXG | 2024-11-23 → 2026-11-22 | 2.43 | -3.67 | +6.10 | 0.23 | 9.2 | oui |
| BNB | PAXG | 2022-11-23 → 2024-11-22 | 12.67 | 16.10 | -3.43 | 0.00 | 12.3 | oui |
| BNB | PAXG | 2020-11-23 → 2022-11-22 | 5.04 | 3.95 | +1.09 | 0.00 | 10.7 | oui |
| BNB | PAXG | 2018-11-23 → 2020-11-22 | 13.19 | 9.93 | +3.26 | 0.00 | 7.9 | oui |
| SOL | BTC | 2024-11-23 → 2026-11-22 | -1.29 | -5.73 | +4.44 | 0.61 | 5.6 | oui |
| SOL | BTC | 2022-11-23 → 2024-11-22 | 26.21 | 18.48 | +7.73 | 0.00 | 5.5 | oui |
| SOL | ETH | 2024-11-23 → 2026-11-22 | -3.15 | -4.81 | +1.66 | 0.51 | 4.6 | oui |
| SOL | ETH | 2022-11-23 → 2024-11-22 | 16.17 | 13.20 | +2.97 | 0.00 | 4.1 | oui |
| SOL | PAXG | 2024-11-23 → 2026-11-22 | -12.10 | -14.43 | +2.33 | 1.54 | 14.1 | oui |
| SOL | PAXG | 2022-11-23 → 2024-11-22 | 64.26 | 61.59 | +2.67 | 0.00 | 15.8 | oui |
| XRP | BTC | 2024-11-23 → 2026-11-22 | -5.49 | -7.89 | +2.39 | 0.62 | 6.7 | oui |
| XRP | BTC | 2022-11-23 → 2024-11-22 | 15.15 | 19.91 | -4.76 | 0.00 | 6.2 | oui |
| XRP | BTC | 2020-11-23 → 2022-11-22 | 2.83 | -3.33 | +6.16 | 0.11 | 5.4 | oui |
| XRP | BTC | 2018-11-23 → 2020-11-22 | 6.94 | 7.58 | -0.64 | 0.00 | 4.6 | oui |
| XRP | ETH | 2024-11-23 → 2026-11-22 | -1.74 | -4.99 | +3.25 | 0.39 | 4.2 | oui |
| XRP | ETH | 2022-11-23 → 2024-11-22 | 9.17 | 12.58 | -3.41 | 0.00 | 3.8 | oui |
| XRP | ETH | 2020-11-23 → 2022-11-22 | -0.83 | -4.07 | +3.24 | 0.14 | 6.6 | oui |
| XRP | ETH | 2018-11-23 → 2020-11-22 | 5.01 | 5.19 | -0.18 | 0.00 | 3.1 | oui |
| XRP | PAXG | 2024-11-23 → 2026-11-22 | -9.98 | -16.69 | +6.71 | 1.32 | 14.5 | oui |
| XRP | PAXG | 2022-11-23 → 2024-11-22 | 47.85 | 42.97 | +4.88 | 0.00 | 13.1 | oui |
| XRP | PAXG | 2020-11-23 → 2022-11-22 | 10.78 | -7.59 | +18.37 | 0.25 | 12.2 | oui |
| XRP | PAXG | 2018-11-23 → 2020-11-22 | 9.44 | 13.16 | -3.73 | 0.00 | 8.1 | oui |
| ADA | BTC | 2024-11-23 → 2026-11-22 | -8.66 | -10.22 | +1.57 | 0.97 | 5.4 | oui |
| ADA | BTC | 2022-11-23 → 2024-11-22 | 19.98 | 18.51 | +1.47 | 0.00 | 6.4 | oui |
| ADA | BTC | 2020-11-23 → 2022-11-22 | -6.22 | -2.41 | -3.81 | 0.00 | 3.8 | oui |
| ADA | BTC | 2018-11-23 → 2020-11-22 | 13.94 | 12.94 | +1.00 | 0.00 | 5.4 | oui |
| ADA | ETH | 2024-11-23 → 2026-11-22 | -7.56 | -8.57 | +1.01 | 0.81 | 4.4 | oui |
| ADA | ETH | 2022-11-23 → 2024-11-22 | 8.32 | 11.20 | -2.89 | 0.00 | 3.9 | oui |
| ADA | ETH | 2020-11-23 → 2022-11-22 | -5.16 | -1.99 | -3.17 | 0.00 | 3.1 | oui |
| ADA | ETH | 2018-11-23 → 2020-11-22 | 8.16 | 8.63 | -0.46 | 0.00 | 3.6 | oui |
| ADA | PAXG | 2024-11-23 → 2026-11-22 | -24.33 | -26.87 | +2.54 | 2.55 | 15.4 | oui |
| ADA | PAXG | 2022-11-23 → 2024-11-22 | 22.91 | 30.20 | -7.29 | 0.00 | 10.2 | oui |
| ADA | PAXG | 2020-11-23 → 2022-11-22 | 0.38 | -5.39 | +5.77 | 0.00 | 8.1 | oui |
| ADA | PAXG | 2018-11-23 → 2020-11-22 | 11.34 | 17.25 | -5.91 | 0.00 | 7.0 | oui |
| LINK | BTC | 2024-11-23 → 2026-11-22 | -1.78 | -4.58 | +2.81 | 0.60 | 6.2 | oui |
| LINK | BTC | 2022-11-23 → 2024-11-22 | 7.92 | 4.91 | +3.00 | 0.00 | 4.7 | oui |
| LINK | BTC | 2020-11-23 → 2022-11-22 | -1.54 | -5.59 | +4.05 | 0.35 | 4.0 | oui |
| LINK | ETH | 2024-11-23 → 2026-11-22 | -2.10 | -3.23 | +1.12 | 0.42 | 4.3 | oui |
| LINK | ETH | 2022-11-23 → 2024-11-22 | 4.31 | 2.99 | +1.32 | 0.00 | 2.9 | oui |
| LINK | ETH | 2020-11-23 → 2022-11-22 | -3.55 | -5.59 | +2.04 | 0.35 | 4.1 | oui |
| LINK | PAXG | 2024-11-23 → 2026-11-22 | -4.19 | -8.15 | +3.96 | 1.06 | 11.1 | oui |
| LINK | PAXG | 2022-11-23 → 2024-11-22 | 10.72 | 10.47 | +0.25 | 0.00 | 10.0 | oui |
| LINK | PAXG | 2020-11-23 → 2022-11-22 | -5.74 | -13.82 | +8.09 | 0.87 | 10.3 | oui |
| LTC | BTC | 2024-11-23 → 2026-11-22 | -5.30 | -7.44 | +2.14 | 0.71 | 6.6 | oui |
| LTC | BTC | 2022-11-23 → 2024-11-22 | 2.34 | 1.60 | +0.74 | 0.00 | 3.8 | oui |
| LTC | BTC | 2020-11-23 → 2022-11-22 | -0.84 | -3.71 | +2.87 | 0.22 | 4.5 | oui |
| LTC | BTC | 2018-11-23 → 2020-11-22 | 5.19 | 4.78 | +0.41 | 0.00 | 4.5 | oui |
| LTC | ETH | 2024-11-23 → 2026-11-22 | -2.45 | -4.88 | +2.43 | 0.46 | 4.3 | oui |
| LTC | ETH | 2022-11-23 → 2024-11-22 | 2.14 | 1.54 | +0.60 | 0.00 | 3.6 | oui |
| LTC | ETH | 2020-11-23 → 2022-11-22 | -0.92 | -2.83 | +1.91 | 0.17 | 3.5 | oui |
| LTC | ETH | 2018-11-23 → 2020-11-22 | 4.16 | 2.97 | +1.19 | 0.00 | 2.7 | oui |
| LTC | PAXG | 2024-11-23 → 2026-11-22 | -16.66 | -19.03 | +2.37 | 1.80 | 17.7 | oui |
| LTC | PAXG | 2022-11-23 → 2024-11-22 | 5.20 | 5.59 | -0.39 | 0.00 | 13.2 | oui |
| LTC | PAXG | 2020-11-23 → 2022-11-22 | -3.69 | -10.51 | +6.83 | 0.63 | 13.4 | oui |
| LTC | PAXG | 2018-11-23 → 2020-11-22 | 9.95 | 10.39 | -0.44 | 0.00 | 9.7 | oui |
| AVAX | BTC | 2024-11-23 → 2026-11-22 | -5.73 | -9.61 | +3.88 | 1.11 | 6.6 | oui |
| AVAX | BTC | 2022-11-23 → 2024-11-22 | 7.16 | 6.54 | +0.63 | 0.00 | 4.1 | oui |
| AVAX | ETH | 2024-11-23 → 2026-11-22 | -4.05 | -5.88 | +1.82 | 0.68 | 4.0 | oui |
| AVAX | ETH | 2022-11-23 → 2024-11-22 | 9.41 | 6.54 | +2.87 | 0.00 | 4.1 | oui |
| AVAX | PAXG | 2024-11-23 → 2026-11-22 | -10.10 | -16.29 | +6.19 | 1.88 | 11.7 | oui |
| AVAX | PAXG | 2022-11-23 → 2024-11-22 | 16.59 | 19.25 | -2.66 | 0.00 | 11.4 | oui |
| DOT | BTC | 2024-11-23 → 2026-11-22 | -9.62 | -12.79 | +3.17 | 1.21 | 5.7 | oui |
| DOT | BTC | 2022-11-23 → 2024-11-22 | 5.22 | 2.37 | +2.85 | 0.00 | 6.3 | oui |
| DOT | ETH | 2024-11-23 → 2026-11-22 | -5.43 | -9.24 | +3.81 | 0.87 | 4.1 | oui |
| DOT | ETH | 2022-11-23 → 2024-11-22 | 4.40 | 2.54 | +1.86 | 0.00 | 6.7 | oui |
| DOT | PAXG | 2024-11-23 → 2026-11-22 | -17.97 | -23.09 | +5.12 | 2.18 | 10.9 | oui |
| DOT | PAXG | 2022-11-23 → 2024-11-22 | 4.18 | 5.52 | -1.34 | 0.00 | 14.6 | oui |
| DOGE | BTC | 2024-11-23 → 2026-11-22 | -7.23 | -10.82 | +3.59 | 1.06 | 6.5 | oui |
| DOGE | BTC | 2022-11-23 → 2024-11-22 | 26.81 | 31.80 | -4.99 | 0.00 | 6.2 | oui |
| DOGE | BTC | 2020-11-23 → 2022-11-22 | 3.77 | 1.74 | +2.03 | 0.00 | 4.5 | oui |
| DOGE | ETH | 2024-11-23 → 2026-11-22 | -5.12 | -9.23 | +4.11 | 0.90 | 5.4 | oui |
| DOGE | ETH | 2022-11-23 → 2024-11-22 | 19.23 | 22.86 | -3.62 | 0.00 | 4.4 | oui |
| DOGE | ETH | 2020-11-23 → 2022-11-22 | 12.76 | 1.89 | +10.87 | 0.00 | 5.0 | oui |
| DOGE | PAXG | 2024-11-23 → 2026-11-22 | -24.71 | -28.97 | +4.25 | 2.83 | 18.8 | oui |
| DOGE | PAXG | 2022-11-23 → 2024-11-22 | 61.78 | 109.32 | -47.53 | 0.00 | 19.3 | oui |
| DOGE | PAXG | 2020-11-23 → 2022-11-22 | 40.72 | 2.45 | +38.27 | 0.00 | 6.4 | oui |

## Bilan de dérive (preset pur, bloc le plus récent)
| Actif | Chemin loader (E1) | Entrée D1a | Écart (pts) |
|---|---|---|---|
| BTC | 0.29 | 0.29 | 0.00 |
| ETH | 0.44 | 0.44 | 0.00 |
| PAXG | n/d | n/d | n/d |

## Notes
- Trend : celle du live pour un actif sans réglage dédié = « Trend de base » (RainbowLiveTrendConfigs.defaults) : TrendMixCalculator.Params.defaults() (fenêtres 14 / 30 / 60, pente 400, enter 0,30, exit 0,10, confirm 10, SMA 100, ATR 5 x 1,25) + RangeMapping.KEEP_PREVIOUS ; identique pour les 12 actifs.
- Presets : jeux Bear / Bull live de BTC, ETH, PAXG lus par le chargeur du rejeu (DB), joués tels quels, modulation ATH désactivée (preset pur, comme E1) ; montant de base = taux de base du lien source (base du lien / capital de son wallet, comme B' / E1 x1) x capital 2484.0 identique pour les 36 paires ; exposition du preset rapportée par bloc.
- Bougies : BTC / ETH / PAXG = bougies habituelles du rejeu (source indiquée par actif) ; 9 nouveaux = Binance USDT, stockage bench dédié (fichiers, manifeste, étiquette BINANCE/USDT, empreinte), jamais lus par le live.
- Multiplicité : une configuration, un run, 36 paires fixées d'avance (33 hors échantillon, 3 dans l'échantillon exclues du verdict) ; 108 blocs valides au total ; 20 décalages de départ par plage ; 5412 rejeux.
- Bande de bruit : largeur = P80 des 20 décalages (0-60 j) du DCA calé moins le DCA calé non décalé ; Δ médian doit dépasser la médiane de ces largeurs sur les blocs.
- Règle 3 : actifs hors des 3 sources seulement (pour une source le preset le plus proche est le sien) ; ATR 14 (RMA) en % du cours, médiane sur l'historique complet de l'actif ; distance = |ln(rapport des ATR %)| ; aucune donnée de test utilisée pour le choix.
- Limites : cryptos fortement corrélées (nombre d'actifs indépendants bien inférieur à 12) ; biais de survie (actifs encore listés) ; peu de blocs par actif ; quasi un seul grand bear (2021-22) ; Binance USDT contre USDC des presets ; coûts simplifiés du simulateur.
