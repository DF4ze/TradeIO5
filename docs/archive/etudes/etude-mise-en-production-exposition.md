---
statut: archivé
sujet: Étape C4 du pivot « Rainbow d'abord » — calage exploitable : invariance au capital, bandes d'exposition du live et onboarding actuel, wallet BTC + ETH à cash commun, table de calage de production, points de code à changer
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 557dfc74b0
---
# Étude — mise en production du calage d'exposition (étape C4, 2026-11-19)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15h-mise-en-production-exposition.md`](../../architecture/15h-mise-en-production-exposition.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md), point de départ : [`etude-calage-exposition.md`](etude-calage-exposition.md) (C3). Décisions de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../../decisions/fil-rouge-dca-automatisation.md) (« Constat C1 », « Constat C2 », « Marge de sécurité »). Aucun réglage du preset modifié, rien appliqué ni persisté, pas de Flyway, live inchangé.

**Question.** Le niveau d'exposition par profil (C3) était mesuré en laboratoire : un actif, bande neutre, base en USDC absolus. Est-il indépendant du capital ? Que donnent les bandes du live et l'onboarding actuel (90 j) ? Que donne un wallet BTC + ETH à cash commun ?

## Protocole (un run, une configuration, règles fixées avant lecture)
- Niveau = multiplicateur du ×1 de C3 (base 1,00 USDC / jour) calé au plafond / k, **k = 1,1** (BTC et ETH, paramètre `ExposureProductionConfig.k`) ; k = 1 en colonne de repère (niveaux recalculés, identiques à C3 : écart max 0). Taux de base = base par jour / cash = multiplicateur × 1,00 / capital ; autonomie équivalente = 1 / (taux × 1,1667).
- Même pire cas que C3 : 78 fenêtres de 24 mois + historique complet (2018-07-22 → 2026-11-17) ; exposition moyenne et gain médian = 4 blocs de 24 mois de C1. Bougies : BTC `5d2823e19f40598a`, ETH `26a9c34ef1ec48c7`.
- **Rejeu utilisé** : `MultiLinkReplayEngine` (le rejeu de l'Expert : `SizingCore.decide` + `LiveModulation`, le vrai chemin de décision), via `SensitivityEvaluator.parity` (un actif) et `WalletEvaluator` (wallet). Coûts simplifiés (barème statique, aucun minimum d'ordre). La bande entre par `ScorerInputs.baseSettings`.
- **Bande du profil** (`StartingProfile`) : min / max du profil (Prudent 20–50, Équilibré 30–70, Dynamique 40–90, Full 0–100), puissance ×1, biais 0, marge de régulation du défaut admin (25 %) — c'est ce que pose `UserModulationService.applyProfile`.
- **Règle d'invariance** : OK ssi écart du drawdown du pire cas ≤ 1 pt et écart du gain médian des blocs ≤ 2 pts par rapport au capital de C3. **La bande « compte »** si B s'écarte de A d'au moins 1 pt de drawdown ou 2 pts de gain médian.
- **Wallet** : capital 4 909 USDC (somme des capitaux de C3), partage 50 / 50 (comme l'onboarding : cash / nombre de liens), base de chaque lien = f × taux de son actif × 2 454,5 USDC. Conforme ssi le pire drawdown ≥ plafond du profil ; sinon f ≤ 1 unique appliqué aux deux niveaux (dichotomie à une dimension, 8 pas, résolution 1/256). 78 fenêtres communes aux deux actifs. PAXG exclu (historique insuffisant).
- **Multiplicité** : par actif 16 niveaux × 83 plages + 4 profils × (3 capitaux + 3 configurations) × 83 = 2 924 rejeux (BTC 16,0 s, ETH 12,3 s) ; wallet 2 656 rejeux à 2 liens (17,4 s). Estimation avant run 17 928 rejeux au pire = 7,5 min en série (< 10 min) ; durée réelle du run ≈ 64 s (pool de 4).

## 1. Invariance au capital
Niveaux recommandés (k = 1,1) et capitaux 1 000 / 2 484 (ou 2 425) / 10 000 USDC, bande neutre : **invariance OK pour les 8 couples actif × profil, écart 0,00 pt de drawdown et ≤ 0,01 pt de gain médian**.

| Actif | Profil | ×1 (k=1 / k=1,1) | Taux base/j/cash | Autonomie éq. (j) | DD pire cas % | Gain méd. % | Expo % |
|---|---|---|---|---|---|---|---|
| BTC | Prudent | 0,76 / 0,69 | 2,774e-4 | 3 090 | −13,72 | +11,15 | 7,3 |
| BTC | Équilibré | 1,30 / 1,18 | 4,736e-4 | 1 810 | −22,70 | +19,04 | 12,1 |
| BTC | Dynamique | 2,17 / 1,92 | 7,712e-4 | 1 111 | −36,38 | +31,00 | 18,9 |
| BTC | Full | 4,48 / 3,53 | 1,423e-3 | 602 | −54,52 | +57,18 | 31,8 |
| ETH | Prudent | 1,01 / 0,92 | 3,804e-4 | 2 254 | −13,64 | +13,67 | 9,4 |
| ETH | Équilibré | 1,69 / 1,54 | 6,349e-4 | 1 350 | −22,73 | +22,81 | 15,0 |
| ETH | Dynamique | 2,72 / 2,47 | 1,019e-3 | 841 | −36,37 | +36,60 | 22,8 |
| ETH | Full | 4,32 / 3,72 | 1,533e-3 | 559 | −54,55 | +47,74 | 32,2 |

**Lecture.** L'invariance est exacte dans le modèle de rejeu parce que les coûts y sont proportionnels (frais constant, aucun minimum d'ordre, lot 1e-8) : le résultat ne « casse » donc nulle part entre 1 000 et 10 000 USDC. Ce n'est pas une preuve pour le live. Ce qui casse hors modèle (calcul, **non rejoué**) :
- **Arrondi de l'onboarding** : `referenceBaseAmount` tronque à 2 décimales (`AMOUNT_SCALE = 2`). L'erreur relative est ≤ 0,01 / base ; elle dépasse 1 % dès que la base du jour < 1 USDC, soit un cash < 1 / taux : BTC 3 605 (Prudent), 2 111 (Équilibré), 1 297 (Dynamique), 703 (Full) ; ETH 2 629 / 1 575 / 981 / 652 USDC. À 1 000 USDC la base Prudent BTC est 0,277 → 0,27 (−2,6 %) ; à 100 USDC, 0,0277 → 0,02 (−28 %).
- **Minimums d'ordre** (`min_buy_usd`, report du reliquat) : la base du jour à 1 000 USDC va de 0,28 (BTC Prudent) à 1,53 (ETH Full) USDC, donc sous tout minimum d'exchange habituel : l'achat quotidien est reporté jusqu'au minimum (carry). Non mesuré : le mode « coûts complets » existe dans le code mais c'est une variante hors de ce run (« une seule configuration »).

