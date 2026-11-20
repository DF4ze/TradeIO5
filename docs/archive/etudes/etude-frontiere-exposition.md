---
statut: archivé
sujet: Frontière exposition → gain / drawdown (étape C1 du pivot « Rainbow d'abord ») — preset en place à exposition croissante, BTC et ETH, niveau par profil
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 7e4dfcdb3a
---
# Étude — frontière exposition → gain / drawdown (étape C1, 2026-11-19)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15d-mesure-exposition-egale.md`](../../architecture/15d-mesure-exposition-egale.md) (§ Frontière C1), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), point de départ : [`etude-exposition-egale.md`](etude-exposition-egale.md) (B', verdict `TIMING_UTILE` à ~10 %). Constat de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../../decisions/fil-rouge-dca-automatisation.md) (« Constat étapes B / B' »).

**Question.** L'avantage de timing du preset Rainbow en place tient-il quand l'exposition monte, et quelle exposition tient le plafond de drawdown de chaque profil ? Aucun réglage du preset modifié, rien appliqué ni persisté, pas de Flyway, live inchangé.

**Exposition** = valeur de la position / valeur totale du portefeuille, moyenne sur les jours de la plage (celle de B', même pour le DCA).

## Protocole (un run, une grille, règle fixée avant lecture)
- **Levier unique** : multiplicateur du montant de base du lien (`linkBaseAmount`) ; preset, Trend, modulation inchangés. Grille ×0,5 / 1 / 1,5 / 2 / 3 / 4 / 6 / 8 / 12 / 16 (élargissement prévu mais non déclenché : tous les plafonds sont encadrés). ×1 = base du lien inchangée : mêmes chiffres que B' (testé ; contrôle runner : bloc BTC récent +2,41 %).
- Mêmes cash commun, contrainte de cash (tout-ou-rien, sans recharge), coûts simplifiés et capital fixe que B'. BTC (2484 USDC) et ETH (2425 USDC), source OKX, bougies identiques à B' (BTC `5d2823e19f40598a`), 4 blocs de 24 mois (fins 2020-11-17, 2022-11-17, 2024-11-17, 2026-11-17) + historique complet hors verdict.
- À chaque niveau et plage : DCA fixe calé sur la même exposition moyenne (calibrage B', tolérance 0,5 pt) et sa bande de bruit (20 décalages 0 → 60 j, P80). Calibrage tenu partout (aucun niveau inatteignable) : toutes les conclusions sont permises.
- Plafonds de drawdown lus dans `ModulationDefaults` : Prudent −15 %, Équilibré −25 %, Dynamique −40 %, Full −60 %. Niveau par profil = exposition maximale dont le **pire drawdown des 4 blocs** respecte le plafond, interpolée linéairement (en pire drawdown) entre les deux points de grille qui l'encadrent ; gain, Δ gain et Δ drawdown de chaque bloc interpolés avec le même poids.
- **Règle de verdict** (niveau de grille ou profil) : le preset bat le DCA égal en gain sur ≥ 3 blocs sur 4, médiane du Δ gain > 0, et aucun bloc où son drawdown est pire que celui du DCA égal de plus de 5 pts. Avantage persistant pour un actif si les 4 profils la satisfont. La bande de bruit est rapportée (colonne de B') mais, comme prescrit, hors règle.
- Saturation (règle fixée) : un niveau est saturé si l'exposition moyenne des blocs a gagné moins de 25 % de la croissance proportionnelle au multiplicateur depuis le niveau précédent.
- Coût : estimation 3 050 rejeux par actif (grille) ; réel 1 851 (BTC, 13,6 s) et 1 861 (ETH, 11,1 s) avec le pool de 4.

## Verdict (règle appliquée telle quelle)
| Actif | Verdict | Profils | Exposition max par profil (Prudent / Équilibré / Dynamique / Full) | Gain médian des blocs à ce niveau |
|---|---|---|---|---|
| BTC | `AVANTAGE_PERSISTANT` | 4/4, 4 blocs sur 4 à chaque profil | 8,2 / 13,8 / 23,1 / 43,5 % (× 0,78 / 1,36 / 2,41 / 5,57) | +12,6 / +22,0 / +39,0 / +87,7 % |
| ETH | `AVANTAGE_PERSISTANT` | 4/4, 3 blocs sur 4 à chaque profil sauf Full (4/4) | 13,5 / 22,5 / 33,9 / 58,6 % (× 1,37 / 2,45 / 3,98 / 13,27) | +20,3 / +36,2 / +47,5 / +61,7 % |

Détail dans les tableaux « niveau par profil » ci-dessous (Δ gain médian, pire Δ drawdown). Aucun point de grille ne viole la règle, pour aucun des deux actifs.

## Lecture
1. **Forme de la courbe** : proportionnelle jusqu'à ×3 (exposition ~27–28 %) : élasticité du gain 1,05–1,16, du drawdown 0,8–1,0 ; le gain croît un peu plus vite que le drawdown de ×3 à ×6 (BTC gain/|DD| 2,5 → 3,1) ; au-delà le gain plafonne puis recule, alors que le drawdown, lui, se plafonne aussi (BTC −66 %, élasticité DD 0,1–0,2 à partir de ×12).
2. **Saturation** : selon la règle fixée, à partir de ×16 (BTC) et ×12 (ETH) ; elle est visible plus tôt : l'exposition croît de moins en moins dès ×8 (BTC 52,7 → 60,6 → 62,7 %), le nombre d'achats tombe (bloc BTC récent 186 → 125, cash épuisé, achats tout-ou-rien refusés). Le gain médian est maximal à ×12 (BTC +121 %) et ×8 (ETH +70 %), puis baisse : au-delà, monter l'exposition coûte du gain.
3. **Avantage de timing aux profils** : persistant à l'exposition de chaque profil, pour les deux actifs (règle satisfaite) ; il grandit avec l'exposition en valeur absolue (Δ gain médian BTC +4 pt à ×1 → +44 pt à ×12) puis se tasse à la saturation. Le drawdown du preset est meilleur que celui du DCA égal sauf marginalement (pire écart −0,8 pt BTC, −1,8 pt ETH sur les blocs).
4. **Optimum gain / |drawdown|** : BTC plateau ×6–×12 (3,1–3,2), maximum ×12 (exposition ~61 %, drawdown −66 %, hors de tous les plafonds de profil) ; ETH ratio quasi plat 1,6–2,1 (maximum ×16, minimum local ×4 → ×6) : pas d'optimum net. Le ratio ne sert donc pas à choisir l'exposition ; c'est le plafond de drawdown qui la fixe.
5. **Fragilités (règle satisfaite mais à garder en tête)** : (a) ETH bloc 2018-20 : le DCA égal bat le preset de ×1 à ×8 (Δ −0,3 pt à ×1, −21,8 pt à ×6), le preset n'y repasse devant qu'à ×12 ; l'avantage ETH repose sur 3 blocs sur 4 ; hors bande de bruit manquée à ×4, ×6, ×8 ; (b) hors verdict, sur l'historique complet ETH le drawdown du preset est pire que celui du DCA égal de 6,6 à 15,4 pts dès ×3 (exposition ≥ 24 %), et BTC de −1,7 à −4,3 pts à ×12 et ×16 : l'avantage de drawdown n'est pas acquis sur le cycle complet aux expositions élevées ; (c) un seul cycle par bloc, « égal » = exposition moyenne égale, pas trajectoire égale.

## Recommandation pour C2
La frontière est lisible et le timing tient aux expositions de tous les profils : le niveau d'exposition est un levier réel (gain et drawdown suivent l'exposition quasi proportionnellement jusqu'à ~30 %), donc un pilotage de l'exposition dans le temps a de la marge **entre les profils** (de ~8 % à ~45–60 %) ; en revanche, au-delà de ~×8 la saturation du cash fait que monter le montant de base ne sert plus (le levier utile y est la bande / le biais, non balayés ici). C2 : politique d'exposition variable comparée à une exposition **constante de même moyenne**, en conservant ETH et la lecture historique complet pour surveiller le drawdown aux expositions élevées.

## Écarts de périmètre
- Levier unique (montant de base) : ni bande d'exposition ni biais balayés, par prescription ; limite à dire au bilan.
- « Base de référence 1,00 » = unité du montant de base du lien (les facteurs de zone / ATH la multiplient) ; ×1 = lien inchangé.
- Interpolations : linéaires entre points de grille (profils : en pire drawdown) ; le gain médian d'un profil est la médiane des gains de blocs interpolés ; aucune extrapolation.
- Saturation décrite par la règle fixée (25 %) ET par le constat qualitatif (nombre d'achats, croissance de l'exposition) : la règle seule ne la signale qu'à ×16 / ×12.
- Rapport complet (markdown, JSON avec bandes de bruit) : `target/exposure-frontier.md|json` (régénérable, empreinte des bougies identique).

## Résultats (synthèses ; tableaux par bloc : [`etude-frontiere-exposition-tableaux.md`](etude-frontiere-exposition-tableaux.md))
## BTC — verdict : AVANTAGE_PERSISTANT
### BTC — synthèse par niveau (médianes sur les 4 blocs)
| × | Expo moy. % | Gain médian % | Pire DD % | Gain/DD méd. | Blocs gagnés | Méd. Δ gain | Pire Δ DD | Règle | Saturé | Él. gain | Él. DD |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0,5 | 5,4 | 8,1 | -9,9 | 1,91 | 4/4 | 2,0 | -0,1 | OK | non | n/d | n/d |
| 1 | 10,4 | 16,2 | -19,0 | 2,04 | 4/4 | 4,1 | -0,5 | OK | non | 1,05 | 0,99 |
| 1,5 | 15,2 | 24,3 | -27,4 | 2,16 | 4/4 | 6,6 | -0,7 | OK | non | 1,08 | 0,98 |
| 2 | 19,7 | 32,4 | -35,2 | 2,28 | 4/4 | 9,4 | -0,8 | OK | non | 1,10 | 0,96 |
| 3 | 27,9 | 48,5 | -46,9 | 2,51 | 4/4 | 15,1 | -0,3 | OK | non | 1,16 | 0,82 |
| 4 | 34,9 | 64,7 | -53,5 | 2,75 | 4/4 | 21,6 | 1,0 | OK | non | 1,29 | 0,60 |
| 6 | 45,8 | 94,0 | -61,8 | 3,14 | 4/4 | 34,7 | 0,9 | OK | non | 1,38 | 0,53 |
| 8 | 52,7 | 105,6 | -65,3 | 3,12 | 4/4 | 38,1 | 1,4 | OK | non | 0,82 | 0,39 |
| 12 | 60,6 | 121,0 | -66,2 | 3,19 | 4/4 | 43,9 | 3,0 | OK | non | 0,98 | 0,10 |
| 16 | 62,7 | 113,7 | -66,7 | 2,85 | 4/4 | 37,4 | 2,8 | OK | oui | -1,88 | 0,22 |

### BTC — niveau par profil (plafonds lus dans `ModulationDefaults`, interpolation linéaire entre points de grille)
| Profil | Plafond DD % | × (interp.) | Expo max % | Gain médian % | Blocs gagnés | Méd. Δ gain | Pire Δ DD | Verdict |
|---|---|---|---|---|---|---|---|---|
| PRUDENT | -15 | 0,78 | 8,2 | 12,6 | 4/4 | 3,2 | -0,3 | AVANTAGE_PERSISTANT |
| BALANCED | -25 | 1,36 | 13,8 | 22,0 | 4/4 | 5,9 | -0,6 | AVANTAGE_PERSISTANT |
| DYNAMIC | -40 | 2,41 | 23,1 | 39,0 | 4/4 | 11,7 | -0,6 | AVANTAGE_PERSISTANT |
| FULL | -60 | 5,57 | 43,5 | 87,7 | 4/4 | 31,9 | 0,9 | AVANTAGE_PERSISTANT |


## ETH — verdict : AVANTAGE_PERSISTANT
### ETH — synthèse par niveau (médianes sur les 4 blocs)
| × | Expo moy. % | Gain médian % | Pire DD % | Gain/DD méd. | Blocs gagnés | Méd. Δ gain | Pire Δ DD | Règle | Saturé | Él. gain | Él. DD |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0,5 | 5,3 | 7,4 | -6,1 | 1,64 | 4/4 | 4,3 | -0,8 | OK | non | n/d | n/d |
| 1 | 10,1 | 14,8 | -11,5 | 1,71 | 3/4 | 8,6 | -1,5 | OK | non | 1,06 | 0,96 |
| 1,5 | 14,7 | 22,2 | -16,3 | 1,77 | 3/4 | 12,8 | -1,8 | OK | non | 1,09 | 0,93 |
| 2 | 19,0 | 29,6 | -20,5 | 1,84 | 3/4 | 17,1 | -1,7 | OK | non | 1,12 | 0,91 |
| 3 | 26,9 | 44,4 | -30,6 | 1,96 | 3/4 | 25,8 | -1,1 | OK | non | 1,16 | 1,14 |
| 4 | 34,1 | 47,6 | -40,2 | 1,80 | 3/4 | 34,5 | 0,2 | OK | non | 0,28 | 1,16 |
| 6 | 45,1 | 61,5 | -48,4 | 1,67 | 3/4 | 40,1 | 0,1 | OK | non | 0,92 | 0,66 |
| 8 | 51,6 | 70,0 | -52,5 | 1,85 | 3/4 | 36,5 | 2,4 | OK | non | 0,97 | 0,61 |
| 12 | 57,7 | 63,2 | -59,2 | 1,89 | 4/4 | 18,3 | -1,3 | OK | oui | -0,90 | 1,08 |
| 16 | 60,4 | 58,4 | -61,7 | 2,11 | 4/4 | 21,6 | -1,3 | OK | oui | -1,69 | 0,89 |

### ETH — niveau par profil (plafonds lus dans `ModulationDefaults`, interpolation linéaire entre points de grille)
| Profil | Plafond DD % | × (interp.) | Expo max % | Gain médian % | Blocs gagnés | Méd. Δ gain | Pire Δ DD | Verdict |
|---|---|---|---|---|---|---|---|---|
| PRUDENT | -15 | 1,37 | 13,5 | 20,2 | 3/4 | 11,7 | -1,7 | AVANTAGE_PERSISTANT |
| BALANCED | -25 | 2,45 | 22,5 | 36,2 | 3/4 | 21,0 | -1,4 | AVANTAGE_PERSISTANT |
| DYNAMIC | -40 | 3,98 | 33,9 | 47,5 | 3/4 | 34,3 | 0,2 | AVANTAGE_PERSISTANT |
| FULL | -60 | 13,27 | 58,6 | 61,7 | 4/4 | 17,2 | -1,3 | AVANTAGE_PERSISTANT |
