# Roadmap — Fil rouge : automatiser le DCA intelligent (Rainbow ATR)

Trace de la réflexion du 2026-10-04 (suite de `prompt-analyse-fil-rouge-dca-automatise.md`). Une étape = compilée + testée + doc `docs/` à jour (sans historique). **Un point avec Clem à la fin de chaque étape** avant de rédiger la suivante. Source de vérité de la logique : `tools/pine/rainbow_dca_v4_atr_moon.pine`.

## Décisions actées (2026-10-04)

- **Paramétrage** : 2 jeux Bull/Bear par actif, pas de Sideways. Presets pine : BTC = `BTC Perso Bear` / `BTC Perso Bull` ; ETH = `ETH Perso Bear/Bull Tuned` ; PAXG = `PAXG Perso Bear/Bull`.
- **Trend** : `TrendAnalyzer` simple d'abord (pas de double indicateur global/local). Constantes par défaut pour les 3 actifs, **à benchmarker par actif** (noté, pas fait). Mapping de `RANGE` : variantes comparées au rejeu (garder le jeu précédent / → Bear / → Bull), pas de choix a priori. Jeu actif réévalué chaque jour, état de la machine conservé au changement de jeu.
- **Delta 23:55 / 00:05** : accepté pour l'instant ; son importance réelle sera mesurée plus tard sur historique (nécessite de l'H1 multi-années, absent de la DB).
- **Validation** : pas de bench d'optimisation automatique. Un rejeu Java sur plage + graphique HTML autonome (CSV) reproduisant le rendu du pine, comparé à l'œil jour par jour par Clem sous TradingView. La « parité 60 cas » actuelle est Java == port Python du pine v3, jamais confrontée à TradingView : elle ne vaut pas preuve.
- **Données de comparaison** : BTC/ETH = Binance (ce que Clem regarde sous TradingView ; achats réels sur OKX), PAXG = Kraken en réel (historique D1 public limité, à vérifier ; sinon PAXGUSDT Binance).
- **Contrat de l'indicateur** : « bête » (pas d'historique inutile), quantification hors Rainbow.
  - L1 `RainbowAtrIndicator` : calcul pur sur fenêtre D1 (warmup seulement) → sma, atr, bornes, zone. Aucun état.
  - L2 `RainbowAtrStrategy` : machine d'états en fonction pure `step(état, sortie L1, jeu de params, athRef) → (signal, nouvel état)` (armements, cooldown, verrou DOWN2, moon, réserve). Le rejeu sur plage est un fold de `step` ; l'exécution quotidienne appelle le même `step`.
  - L3 `AthReference` : ATH par **actif** (pas par user), valeur + date, semé sur le long historique (Binance), mis à jour chaque jour. Ordre à figer : ATH de la veille pour l'entrée moon (« clôture > ATH »), ATH du jour (bougie incluse) pour la distance.
  - Signal **décomposé** : `multZone` (×3/×2/×1/×0,5), `facteurAth` (achat), fraction de vente, `facteurAthVente`, jeu actif, trend. Jamais de montant.
  - L4 `Sizer` : propre à l'user/wallet/actif ; achat = multiple de la base, vente = fraction de la position réelle ; plafond global sur le multiplicateur effectif ; contraintes wallet. Une implémentation de **référence** (`base × multZone × facteurAth`, sans plafond) reproduit le pine pour la comparaison.
  - État persisté par **(user, actif)**, chargé/sauvé par un orchestrateur (pas d'accès DB dans la Strategy).
- **Hors contrat, à revoir plus tard** : sortir la modulation ATH du Rainbow vers la couche exposition ; budget par période ; lissage du facteur ATH au changement de trend.

## Étapes

| # | Étape | Statut |
|---|---|---|
| 1 | Étude de compatibilité + socle moteur (L1/L2/L3, contrat `RainbowSignal`, Sizer de référence) | codée + testée (2026-10-04), point avec Clem à faire |
| 2 | Trend → Bull/Bear + rejeu Java sur plage + graphique HTML (CSV) vs pine v4 | codée (2026-10-04), comparaison de Clem sous TradingView à faire |
| 3 | Exécution quotidienne en dry-run (orchestrateur, état user-actif, `AthReference` en DB, Trend → jeu) | à faire |

Hors roadmap (roadmap suivante) : wallet réel (`WalletSnapshot`), sizing réel et plafonds, curseur d'exposition, risque macro, exécution réelle (dry-run obligatoire avant), paramétrage automatique d'un actif quelconque, bench du delta 23:55/00:05.

## Étape 1 — Étude + socle moteur
- **Étude (lecture seule)** : (a) les `Strategy` du Tree sont-elles sans état ? comment brancher L2 (état en entrée/sortie) via un adaptateur/orchestrateur ; (b) où stocker `AthReference` et l'état user-actif (entités, migration `ddl-auto`) ; (c) mesurer la fenêtre de warmup suffisante pour l'ATR (RMA) vs calcul depuis la 1re bougie, sur BTC/ETH/PAXG ; (d) historique D1 Kraken PAXG.
- **Code** : extraire `step` de `RainbowAtrEngine#simulate` (la boucle devient un fold), séparer L1/L2, introduire `AthReference` injecté, `RainbowSignal` décomposé, interface `Sizer` + implémentation de référence.
- **Critères** : non-régression stricte (fold de `step` == `simulate` actuel sur les cas golden + 60 cas parité) ; `RainbowAtrEngineTest` vert ; doc `04-market-data.md` à jour, dont la mention honnête de la parité (Java == port Python, pas TradingView).
- **Résultats de l'étude (2026-10-04)** :
  - (a) Les `Strategy` du Tree sont sans état (`evaluate(contexte, paramètres)`) : L2 reste une fonction pure `step` hors interface `Strategy`, branchée par un orchestrateur qui charge/sauve l'état (étape 3).
  - (b) Stockage : à décider à l'étape 3 (entités `AthReference` par actif et état par (user, actif) ; `ddl-auto=update` en dev et prod, donc pas de migration SQL à écrire).
  - (c) Warmup ATR (RMA amorcée au début de la fenêtre vs depuis la 1re bougie, BTC/ETH/PAXG Binance D1, périodes 21 et 28) : 5 × période ⇒ écart max ~1-2 % ; 10 × période ⇒ ≤ 0,02 %. Retenu : `max(SMA, 10 × ATR)` (`RainbowAtrDataset.recommendedWindow`).
  - (d) Kraken PAXG D1 public : seulement ~720 dernières bougies (~2 ans) ; pour un long historique, PAXGUSDT Binance.
- **Réalisé** : `RainbowAtrBand` (L1), `RainbowAtrState` + `RainbowAtrStrategy#step`/`stepInvalid` (L2), `AthReference` (L3), `RainbowMoon` (automate moon unique), `RainbowSignal`, `Sizer` + `ReferenceSizer` ; `simulate` = fold de `step` ; `RainbowAtrIndicator` réduit à L1. Non-régression : parité 60 cas identique avant/après (sortie à 6 décimales), `RainbowAtrStepTest` (simulate == fold manuel de `step`).
- **Point**.

## Étape 2 — Trend → Bull/Bear + rejeu vs pine
- Sélection du jeu par la Trend à chaque jour (variantes de `RANGE`), preset par actif, constantes Trend par défaut.
- Rejeu Java sur plage (CSV D1) avec Sizer de référence ; sorties : CSV jour par jour + **HTML autonome** (lightweight-charts) reproduisant le rendu du pine (bornes ATR, zones, achats/ventes, ATH, moon, réalisé/potentiel/total). Mode « preset fixe » (sans Trend) pour la comparaison propre au pine v4. Style/coût du graphique : demander avant de lancer.
- Optionnel, à décider au point : pine v5 « auto » (détection de l'actif, Trend portée en pine, jeu Bull/Bear par barre).
- **Réalisé (2026-10-04)** : jeux Bull/Bear du pine en Java (`RainbowAtrPresets`), `RainbowSetSelector`, `RainbowAtrReplay`, `RainbowAtrReplayMain` (5 modes × BTC/ETH/PAXG, plage 2024-06-10 → dernière bougie close, sorties dans `target/rainbow-replay/`). Style du graphique = visualiseur « Rainbow DCA Bench » (validé par Clem). Données : D1 Binance (BTC/ETH/PAXGUSDT) ; PAXG en Binance et non Kraken (historique Kraken public ~720 j < plage).
- **Point** : Clem compare Java vs pine ; écarts à expliquer avant d'aller plus loin.

## Étape 3 — Exécution quotidienne en dry-run
- Orchestrateur : charge l'état (user, actif), appelle L1 → Trend → choix du jeu → L2 `step` avec `AthReference` → signal → Sizer (wallet mock au départ) → sauvegarde de l'état. Remplace/adapte le bench live (`08-rainbow-bench-grandeur-nature.md`).
- Test « état persisté == état rejoué » ; cron désactivé par défaut.
- **Point** : cadrage de la roadmap suivante (wallet réel, sizing, exposition, risque macro, exécution).