## 2. Bandes du live et onboarding actuel
A = base recommandée, bande neutre (= C3) ; B = base recommandée + bande du profil ; C = onboarding actuel (cash / (90 j × 1,1667) = 23,65 USDC BTC, 23,09 ETH) + bande du profil. Écart = pire drawdown − plafond (négatif = dépassement).

| Actif | Profil | Plafond % | A : DD / écart | B : DD / écart | C : DD / écart | Expo % A / B / C | Gain méd. % A / B / C |
|---|---|---|---|---|---|---|---|
| BTC | Prudent | −15 | −13,7 / +1,3 | −29,3 / **−14,3** | −46,2 / **−31,2** | 7,3 / 19,5 / 37,9 | 11,2 / 24,6 / 30,6 |
| BTC | Équilibré | −25 | −22,7 / +2,3 | −42,6 / **−17,6** | −58,0 / **−33,0** | 12,1 / 29,9 / 51,0 | 19,0 / 39,7 / 48,4 |
| BTC | Dynamique | −40 | −36,4 / +3,6 | −55,7 / **−15,7** | −67,5 / **−27,5** | 18,9 / 41,9 / 63,8 | 31,0 / 58,6 / 68,1 |
| BTC | Full | −60 | −54,5 / +5,5 | −54,5 / +5,5 | −69,1 / **−9,1** | 31,8 / 31,8 / 65,6 | 57,2 / 57,2 / 114,8 |
| ETH | Prudent | −15 | −13,6 / +1,4 | −27,9 / **−12,9** | −55,0 / **−40,0** | 9,4 / 20,7 / 34,9 | 13,7 / 16,5 / 41,9 |
| ETH | Équilibré | −25 | −22,7 / +2,3 | −40,4 / **−15,4** | −67,5 / **−42,5** | 15,0 / 31,3 / 47,2 | 22,8 / 25,2 / 48,7 |
| ETH | Dynamique | −40 | −36,4 / +3,6 | −52,7 / **−12,7** | −76,7 / **−36,7** | 22,8 / 43,6 / 59,5 | 36,6 / 32,4 / 56,2 |
| ETH | Full | −60 | −54,5 / +5,5 | −54,5 / +5,5 | −75,9 / **−15,9** | 32,2 / 32,2 / 62,8 | 47,7 / 47,7 / 52,3 |

**Réponse : l'onboarding actuel (autonomie 90 j) avec la bande de son profil ne respecte pas le plafond, aux 8 couples.** Dépassements : BTC −31,2 / −33,0 / −27,5 / −9,1 pts (Prudent → Full), ETH −40,0 / −42,5 / −36,7 / −15,9 pts. L'exposition moyenne réelle (35–66 %) est 2 à 5 fois celle du niveau recommandé.

