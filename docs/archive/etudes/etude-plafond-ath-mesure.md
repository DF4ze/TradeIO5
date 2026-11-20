---
statut: journal
sujet: Mesures H0 et plafond de mise ATH (étapes 1 et 2) — tableaux complets BTC, ETH, PAXG
modifié_le: 2026-11-16
empreinte: b6480fa9fe
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

## Mesure H0 (étape 1, 2026-11-16) — tableau complet
Synthèse : [`etude-optimisation-mode-autonome.md`](etude-optimisation-mode-autonome.md) §11.1. `policy-compare` aux entrées par défaut du simulateur (capital = valeur du wallet réel du jour, plage glissante : cf. §11.3 de la synthèse).

| Actif | Plage | Politique | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | ms |
|---|---|---|---|---|---|---|---|---|---|---|
| BTC | 24 m | CONSTANT | 2 571 | 2 309 | +11,4 | −2,0 | 4,8 | +62 | +200 | 108 |
| BTC | 24 m | REGIME_BAND | 2 435 | 2 309 | +5,5 | −9,2 | 13,7 | −4 | +130 | 56 |
| BTC | 24 m | EXPOSURE_GAP_BIAS | 2 575 | 2 309 | +11,5 | −2,0 | 4,8 | +63 | +203 | 35 |
| BTC | 24 m | BEAR_DERISK_RAMP | 2 571 | 2 309 | +11,4 | −2,0 | 4,8 | +62 | +200 | 33 |
| BTC | max | CONSTANT | 3 377 | 11 026 | −69,4 | −8,9 | 5,7 | −7 127 | −522 | 59 |
| BTC | max | REGIME_BAND | 6 154 | 11 026 | −44,2 | −25,8 | 16,1 | −5 679 | +808 | 54 |
| BTC | max | EXPOSURE_GAP_BIAS | 3 366 | 11 026 | −69,5 | −8,8 | 5,6 | −7 137 | −523 | 42 |
| BTC | max | BEAR_DERISK_RAMP | 3 377 | 11 026 | −69,4 | −8,9 | 5,7 | −7 127 | −522 | 35 |
| ETH | 24 m | CONSTANT | 2 428 | 2 228 | +9,0 | −6,9 | 6,5 | +87 | +113 | 20 |
| ETH | 24 m | REGIME_BAND | 2 471 | 2 228 | +10,9 | −15,6 | 18,7 | −64 | +307 | 14 |
| ETH | 24 m | EXPOSURE_GAP_BIAS | 2 423 | 2 228 | +8,7 | −6,9 | 6,4 | +89 | +106 | 15 |
| ETH | 24 m | BEAR_DERISK_RAMP | 2 428 | 2 228 | +9,0 | −6,9 | 6,5 | +87 | +113 | 14 |
| ETH | max | CONSTANT | 3 196 | 9 641 | −66,8 | −8,6 | 5,8 | −5 595 | −850 | 32 |
| ETH | max | REGIME_BAND | 6 272 | 9 641 | −34,9 | −20,7 | 18,4 | −4 637 | +1 268 | 27 |
| ETH | max | EXPOSURE_GAP_BIAS | 3 169 | 9 641 | −67,1 | −8,5 | 5,7 | −5 606 | −866 | 23 |
| ETH | max | BEAR_DERISK_RAMP | 3 196 | 9 641 | −66,8 | −8,6 | 5,8 | −5 595 | −850 | 26 |
| PAXG | max (19 m) | CONSTANT | 1 081 | 1 039 | +4,0 | −1,8 | 5,5 | −33 | +74 | 10 |
| PAXG | max (19 m) | REGIME_BAND | 1 132 | 1 039 | +8,9 | −6,4 | 17,6 | −15 | +107 | 7 |
| PAXG | max (19 m) | EXPOSURE_GAP_BIAS | 1 080 | 1 039 | +4,0 | −1,8 | 5,5 | −33 | +74 | 5 |
| PAXG | max (19 m) | BEAR_DERISK_RAMP | 1 081 | 1 039 | +4,0 | −1,8 | 5,5 | −33 | +74 | 6 |

Frais : 0 (non simulés).

## Coût en gain d'un plafond (étape 2) — synthèse
Variation de la valeur finale vs sans plafond (détail ci-dessus). Synthèse : §11.2.

