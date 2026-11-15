---
statut: journal
sujet: Prompts du chantier « simulateur complet » — cadre commun, ordre des lots
modifié_le: 2026-11-15
empreinte: d74c89c364
---
# Prompt — Simulateur complet de TradeIO-5 : cadre commun

Prompts d'implémentation, **à supprimer une fois le chantier livré** (les décisions vivent dans `decisions/`, l'état dans `architecture/`). Un prompt par lot, à envoyer dans une conversation de code **séparée**. Les lots suivants sont écrits un par un, après l'analyse du précédent (elle peut modifier leur contenu).

| Lot | Prompt | Contenu |
|---|---|---|
| 1 | [`prompt-simulateur-lot1-coeur-parite.md`](prompt-simulateur-lot1-coeur-parite.md) | cœur partagé live/simu + rejeu mono-actif (BTC) + parité prod |
| 2 | [`prompt-simulateur-lot2-cash-commun.md`](prompt-simulateur-lot2-cash-commun.md) | multi-actifs, cash et exposition communs, parité par wallet |
| 3 | [`prompt-simulateur-lot3-frais.md`](prompt-simulateur-lot3-frais.md) | cache global des frais (24 h) + frais, minimum d'ordre et carry dans le rejeu |
| 4 | [`prompt-simulateur-lot4-episodes-gain.md`](prompt-simulateur-lot4-episodes-gain.md) | timeline des épisodes + répartition du gain (exposition / timing / frais) |
| 5 | [`prompt-simulateur-lot5-ecran-expert.md`](prompt-simulateur-lot5-ecran-expert.md) | type de simulation libre (bougies D1) + écran Expert (graphiques, épisodes, répartition du gain) |
| 6 | à écrire | drawdown max par profil (mesure + signalement) |

## Contexte (valable pour tous les lots)

Tu travailles sur TradeIO-5 (`D:\Documents\Spring\TradeIO-5`, back Spring Java 21 + front `/app`). Propriétaire : Clem. Échanges en français, réponses courtes et techniques. Ne lui parle jamais par numéro de chapitre ou de paragraphe : décris l'élément dont tu parles.

### Lecture obligatoire avant de coder
1. `CLAUDE.md`, `docs/README.md`, `docs/CODING_RULES.md`.
2. Études cadre : [`etude-optimisation-mode-autonome.md`](etude-optimisation-mode-autonome.md) (surtout le but, le rejeu intégral et les décisions de Clem), [`etude-protocole-calibrage-couches.md`](etude-protocole-calibrage-couches.md) (timeline des épisodes).
3. Architecture : `architecture/13-espace-expert.md`, `08-rainbow-live.md`, `08c-rainbow-chemin-achat-wallet.md`, `08d-rainbow-modulation.md`, `10-execution-plan.md`, `09-trend.md`.
4. `decisions/` (notamment `espace-expert.md`, `modulation-exposition-puissance.md`, `wallet-reel-capital-commun.md`, `regles-transverses.md`) : ne re-questionne pas un point tranché.

### Pourquoi
La simulation actuelle de l'Espace Expert ne mesure pas ce qui tourne en prod : son capital est calculé après coup (`SimulationEngine#wallet` : capital = max(investi − vendu)) et elle ne rejoue ni bande, ni puissance, ni biais, ni cash commun, ni frais, ni minimum d'ordre, ni carry. But : un rejeu de l'historique qui se comporte comme la prod, capital fixé au départ, pour optimiser un mode « Full Autonome ».

