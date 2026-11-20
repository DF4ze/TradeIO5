---
statut: archivé
sujet: Étape D0 du pivot « Rainbow d'abord » — le réglage sur les 24 mois avant T tient-il dans les 3 et 6 mois suivants ? verdict NON_PERSISTANT sur BTC
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 2e6f4de60b
---
# Étude — réglage récent persistant ? (étape D0, 2026-11-20)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15i-reglage-recent.md`](../../architecture/15i-reglage-recent.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), points de départ [`etude-sensibilite-rainbow.md`](etude-sensibilite-rainbow.md) (A) et [`etude-optimisation-par-blocs.md`](etude-optimisation-par-blocs.md) (B). Tableaux par T : [`etude-reglage-recent-tableaux.md`](etude-reglage-recent-tableaux.md). Aucun preset modifié, rien appliqué ni persisté, pas de Flyway.

**Question.** Réglé sur les 24 mois avant une date T avec la méthode de Clem (Bull seul puis Bear seul, 2 tours, lecture « gel »), le preset tient-il sur les 3 et 6 mois qui suivent T, mieux que le preset en place et mieux qu'un bruit aléatoire de même amplitude ?

## Protocole (un run, une configuration, règles fixées avant lecture)
- 34 T tous les 2 mois, 2020-11-17 → 2026-05-17 (dernier T avec 6 mois complets, dernière bougie 2026-11-17). BTC, capital 2 484 USDC, graine 20261116, bougies `5d2823e19f40598a`.
- Réglage de T : dataset reconstruit sur les bougies ≤ T (aucune donnée postérieure, test dédié). Recherche conjointe `SearchEngine` sur 31 réglages par jeu (hors périodes SMA / ATR, Trend, baseAmount), 1 000 essais par jeu et par tour, critère = PnL du régime en lecture « gel », garde-fou unique : exposition moyenne du cycle réel ≤ 1,25 × celle du preset en place.
- Test : [T, T+3 m] et [T, T+6 m] ; comparé au preset en place, au DCA à exposition égale (calé sur l'exposition du preset réglé), à la médiane de 20 perturbations aléatoires de même amplitude (mêmes réglages, même garde-fou).
- Verdict (règle fixée avant lecture, horizon le plus long) : PERSISTANT si Δ > 0 vs preset sur ≥ 60 % des T, médiane Δ > 0 et bat la médiane aléatoire sur ≥ 60 % des T ; idem à 3 mois, rapporté à part.
- Coût : estimation 23,4 min (mesure 36,8 ms par candidat), run lancé sur décision de Clem (plafond de 10 min levé).

## Verdict : NON_PERSISTANT
Règle fixée avant lecture (horizon le plus long) : RÉGLAGE_RÉCENT_PERSISTANT si Δ > 0 contre le preset en place sur ≥ 60 % des T, médiane du Δ > 0 ET le preset réglé bat la médiane des 20 perturbations aléatoires sur ≥ 60 % des T ; idem à l'horizon court, rapporté à part. Sinon NON_PERSISTANT, avec la cause : gain (part ou médiane du Δ), bruit (pas mieux que l'aléatoire), phase (la règle ne tient que dans un type de marché).

