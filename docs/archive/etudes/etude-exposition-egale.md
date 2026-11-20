---
statut: archivé
sujet: Mesure à exposition égale (étape B' du pivot « Rainbow d'abord ») — preset en place contre DCA fixe calé sur la même exposition, BTC et ETH
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: c1f27c68c5
---
# Étude — mesure à exposition égale (étape B', 2026-11-19)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15d-mesure-exposition-egale.md`](../../architecture/15d-mesure-exposition-egale.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), point de départ : [`etude-optimisation-par-blocs.md`](etude-optimisation-par-blocs.md) (§ « Rappel : DCA fixe sur l'historique complet »).

**Exposition** = valeur de la position / valeur totale du portefeuille, moyenne sur les jours de la plage (celle de l'étape A, même pour le DCA).

## Entrées (un seul run, 2026-11-19, une configuration)
- BTC (capital 2484 USDC) et ETH (2425 USDC), source OKX, empreintes de bougies identiques à l'étape B (BTC `5d2823e19f40598a`), fin 2026-11-17. Mêmes 4 blocs de 24 mois que l'étape B, tous rejouables pour les deux actifs (ETH rejoué sans code nouveau).
- Coûts simplifiés, modulation neutre, capital fixe sans apport, même cash commun et contrainte de cash que le preset (DCA tout-ou-rien, sans recharge).
- Règle de verdict fixée avant lecture (voir 15d) ; calibrage 0,5 pt, 20 décalages 0 → 60 j.
- Coût : estimation 620 rejeux × 25 ms = 0,26 min ; réel BTC 194 rejeux en 3,4 s, ETH 188 rejeux en 2,7 s (challenger B BTC compris, ~44 s au total dont démarrage Spring). Calibrage 17 à 20 rejeux par plage, tolérance tenue partout (écart ≤ 0,07 pt).
- Contrôle : bloc BTC récent = +2,41 % (preset) et −7,28 % (DCA habituel), comme l'étape A.

## BTC — gain en % du capital total (expo moyenne % ; drawdown %)
| Bloc | Preset | DCA égal | Bande DCA (P20 / P80, min–max) | DCA habituel | Challenger B | Δ | Bloc |
|---|---|---|---|---|---|---|---|
| 2024-11 → 2026-11 | +2,41 (6,7 ; −2,9) | −3,73 (6,7 ; −5,7) | −3,65 / −3,40 (−3,73 – −3,32) | −7,28 (13,3) | +1,96 (8,0) | +6,14 | OK |
| 2022-11 → 2024-11 | +30,75 (10,6 ; −3,5) | +20,36 (10,7 ; −5,5) | +16,73 / +19,41 (15,90 – 20,36) | +46,74 (22,3) | +35,40 (12,5) | +10,39 | OK |
| 2020-11 → 2022-11 | −10,72 (10,8 ; −19,0) | −11,26 (10,8 ; −18,5) | −11,23 / −10,95 (−11,26 – −10,74) | −14,49 (13,9) | −11,40 (12,4) | +0,55 | OK (de justesse) |
| 2018-11 → 2020-11 | +29,95 (13,5 ; −9,3) | +27,87 (13,6 ; −12,1) | +23,52 / +26,93 (22,36 – 27,87) | +43,91 (20,5) | +36,24 (16,1) | +2,07 | OK |
| Historique complet (hors verdict) | +103,34 (10,3 ; −13,5) | +15,03 (10,3 ; −15,3) | +14,31 / +14,84 | +340,12 (70,6) | +130,36 (11,9) | +88,30 | — |

Gain / capital engagé (preset vs DCA égal) : 35,7 vs −55,4 ; 289,9 vs 191,3 ; −99,2 vs −104,7 ; 221,6 vs 205,7 ; historique 999 vs 145. Preset : réalisé 707 / 1602 / 631 / 1531 $, latent 213 / 15 / 732 / 571 $, achats / ventes 186 / 11, 122 / 13, 239 / 14, 203 / 15 ; DCA égal : ~730 achats par bloc, montant 0,44 à 0,78 USDC par jour.

**Verdict BTC : `TIMING_UTILE`** — 4 blocs sur 4, médiane Δ gain +4,11 pt.

## ETH
| Bloc | Preset | DCA égal | Bande DCA (P20 / P80, min–max) | DCA habituel | Δ | Bloc |
|---|---|---|---|---|---|---|
| 2024-11 → 2026-11 | +13,59 (12,5 ; −10,2) | −7,22 (12,5 ; −15,5) | −7,02 / −6,38 (−7,22 – −6,18) | −8,30 (14,4) | +20,80 | OK |
| 2022-11 → 2024-11 | +16,05 (10,1 ; −4,8) | +7,27 (10,2 ; −10,0) | +5,78 / +6,87 (5,48 – 7,27) | +14,55 (19,5) | +8,78 | OK |
| 2020-11 → 2022-11 | +4,75 (7,2 ; −9,9) | −3,58 (7,2 ; −12,7) | −4,25 / −3,80 (−4,25 – −3,58) | −10,19 (19,6) | +8,33 | OK |
| 2018-11 → 2020-11 | +24,03 (10,7 ; −11,5) | +24,28 (10,8 ; −10,1) | +21,02 / +23,57 (20,36 – 24,28) | +47,73 (19,9) | −0,26 | échec : gain |
| Historique complet (hors verdict) | +84,04 (10,4 ; −14,8) | +9,31 (10,4 ; −18,7) | +9,06 / +9,26 | +294,01 (73,6) | +74,73 | — |

Pas de challenger de l'étape B pour ETH (réglé sur BTC seulement). **Verdict ETH : `TIMING_UTILE`** — 3 blocs sur 4, médiane Δ gain +8,55 pt ; l'échec est côté gain (bloc 2018-20, −0,26 pt, rapport gain/|DD| aussi inférieur).

## Lecture en 5 lignes
1. Timing : à exposition égale, le preset bat le DCA calé en gain sur 7 blocs sur 8 et sort de la bande de bruit sur 7 sur 8, sans jamais aggraver le drawdown de plus de 5 pts (pire : +0,5 pt BTC 2020-22, +1,5 pt ETH 2018-20) ; il le réduit nettement ailleurs (jusqu'à 5 pts).
2. Robustesse : l'avantage est net (+6, +10, +2 pt BTC ; +21, +9, +8 pt ETH) mais mince sur le bloc baissier BTC 2020-22 (+0,55 pt, preset à −10,72 contre un P80 de bande à −10,95) et nul sur ETH 2018-20 ; la bande est étroite (1 à 4 pt) car le DCA égal est un petit montant quotidien.
3. Prix : l'exposition moyenne n'est que de 7 à 14 % ; les gains restent modestes en % du capital total (−11 à +31 %).
4. Levier : à exposition libre, le DCA habituel (13 à 22 % d'exposition, 70 % sur l'historique) gagne plus sur les blocs haussiers et sur l'historique (+340 % / +294 % contre +103 % / +84 %) ; à exposition égale (10 %), le preset garde +88 / +75 pt d'avance sur l'historique complet. Le niveau de gain est donc fixé par l'exposition ; le preset apporte un écart de timing positif sur ce niveau.
5. Limites : « égal » = exposition moyenne égale, pas trajectoire égale (le DCA monte linéairement, le preset vend dans les hausses) ; un actif par ligne, 4 blocs par actif non indépendants (cycles) ; la bande mesure le seul bruit du point d'entrée, pas celui du cycle.

## Recommandation
Timing utile : le levier à piloter est la part engagée (L2 : exposition / taille d'achat) ; relancer le L2 avec cette mesure (gain à exposition égale et gain / capital engagé), sans remettre en cause le signal Rainbow.

## Écarts de périmètre
- ETH rejoué sans code nouveau ; challenger B uniquement pour BTC (recalculé par `BlockTuner` sur les 4 blocs, déterministe).
- Latent = position valorisée au dernier jour du bloc (l'étape A le prenait à la fin de la dernière phase Bull : 240,3 $ contre 213,0 $ ici ; PnL et achats / ventes identiques).
- Décalages : `round(i × 60 / 19)` jours (0, 3, 6, …, 60), même fin de plage ; le décalage 0 est le DCA calé lui-même. Le preset non décalé est comparé au P80 des gains (ou rapports gain/|DD|) du DCA décalé.
- Historique complet : mesuré, bande comprise, mais hors verdict (règle sur les blocs).
- Suite complète verte (1763 tests, 0 échec, 33 ignorés).
