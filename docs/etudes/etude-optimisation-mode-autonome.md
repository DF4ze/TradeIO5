---
statut: journal
sujet: Optimiser TradeIO vers un mode Full Autonome — définition du bon paramétrage, instrument de mesure, méthode, agencement des briques
modifié_le: 2026-11-16
empreinte: 460dbae663
---
# Étude — Optimiser TradeIO vers un mode « Full Autonome »

Statut : **étude, aucun code**. Ouverte le 2026-11-14. But fixé par Clem : « TradeIO doit permettre un mode Full Autonome qui sait jouer sur tous ses atouts pour gagner de l'argent ». Cette étude est le document maître ; les sujets détaillés (timeline des actions, retour dans la bande, risque ATH) sont dans [`etude-protocole-calibrage-couches.md`](etude-protocole-calibrage-couches.md).

Sources lues : `architecture/` 01, 02, 05, 08, 08d, 09, 13 ; `decisions/` ; `calibration/calibration-rainbow-atr-v3-regime.md` ; `etude-bench-auto-trend-mix-nouvel-actif.md` ; spec V1 ; `mindset/` ; `SimulationEngine` ; logs prod du 2026-10-15 au 2026-11-14.

## 0. Thèse

1. On n'optimise que ce qu'on a défini : §2 propose la définition d'un « bon paramétrage ».
2. Le bench doit rejouer toute la chaîne avec un vrai capital (§3), sinon il règle autre chose que le gain réel.
3. Hypothèse H0 (à vérifier en premier) : l'argent se joue dans **combien de capital est exposé et quand** (couche portefeuille), moins dans les réglages fins du Rainbow ou de la Trend (§1).
4. La recherche automatique a déjà échoué deux fois hors échantillon : espace réduit, score robuste, validation en conditions réelles (§4).
5. Le mode autonome est une boucle fermée mesure → recalibrage, avec garde-fous, par paliers (§6, §8).

## 1. Ce que l'existant a déjà prouvé

| Constat | Source | Ce que ça implique |
|---|---|---|
| Recherche auto des jeux Bull/Bear, même sous garde-fous : meilleure en échantillon, **moins bonne hors échantillon dans 4 cas sur 4** | étude Trend Mix §7 | surapprentissage ; un score d'entraînement ne prouve rien |
| Grille du Trend Mix (≈ 14 400 combinaisons) : aucun edge robuste ; une Trend « oracle » ne fait guère mieux que le défaut | idem | la Trend n'est pas le levier ; ne plus l'optimiser, seulement contrôler sa qualité (K1–K7b) |
| Modulation ATH à l'achat = « levier n°1 » du bench (score ÷ 3 à 9 sans elle) | calibration v3 | ce n'est pas un talent de timing : c'est du **capital déployé en plus dans les creux** (280 % du budget DCA fixe en moyenne, jusqu'à 478 %). Effet de taille de mise, donc de risque |
| Bande, puissance, biais, carry, cash commun, frais, minimum d'ordre : absents du rejeu | `13-espace-expert.md`, `08d` | le bench règle le signal, la prod exécute le signal **modulé** : deux objets différents |

Lecture d'ensemble : chaque fois qu'on a mesuré à capital égal, l'avantage du timing fin est faible ou nul ; chaque fois qu'un jeu « gagne », il déploie plus. D'où l'hypothèse **H0 : l'essentiel du gain vient de l'exposition (couche portefeuille), pas du réglage du signal**.

## 2. Qu'est-ce qu'un bon paramétrage

