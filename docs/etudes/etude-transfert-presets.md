---
statut: journal
sujet: Résultats D1a — transfert des presets BTC / ETH / PAXG sur 11 actifs, verdict PRESET_GENERIQUE (BTC et ETH tous deux GENERIQUE, aucun choisi)
modifié_le: 2026-11-23
empreinte: 1446502fb3
---
# D1a — un preset existant transfère-t-il sur un actif jamais vu ? (2026-11-23)

Mécanisme, règles et runner : [`../architecture/15l-transfert-presets.md`](../architecture/15l-transfert-presets.md). Tableaux complets (blocs, empreintes, dérive) : [`etude-transfert-presets-tableaux.md`](etude-transfert-presets-tableaux.md). Aucun réglage, rien appliqué ni persisté.

## Verdict (règles fixées avant lecture)
**`PRESET_GENERIQUE`, avec deux presets GENERIQUE : BTC et ETH. Aucun n'est choisi a posteriori.**

| Règle | Résultat |
|---|---|
| 2 — BTC | `TRANSFERE` sur 9 / 10 actifs hors échantillon conclus (90 %) : `GENERIQUE` |
| 2 — ETH | 8 / 10 (80 %) : `GENERIQUE` |
| 2 — PAXG | 6 / 11 (55 %) : non |
| 3 — choix par volatilité | les 9 nouveaux actifs ont l'ATR % médian le plus proche de celui d'ETH (5,3 à 7,7 contre ETH 5,80, BTC 4,25, PAXG 1,66) ; ETH `TRANSFERE` sur 7 / 9 (78 %, seuil atteint) mais bat la moyenne des deux autres sur 4 / 9 (44 %, seuil 60 %) : **ne tient pas** |

Δ médian poolé (blocs hors échantillon) : BTC +1,95, ETH +1,61, PAXG +2,61 pts de capital (PAXG, non GENERIQUE, a le meilleur Δ poolé mais le plus faible taux de réussite).

## Preset × actif : Δ médian en pts de capital (blocs gagnés / valides), TRANSFERE
`[é]` = dans l'échantillon, exclu du verdict.

| Actif | BTC | ETH | PAXG |
|---|---|---|---|
| BTC | [é] +0,39 (3/4) oui | +1,54 (4/4) oui | +3,03 (4/4) oui |
| ETH | +0,94 (3/4) oui | [é] +1,87 (3/4) oui | +1,40 (2/4) non |
| PAXG | provisoire | provisoire | [é] provisoire |
| BNB | +1,45 (3/4) oui | +1,12 (4/4) oui | +2,18 (3/4) oui |
| SOL | +6,08 (2/2) oui | +2,32 (2/2) oui | +2,50 (2/2) oui |
| XRP | +0,88 (2/4) non | +1,53 (2/4) non | +5,80 (3/4) oui |
| ADA | +1,24 (3/4) oui | −1,68 (1/4) non | −1,69 (2/4) non |
| LINK | +3,00 (3/3) oui | +1,32 (3/3) oui | +3,96 (3/3) oui |
| LTC | +1,44 (4/4) oui | +1,55 (4/4) oui | +0,99 (2/4) non |
| AVAX | +2,25 (2/2) oui | +2,35 (2/2) oui | +1,77 (1/2) non |
| DOT | +3,01 (2/2) oui | +2,83 (2/2) oui | +1,89 (1/2) non |
| DOGE | +2,03 (2/3) oui | +4,11 (2/3) oui | +4,25 (2/3) oui |

Exposition moyenne du preset par bloc : 1,8 à 19,3 % (médiane 5,4 %) : pas de saturation du cash.

## Actifs « provisoire » (hors verdict)
PAXG (3 paires) : 761 bougies D1 en base (Kraken, depuis 2024-10-23), aucun bloc de 24 mois rejouable après préchauffage. Rapporté sans verdict ; jamais exclu ni bloquant. SOL, AVAX, DOT : seulement 2 blocs (premier jour 2020-08 / 09) ; LINK, DOGE : 3 blocs ; conclus (≥ 2), mais fragiles.

## A posteriori / dans l'échantillon (hors verdict)
Meilleur preset par actif (Δ médian) : PAXG pour BTC, BNB, XRP, LINK, DOGE ; BTC pour SOL, ADA, DOT ; ETH pour LTC, AVAX (et pour ETH, dans l'échantillon). Le classement varie d'un actif à l'autre : il n'est pas stable, ce qui plaide contre un choix fin.

## Lecture
- **Le preset en place bat presque toujours le DCA à exposition égale hors échantillon**, mais de peu : Δ médians de +1 à +3 pts de capital pour une exposition de ~5 % (BTC / ETH : +4 / +8,5 pts dans les études B' avec modulation ATH ; ici preset pur). Le signe est robuste entre presets : BTC, ETH et PAXG réussissent chacun sur la majorité des actifs ; la différence entre eux est faible devant le bruit.
- **Aucune règle de choix ne se démarque** : la règle 3 (volatilité) sélectionne ETH partout mais n'est pas meilleure que les deux autres (44 %). Si on doit livrer un preset de départ unique en D1b, BTC et ETH sont équivalents à ce stade ; trancher entre eux est une décision, pas un résultat.
- **Dérive** : le preset BTC et le preset ETH joués sur leur propre actif reconstruisent exactement le chemin E1 sur le dernier bloc (0,29 % et 0,44 %, écart 0,00) ; PAXG non comparable (historique < 24 mois).

## Limites
Cryptos fortement corrélées (nombre d'actifs indépendants bien inférieur à 12 : « 90 % de 10 » compte beaucoup moins que 10 confirmations) ; biais de survie (actifs encore listés) ; peu de blocs par actif (2 à 4, non indépendants, bande de bruit nulle sur plusieurs blocs) ; quasi un seul grand bear (2021-22) ; bougies Binance USDT pour les 9 nouveaux contre USDC des presets et sources de rejeu différentes pour BTC / ETH (OKX) et PAXG (Kraken) ; coûts simplifiés du simulateur ; PAXG non conclu.

## Multiplicité
Une configuration, **36 paires** fixées d'avance (33 hors échantillon, 3 dans l'échantillon exclues), **108 blocs valides** (hors historique complet rapporté), 20 décalages de départ par plage (blocs et historique complet), **5 412 rejeux**, 3 règles × seuils figés avant lecture. Un premier run avec le montant de base de l'onboarding a été **écarté** après lecture de ses chiffres, sur le bilan de dérive (ETH reconstruit −35 % contre +0,44 % par le chemin E1 : cash saturé) ; la base a été remplacée par le taux de base du lien source, sans toucher aux règles ni aux seuils ; le run rapporté est le second.

## Bilan de dérive et tests
Moteur de rejeu, presets, live et parité : inchangés. Tests : `TransferStudyTest` 8 / 8, plus régression `SwitchRobustnessTest`, `SwitchCutTest`, `ExposureProductionTest`, `ExposureParityTest` (résultats en fin de session).
