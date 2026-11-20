---
statut: archivé
sujet: Carte de sensibilité Rainbow BTC (étape A du pivot « Rainbow d'abord ») — Bull seul / Bear seul, PnL réel 24 mois, lecture gel
vérifié_le: 2026-11-18
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 66736fae0c
---
# Étude — carte de sensibilité Rainbow BTC (étape A, 2026-11-18)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15b-carte-sensibilite-rainbow.md`](../../architecture/15b-carte-sensibilite-rainbow.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md) (pivot « Rainbow d'abord »), position de Clem dans [`../decisions/fil-rouge-dca-automatisation.md`](../../decisions/fil-rouge-dca-automatisation.md).

## Entrées (run du 2026-11-18, reproductible)
- Actif BTC (source OKX), wallet 1, lien 1, capital 2484 USDC ; vue 24 mois glissants finissant le 2026-11-17 (J-1) ; historique complet rapporté en lecture seule pour les 5 paramètres les plus influents de chaque jeu.
- Empreinte des bougies : `5d2823e19f40598a` (3233 bougies, 2018-01-11 → 2026-11-17). Deux runs consécutifs : JSON identique hors durées.
- Un paramètre à la fois, 31 réglages × Bull et Bear, 9 valeurs (valeur en place incluse) ; plages : règles de `ExpertPresetValidator` (plafonds de balayage 5 pour les facteurs ATH, 6 pour les multiplicateurs de zones, 20 pour les bornes ATR : choix de balayage). Interrupteurs et modes : une variante par option. Gelés : périodes SMA / ATR, `baseAmount`, Trend.
- Coût : 442 valeurs testées, 881 rejeux (394 cycle, 394 gel, 89 historique complet), 48 combinaisons ignorées (ordre des bornes ATR, `athBuyMax ≥ athBuyMin`), 31 s au total.

## Comparateurs (24 mois)
| Ligne | Plage | Valeur finale | PnL réel $ | PnL % | Réalisé $ | Valeur fin bull $ | dont position $ | Drawdown | Expo moy. | Achats / ventes |
|---|---|---|---|---|---|---|---|---|---|---|
| Preset en place | 2024-11-17 → 2026-11-17 | 2543.8 | +59.8 | +2.4 | 707.1 | 2579.6 | 240.3 | -2.9 % | 7 % | 186 / 11 |
| DCA fixe (même cash) | 2024-11-17 → 2026-11-17 | 2302.9 | -181.1 | -7.3 | — | — | — | — | — | — |
| Preset, historique complet (lecture seule) | 2018-07-10 → 2026-11-17 | 5050.9 | +2566.9 | +103.3 | 7076.8 | 5086.7 | 240.4 | -13.5 % | 10 % | 750 / 60 |
| DCA fixe, historique complet | 2018-07-10 → 2026-11-17 | 10932.6 | +8448.6 | +340.1 | — | — | — | — | — | — |

Lecture gel (seul le régime testé compte, un jeu à la fois) : Bull gain total +16.8 $ (DCA fixe même budget +19.7 $, investi 116.6 $) ; Bear gain total -68.7 $ (DCA fixe même budget -214.2 $, investi 721.2 $).

## Bull seul (l'autre jeu = preset en place), classé par influence
| # | Paramètre | En place | Meilleure | Pire | Écart max−min $ | Meilleure − preset $ | Sens | Bat le preset ? | Bat le DCA ? | Gel : meilleure | Gel : écart $ |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | sellFraction | 0.2000 | 0.5757 | 0.01000 | 133.6 | +42.1 | monter | oui | oui | 0.01000 | 5.7 |
| 2 | atrMultUp3 | 2.500 | 2.500 | 8.571 | 131.5 | +0.0 | en place | non | oui | 5.714 | 24.8 |
| 3 | athSellMin | 1.000 | 2.857 | 0.000 | 108.1 | +31.5 | monter | oui | oui | 0.000 | 3.4 |
| 4 | cooldownDays | 10 | 0 | 104 | 78.5 | +16.9 | baisser | oui | oui | 0 | 18.2 |
| 5 | athSellMax | 2.000 | 5.000 | 0.000 | 31.5 | +18.9 | monter | oui | oui | 0.000 | 3.1 |
| 6 | athRefDdSellPct | 30.00 | 100.0 | 1.000 | 26.6 | +20.3 | monter | oui | oui | 1.000 | 0.7 |
| 7 | blockBuyAfterSellUntilDown2 | true | true | false | 19.1 | +0.0 | en place | non | oui | false | 4.8 |
| 8 | cooldownAfterSellOn | true | false | true | 16.9 | +16.9 | → false | oui | oui | false | 1.4 |
| 9 | allowSellDuringCooldown | false | true | false | 15.4 | +15.4 | → true | oui | oui | false | 1.2 |
| 10 | atrMultDown2 | 1.000 | 2.857 | 0.000 | 14.5 | +10.5 | monter | oui | oui | 1.000 | 11.1 |
| 11 | moonTrailingStopPct | 9.000 | 0.000 | 9.000 | 13.0 | +13.0 | baisser | oui | oui | 42.86 | 7.7 |
| 12 | moonReservePct | 30.00 | 42.86 | 100.0 | 11.0 | +0.2 | monter | oui | oui | 0.000 | 10.9 |
| 13 | sellReentryMode | TRAILING_STOP | TRAILING_STOP | FIXED_DELAY | 9.6 | +0.0 | en place | non | oui | TRAILING_STOP | 1.2 |
| 14 | multX2 | 2.000 | 6.000 | 0.1000 | 7.9 | +5.4 | monter | oui | oui | 6.000 | 33.3 |
| 15 | athRefDdBuyPct | 60.00 | 1.000 | 43.43 | 7.7 | +7.3 | baisser | oui | oui | 1.000 | 36.4 |
| 16 | athBuyMin | 0.5000 | 3.571 | 0.000 | 7.4 | +6.4 | monter | oui | oui | 3.571 | 32.8 |
| 17 | multTriggered | 3.000 | 0.1000 | 6.000 | 5.5 | +2.7 | baisser | oui | oui | 6.000 | 10.9 |
| 18 | moonStopSellPct | 100.0 | 100.0 | 0.000 | 4.8 | +0.0 | en place | non | oui | 0.000 | 5.0 |
| 19 | trailingStopBuyPct | 3.000 | 3.000 | 0.000 | 4.3 | +0.0 | en place | non | oui | 0.000 | 2.0 |
| 20 | athOn | true | true | false | 4.3 | +0.0 | en place | non | oui | true | 4.0 |
| 21 | moonOn | true | false | true | 3.1 | +3.1 | → false | oui | oui | false | 6.7 |
| 22 | athBuyMax | 4.000 | 0.7143 | 5.000 | 1.2 | +0.9 | baisser | oui | oui | 5.000 | 13.1 |
| 23 | buyReentryMode | TRAILING_STOP | FIXED_DELAY | TRAILING_STOP | 1.2 | +1.2 | → FIXED_DELAY | oui | oui | IMMEDIATE | 5.9 |
| 24 | trailingStopSellPct | 5.000 | 5.000 | 0.000 | 0.8 | +0.0 | en place | non | oui | 5.000 | 4.7 |
| 25 | atrMultDown1 | 0.000 | 0.000 | 0.000 | 0.0 | +0.0 | en place | non | oui | 0.000 | 0.0 |
| 26 | atrMultUp1 | 0.000 | 0.000 | 0.000 | 0.0 | +0.0 | en place | non | oui | 0.000 | 0.0 |
| 27 | atrMultUp2 | 0.000 | 0.000 | 0.000 | 0.0 | +0.0 | en place | non | oui | 0.000 | 0.0 |
| 28 | fixedDelayDays | 15 | 15 | 15 | 0.0 | +0.0 | en place | non | oui | 15 | 0.0 |
| 29 | moonReserveRatchet | false | false | false | 0.0 | +0.0 | en place | non | oui | false | 0.0 |
| 30 | multX0_5 | 0.5000 | 0.5000 | 0.5000 | 0.0 | +0.0 | en place | non | oui | 0.5000 | 0.0 |
| 31 | multX1 | 1.000 | 1.000 | 1.000 | 0.0 | +0.0 | en place | non | oui | 1.000 | 0.0 |

## Bear seul (l'autre jeu = preset en place), classé par influence
| # | Paramètre | En place | Meilleure | Pire | Écart max−min $ | Meilleure − preset $ | Sens | Bat le preset ? | Bat le DCA ? | Gel : meilleure | Gel : écart $ |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | multX2 | 2.000 | 6.000 | 0.1000 | 144.9 | +98.2 | monter | oui | oui | 0.1000 | 145.3 |
| 2 | atrMultDown2 | 2.500 | 5.714 | 0.000 | 83.2 | +46.6 | monter | oui | oui | 8.571 | 92.7 |
| 3 | atrMultUp3 | 2.500 | 2.500 | 0.000 | 75.8 | +0.0 | en place | non | oui | 0.000 | 223.1 |
| 4 | athBuyMax | 4.000 | 5.000 | 0.7143 | 61.0 | +14.2 | monter | oui | oui | 0.7143 | 69.4 |
| 5 | sellFraction | 0.3000 | 0.7171 | 0.01000 | 56.1 | +30.0 | monter | oui | oui | 0.7171 | 161.5 |
| 6 | cooldownDays | 10 | 10 | 313 | 55.0 | +0.0 | en place | non | oui | 365 | 72.2 |
| 7 | athOn | true | true | false | 44.9 | +0.0 | en place | non | oui | false | 11.3 |
| 8 | athRefDdBuyPct | 60.00 | 15.14 | 100.0 | 44.4 | +24.5 | baisser | oui | oui | 100.0 | 50.5 |
| 9 | athSellMin | 1.000 | 3.571 | 0.000 | 43.9 | +30.0 | monter | oui | oui | 3.571 | 64.1 |
| 10 | athRefDdSellPct | 30.00 | 100.0 | 1.000 | 38.8 | +29.0 | monter | oui | oui | 100.0 | 103.5 |
| 11 | athBuyMin | 0.5000 | 3.571 | 0.000 | 22.2 | +19.1 | monter | oui | oui | 0.000 | 28.4 |
| 12 | multTriggered | 3.000 | 6.000 | 0.1000 | 21.5 | +10.9 | monter | oui | oui | 0.1000 | 38.3 |
| 13 | sellReentryMode | TRAILING_STOP | TRAILING_STOP | FIXED_DELAY | 19.5 | +0.0 | en place | non | oui | TRAILING_STOP | 30.5 |
| 14 | buyReentryMode | TRAILING_STOP | TRAILING_STOP | FIXED_DELAY | 18.1 | +0.0 | en place | non | oui | FIXED_DELAY | 29.0 |
| 15 | athSellMax | 4.000 | 5.000 | 0.000 | 16.4 | +3.3 | monter | oui | oui | 5.000 | 91.3 |
| 16 | trailingStopBuyPct | 3.000 | 0.000 | 14.29 | 10.0 | +5.6 | baisser | oui | oui | 14.29 | 19.8 |
| 17 | blockBuyAfterSellUntilDown2 | true | true | false | 3.1 | +0.0 | en place | non | oui | true | 40.4 |
| 18 | allowSellDuringCooldown | false | false | false | 0.0 | +0.0 | en place | non | oui | false | 0.0 |
| 19 | atrMultDown1 | 0.000 | 0.000 | 0.000 | 0.0 | +0.0 | en place | non | oui | 0.000 | 0.0 |
| 20 | atrMultUp1 | 0.000 | 0.000 | 0.000 | 0.0 | +0.0 | en place | non | oui | 0.000 | 0.0 |
| 21 | atrMultUp2 | 0.000 | 0.000 | 0.000 | 0.0 | +0.0 | en place | non | oui | 0.000 | 0.0 |
| 22 | cooldownAfterSellOn | true | true | true | 0.0 | +0.0 | en place | non | oui | true | 0.0 |
| 23 | fixedDelayDays | 15 | 15 | 15 | 0.0 | +0.0 | en place | non | oui | 15 | 0.0 |
| 24 | moonOn | true | true | true | 0.0 | +0.0 | en place | non | oui | true | 0.0 |
| 25 | moonReservePct | 30.00 | 30.00 | 30.00 | 0.0 | +0.0 | en place | non | oui | 30.00 | 0.0 |
| 26 | moonReserveRatchet | false | false | false | 0.0 | +0.0 | en place | non | oui | false | 0.0 |
| 27 | moonStopSellPct | 100.0 | 100.0 | 100.0 | 0.0 | +0.0 | en place | non | oui | 100.0 | 0.0 |
| 28 | moonTrailingStopPct | 9.000 | 9.000 | 9.000 | 0.0 | +0.0 | en place | non | oui | 57.14 | 32.1 |
| 29 | multX0_5 | 0.5000 | 0.5000 | 0.5000 | 0.0 | +0.0 | en place | non | oui | 0.5000 | 0.0 |
| 30 | multX1 | 1.000 | 1.000 | 1.000 | 0.0 | +0.0 | en place | non | oui | 1.000 | 0.0 |
| 31 | trailingStopSellPct | 5.000 | 5.000 | 5.000 | 0.0 | +0.0 | en place | non | oui | 5.000 | 12.3 |

## Lecture en 5 lignes
1. Le preset en place bat déjà le DCA fixe sur 24 mois (+59,8 $ / +2,4 % contre −181 $ / −7,3 %) avec une exposition moyenne de 7 % : le cash reste surtout inactif.
2. Bull : les réglages de **vente** pèsent le plus (`sellFraction` ↑, `athSellMin` ↑, `cooldownDays` ↓, `athRefDdSellPct` ↑, `athSellMax` ↑ : +17 à +42 $ chacun) ; mais l'historique complet inverse le classement de `sellFraction` (0,2 : +2567 $ ; 0,58 : +887 $) : un optimum 24 mois n'est pas un optimum de cycle.
3. Bear : `multX2` domine (écart 145 $, monotone : plus on achète en zone X2, plus l'exposition monte de 7 % à 16 % et plus le PnL réel monte) ; en lecture gel le même `multX2` ↑ est PIRE (−167 $ contre −69 $) : le gain vient du bull qui suit, c'est de l'exposition, pas du timing du Bear.
4. Réglages muets par construction : `atrMultDown1 / Up1 / Up2` valent 0 dans le preset (zones X1 et X0,5 vides ⇒ `multX1`, `multX0_5` sans effet), toute valeur plus haute viole l'ordre des bornes (24 combinaisons ignorées par jeu) ; `fixedDelayDays` (mode non utilisé) et la Moon côté Bear n'ont aucun effet sur le PnL réel.
5. Verdict : plusieurs réglages battent le preset en place ET le DCA en PnL réel, mais sur une seule fenêtre de 24 mois, parmi 442 valeurs testées (la barre de significativité monte avec ce nombre), pour des écarts de 1 à 6 points du capital : rien n'est validé hors échantillon, c'est le rôle de l'étape B.

## Traitement du gel (vérifié dans l'Expert)
Lecture gel = `SimulationEngine` type `RAINBOW` réutilisé tel quel (`RainbowAtrReplay.run`, barres hors régime via `RainbowAtrReplay.inactive`, régimes de `SimulationRegimes.resolve`). Écart connu : l'automate Moon est préchauffé avec le seul jeu testé sur tout l'historique (comportement de l'Expert), d'où des écarts Moon en gel absents du rejeu de cycle.