### Décisions de Clem (tranchées, 2026-11-14)
- Emplacement : Espace Expert, nouveau type de simulation à côté de `TREND` / `RAINBOW` / `AFFECTATION` (`SimulationType`), mêmes graphiques synchronisés, calcul côté Java, front sans recalcul. La simulation actuelle reste l'outil de réglage visuel du signal : on ajoute, on ne remplace pas.
- Périmètre : les liens réels de l'utilisateur (actifs, affectations, bande, puissance, biais, marge, montant de base), modifiables avant lancement. **Mono-actif d'abord**, multi-actifs au lot 2.
- Capital : de départ seul, sans apports ; défaut = valeur du wallet réel de l'utilisateur (cash USDC + actifs au prix), modifiable. Cash épuisé ⇒ plus d'achat.
- Chaîne rejouée, **même code que le live, sans I/O**, une seule implémentation : Trend Mix → jeu Bull/Bear → signal Rainbow → montant de base du lien → modulation (bande, puissance, biais) → cash disponible → frais → minimum d'ordre et carry.
- Source des candles = celle du lien (comme le live, sans fallback silencieux : `etude-source-candles-provider-du-lien.md`).
- Frais : taux réels par exchange et par route ; cache global, durée de vie 24 h, réutilisable (ajout d'un lien).
- Objectif mesuré : valeur finale du wallet (USDC + actifs au prix de marché) sous plafond de drawdown. Référence : DCA fixe de même budget sur la même courbe ; HODL à exposition constante en lecture secondaire.
- Plafond de drawdown par profil (`StartingProfile`) : Prudent −15 %, Équilibré −25 %, Dynamique −40 %, Full −60 % ; premier lot = mesure et signalement, aucun effet sur les ordres.
- **Test de vérité** (porte de sortie du chantier) : rejouer octobre 2026 → aujourd'hui et retrouver les actions et montants réellement enregistrés. Tant que la parité n'est pas atteinte, le simulateur ne sert pas à décider.

### Repères dans le code (vérifiés le 2026-11-14, à re-vérifier)
Racine : `src/main/java/fr/ses10doigts/tradeIO5/`.
- Simulation actuelle : `service/expert/simulation/` — `SimulationEngine` (pur), `ExpertSimulationService` (async, garde-fous : 1 simu active par user, 10 demandes/60 s, pool 2 threads, file 10, résultats en mémoire TTL 30 min), `DailyDataLoader` (D1 via `MarketDatasetEngine#getDailyDatasetForAsset`, profondeur 5000), `SimulationRegimes`, `SimulationType`, `SimulationInput`, `SimulationRequestResolver`, `SimulationEligibility`. DTO : `model/dto/expert/ExpertSimulationDtos`.
- Rejeu du signal : `service/dca/atr/RainbowAtrReplay` (`DayRow`, `run`, `runFull`, barres inactives `inactive(k)`), `RainbowSetSelector`, `RainbowAtrStrategy#step`, `ReferenceSizer`, `RainbowMoon`, `AthReference`.
- Signal live : `service/dca/atr/bench/RainbowTrendLiveService#run` — produit `LiveDemand`. **Point clé** : le moteur suit une *position virtuelle* (état persisté `RainbowLiveEngineState`) pour le signal ; la vente live = fraction du signal × position **tradable réelle** du lien (`LiveDemand#sellQtyOfTradable`). Le rejeu doit reproduire cette séparation.
- Dimensionnement live : `service/dca/atr/bench/` — `LiveDemand`, `LiveSizing` (package-private ; écrit dans `RainbowLiveLiveBlock`), `LiveModulation` (package-private), `CashLedger`, `ExposureLedger`, `ExposureBook`, `PortfolioReading`, `RainbowLiveExecutionService` (passe quotidienne : `runUser`, `runLinks`, `runLink`). Calcul pur : `service/modulation/ExposureModulator`, constantes `ModulationDefaults`, bornes admin `RainbowModulationControl(Service)`, réglages user `UserTradingSettingsService#effectiveSettings`.
- Carry / minimum d'ordre : `service/execution/plan/OrderPlanner` (`withCarry`, `effectiveAmount`, `BELOW_MIN`), `AssetInput`.
- Frais et routes : `service/connector/fee/TradingFeeProvider` (OKX, Kraken ; cache mémoire 24 h par clé et instrument), `service/market/instrument/` (`PathFinder`, `FeeTest`, `BookSlippage`, `InstrumentCatalog`, `ExecutionDefaults`), `service/dca/atr/binding/BindingPathQuoteService`.
- Entités de parité : `model/entity/dca/bench/RainbowLiveRun` (par affectation et jour), `RainbowLiveBindingRun` (par lien et jour, bloc `live` = `RainbowLiveLiveBlock` : cashUsd, positionQty, tradableQty, actionType, actionAmountUsdc, actionQuantity, candleSource, modulation JSON), `RainbowLiveEngineState`, `RainbowLiveBinding` (baseAmount, bagPercent, priority, minBuyUsd, preferredRoute), `AssetAffectation`.
- Front : `static/assets/app/js/expert-common.js`, `expert-chart.js`, `expert-preset-screen.js`, `page-expert-{trend,rainbow,affectation}.js`.

### Règles transverses
- Cotation tout en USDC, aucun nœud fiat (`ExecutionDefaults.FIAT_CURRENCIES`), USDT abandonné.
- JDK 21 obligatoire (voir `docs/operations/configuration.md`). Secrets gitignorés.
- Schéma DB : Flyway actif — toute modif d'entité = nouveau `V<n>__*.sql` idempotent, jamais de migration appliquée modifiée ; répéter en recette avant la prod (`operations/flyway.md`, `operations/recette.md`).
- Bench coûteux ou graphique lourd : demander à Clem avant de relancer. Ne pas relancer `tools/rainbow-replay/` sans raison.
- Une seule implémentation pour le live et le simulateur : extraire le cœur, ne jamais dupliquer la logique. Constante métier dupliquée interdite.
- Garde-fous existants à respecter : `RainbowLiveNoExchangeDependencyTest`, `ExecutionPlanArchitectureTest` (le cœur extrait ne dépend d'aucun port d'ordre ni exchange).
- Non-régression : à réglages neutres, le live produit exactement les mêmes montants qu'avant l'extraction (`LiveDemandTest`, tests de modulation existants doivent rester verts).

### Comment procéder (chaque lot)
1. État des lieux court de ce que le lot touche ; pose à Clem les questions ouvertes **avant** de coder, plutôt que de deviner (ne re-questionne pas `decisions/`).
2. Mode itératif : un lot, Clem regarde, on ajuste. Tests verts (`mvn test` JDK 21) avant de rendre la main.
3. Documente dans la même session : `architecture/13-espace-expert.md` (+ `08*`, `10`, `api/` touchés), retire ce qui n'est plus vrai, reporte les décisions nouvelles dans `decisions/`, puis `python tools/doc-police/doc_police.py index` et `check`. Ne commite et ne déploie que sur demande de Clem.
