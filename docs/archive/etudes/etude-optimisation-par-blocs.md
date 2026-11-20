---
statut: archivé
sujet: Optimisation par blocs BTC (étape B du pivot « Rainbow d'abord ») — descente de coordonnée Bull / Bear, 4 blocs de 24 mois, 4 rotations, challenger
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 1a44fea6d1
---
# Étude — optimisation par blocs BTC (étape B, 2026-11-18)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15c-optimisation-par-blocs.md`](../../architecture/15c-optimisation-par-blocs.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), protocole tranché dans [`../decisions/fil-rouge-dca-automatisation.md`](../../decisions/fil-rouge-dca-automatisation.md) (« Étape B, protocole »), point de départ : [`etude-sensibilite-rainbow.md`](etude-sensibilite-rainbow.md).

## Entrées (un seul run, 2026-11-18, reproductible)
- Actif BTC (source OKX), wallet 1, lien 1, capital 2484 USDC. Empreinte des bougies `5d2823e19f40598a` (3233 bougies, 2018-01-11 → 2026-11-17), la même qu'à l'étape A.
- 4 blocs de 24 mois contigus, sans chevauchement, terminés le 2026-11-17 / 2024-11-17 / 2022-11-17 / 2020-11-17 (début = lendemain de la fin du bloc précédent : 2024-11-18, 2022-11-18, 2020-11-18, 2018-11-18). Les 4 sont rejouables, aucun écarté.
- Configuration : 3 passes max, gain moyen minimal 0,25 pt de capital sur les blocs de réglage, plateau ≥ 90 % du meilleur, EXPOSURE_GUARD ×1,25, affinage 9 points, élargissement des plafonds de balayage par doublements jusqu'à 100 au plus.
- Réglages balayés : Bull 14 (`sellFraction`, `athSellMin`, `athSellMax`, `athRefDdSellPct`, `cooldownDays`, `cooldownAfterSellOn`, `allowSellDuringCooldown`, `trailingStopSellPct`, `atrMultUp3`, Moon : `moonOn`, `moonReservePct`, `moonReserveRatchet`, `moonTrailingStopPct`, `moonStopSellPct`), Bear 7 (`sellFraction`, `athSellMin`, `athSellMax`, `athRefDdSellPct`, `atrMultDown2`, `athRefDdBuyPct`, `trailingStopBuyPct`). Ignorés (taille d'achat = exposition = L2) : `multX2`, `multTriggered`, `athBuyMin`, `athBuyMax`. Muets, non touchés : `atrMultDown1 / Up1 / Up2`, `multX1`, `multX0_5`, `fixedDelayDays`, Moon Bear.

## Coût
- Estimation avant lancement (rejeux au pire × 25 ms) : 23 686 rejeux × 25 ms = 9,9 min en série, sous le seuil de 10 min : le run est parti sans arrêt.
- Mesuré : 12 549 rejeux, 46 s au total avec le pool dédié de 4 threads (le pire cas n'est pas atteint : arrêt anticipé des passes, 13 passes au total sur 5 réglages).

## Mesure
Comme à l'étape A : PnL réel du cycle (valeur finale, les deux jeux actifs, capital fixe sans apport, coûts simplifiés, modulation neutre, pas de plafond ATH), en % du capital. Contrôle : le bloc le plus récent redonne le comparateur de l'étape A à l'identique (+2,41 %, réalisé 707,1 $, latent 240,3 $, 186 achats / 11 ventes, exposition 6,7 %, DCA fixe −7,28 %).

## Rotations (réglage sur 3 blocs, test sur le 4ᵉ)
| Test | Plage de test | Réglages retenus (avant → après) | Preset en place % | Réglé % | DCA fixe % | Δ vs preset | Δ vs DCA | Expo réglé / preset % | Drawdown réglé / preset % | Borne expo |
|---|---|---|---|---|---|---|---|---|---|---|
| bloc 0 | 2024-11-18 → 2026-11-17 | Bull `sellFraction` 0,2 → 0,152 ; `athSellMax` 2 → 1,31 ; `atrMultUp3` 2,5 → 2,89 | +2,41 | +1,01 | −7,28 | **−1,40** | +8,29 | 7,3 / 6,7 | −4,1 / −2,9 | oui |
| bloc 1 | 2022-11-18 → 2024-11-17 | Bull `athSellMax` 2 → 0,46 ; `athRefDdSellPct` 30 → 46,3 ; `moonReservePct` 30 → 65 ; Bear `sellFraction` 0,3 → 0,081 ; `athRefDdBuyPct` 60 → 51,2 | +30,75 | +34,86 | +46,74 | +4,11 | **−11,87** | 13,0 / 10,6 | −4,2 / −3,5 | oui |
| bloc 2 | 2020-11-18 → 2022-11-17 | Bull `sellFraction` 0,2 → 0,152 ; `athRefDdSellPct` 30 → 17,9 | −10,72 | −11,64 | −14,49 | **−0,93** | +2,85 | 11,5 / 10,8 | −20,1 / −19,0 | oui |
| bloc 3 | 2018-11-18 → 2020-11-17 | Bull `athSellMax` 2 → 0,73 ; `cooldownDays` 10 → 42 ; `moonReservePct` 30 → 58 ; `moonTrailingStopPct` 9 → 2,7 ; Bear `sellFraction` 0,3 → 0,166 ; `athSellMin` 1 → 0,51 ; `atrMultDown2` 2,5 → 10,4 ; `athRefDdBuyPct` 60 → 28,4 | +29,95 | +57,59 | +43,91 | +27,64 | +13,68 | **32,1 / 13,5** | −26,4 / −9,3 | **non** |

Agrégat : bat le preset 2/4, bat le DCA fixe 3/4, les deux **avec exposition dans la borne 0/4**. Médiane Δ vs preset +1,59 pt, vs DCA fixe +5,57 pt ; moyennes +7,36 / +3,24 pt (tirées par la seule rotation 4).

## Verdict : NUL (non adoptable)
- KO : rotations gagnantes (bat preset ET DCA fixe, exposition dans la borne) 0/4, 3 requises.
- OK : médiane Δ vs preset +1,59 pt, médiane Δ vs DCA fixe +5,57 pt.
- KO : exposition ≤ 1,25 × celle du preset sur tous les blocs de test (rotation 4 : 32,1 % contre 13,5 %).

## Ce qui s'inverse d'un bloc à l'autre
| Réglage | Par rotation |
|---|---|
| Bull `athRefDdSellPct` | sens opposé : 30 → 46,3 (bloc 1) ; 30 → 17,9 (bloc 2) ; inchangé ailleurs |
| Bull `sellFraction` | baisse à 0,152 dans 2/4 rotations seulement (blocs 0, 2) ; l'étape A le montait (meilleur 24 mois 0,58) |
| Bull `athSellMax` | baisse dans 3/4 rotations, mais à des niveaux très différents (1,31 ; 0,46 ; 0,73) |
| Bull `moonReservePct` | monte dans 2/4 (65 ; 58) |
| Bear `sellFraction` | baisse dans 2/4 (0,081 ; 0,166) |
| Bear `athRefDdBuyPct` | baisse dans 2/4 (51 ; 28) |
| Bull `cooldownDays`, `atrMultUp3`, `moonTrailingStopPct` ; Bear `athSellMin`, `atrMultDown2` | retenus dans 1/4 rotation seulement |

Aucun réglage n'est retenu avec la même valeur dans plus de 2 rotations. `cooldownDays` : le meilleur, dans 3 rotations, est un cooldown de 200 à 350 jours rejeté par le garde-fou d'exposition (gain +1,1 à +3,9 pt, sur 1 bloc de réglage sur 3).

## Challenger unique (réglé sur les 4 blocs, NON TESTÉ)
| Jeu | Paramètre | Avant → après |
|---|---|---|
| Bull | `athSellMax` | 2,0 → 0,896 |
| Bear | `athSellMin` | 1,0 → 0,357 |
| Bear | `trailingStopBuyPct` | 3,0 → 0,375 |

Dans l'échantillon (blocs de réglage, à ne pas lire comme une preuve) : Δ vs preset −0,45 / +4,65 / −0,69 / +6,30 pt, exposition 8,0 / 12,5 / 12,4 / 16,0 %. Les 3 réglages sont ceux qui sont retenus le moins souvent d'une rotation à l'autre : le challenger n'a pas de crédibilité propre.
Attention : `trailingStopBuyPct` est divisé par deux à chaque passe (3 → 1,5 → 0,75 → 0,375) parce que le plateau, collé au bord 0, se recentre à chaque tour ; c'est une dérive de la méthode contre un bord, pas un optimum.
Export au format de l'Expert (`RainbowPresetRequest`) : `target/tune-challenger-bull.json` (jeu Bull = Rainbow UP) et `target/tune-challenger-bear.json` (jeu Bear = Rainbow DOWN).

## Multiplicité
21 réglages, 4 rotations + 1 challenger, 13 passes, 3 880 valeurs testées, 12 549 rejeux, 0 valeur ignorée (domaine), 2 344 rejets EXPOSURE_GUARD (bloc par bloc). Un seul run, une seule configuration : aucune variante relancée.

## Rappel : DCA fixe sur l'historique complet (lecture seule, contient les blocs de réglage)
| Ligne | PnL % capital | Expo moy. % | Drawdown % |
|---|---|---|---|
| Preset en place | +103,3 | 10,3 | −13,5 |
| Challenger | +130,4 | 11,9 | −14,0 |
| DCA fixe | **+340,1** | — | — |

Le challenger améliore le preset de 27 points dans l'échantillon, et reste à 210 points du DCA fixe : l'écart structurel du preset au DCA sur l'historique complet (exposition de 10 %) n'est pas touché par des réglages de vente.

## Lecture en 5 lignes
1. Aucun réglage ne tient d'un bloc à l'autre : le plus fréquent (`athSellMax` ↓, 3/4) change de niveau à chaque rotation, `athRefDdSellPct` s'inverse, `sellFraction` ↓ n'est retenu que 2 fois sur 4 (l'étape A le montait).
2. Sur le bloc de test, le signe du Δ vs preset suit le cycle, pas le réglage : −1,4 et −0,9 pt sur les blocs 2024-26 et 2020-22, +4,1 et +27,6 pt sur 2022-24 et 2018-20.
3. Le seul gain net (+27,6 pt, bloc 2018-20) vient d'une exposition de 32 % contre 13,5 % : c'est de l'exposition (L2), hors borne, pas du timing de vente.
4. Le DCA fixe est battu 3 fois sur 4 mais gagne sur le bloc 2022-24 (haussier) : battre le DCA dépend du cycle autant que des réglages.
5. Non validé : tout réglage. Le challenger (3 réglages, non testé) n'est pas adoptable ; le preset en place n'est pas démontré mauvais (+2,4 % contre −7,3 % pour le DCA sur 24 mois), ni démontré optimal.

## Ce que le résultat nul implique
Ce qui est établi : à ces 4 blocs et avec cette mesure, les réglages de vente ne donnent pas de gain stable hors échantillon. Trois explications restent ouvertes, que ce run ne départage pas : le preset en place est déjà bon pour ce qui est réglable par la vente ; la mesure (un seul actif, un cycle par bloc, 3 blocs de réglage) est trop bruitée pour séparer un effet de 0,3 à 1 point de capital ; les cycles sont trop différents entre eux pour qu'un jeu unique les serve tous (la bascule de signe de Δ selon le bloc va dans ce sens). Le levier qui fait bouger le PnL est l'exposition (bloc 3), qui relève du L2.

## Écarts de périmètre
- Blocs : début = lendemain de la fin du précédent (2024-11-18 et non 2024-11-17) pour garantir l'absence de chevauchement ; les chiffres du bloc récent sont identiques à l'étape A.
- Élargissement : l'Expert ne borne pas le haut des facteurs ATH ni des bornes ATR, le « maximum autorisé » n'existe donc pas ; doublements successifs jusqu'à 100 au plus (3 doublements). Atteint : Bear `athSellMax` à 40 (×8), toujours collé au bord (signalé, non conclu).
- Garde-fou plus strict que demandé : un réglage n'est retenu que s'il le respecte sur TOUS les blocs de réglage ; un bloc rejeté compte comme le réglage courant dans le score. Il ne s'applique pas au bloc de test : c'est là qu'il est mesuré (rotation 4).
- Plateau = plage contiguë autour du meilleur (un trou coupe), re-mesurée avant d'être retenue.
- Seuil de gain minimal 0,25 pt de capital (configuration) : choisi a priori, non ajusté.
- Tests : suite complète verte (1756 tests, 0 échec, 32 ignorés) (voir bilan en fin de session).
