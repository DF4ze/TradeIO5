---
statut: archivé
sujet: Étape C3 du pivot « Rainbow d'abord » — calage de l'exposition par profil sur le pire cas (fenêtres glissantes de 24 mois + historique complet), marge de sécurité mesurée sans le futur, équivalence avec l'onboarding (BTC, ETH)
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: d03354c588
---
# Étude — calage de l'exposition sur le pire cas, avec marge (étape C3, 2026-11-19)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15g-calage-exposition-pire-cas.md`](../../architecture/15g-calage-exposition-pire-cas.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), points de départ : [`etude-frontiere-exposition.md`](etude-frontiere-exposition.md) (C1) et [`etude-politique-exposition.md`](etude-politique-exposition.md) (C2). Constats de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../../decisions/fil-rouge-dca-automatisation.md) (« Constat C1 », « Constat C2 »).

**Question.** À C1 / C2 le niveau d'exposition de chaque profil était calé sur 4 blocs de 24 mois dont un seul contient un vrai bear, et le plafond n'était pas tenu hors échantillon. Quel niveau tient le plafond sur le pire cas, et quelle marge de sécurité faut-il, mesurée et non supposée ? Aucun réglage du preset modifié, rien appliqué ni persisté, pas de Flyway, live inchangé.

## Protocole (un run, une configuration, règles fixées avant lecture)
- Levier unique : multiplicateur du montant de base du lien (C1 / C2), preset, Trend, modulation, capital (BTC 2484, ETH 2425 USDC), cash, coûts et bougies inchangés. Grille : celle de C1 + niveaux ajoutés en C2 = 16 niveaux (×0,125 à ×64). Bougies : BTC `5d2823e19f40598a`, ETH `26a9c34ef1ec48c7`, source OKX, fin 2026-11-17.
- **Pire cas** : 78 fenêtres glissantes de 24 mois (pas 30 j, 2018-07-22 → 2020-07-21 jusqu'à 2024-11-18 → 2026-11-17) + historique complet ; drawdown de calage d'un niveau = le pire. Niveau sans marge = premier franchissement du plafond (−15 / −25 / −40 / −60 %, lus dans `ModulationDefaults`), interpolé comme en C1. Fenêtres tronquées : 0 ; non rejouables : 0.
- **Bear 2021-22** (convention fixée avant lecture : 2021-11-10 → 2022-11-21) : 12 fenêtres sur 78 le contiennent en entier, 31 en recouvrent ≥ 90 j. Elles se chevauchent (30 j de pas pour 24 mois) : elles servent à trouver le pire cas, pas à compter des événements.
- **Marge** : 21 dates T (2020-11-17 → 2025-11-17, tous les 3 mois) ; calage ancré (seules les fenêtres et l'historique terminés avant T) au plafond / k, replay 12 mois depuis T, r(T) = drawdown réel / plafond. 7 des 21 T ont un test qui recouvre le bear ≥ 90 j. Règle : k recommandé = plus petit k de {1 ; 1,1 ; 1,25 ; 1,5 ; 2} tel que le plafond tienne sur ≥ 90 % des T **pour chacun des 4 profils** (pire profil nommé).
- **Multiplicité** (par actif) : 16 niveaux × 78 fenêtres + 22 historiques + 4 blocs ; 21 T × 5 marges × 4 profils = 420 calages ; 378 replays de test ; 2 082 rejeux (BTC 10,7 s, ETH 8,4 s, pool de 4 ; estimation 2 124 rejeux, 0,89 min par actif).

## Verdict (règles appliquées telles quelles)
| Actif | Profil | Plafond DD % | Niveau sans marge | Part des T tenus (k = 1) | k | Niveau recommandé | Expo moy. % (blocs C1 / hist.) | Gain médian % (blocs) | Écart au niveau C1 (pt) | DD hist. complet % |
|---|---|---|---|---|---|---|---|---|---|---|
| BTC | Prudent | −15 | ×0,76 | 86 % (18/21) | 1,10 | ×0,69 | 7,3 / 7,8 | +11,1 | −1,5 | −10,5 |
| BTC | Équilibré | −25 | ×1,30 | 86 % | 1,10 | ×1,18 | 12,1 / 11,6 | +19,0 | −2,9 | −14,9 |
| BTC | Dynamique | −40 | ×2,17 | 90 % (19/21) | 1,10 | ×1,92 | 18,9 / 15,9 | +31,0 | −8,0 | −19,4 |
| BTC | Full | −60 | ×4,48 | 86 % | 1,10 | ×3,53 | 31,8 / 22,3 | +57,2 | −31,6 | −24,8 |
| ETH | Prudent | −15 | ×1,01 | 100 % (21/21) | 1,00 | ×1,01 | 10,3 / 10,6 | +15,0 | −5,2 | −15,0 (au plafond) |
| ETH | Équilibré | −25 | ×1,69 | 100 % | 1,00 | ×1,69 | 16,4 / 15,6 | +25,1 | −11,1 | −25,0 (au plafond) |
| ETH | Dynamique | −40 | ×2,72 | 100 % | 1,00 | ×2,72 | 24,7 / 22,1 | +40,3 | −7,0 | −40,0 (au plafond) |
| ETH | Full | −60 | ×4,32 | 100 % | 1,00 | ×4,32 | 36,1 / 31,9 | +47,8 | −15,2 | −61,9 (+1,9 pt) |

Niveaux de C1 (4 blocs, rejoués) : BTC ×0,78 / 1,36 / 2,41 / 5,57 ; ETH ×1,37 / 2,45 / 3,98 / 13,27 (identiques à C1, vérifié). Part des T tenus par k (pire profil) : BTC 86 % (k = 1, Prudent) → **90 % (k = 1,1, Full)** → 95 % (1,25) → 100 % (1,5, 2) ; ETH 100 % dès k = 1.

r(T) à k = 1 (r > 1 = plafond dépassé), BTC : médiane 0,14–0,20, P90 1,00–1,02, max 1,09 (Full) à 1,49 (Équilibré). Les 3 à 4 dépassements sont tous des T dont le test recouvre le bear (2021-08-17 → 2022-02-17 : 4 / 7 tenus Prudent, Équilibré, Full ; 5 / 7 Dynamique) ; hors bear 14 / 14 tenus, r max 0,22 (0,61 Full à 2020-11-17, niveau calé sans bear). ETH : médiane 0,31–0,35, P90 0,69–0,70, max 0,77–0,81, bear 7 / 7 tenus. Tableaux par date et par k : `target/exposure-calibration.md|json` (régénérables).

## Lecture
1. **C1 était optimiste** : niveau sans marge sous celui de C1 de −2 / −4 / −10 / −20 % (BTC) et −26 / −31 / −32 / −67 % (ETH, Full ×13,3 → ×4,3). Le pire cas est une fenêtre qui contient le bear 2021-22 pour BTC (Prudent, Équilibré, Dynamique ; Full : fenêtre 2021-12 → 2023-12 qui en recouvre l'essentiel), et l'**historique complet** pour ETH (drawdown cumulé 2018 → 2026 plus dur que toute fenêtre de 24 mois).
2. **Marge** : BTC k = 1,1 (plafond divisé par 1,1) ; ETH aucune (k = 1). Sans marge, BTC dépasse son plafond sur 3 à 4 T sur 21, tous dans le bear 2021-22.
3. **Prix de la sécurité** (gain médian des blocs de C1 au niveau recommandé contre le niveau C1) : BTC −1,5 / −2,9 / −8,0 / −31,6 pt ; ETH −5,2 / −11,1 / −7,0 / −15,2 pt. Le coût vient surtout de la correction de C1 (pire cas) ; la marge k = 1,1 seule retire ≈ 10 % de niveau.
4. **ETH contre BTC** : BTC est sensible au bear 2021-22 (la marge compte, r max 1,49 sans elle) ; ETH est calé par l'historique complet (niveau identique à toute date T, r max 0,81), donc plus bas, plafond tenu sur tous les T, mais **posé exactement sur le plafond sur ce chemin** (dépassement apparent 0,0003 / 0,002 / 0,008 pt pour Prudent / Équilibré / Dynamique = résidu d'interpolation ; Full +1,9 pt : interpolation linéaire entre ×4 et ×6 sur une courbe non linéaire).
5. **Non couvert** : un bear futur plus profond que 2021-22 (ou que 2018 pour ETH) ; un seul grand bear par actif en 8 ans (k mesuré sur 7 T dans le bear, non indépendants : ≈ 1 événement) ; test sur 12 mois alors que le plafond est lu sur 24 mois et sur l'historique (r(T) sous-estime le drawdown de long horizon : c'est ce qui rend ETH « 100 % » à k = 1 alors que son historique complet est au plafond) ; wallet multi-actifs (cash commun) ; frais et glissement réels.

**Recommandation.** Mode Full Autonome v1 (exposition constante par profil) : niveaux recommandés ci-dessus (BTC ×0,69 / 1,18 / 1,92 / 3,53 ; ETH ×1,01 / 1,69 / 2,72 / 4,32 de la base de 1 USDC), **avec k = 1,1 aussi pour ETH** si on veut une marge sur l'historique complet (la règle fixée donne k = 1 pour ETH ; ce choix serait une décision de Clem, pas un résultat). Affichage : « cible de risque calibrée sur l'historique, pas garantie : un repli plus profond que celui de 2021-22 dépasserait le plafond ».

## Équivalence avec l'onboarding (vérifiée dans le code, rien modifié)
- `RainbowLiveOnboardingService.referenceBaseAmount(cash, jours, mult. moyen)` = `cash / (jours × multiplicateur moyen)` arrondi vers le bas ; défaut `ModulationDefaults.AUTONOMY_DAYS` = 90 j, bornes 7–365 j ; multiplicateur moyen = (multX2 + multX1 + multX0,5) / 3 = 1,1667 (BTC et ETH) ; cash = cash du wallet / nombre de liens.
- **Le ×1 de C1 n'est pas la proposition d'onboarding** : `SensitivityEvaluator.referenceBase` lit la base enregistrée du lien (`linkBaseAmount`, à défaut base du preset) = **1,00 USDC** ; `AssetInput.referenceBase` (proposition d'onboarding, 23,65 BTC / 23,09 ETH à 90 j) n'est lu que par `ReplayScorer`, pas par C1 / C2 / C3.
- Le multiplicateur est donc une base en USDC rapportée au capital rejoué. Autonomie équivalente d'un niveau ×m : `cash / (1,1667 × m × 1,00)`. **L'autonomie par défaut (90 j) correspond à ×23,65 (BTC) et ×23,09 (ETH)**, très au-delà du niveau Full (×3,5 à ×4,5 sans C1 ; le plafond −60 % est dépassé bien avant). Autonomies correspondant aux niveaux recommandés : BTC 3 090 / 1 810 / 1 111 / 602 j ; ETH 2 048 / 1 227 / 764 / 482 j (niveaux de C1 : BTC 2 728 / 1 569 / 883 / 382 j, ETH 1 521 / 850 / 523 / 157 j).
- **Équivalence non directe** : (a) tous ces niveaux sont hors des bornes 7–365 j de l'autonomie ; (b) l'équivalence est exacte sur le montant de base seulement, le moteur applique en plus facteurs de zone, ATH, modulation (le multiplicateur moyen 1,1667 n'est pas l'exposition) ; (c) le cash de l'onboarding est le cash du wallet divisé par le nombre de liens, C3 rejoue un actif avec tout son capital ; (d) l'invariance au capital (le niveau en autonomie reste valable pour un autre capital) n'est pas vérifiée (minimums d'ordre, frais). Pour la rendre exacte : exprimer le niveau du profil en autonomie (indépendante du capital) en levant les bornes, ou en base = capital / (autonomie × multiplicateur moyen) avec un paramètre de profil dédié ; vérifier sur un second capital ; décider le partage du cash entre liens. Rien n'est implémenté.

## Écarts de périmètre
- « Niveaux fins ajoutés en C2 » lus comme les élargissements de C2 (`constantExtra` : ×0,125, ×0,25, ×24, ×32, ×48, ×64) : 16 niveaux, pas 20.
- k recommandé « pour l'actif, tous profils confondus » lu comme « pour chacun des 4 profils » (le pire profil fixe k) ; lecture poolée (84 couples T × profil) non retenue.
- Bear 2021-22 = convention de dates (BTC plus haut → creux) appliquée aux deux actifs ; « test contient le bear » = recouvrement ≥ 90 j.
- Historique complet à T = de la fin du préchauffage à T − 1 (peut durer moins de 24 mois aux premiers T : T = 2020-11-17 n'a qu'une seule fenêtre complète avant lui).
- Niveau C1 et gain « à ce niveau » : rejoués directement au multiplicateur interpolé (C1 interpolait les gains : écart ≤ 1,3 pt, p. ex. ETH Full +63,0 contre +61,7).
- Niveau « tenu » = drawdown ≥ plafond au sens strict : les « DÉPASSÉ » d'ETH sont des résidus d'interpolation (voir lecture 4), rapportés tels quels.