**Lecture.** (1) La base de 90 j est ×17 à ×40 au-dessus du niveau recommandé (23,65 USDC contre 0,69–3,53) ; le niveau du profil devrait s'exprimer en autonomie de 559 à 3 090 j. (2) **La bande compte** (7 couples sur 8 ; seul Full, bande 0–100, est neutre) : le plancher de bande agit comme une cible d'exposition (B : exposition moyenne 19,5–43,6 % ≈ le min de la bande, contre 7–23 % en A) et c'est lui, pas la base, qui fixe alors le drawdown. Avec les bandes actuelles de `StartingProfile`, le niveau de base calé en C3 ne protège pas le plafond (B : −12,7 à −17,6 pts). La bande conseillée avec le niveau de C3 est donc **neutre (0–100)** ; garder des bandes de profil exige de recaler le niveau sous bande (non fait ici : hors périmètre, une seule configuration).

## 3. Wallet BTC + ETH à cash commun (4 909 USDC, 50 / 50, 78 fenêtres)
| Profil | Plafond % | Bande | DD pire cas % (f = 1) | Conforme | f | DD pire cas % (f) | Expo % (f) | Gain méd. % (f) |
|---|---|---|---|---|---|---|---|---|
| Prudent | −15 | neutre | −10,63 | oui | 1,000 | −10,63 | 8,4 | +12,5 |
| Prudent | −15 | 20–50 | −26,14 | non | 0,008 | −12,06 | 0,4 | +0,3 |
| Équilibré | −25 | neutre | −17,93 | oui | 1,000 | −17,93 | 13,6 | +21,2 |
| Équilibré | −25 | 30–70 | −37,94 | non | 0,012 | −23,76 | 1,1 | +0,7 |
| Dynamique | −40 | neutre | −28,84 | oui | 1,000 | −28,84 | 20,8 | +34,2 |
| Dynamique | −40 | 40–90 | −48,61 | non | 0,242 | −39,96 | 28,3 | +23,4 |
| Full | −60 | neutre = 0–100 | −44,93 | oui | 1,000 | −44,93 | 31,9 | +56,8 |