- **6 mois** : KO — Δ > 0 vs preset sur 15/34 T (44 %), médiane Δ -0.95 pt (-13 % du PnL preset), bat la médiane des perturbations sur 13/34 (38 %), bat le DCA égal sur 17/34. Événements effectifs ≈ 11.3 (périodes de test chevauchées, non indépendantes). Échec : gain, bruit (pas mieux que l'aléatoire).
  - phase haussière : 19 T, Δ > 0 sur 12, médiane Δ +0.85 pt, bat l'aléatoire 9
  - phase baissière : 15 T, Δ > 0 sur 3, médiane Δ -2.16 pt, bat l'aléatoire 4
- **3 mois** : KO — Δ > 0 vs preset sur 11/34 T (32 %), médiane Δ -0.56 pt (-34 % du PnL preset), bat la médiane des perturbations sur 8/34 (24 %), bat le DCA égal sur 15/34. Événements effectifs ≈ 22.7 (périodes de test chevauchées, non indépendantes). Échec : gain, bruit (pas mieux que l'aléatoire).
  - phase haussière : 14 T, Δ > 0 sur 7, médiane Δ +1.48 pt, bat l'aléatoire 5
  - phase baissière : 20 T, Δ > 0 sur 4, médiane Δ -1.30 pt, bat l'aléatoire 3

## Lecture
- **Pas de persistance** : à 6 mois le réglé bat le preset sur 44 % des T (médiane −0,95 pt) et l'aléatoire sur 38 % ; à 3 mois 32 % et 24 %. Il ne bat pas non plus le DCA à exposition égale mieux qu'une fois sur deux (17/34 à 6 mois). Causes : gain et bruit.
- **Le gain est de l'exposition, pas du réglage.** Les gros Δ positifs à 6 mois (2023-11 +43,1 ; 2024-05 +29,7 ; 2023-05 +25,1 ; 2024-01 +14,6) viennent d'une exposition qui passe de 1–12 % (preset) à 34–73 % (réglé) en marché haussier ; le DCA égal fait +46,95 / +29,02 / +28,99 / +5,61 : même ordre. Dans les baissiers, la même exposition accrue coûte cher (2021-01 −20,2 ; 2022-01 −24,3 ; 2023-03 −8,5 ; 2026-01 −7,0).
- **Asymétrie de phase** (rapportée, hors verdict) : à 6 mois, 19 T haussiers Δ > 0 sur 12 (médiane +0,85 pt) ; 15 T baissiers Δ > 0 sur 3 (médiane −2,16 pt).
- **Le garde-fou in-sample ne borne pas l'exposition en test.** L'exposition de la vue de réglage reste ≤ 1,25 × preset (ex. 2023-11 : 25,3 → 18,9 %) mais elle explose en test (2,3 → 68,3 % à 6 mois) : le jeu réglé dépend du régime de la fenêtre.
- **Le critère « gel » diverge du cycle réel dans l'échantillon.** Gains de gel Bull de +1 000 à +34 000 USDC pour un capital de 2 484 contre +10 à +376 pour le preset, alors que le cycle réel de la vue passe souvent de +30 % à +3 / +13 % (2020-11, 2021-01, 2021-07). Réglages poussés aux bords : cooldownDays 10 → 331, sellFraction 0,20 → 0,99, atrMultUp3 2,5 → 12,3, trailingStopSellPct 5 → 78,5 (T 2026-05-17). Sur-ajustement du critère de recherche, pas une amélioration du cycle.
- **Réglages stables ou erratiques.** Quasi tous bougent à chaque T (BULL 34/34 sur 25 réglages). Sens quasi constant : atrMultDown1/2, Up1/2/3, multX0_5, multX1, trailingStopBuyPct (hausse 34/34 ou 33/34), moonStopSellPct (baisse 34/34 Bull, 29/34 Bear) — dérive vers les bords de plage, pas un signal ; erratiques (hausse ≈ baisse) : athBuyMax, athRefDdBuyPct, athSellMax (Bear), multTriggered, moonReservePct (Bear).
- **Zones X1 / X0,5** : activées à tous les T côté Bull (34/34) et 31/34 côté Bear ; Δ moyen +2,04 pt avec zone activée, aucun T sans zone : pas de contraste, donc pas de preuve d'utilité propre.

## Comparaison avec l'étape A
Sens majoritaire D0 identique à l'étape A sur 15 réglages sur 24 (Bull 9/12 ; Bear 6/12) ; divergence nette sur Bull cooldownDays (A ↓, D0 ↑ 33/34), Bull atrMultDown2 (A =, D0 ↑ 34/34), Bull moonReservePct (A ↓, D0 ↑). Tableau complet ci-dessous. L'accord partiel suit surtout la dérive vers les bords.

### Comparaison avec l'étape A (réglage « gel » le meilleur sur le dernier bloc)
| Jeu | Réglage | Étape A | D0 hausse | D0 baisse | D0 bougé | Dernier T | Même sens que la majorité D0 |
|---|---|---|---|---|---|---|---|
| BULL | athRefDdBuyPct | ↓ | 12 | 21 | 33/34 | ↑ 60.00 → 94.56 | oui |
| BULL | multX2 | ↑ | 22 | 12 | 34/34 | ↑ 2.000 → 4.260 | oui |
| BULL | athBuyMin | ↑ | 28 | 6 | 34/34 | ↑ 0.5000 → 3.025 | oui |
| BULL | atrMultUp3 | ↑ | 34 | 0 | 34/34 | ↑ 2.500 → 12.30 | oui |
| BULL | cooldownDays | ↓ | 33 | 1 | 34/34 | ↑ 10 → 331 | non |
| BULL | athBuyMax | ↑ | 20 | 14 | 34/34 | ↓ 4.000 → 3.325 | oui |
| BULL | atrMultDown2 | = | 34 | 0 | 34/34 | ↑ 1.000 → 10.40 | non |
| BULL | moonReservePct | ↓ | 25 | 9 | 34/34 | ↑ 30.00 → 86.50 | non |
| BULL | multTriggered | ↑ | 18 | 16 | 34/34 | ↑ 3.000 → 5.440 | oui |
| BULL | moonTrailingStopPct | ↑ | 28 | 6 | 34/34 | ↓ 9.000 → 4.000 | oui |
| BULL | moonOn | ≠ | 0 | 0 | 20/34 | = | oui |
| BULL | buyReentryMode | ≠ | 0 | 0 | 26/34 | TRAILING_STOP → FIXED_DELAY | oui |
| BEAR | atrMultUp3 | ↓ | 21 | 10 | 31/34 | = | non |
| BEAR | sellFraction | ↑ | 19 | 11 | 30/34 | = | oui |
| BEAR | multX2 | ↓ | 22 | 8 | 30/34 | = | non |
| BEAR | athRefDdSellPct | ↑ | 22 | 10 | 32/34 | = | oui |
| BEAR | atrMultDown2 | ↑ | 27 | 5 | 32/34 | = | oui |
| BEAR | athSellMax | ↑ | 11 | 21 | 32/34 | = | non |
| BEAR | cooldownDays | ↑ | 30 | 2 | 32/34 | = | oui |
| BEAR | athBuyMax | ↓ | 13 | 19 | 32/34 | = | oui |
| BEAR | athSellMin | ↑ | 28 | 4 | 32/34 | = | oui |
| BEAR | athRefDdBuyPct | ↑ | 13 | 18 | 31/34 | = | non |
| BEAR | blockBuyAfterSellUntilDown2 | = | 0 | 0 | 21/34 | = | non |
| BEAR | multTriggered | ↓ | 17 | 14 | 31/34 | = | non |

## Réglages
### Réglages qui bougent le plus souvent
| Jeu | Réglage | T où bougé | Hausse | Baisse | Déplacement médian (fraction de la plage) |
|---|---|---|---|---|---|
| BULL | athBuyMax | 34/34 | 20 | 14 | +0.06 |
| BULL | athBuyMin | 34/34 | 28 | 6 | +0.34 |
| BULL | athRefDdSellPct | 34/34 | 21 | 13 | +0.11 |
| BULL | athSellMax | 34/34 | 23 | 11 | +0.17 |
| BULL | athSellMin | 34/34 | 31 | 3 | +0.31 |
| BULL | atrMultDown1 | 34/34 | 34 | 0 | +0.30 |
| BULL | atrMultDown2 | 34/34 | 34 | 0 | +0.62 |
| BULL | atrMultUp1 | 34/34 | 34 | 0 | +0.21 |
| BULL | atrMultUp2 | 34/34 | 34 | 0 | +0.32 |
| BULL | atrMultUp3 | 34/34 | 34 | 0 | +0.34 |
| BULL | cooldownDays | 34/34 | 33 | 1 | +0.46 |
| BULL | fixedDelayDays | 34/34 | 33 | 1 | +0.46 |
| BULL | moonReservePct | 34/34 | 25 | 9 | +0.26 |
| BULL | moonStopSellPct | 34/34 | 0 | 34 | -0.47 |
| BULL | moonTrailingStopPct | 34/34 | 28 | 6 | +0.21 |
| BULL | multTriggered | 34/34 | 18 | 16 | +0.08 |
| BULL | multX0_5 | 34/34 | 34 | 0 | +0.67 |
| BULL | multX1 | 34/34 | 33 | 1 | +0.71 |
| BULL | multX2 | 34/34 | 22 | 12 | +0.14 |
| BULL | sellFraction | 34/34 | 31 | 3 | +0.32 |
| BULL | trailingStopBuyPct | 34/34 | 34 | 0 | +0.40 |
| BULL | trailingStopSellPct | 34/34 | 32 | 2 | +0.52 |
| BULL | athRefDdBuyPct | 33/34 | 12 | 21 | -0.12 |
| BEAR | athBuyMax | 32/34 | 13 | 19 | -0.02 |
| BEAR | athRefDdSellPct | 32/34 | 22 | 10 | +0.09 |
| BEAR | athSellMax | 32/34 | 11 | 21 | -0.15 |
| BEAR | athSellMin | 32/34 | 28 | 4 | +0.32 |
| BEAR | atrMultDown2 | 32/34 | 27 | 5 | +0.38 |
| BEAR | atrMultUp2 | 32/34 | 32 | 0 | +0.08 |
| BEAR | cooldownDays | 32/34 | 30 | 2 | +0.30 |
| BEAR | trailingStopBuyPct | 32/34 | 27 | 5 | +0.31 |
| BEAR | trailingStopSellPct | 32/34 | 28 | 4 | +0.34 |
| BEAR | athRefDdBuyPct | 31/34 | 13 | 18 | -0.13 |
| BEAR | atrMultUp3 | 31/34 | 21 | 10 | +0.01 |
| BEAR | fixedDelayDays | 31/34 | 27 | 4 | +0.33 |
| BEAR | moonReservePct | 31/34 | 16 | 15 | +0.01 |
| BEAR | moonTrailingStopPct | 31/34 | 25 | 6 | +0.31 |
| BEAR | multTriggered | 31/34 | 17 | 14 | +0.01 |
| BEAR | multX1 | 31/34 | 23 | 8 | +0.19 |
| BEAR | athBuyMin | 30/34 | 25 | 5 | +0.20 |
| BEAR | atrMultDown1 | 30/34 | 30 | 0 | +0.13 |
| BEAR | multX0_5 | 30/34 | 24 | 6 | +0.14 |
| BEAR | multX2 | 30/34 | 22 | 8 | +0.17 |
| BEAR | sellFraction | 30/34 | 19 | 11 | +0.04 |
| BULL | sellReentryMode | 30/34 | 0 | 0 | — |
| BEAR | moonStopSellPct | 29/34 | 0 | 29 | -0.50 |
| BEAR | atrMultUp1 | 28/34 | 28 | 0 | +0.04 |
| BULL | buyReentryMode | 26/34 | 0 | 0 | — |
| BEAR | blockBuyAfterSellUntilDown2 | 21/34 | 0 | 0 | — |
| BEAR | buyReentryMode | 21/34 | 0 | 0 | — |
| BEAR | sellReentryMode | 21/34 | 0 | 0 | — |
| BULL | moonOn | 20/34 | 0 | 0 | — |
| BEAR | cooldownAfterSellOn | 19/34 | 0 | 0 | — |
| BEAR | moonReserveRatchet | 19/34 | 0 | 0 | — |
| BULL | allowSellDuringCooldown | 19/34 | 0 | 0 | — |
| BULL | moonReserveRatchet | 19/34 | 0 | 0 | — |
| BEAR | moonOn | 17/34 | 0 | 0 | — |
| BEAR | allowSellDuringCooldown | 15/34 | 0 | 0 | — |
| BULL | cooldownAfterSellOn | 12/34 | 0 | 0 | — |
| BULL | blockBuyAfterSellUntilDown2 | 8/34 | 0 | 0 | — |
| BEAR | athOn | 5/34 | 0 | 0 | — |
| BULL | athOn | 3/34 | 0 | 0 | — |

### Zones X1 / X0,5
Activées (bornes > 0 après réglage) sur 34 T : Bull X1 34, Bull X0,5 34, Bear X1 31, Bear X0,5 31. Δ moyen à l'horizon de verdict : +2.04 pt si au moins une zone activée (34 T), — pt sinon (0 T).

### Réglages au T le plus récent (2026-05-17) — NON APPLIQUÉS
| Jeu | Réglage | Avant | Après |
|---|---|---|---|
| BULL | atrMultDown2 | 1.000 | 10.40 |
| BULL | atrMultDown1 | 0.000 | 5.800 |
| BULL | atrMultUp1 | 0.000 | 10.80 |
| BULL | atrMultUp2 | 0.000 | 11.20 |
| BULL | atrMultUp3 | 2.500 | 12.30 |
| BULL | buyReentryMode | TRAILING_STOP | FIXED_DELAY |
| BULL | trailingStopBuyPct | 3.000 | 43.50 |
| BULL | trailingStopSellPct | 5.000 | 78.50 |
| BULL | cooldownDays | 10 | 331 |
| BULL | fixedDelayDays | 15 | 12 |
| BULL | sellFraction | 0.2000 | 0.9901 |
| BULL | athOn | true | false |
| BULL | athRefDdBuyPct | 60.00 | 94.56 |
| BULL | athRefDdSellPct | 30.00 | 3.970 |
| BULL | athBuyMin | 0.5000 | 3.025 |
| BULL | athBuyMax | 4.000 | 3.325 |
| BULL | athSellMax | 2.000 | 2.075 |
| BULL | athSellMin | 1.000 | 0.5500 |
| BULL | multX2 | 2.000 | 4.260 |
| BULL | multX1 | 1.000 | 5.941 |
| BULL | multX0_5 | 0.5000 | 4.555 |
| BULL | multTriggered | 3.000 | 5.440 |
| BULL | moonReservePct | 30.00 | 86.50 |
| BULL | moonTrailingStopPct | 9.000 | 4.000 |
| BULL | moonStopSellPct | 100.0 | 79.50 |

### Multiplicité
34 T, 136 recherches, 128656 essais du moteur, 108820 rejeux de candidats, 1723 tirages de perturbation, 2 horizons, 1 configuration (un seul run, aucune variante).

### Écarts de périmètre et limites
- Critère de recherche = PnL total du régime en lecture « gel » (USDC) : ventes encaissées + position (latent), moins l'investi, comme à l'étape A. Les écarts sont rapportés en points de capital ET en % du PnL du preset en place (quand il est > 0).
- Recherche = SearchEngine (Sobol + halving + plateau) sur 31 dimensions par jeu ; SobolSequence ne fournit que 12 dimensions quasi-aléatoires, les suivantes sont tirées pseudo-aléatoirement à graine fixe (limite du moteur existant).
- Aucun gel de paramètre par sensibilité (seuil 0) : un réglage muet seul (zones X1 / X0,5 à bornes 0) doit pouvoir s'activer avec d'autres ; la phase de sensibilité du moteur est réduite à 2 points par réglage (elle ne sert plus qu'à l'ordre d'évaluation).
- Bornes ATR : chaque tirage est trié (Down1 ≤ Down2 ; Up1 ≤ Up2 ≤ Up3) ; athBuyMin ≤ athBuyMax idem ; Down1 / Up1 / Up2 peuvent donc être > 0 (zones X1 / X0,5).
- Garde-fou unique : exposition moyenne du cycle réel (deux jeux actifs) sur la vue de réglage ≤ 1.25 × celle du preset en place ; le garde-fou est un veto (score −1000), jamais une pénalité. Aucun plafond de drawdown, aucune fenêtre multiple.
- Un jeu choisi par le moteur n'est retenu que s'il bat STRICTEMENT le jeu de départ dans l'échantillon (critère de recherche) ; sinon le jeu reste inchangé (Δ = 0 au test).
- Test en avançant : joué sur [T, T + horizon] (le jour T appartient à la vue de réglage ET au test ; les deux ne partagent pas de décision future). La Trend du test est calculée sur toutes les bougies disponibles comme aux étapes A à C4 ; le réglage, lui, ne voit que les bougies ≤ T (dataset reconstruit).
- DCA à exposition égale : calé (dichotomie de B') sur l'exposition moyenne du preset RÉGLÉ sur la même période de test.
- Perturbations aléatoires : mêmes réglages déplacés que le réglage, même amplitude normalisée dans la plage, sens aléatoire (réfléchi au bord), option tirée pour un mode / interrupteur ; mêmes tris de bornes ; garde-fou d'exposition appliqué sur la vue de réglage (tirages refusés redessinés, au plus 100). Sans réglage retenu, la perturbation est le preset en place (Δ = 0, ne bat pas l'aléatoire).
- Périodes de test chevauchées : à 6 mois avec un pas de 2 mois, chaque jour est couvert par 3 périodes (événements effectifs ≈ nombre de T / 3) ; à 3 mois ≈ nombre de T / 1,5. Les pourcentages de T ne sont pas des fréquences indépendantes.
- BTC seul (ETH seulement après validation) ; un seul run, une seule configuration, aucune variante, aucun preset modifié.