| Plafond | CONSTANT (24 m / max) | REGIME_BAND BTC, ETH 24 m | REGIME_BAND BTC, ETH max | REGIME_BAND PAXG |
|---|---|---|---|---|
| Prudent 3/1 | −0,7 % / −0,3 % | −5,7 % / −5,4 % | −16,8 % / −16,6 % | −1,7 % |
| Équilibré 6/2 | −0,5 % / −0,3 % | −0,7 % / −0,6 % | −4,7 % / −4,4 % | −0,1 % |
| Dynamique 10/3 | −0,1 % / −0,1 % | −0,0 % | −0,3 % | +0,1 % |
| Full 15/5 | 0 | 0 | 0 | 0 |

## Lecture détaillée H0 (étape 1)
**Réponse : oui, la couche exposition pèse plus que le timing du signal, mais pas par toutes les règles.**
- Sur plage longue la perte vs DCA fixe vient presque entièrement du terme **exposition** (BTC −7 127, ETH −5 595) : le Rainbow ne détient que ~5 % du wallet en moyenne, le timing est secondaire (−522, −850). Une bande par régime (bull 20–90, bear 0–30) fait passer l'exposition moyenne à 16–18 %, déplace le terme exposition de +1 450 (BTC) / +960 (ETH) **et** le timing de +1 330 / +2 120 : la valeur finale double (BTC 3 377 → 6 154, ETH 3 196 → 6 272), soit un effet plus grand que tout le timing de la référence. Coût : drawdown ×3 (−8,9 → −25,8 %, −8,6 → −20,7 %).
- Sur 24 mois le signe dépend de l'actif : BTC −5,9 pts de gain (DD −2 → −9,2 %), ETH +1,9, PAXG +4,9 (DD −1,8 → −6,4 %). Effet de plancher Bull (achats de rattrapage sous 20 %), pas un gain de timing prouvé : à départ ou actif différent, le signe change.
- `EXPOSURE_GAP_BIAS` (gain 1) : écart ≤ 0,3 pt sur toutes les lignes, indiscernable de la constante. `BEAR_DERISK_RAMP` : résultat **identique** à la constante partout : l'exposition (~5 %) reste sous la cible / le plafond, la politique ne se déclenche jamais.
- Conclusion pour l'ordre des étapes 4 / 5 : le levier pertinent est la **bande par régime** (plancher d'exposition), pas le biais ni la rampe de dé-risque tels que testés. Les politiques qui ne font que baisser le plafond ne peuvent rien tant que le wallet est loin de son plafond.

## Lecture détaillée du plafond ATH (étape 2)
**(1) La mise de 10–15 × la base se confirme.** Sans plafond, le signal atteint 12,00 × la base (BTC, ETH) et 14,96 × (PAXG) ; après modulation, 11,8–12,9 × avec la bande neutre (`CONSTANT`) et **22,7–23,0 × avec la bande par régime** sur BTC et ETH (14,3 × sur PAXG) : le plancher Bull multiplie encore la mise, produit ≤ `modulationProductMax` 2,5. Le plafond d'achat Full (15) ne mord jamais sur ces trois actifs (max observé 12 / 14,96) ; Dynamique (10) ne touche que 13–90 achats.

**(2) Coût en gain d'un plafond** (variation de la valeur finale vs sans plafond) :

Le coût est faible tant que le wallet n'est pas poussé par la bande ; il devient net avec la bande par régime sur plage longue : le plafond coupe les plus grosses mises (zones ARMÉ / EXTREME_BAS, aux creux) et ce cash reste inemployé (wallet limité par son capital). Côté risque : Prudent réduit le drawdown de `CONSTANT` (BTC max −9,1 → −7,9 %), très peu celui de la bande (−23,8 → −20,7 %). **Exposition moyenne** ~7 % (constante) : un plafond la réduit peu en `CONSTANT` (−0,1 à −0,5 pt) mais de 3,6 pts avec la bande sous Prudent (BTC 24 m 20,9 → 17,3 %, max 19,6 → 16,0 %) : le plafond réduit bien le déploiement.

**(3) Le plafond ne change pas le verdict H0 sur plage longue.** La bande par régime reste le levier dominant : BTC max 5 462–6 568 vs 3 359–3 369 en `CONSTANT`, ETH max 5 354–6 420 vs 3 276–3 286, quel que soit le plafond ; la perte vs DCA fixe reste du même ordre (−62 à −69 %), drawdown de la bande −21 à −24 %. Sur 24 mois, en revanche, **Prudent efface l'avantage de la bande** sur BTC (2 412 vs 2 466 en `CONSTANT` au même plafond) et ETH (2 358 vs 2 407), alors qu'il subsiste sans plafond (+75 / +70) et sous Équilibré (+70 / +67) ; PAXG : la bande reste devant sous tous les plafonds. Lecture : l'effet reste un effet de plancher d'exposition, pas de timing du signal.
