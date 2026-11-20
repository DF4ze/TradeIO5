---
statut: archivé
sujet: Étape C2 du pivot « Rainbow d'abord » — politique d'exposition par régime contre exposition constante, à plafond de drawdown égal (BTC, ETH, 4 profils) : verdict NUL
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 6a8fc6a369
---
# Étude — politique d'exposition vs exposition constante (étape C2, 2026-11-19)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15f-politique-exposition-regime.md`](../../architecture/15f-politique-exposition-regime.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), point de départ : [`etude-frontiere-exposition.md`](etude-frontiere-exposition.md) (C1) et « Constat C1 » de [`../decisions/fil-rouge-dca-automatisation.md`](../../decisions/fil-rouge-dca-automatisation.md). Tableaux par rotation : [`etude-politique-exposition-tableaux.md`](etude-politique-exposition-tableaux.md).

**Question.** À plafond de drawdown égal (celui de chaque profil), une politique d'exposition par régime gagne-t-elle plus que l'exposition constante maximale (frontière C1) ? Aucun réglage du preset Rainbow modifié, rien appliqué ni persisté, pas de Flyway, live inchangé.

## Protocole (un run, une grille, règle fixée avant lecture)
- **Constante** : montant de base seul (multiplicateur de C1), bande neutre de C1. Réglage : plus grand multiplicateur dont le pire drawdown des blocs de réglage respecte le plafond, interpolé comme en C1 (grille C1 + ×0,125 / 0,25 / 24 / 32 / 48 / 64 ajoutés d'office : un réglage sur 3 blocs peut ne pas encadrer un plafond que 4 blocs encadrent).
- **Politique `REGIME_BAND`**, 3 paramètres : borne haute d'exposition en Bull {20, 30, 45, 65, 100 %}, en Bear (mêmes valeurs), multiplicateur (grille C1 ×0,5 … ×16) = 5 × 5 × 10 = 250 combinaisons. Bornes basses et reste = réglages de base de C1. Réglage : gain médian maximal sur les blocs de réglage sous « pire drawdown ≥ plafond ».
- 4 blocs de 24 mois (fins 2020-11-17, 2022-11-17, 2024-11-17, 2026-11-17), réglage sur 3, test sur le 4ᵉ, 4 rotations ; BTC et ETH ; plafonds lus dans `ModulationDefaults` (−15 / −25 / −40 / −60 %). Mêmes capital (BTC 2484, ETH 2425 USDC), cash commun, coûts et bougies que C1 (BTC `5d2823e19f40598a`, ETH `26a9c34ef1ec48c7`). Contrôle du runner : constante ×1 sur le bloc BTC récent = +2,41 % (C1).
- **Règle de verdict** : `POLITIQUE_UTILE` si la politique bat la constante en gain dans ≥ 3 rotations sur 4, médiane Δ > 0, plafond au moins aussi bien tenu (aucune rotation où la politique dépasse le plafond et la constante non). Sinon `NUL`. Stabilité des réglages (mêmes bornes dans ≥ 3 rotations) rapportée, hors règle.
- **Multiplicité** : par actif 16 multiplicateurs (constante) + 250 combinaisons (politique), 4 rotations × 4 profils + 4 historiques = 5 320 sélections sur 1 088 rejeux (cache : un rejeu par combinaison et bloc) ; 2 176 rejeux au total, 29 s (pool de 4) ; estimation avant run 2 716 rejeux au pire (1,13 min). Un seul run, aucune variante.

## Verdict
### Synthèse profil × actif (règle appliquée telle quelle)
| Actif | Profil | Plafond DD % | Verdict | Rotations gagnées | Médiane Δ gain (pt) | Plafond (blocs de test dépassés : politique / constante) | Réglages stables (bornes modales) | Raison |
|---|---|---|---|---|---|---|---|---|
| BTC | PRUDENT | -15.00 | NUL | 1/4 | -4.54 | 1 / 1 | oui (Bull≤20/Bear≤20 3/4) | gain (1/4 rotations gagnées, médiane Δ -4.54 pt) |
| BTC | BALANCED | -25.00 | NUL | 2/4 | 0.59 | 1 / 1 | NON (Bull≤20/Bear≤30 2/4) | gain (2/4 rotations gagnées, médiane Δ 0.59 pt) ; instabilité des réglages (bornes modales Bull≤20/Bear≤30 : 2/4) |
| BTC | DYNAMIC | -40.00 | NUL | 2/4 | 0.02 | 1 / 1 | NON (Bull≤20/Bear≤45 2/4) | gain (2/4 rotations gagnées, médiane Δ 0.02 pt) ; instabilité des réglages (bornes modales Bull≤20/Bear≤45 : 2/4) |
| BTC | FULL | -60.00 | NUL | 0/4 | -26.53 | 1 / 1 | NON (Bull≤20/Bear≤65 2/4) | gain (0/4 rotations gagnées, médiane Δ -26.53 pt) ; instabilité des réglages (bornes modales Bull≤20/Bear≤65 : 2/4) |
| ETH | PRUDENT | -15.00 | NUL | 1/4 | -6.95 | 1 / 1 (politique pire) | NON (Bull≤45/Bear≤45 2/4) | gain (1/4 rotations gagnées, médiane Δ -6.95 pt) ; plafond (la politique dépasse le plafond là où la constante le tient) ; instabilité des réglages (bornes modales Bull≤45/Bear≤45 : 2/4) |
| ETH | BALANCED | -25.00 | NUL | 1/4 | -9.00 | 1 / 1 | NON (Bull≤100/Bear≤100 2/4) | gain (1/4 rotations gagnées, médiane Δ -9.00 pt) ; instabilité des réglages (bornes modales Bull≤100/Bear≤100 : 2/4) |
| ETH | DYNAMIC | -40.00 | NUL | 1/4 | -15.07 | 2 / 1 (politique pire) | oui (Bull≤100/Bear≤100 3/4) | gain (1/4 rotations gagnées, médiane Δ -15.07 pt) ; plafond (la politique dépasse le plafond là où la constante le tient) |
| ETH | FULL | -60.00 | NUL | 1/4 | -33.23 | 0 / 1 | NON (Bull≤100/Bear≤100 2/4) | gain (1/4 rotations gagnées, médiane Δ -33.23 pt) ; instabilité des réglages (bornes modales Bull≤100/Bear≤100 : 2/4) |

**8 profils × actifs sur 8 : `NUL`.** Raison : gain dans les 8 cas ; plafond en plus pour ETH Prudent et Dynamique ; instabilité des réglages en plus dans 6 cas sur 8 (seuls BTC Prudent et ETH Dynamique reviennent aux mêmes bornes 3 rotations sur 4).

## Lecture
1. **À plafond égal, la politique n'apporte pas de gain** : elle bat la constante dans 0 à 2 rotations sur 4 (BTC 1 / 2 / 2 / 0, ETH 1 / 1 / 1 / 1 pour Prudent → Full) ; les seules médianes Δ > 0 sont BTC Équilibré (+0,59 pt) et Dynamique (+0,02 pt), sur 2 rotations seulement.
2. **Par quoi** : aux profils élevés les réglages retenus mêlent un multiplicateur élevé (×8 à ×16) et une borne Bear basse (20–65 %) = plus d'exposition en Bull, dé-risque en Bear ; aux profils bas ils restent en bas de grille (×0,5 à ×1,5), et ETH retombe souvent sur des bornes à 100 % (= la constante, à un point de grille près). Le dé-risque ne paie que quand le bloc de test est le bear 2020-22 (BTC Prudent / Équilibré : +7,7 / +7,6 pt, ETH : +1,4 pt) ; ailleurs la borne Bull basse choisie sur 3 blocs coûte du gain (jusqu'à −104 pt BTC Full sur 2018-20, −116 pt ETH Full).
3. **Plafond** : aucun des deux candidats ne le garantit hors échantillon. Chaque profil a 1 bloc de test sur 4 au-dessus du plafond pour la constante (le bear 2020-22 : réglée sur trois blocs sans bear profond, la constante vise un drawdown qu'elle ne tient pas sur le bear) ; la politique en dépasse 1 aussi (0 pour ETH Full, 2 pour ETH Dynamique), dont un dépassement là où la constante tient (ETH Prudent, Dynamique).
4. **Pour quels profils** : aucun. Les 8 cas sont `NUL`, y compris les profils à fort plafond où la marge (multiplicateurs ×8–×16) permettrait le plus de pilotage.
5. **Historique complet** (lecture seule, hors verdict ; réglages sur les 4 blocs donc in-sample) : la politique y affiche plus de gain que la constante sur BTC (Équilibré 248 contre 140 %, Dynamique 458 contre 249 %, Full 616 contre 570 %) avec plus de drawdown (−22,9 / −36,1 / −46,3 % contre −16,2 / −21,5 / −32,0 %, plafonds tenus) ; sur ETH le drawdown du cycle complet dépasse le plafond pour la constante aux 4 profils (−20,2 / −36,0 / −58,3 / −74,8 % contre −15 / −25 / −40 / −60) et pour la politique à Équilibré, Dynamique, Full. Les gains in-sample de la politique ne se retrouvent pas hors échantillon. Le drawdown à forte exposition (ETH, cycle complet) dépasse les plafonds de profil : le plafond ne se déduit pas de blocs sans bear.

**Recommandation.** `NUL` : le mode Full Autonome se réduit à fixer l'exposition constante par profil, ce que la frontière C1 fournit déjà ; point d'attention : ce niveau doit être calé sur un historique qui contient un bear profond (cycle complet) ou avec une marge sur le plafond, sinon le drawdown du profil n'est pas tenu.

## Écarts de périmètre
- « Bornes basses et tout le reste : valeurs par défaut du profil » lu comme les réglages de base de C1 (bande neutre 0–100, puissance 1, biais 0), seul moyen de garder « ×1 = chiffres de C1 » et « bornes hautes = défaut ⇒ constante exacte » (testés). Les bandes de `StartingProfile` (Prudent 20–50 % …) ne sont donc pas appliquées ; « valeur par défaut » d'une borne haute = 100 %.
- 5 valeurs par borne (5 × 5 × 10 = 250 combinaisons, comme annoncé) au lieu de ~6.
- Asymétrie du protocole prescrit : la constante est interpolée (elle se pose pile sur le plafond), la politique reste sur la grille (ses combinaisons sont en dessous). Biais défavorable à la politique, non corrigé (règle non modifiée) ; il n'explique pas les 0–2 rotations sur 4 gagnées.
- Dépassement de plafond sur le bloc de test : rapporté, jamais corrigé.
- Rejeux du test de la constante au multiplicateur interpolé (hors grille) : un rejeu par rotation et profil.