**Lecture.** En bande neutre le wallet est conforme sans réduction (marge 4,4 à 15,1 pts : la diversification BTC + ETH réduit le drawdown du wallet sous celui de chaque actif ; la règle fixée interdit f > 1, donc cette marge n'est pas exploitée). Sous la bande du profil, f s'effondre (0,008 / 0,012 / 0,242) : le wallet ne tient le plafond qu'avec une base presque nulle, et le gain médian disparaît (+0,3 / +0,7 %). Même conclusion qu'au §2 : les bandes de profil actuelles sont incompatibles avec un plafond calé sur la base.

## 4. Table de calage de production (bande neutre, k = 1,1)
Bornes de l'autonomie du live : 7–365 j. **Toutes les lignes sont hors bornes** (559 à 3 090 j) : élargir la borne haute d'au moins 365 → 3 100 j (×8,5 ; en pratique 3 650 j) ou, mieux, exprimer le niveau en taux de base par profil sans passer par l'autonomie.

| Profil | Plafond % | Ligne | Taux base/j/cash | Autonomie éq. (j) | DD de calage % | Marge (pt) | Gain médian attendu % |
|---|---|---|---|---|---|---|---|
| Prudent | −15 | BTC seul | 2,774e-4 | 3 090 | −13,72 | +1,28 | +11,2 |
| | | ETH seul | 3,804e-4 | 2 254 | −13,64 | +1,36 | +13,7 |
| | | Wallet BTC + ETH (f = 1) | BTC 2,774e-4 / ETH 3,804e-4 | 3 090 / 2 254 | −10,63 | +4,37 | +12,5 |
| Équilibré | −25 | BTC seul | 4,736e-4 | 1 810 | −22,70 | +2,30 | +19,0 |
| | | ETH seul | 6,349e-4 | 1 350 | −22,73 | +2,27 | +22,8 |
| | | Wallet (f = 1) | 4,736e-4 / 6,349e-4 | 1 810 / 1 350 | −17,93 | +7,07 | +21,2 |
| Dynamique | −40 | BTC seul | 7,712e-4 | 1 111 | −36,38 | +3,62 | +31,0 |
| | | ETH seul | 1,019e-3 | 841 | −36,37 | +3,63 | +36,6 |
| | | Wallet (f = 1) | 7,712e-4 / 1,019e-3 | 1 111 / 841 | −28,84 | +11,16 | +34,2 |
| Full | −60 | BTC seul | 1,423e-3 | 602 | −54,52 | +5,48 | +57,2 |
| | | ETH seul | 1,533e-3 | 559 | −54,55 | +5,45 | +47,7 |
| | | Wallet (f = 1) | 1,423e-3 / 1,533e-3 | 602 / 559 | −44,93 | +15,07 | +56,8 |

Bande conseillée : **neutre (0–100)** pour les 4 profils (Full : bande 0–100 déjà neutre). Les valeurs d'autonomie sont celles du taux à cash alloué au lien (wallet : cash / 2). Avertissement à afficher : « cible de risque calibrée sur l'historique, pas garantie ».

## 5. Points de code à changer (lecture seule, rien modifié)
- `RainbowLiveOnboardingService.proposal(...)` / `referenceBaseAmount(cashShare, days, mean)` : la proposition par profil est `cashShare × taux(profil, actif)`, pas `cash / (jours × mean)` ; garder `referenceBaseAmount` pour le chemin « autonomie en jours » de l'utilisateur et l'optimiseur (`ReferenceBase`). `AMOUNT_SCALE = 2` / `RoundingMode.DOWN` : à relever (≥ 4 décimales) ou à remplacer par le report de reliquat (`min_buy_usd`), sinon l'invariance casse sous ~700–3 600 USDC (§1).
- `ModulationDefaults.AUTONOMY_DAYS` (`Limit.of("90", "7", "365")`) et ses bornes : borne haute à ≥ 3 100 j si le niveau passe par l'autonomie ; les bornes sont en code (la base ne peut pas les relever), validées par `RainbowModulationControlService.check`, exposées par `RainbowLiveOnboardingController.AutonomyRange` et lues par `page-home.js` / `admin/modulation.js`. Alternative préférée : un taux de base par profil et par actif (table en code comme `ModulationDefaults.ATH_CAPS`, sans migration ; ou colonnes admin éditables).
- `StartingProfile` : les bandes PRUDENT(20, 50), BALANCED(30, 70), DYNAMIC(40, 90) dépassent le plafond (§2) ; soit passer à 0–100 pour les 4 profils avec le niveau de base en seul levier, soit recaler le niveau sous bande. `FULL(0, 100)` est déjà neutre. `UserModulationService.applyProfile` pose la bande sans toucher à la base : prévoir qu'il pose aussi la base du lien.
- Partage du cash entre liens : `proposals(...)` divise le cash du wallet par le nombre de liens (BTC, ETH et PAXG si lié), alors que C4 mesure 50 / 50 BTC + ETH. Avec PAXG lié chaque part vaut cash / 3 (non mesuré, plus prudent) ; à fixer explicitement (parts par profil, PAXG sans niveau).
- Base de données : aucune migration si le taux par profil est une constante de code ou si seules les bornes changent. Migration Flyway (`V5__…sql`, idempotente comme `V4__drawdown_caps.sql`) seulement si les taux sont des paramètres admin (colonnes sur `rainbow_modulation_control`). Utilisateurs existants : les bases des liens (`RainbowLiveBinding.baseAmount`) et les réglages de bande (`UserTradingSettings`) sont déjà persistés et ne changent pas tant que l'utilisateur n'applique pas une proposition ; ceux qui ont appliqué la proposition à 90 j dépassent leur plafond (§2, C) ; un passage au mode Full Autonome devra réécrire base et bande de leurs liens (décision à prendre dans l'étape d'implémentation).

## Limites
- **Un seul grand bear par actif (2021-22)** : la marge de BTC repose sur un événement ; un repli plus profond dépasserait le plafond. Les marges mesurées (+1,3 à +5,5 pts) en sont le prix, pas une garantie.
- Frais et glissement simplifiés (barème statique), aucun minimum d'ordre ni arrondi d'exchange modélisés ; pas de test sur le live réel.
- Wallet 50 / 50 BTC + ETH seulement ; PAXG exclu ; la marge du wallet (4–15 pts) n'est pas exploitée (f ≤ 1 par règle).
- Niveaux calés en bande neutre, puissance ×1, biais 0 : aucun balayage de bande, de puissance ou de biais ; une configuration non rejouable avec le code existant n'est pas extrapolée.

## Écarts de périmètre
- Capital du wallet : « capital normalisé de C3 » lu comme la somme des deux capitaux de C3 (4 909 USDC), parts de 50 % ; le taux d'un lien est rapporté à son cash alloué (cohérent avec l'onboarding).
- Bande du profil = min / max seulement (puissance, biais, marge inchangés), conformément à `applyProfile`.
- Niveaux k = 1 « sans relancer de calage coûteux (cache de C3) » : recalculés dans le run (≈ 1 300 rejeux par actif, quelques secondes) et vérifiés identiques à `target/exposure-calibration.json` (écart max 0) plutôt que lus dans un cache.
- Ordres minimaux et arrondis : chiffrés par le calcul (§1), pas par rejeu (variante « coûts complets » exclue : une seule configuration).