### 2.1 Cadre de mesure : le wallet fermé réel
Capital de départ `C0` en USDC fixé **avant** le rejeu, sans apports ; toute la chaîne rejouée comme en prod (Trend → jeu → signal → modulation → cash → frais → minimum d'ordre et carry) ; capital commun aux actifs joués ensemble.

### 2.2 Objectif
**Maximiser la valeur finale du wallet (USDC + actifs au prix de marché), sous contrainte de risque.** En pratique : rendement annualisé, avec un drawdown maximal du wallet à ne pas dépasser (tolérance de l'utilisateur, liée au profil de départ).

Références à battre, à capital égal : (a) HODL à exposition constante ; (b) DCA fixe de même budget ; (c) Rainbow sans Trend ; (d) 100 % USDC.

Référence principale retenue : **(b) DCA fixe**. Un jeu qui ne la bat pas hors échantillon n'apporte rien, quel que soit son score interne.

### 2.3 D'où vient le gain (décomposition obligatoire)
`gain vs référence = effet exposition + effet timing − frais` : exposition = exposition moyenne × mouvement du marché ; timing = prix d'achat / de vente vs prix moyen de la période ; frais = frais réels + spread. Elle tranche H0, se calcule par épisode de la timeline et s'additionne.

### 2.4 Un objectif différent par régime (cohérent avec `mindset/`)
Bear = accumuler (quantité par USDC, cash restant en fin de phase, drawdown) ; Bull = encaisser (USDC réalisés, prix de vente vs sommet, bag conservé). Chaque phase est valorisée au prix de marché à la bascule : la somme des phases égale le total ; un jeu Bear se juge aussi sur ce qu'il remet au Bull suivant.

### 2.5 « Bon » = robuste, pas « meilleur »
Un jeu n'est retenu que s'il tient quand on le bouscule : **plateau** (voisins ±1 cran presque identiques), **date de départ** (décalée de 1 à 30 jours), **source des candles** (le Rainbow PAXG change beaucoup selon le provider), **hors échantillon** (phases jamais vues), **non dégénéré** (vend et rachète, capital déployé plafonné, K9 / K11).

### 2.6 Score proposé
- Vetos : drawdown > tolérance, jeu dégénéré, capital dépassé.
- Score = **médiane** du rendement excédentaire vs le DCA fixe (b) sur l'ensemble des rejeux bousculés, moins une pénalité de dispersion. On retient le centre du plus grand plateau, jamais le maximum.
- Le score de Clem (harmonique réalisé / potentiel) reste affiché comme lecture secondaire. Les KPI K8–K16 de l'étude Trend sont réutilisés tels quels plutôt que réinventés.

## 3. L'instrument : un rejeu intégral fidèle à la prod

Livré (simulateur complet) : même code que le live, sans I/O, `C0` fixé avant le rejeu, cash commun, frais, minimum d'ordre, carry, timeline et décomposition §2.3 ; test de vérité = parité avec les runs de prod. Description : [`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md), [`../architecture/13a-rejeu-politique-ath-couts.md`](../architecture/13a-rejeu-politique-ath-couts.md).

## 4. Méthode : chercher sans se raconter d'histoires

Contrainte de fond : BTC D1 depuis 2017 = ~3 cycles et quelques dizaines de phases ; avec ~30 paramètres par jeu, toute recherche trouve un « gagnant » par hasard (K16 : 1 paramètre libre pour 10 régimes observés). Méthode : tri par sensibilité (4 à 6 paramètres libres, le reste figé) → recherche dans l'espace réduit (tirage quasi aléatoire, pas de montée coordonnée), chaque lot repartant du meilleur plateau → validation walk-forward + bousculades du §2.5, **comptage des essais** (plus on essaie, plus la barre monte) → champion / challenger en prod (le seul vrai hors-échantillon). Le « moment actuel » se juge sur plusieurs horizons de phases (récentes pondérées plus fort, pire horizon en garde-fou), pas sur J-7 / J-14 / J-21. Moteur livré : [`../architecture/15-optimiseur.md`](../architecture/15-optimiseur.md).

## 5. Inventaire des atouts et rôle cible

| Brique | État | Rejouable sur l'historique ? | Rôle dans le mode autonome |
|---|---|---|---|
| Trend Mix | en prod | oui | L1 : choisit Bull / Bear. Figé, contrôlé, non optimisé |
| Rainbow ATR (jeux Bull / Bear) | en prod | oui | L1 : exécutant (quand acheter / vendre vs le cours) |
| To the moon | en prod | oui | L1 : assurance du bag en Bull |
| Modulation ATH | en prod (dans le signal) | oui | à déplacer en **L2 risque** (taille de mise), plafonnée |
| Bande, puissance, biais, marge ; carry, minimum d'ordre, frais | en prod (live), rejeu livré | oui | L2 : pilotage de l'exposition ; coûts réels dans la mesure |
| Retour dans la bande (A + C) | décidé, non codé | à rejouer | L2 |
| OI / funding / liquidations, zones de rejet, carnet, calendrier macro, veille média, pipeline Opinion → Scenario → Decision | divers (en observation ou en pause) | partiel ou non | L3 secondaire ; ne pas relancer la branche Opinion, réutiliser ses indicateurs |
| Supertrend en stop suiveur | idée | oui | L2 : protection |
| ETF flow BTC / ETH | historisé en base, verdict « validateur J-1 » | oui (profondeur à vérifier) | L3 : premier candidat |
| DXY, SP500, NASDAQ ; plafond stablecoin ; Fear & Greed | lus au fil de l'eau | historique à constituer (faisabilité à vérifier par provider) | L3 : score de risque marché (`riskCursor`) |

Règle d'entrée en L3 : une source n'agit sur les montants que comme **modulateur borné** (`riskCursor`). Rejouable ⇒ mesurée au bench d'abord. Non rejouable ⇒ tourne « à blanc » en prod : son avis est journalisé avec ce qu'il aurait changé, et jugé après plusieurs mois.

## 6. Architecture cible : la boucle autonome

`Données → Régime (Trend) → Signal (Rainbow) → Dimensionnement (L2 : budget, bande par régime, plafond de risque, retour dans la bande) → Contexte (L3, modulateur borné) → Exécution (plan, Fee Test, plafonds, kill switch) → Mesure (journal, timeline, décomposition) → Recalibrage (champion / challenger)` puis retour au début.

Paliers d'autonomie :
| Palier | Contenu | État |
|---|---|---|
| N1 | signal quotidien, exécution manuelle | aujourd'hui |
| N2 | exécution automatique, presets fixes | presque (cron d'exécution désactivé) |
| N3 | L2 automatique : exposition par régime, budget de risque, retour dans la bande | à concevoir |
| N4 | recalibrage automatique borné : challenger proposé, testé à blanc, promu sous conditions | à concevoir |
| N5 | contexte L3 actif | après N4 |

Garde-fous communs : tout changement automatique est borné, journalisé, réversible ; validation humaine au début de chaque palier, levée ensuite.

## 7. Leviers, classés par gain attendu (hypothèses à confirmer par le §2.3)

1. **Exposition par régime** : bande Bull ≠ bande Bear, allègement à la bascule Bear (« 50 % d'exposition à la déclaration d'un Bear ? »). Si H0 est vraie, c'est le premier levier.
2. **Budget et risque** : plafond de mise (ATH × zone), autonomie du cash (être à sec avant le creux est une perte sèche), rôle de la Puissance.
3. **Vente en Bull** : fraction, délai, moon — c'est là que le gain devient réel.
4. **Frais et routes** : fréquence et taille des ordres, surtout PAXG.
5. **Réglages fins L1** par régime, dans l'espace réduit.
6. **Contexte L3**.
7. Trend : rien à gagner d'après les mesures ; contrôle seulement.

## 8. Feuille de route

Étapes et portes de sortie : [`roadmap-moteur-optimisation.md`](roadmap-moteur-optimisation.md) ; ordre : §11.

## 9. Limites

- Aucune méthode ne garantit un gain : peu de cycles, le passé ne se répète pas à l'identique.
- Tant que E1 n'est pas faite, les chiffres des benchs existants ne sont pas comparables à la prod.
- H0 peut être fausse ; l'étape E2 est faite pour le savoir avant d'investir ailleurs.
- Cadre inchangé : tout en USDC, aucun noeud fiat.

## 10. Décisions de Clem (2026-11-14)

- **Objectif** : valeur finale maximale du wallet sous plafond de drawdown — validé. Plafonds par profil fixés : voir [`../decisions/espace-expert.md`](../decisions/espace-expert.md).
- **Référence principale** : DCA fixe de même budget (le HODL à exposition constante reste affiché en lecture secondaire).
- **Capital du bench** : capital de départ seul, sans apports.
- **Premier chantier** : le simulateur complet (§3), dans l'Espace Expert.
- **Drawdown maximal réglable par profil** : c'est la « prise de risque » que Clem veut introduire. Premier lot : le simulateur le mesure et signale un dépassement, sans effet sur les ordres.

## 11. Moteur d'optimisation automatique (analyse du 2026-11-16)
Décisions : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md).

**Tension** : « presets qui gagnent actuellement » = peu de données récentes (12–24 mois ≈ 1–2 phases) ⇒ risque max de surapprentissage. Réponse : score pondéré multi-fenêtres ancrées (récente ×3, 24 mois ×2, historique ×1) + veto sur la pire fenêtre ; centre du plateau, pas le maximum ; dérive bornée (rayon δ autour du preset en place, interpolation, hystérésis, promotion si gain > 2σ des perturbations).

| Module | Optimise | Cadence | Sortie |
|---|---|---|---|
| A. PresetOptimizer (L1) | Rainbow Bull/Bear **par actif**, Trend figée | tous les X jours | challenger `RainbowPreset` SYSTEM |
| B. PolicyOptimizer (L2) | paramètres d'une politique de pilotage | après A | politique évaluée sur l'état du wallet utilisateur |
| C. Orchestrateur | champion / challenger, bornes, journal | cron | promotion auto-bornée ou refus tracé |

- **Politique L2** = fonction (régime, exposition, écart à la cible, PnL / drawdown) → (bande, puissance, biais, amplitude ATH). ~10–14 paramètres ; jamais une trajectoire optimisée jour par jour (oracle). Presets calibrés sur wallet de référence normalisé (partagés par tous les users) ; la politique, elle, lit le wallet de l'utilisateur. Plafonds de drawdown par profil = contraintes de l'objectif (§2).
- **Preuve** : walk-forward **imbriqué** (à chaque date T : optimiser sur ≤ T, déployer sur [T, T+X], enchaîner) comparé à un preset statique. Coûteux : demander avant de lancer. Le shadow prod (champion / challenger) n'est qu'un garde-fou anti-catastrophe (trop peu de trades en D1).
- **Garde-fous** : aucune donnée > J-1 ; plafond ATH × zone dans le bench ; comptage des essais ; bornes dures sur toute valeur promue ; tout changement journalisé et réversible.

**Ordre** : (1) hook de politique dynamique dans `MultiLinkReplayEngine` + test H0 avec 2–3 règles manuelles ; (2) ATH détaché du preset en réglage utilisateur plafonné ; (3) moteur de recherche headless (sensibilité → Sobol / halving, sortie JSON) ; (4) PolicyOptimizer ; (5) PresetOptimizer + walk-forward imbriqué ; (6) orchestrateur auto-borné + cron.

**Ouvert** : valeur de X (cadence) ; bornes δ de dérive ; forme exacte de la politique (règles par régime vs fonction continue) ; budget de calcul du walk-forward imbriqué.

### 11.1 Verdict H0 (étape 1, 2026-11-16)
Hook livré : [`../architecture/13a-rejeu-politique-ath-couts.md`](../architecture/13a-rejeu-politique-ath-couts.md). Mesure : `policy-compare`, base locale copie de prod, un wallet par actif (liens des autres actifs coupés), départ 100 % USDC, sans frais ; « 24 mois » = 2024-11-15 → 2026-11-15, « max » = plus longue plage atteignable (BTC, ETH 2018-07-12 ; PAXG 2025-04-23, Kraken ne sert que 754 bougies D1). Gain = écart à un DCA fixe de même budget. Tableau complet et lecture détaillée : [`etude-plafond-ath-mesure.md`](etude-plafond-ath-mesure.md) (« Mesure H0 »).

**Réponse : oui, la couche exposition pèse plus que le timing du signal, mais pas par toutes les règles.** Sur plage longue la perte vs DCA fixe vient presque entièrement du terme exposition (le Rainbow ne détient que ~5 % du wallet) ; une bande par régime (bull 20–90, bear 0–30) double la valeur finale mais triple le drawdown. Sur 24 mois le signe dépend de l'actif (effet de plancher Bull, pas un gain de timing prouvé). `EXPOSURE_GAP_BIAS` est indiscernable de la constante ; `BEAR_DERISK_RAMP` ne se déclenche jamais. **Levier retenu pour les étapes 4 / 5 : la bande par régime.**

**Limites** : (1) sans ordre de rééquilibrage (décision de Clem), « Bear ⇒ exposition basse » ne fait que couper les achats ; (2) presets non rejoués dans ce run ; (3) capital faible rapporté à 8 ans d'historique (wallet cash-limité) ; (4) une règle manuelle, une fenêtre : pas de preuve de robustesse (§2.5).

Plan d'implémentation : [`roadmap-moteur-optimisation.md`](roadmap-moteur-optimisation.md).

### 11.2 Plafond de mise ATH (étape 2, 2026-11-16)
Livré (rejeu seulement, live inchangé) : [`../architecture/13a-rejeu-politique-ath-couts.md`](../architecture/13a-rejeu-politique-ath-couts.md). Mesure par `policy-compare` (méthode du §11.1) : `CONSTANT` et `REGIME_BAND` × plafonds aucun / Prudent / Équilibré / Dynamique / Full. Tableaux et lecture détaillée : [`etude-plafond-ath-mesure.md`](etude-plafond-ath-mesure.md).

La mise de 10–15 × la base se confirme (jusqu'à ~23 × après modulation avec la bande par régime). Le plafond coûte peu en `CONSTANT`, nettement plus avec la bande sur plage longue (Prudent −17 %), et ne change pas le verdict H0 : la bande reste le levier dominant ; sur 24 mois Prudent efface son avantage.

**Limites** : (1) défauts par profil non validés (constantes de code) ; (2) capital faible rapporté à l'historique ; (3) une règle de bande, une fenêtre par actif, sans frais ; (4) l'écart avec §11.1 (BTC 24 m `CONSTANT` 2 483 vs 2 571 ; DCA fixe 2 111 vs 2 309) s'explique par les entrées implicites du simulateur (§11.3) : les comparaisons ne valent qu'à l'intérieur d'un même tableau.

### 11.3 Évaluateur et moteur de recherche (étape 3, 2026-11-16)
Livré : [`../architecture/15-optimiseur.md`](../architecture/15-optimiseur.md). Mesures complètes : [`etude-demo-l2-optimiseur.md`](etude-demo-l2-optimiseur.md).

**Reproductibilité (écart §11.1 ↔ §11.2).** Le chemin par défaut du simulateur dépend d'entrées implicites (capital = valeur du wallet réel du jour, plage glissante, réglages utilisateur non neutres) : les chiffres de §11.1 ne sont pas rejouables. Remède : `ScorerInputs` tout explicite + empreinte des bougies dans chaque rapport (mesures : [`etude-demo-l2-optimiseur.md`](etude-demo-l2-optimiseur.md)).

**Démo L2** (bande par régime × plafond d'achat ATH × montant de base, profil Équilibré, graine fixe) : entraînement sur des fenêtres 12 m / 24 m / historique finissant le 2025-11-15, **test hors échantillon sur les 12 mois suivants** (2025-11-15 → 2026-11-15), coûts complets statiques. Gain vs DCA fixe de même budget :

Tableau complet (essais, entraînement, hors échantillon, exposition / timing) : [`etude-demo-l2-optimiseur.md`](etude-demo-l2-optimiseur.md).

- **Bat-il le DCA fixe hors échantillon ?** BTC oui (+2,3 %), PAXG à peine (+1,0 %, sans mieux que le point de départ), ETH non (−2,0 %, pire que le départ : surapprentissage). Une seule période de 12 mois, ~200 essais : la barre de significativité monte avec le nombre d'essais, aucune de ces différences n'est établie.
- **Timing ou exposition ?** Le gain vient du timing (BTC +171) ; l'exposition est négative (−110 à −127) : le candidat n'est pas simplement « plus exposé ». Timing pur à l'entraînement : BTC +3,3 %, ETH +8,9 %. Sur l'historique complet tous les candidats perdent lourdement face au DCA fixe (−37 à −63 %) : terme exposition (−3 000 à −7 700 USDC).
- **Robustesse** : verdict « non robuste » sur les 3 actifs (bousculades de fin −15 / −30 j et voisins ±1 cran hors tolérance 25 %).
- **`baseAmount`** : libre, il tombe sur la borne basse (0,5 × la base) pour ETH. Gelé, ETH hors échantillon passe de −2,0 % à −0,3 %, BTC de +2,3 % à +2,8 % : le gain en % d'un candidat est mesuré contre un DCA fixe de **son propre** budget, ce qui récompense un petit budget quand le gain est négatif. À traiter avant l'étape 4 (référence DCA fixe à budget de référence commun).

**Budget de calcul** (mono-thread, un lien) : 10–13 ms par run sur 12 m simplifiée, 71–92 ms sur toutes les fenêtres, 75–81 ms en coûts complets. Une recherche L2 (≈ 200 essais) ≈ 4–6 s sur 4 threads ; étape 4 mesurée : 47 s (§11.4) ; étape 5 (walk-forward imbriqué mensuel sur 8 ans) ≈ 25 min sur 4 threads, jamais lancée sans demande.

### 11.4 Calibrage de la politique (étape 4, pré-étape 5 incluse, 2026-11-16)
Livré : mode `POLICY_L2`, plis annuels, évaluateur corrigé ([`../architecture/15-optimiseur.md`](../architecture/15-optimiseur.md)). Mesures : [`etude-calibrage-politique-l2.md`](etude-calibrage-politique-l2.md). Synthèse : 4 profils `GARDER_DEPART` mais **porte vers l'étape 5 non atteinte** : départ vetoé à l'entraînement dans 58 % des plis-profils (cible < 30 %), drawdown 12 m / 24 m ; résultat nul non concluant. Le DCA fixe de référence ne dépassait déjà pas le capital : l'écart de l'étape 4 était un effet de timing (fenêtre complète désormais hors score). Pénalité de dispersion : BTC 43 %, ETH 50 % (alerte).
