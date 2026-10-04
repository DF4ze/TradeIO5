# Roadmap — Bench grandeur nature Rainbow DCA ATR

Séquence le chantier ; les décisions vivent dans `prompt-analyse-rainbow-atr-bench-grandeur-nature.md` §4 (source de vérité). **Un point avec Clem à la fin de chaque étape** (questions soulevées, ajustements) avant de rédiger/lancer la suivante. Une étape = compilée + testée + doc `docs/` à jour (sans historique).

| # | Étape | Statut |
|---|---|---|
| 0 | Étude (lecture seule) | **fait** (décisions dans `prompt-analyse-rainbow-atr-bench-grandeur-nature.md` §4, pas de document d'étude séparé) |
| 1 | Back A — Persistance & presets | **fait (2026-10-03)** |
| 2 | Back B — Moteur câblé + jobs quotidiens | **fait (2026-10-03)** |
| 3 | Back C — API REST | **fait (2026-10-03)** |
| 4 | Front — Page dans `userPage` | **fait (2026-10-03)** |
| — | Étude séparée : slider « Taux d'exposition » | hors périmètre |

## Étape 0 — Étude
- État des lieux réel : scheduler (cron/fuseau UTC/activation), User/ownership, bougies D1 BTC/ETH/PAXG (provider, fallback, historique PAXG au mieux), persistance/migrations, `userPage`, `WalletSnapshot`, lib graphique.
- Architecture proposée + écarts éventuels avec §4.
- **Point** : valider l'étude (abstraction wallet existante réutilisable pour le mock ?).

## Étape 1 — Back A : persistance & presets
- Entités : `wallet mock` (1 par preset, 1000 USDC par défaut, cash + position, implémentation de l'abstraction wallet), `preset` (user × actif, params ATR/globals, fenêtre, baseAmount, actif/inactif), `run` (jour × actif × user × preset ; blocs 23:55 et 00:05 côte à côte ; snapshot de config ; close ; action fictive ; position/cash ; upsert idempotent par passe).
- Migration, repositories, presets par défaut (jeu global bench par actif ; pine historiques en référence ?), suppression preset => cascade historique.
- Critères : tests repo, idempotence upsert, clé user non codée en dur.
- **Fait le 2026-10-03** (doc : `architecture/08-rainbow-bench-grandeur-nature.md`). Classes réelles : `model/entity/dca/bench/{RainbowLivePreset,RainbowLiveMockWallet,RainbowLiveRun,RainbowLivePassBlock,RainbowAtrConfig,PortfolioView,RainbowLivePass,RainbowLiveAction}`, `repository/dca/bench/*`, `service/dca/atr/bench/{RainbowLivePresetService,RainbowLiveRunService,RainbowLiveDefaultPresets}`.
- Ajustements utiles à l'étape 2 : `upsertPass(preset, day, pass, RainbowLivePassBlock)` remplit `computedAt`/`configHash`/`cashAfter`/`positionAfter` ; le moteur fournit indicateurs + action (BUY/SELL: montant USDC + quantité > 0). Rejeu 23:55 conserve l'action d'origine. `PortfolioView.cash()/quantity(asset)` à passer au moteur pour le plafond de cash. Bornes `boundDown2/Down1/Up1/Up2/Up3` = SMA ± ATR×mult, à calculer côté moteur. Colonne du jour = `run_day` (`day` réservé H2). Presets : `baseAmount` uniquement dans la config ; actif/capital non éditables.
- **Point**.

## Étape 2 — Back B : moteur câblé + jobs
- Moteur `RainbowAtrEngine` **inchangé** (parité pine) ; plafonds de cash/position portés par le **service** : action du jour (events du dernier index, `trace=true`) dimensionnée sur le wallet mock (achat ≤ cash, vente ≤ position, `NONE` sinon), rejeu sur la fenêtre du preset, ATH/moon sur tout l'historique D1.
- Service d'exécution (user × actif × preset) ; jobs 23:55 UTC (action fictive) et 00:05 UTC (indicateurs sur vraie clôture, action « qui aurait été prise », sans changer l'action fictive) ; horloge injectable ; activation par propriété (défaut `-`, `zone = UTC`).
- Garde : aucun chemin d'exécution d'ordre (test transitif).
- **Fait le 2026-10-03** (doc : `architecture/08-rainbow-bench-grandeur-nature.md`). Classes réelles : `service/dca/atr/bench/RainbowLiveExecutionService` (`runPass(pass, asOf[, dayOverride])`, `PassSummary`, `dayFor`), `service/scheduler/RainbowLivePass{2355,0005}Job`, `controller/RainbowLiveAdminController` (`POST /api/admin/rainbow-live/run`), constantes dans `RainbowLiveDefaultPresets` (`D1_LOOKBACK_CANDLES` = 4000, propriétés, zone) ; `Bucket.BASE_MAX_ITEMS` rendu `public` (garde de taille). Crons NON activés dans `application-dev/prod.properties` (à faire sur demande de Clem).
- Choix : achat + vente sur la même bougie (ex. `MOON_STOP` sans cooldown) ⇒ la vente l'emporte (WARN) — validé par Clem, achat+vente simultanés possibles à terme, à peaufiner plus tard. Limite assumée : position moteur (rejeu repartant de 0) ≠ wallet mock — validé, traité à l'étape vrai wallet / slider d'exposition.
- Mesure réelle 23:55 vs 00:05 (delta d'action/indicateurs, temps de chargement D1, providers retenus) : **non réalisée** (hors CI, nécessite le binaire déployé + réseau providers) ; à faire après activation des crons ou via l'endpoint admin.
- Ajustements utiles à l'étape 3 : `PassSummary` sérialisable tel quel ; `RainbowLiveRun.deltaActionDiffers()` dispo ; runs identifiés par (preset, `day` UTC) ; pas de rattrapage ⇒ des jours peuvent manquer dans la série (l'API/la page doivent le tolérer).
- **Point**.

## Étape 3 — Back C : API REST
- CRUD presets, lecture runs/ordres fictifs, performance vs DCA fixe (lancé à la 1re ligne du preset), delta 23:55/00:05, marqueurs « config modifiée ». Auth existante.
- **Fait le 2026-10-03** (docs : `architecture/08-rainbow-bench-grandeur-nature.md` §API REST, `api/rest-endpoints.md` §Bench Rainbow (utilisateur), `architecture/07-security.md`). Classes réelles : `controller/RainbowLiveController` (`/api/rainbow-live`, `@PreAuthorize("isAuthenticated()")`) + `RainbowLiveControllerAdvice` (400/404/409, limité au contrôleur) ; `service/dca/atr/bench/{RainbowLiveQueryService, RainbowLivePerformanceCalculator, RainbowLiveDeltaCalculator, RainbowLivePresetNotFoundException, RainbowLivePresetConflictException}` ; `model/dto/dca/bench/RainbowLiveDtos` ; `model/entity/dca/bench/RainbowLiveUserSeed` (+ repo) ; `RainbowAtrConfig#diff`.
- Correctif seed : marqueur par user (`RainbowLiveUserSeed`) ⇒ `ensureDefaultPresets` no-op une fois posé ; supprimer tous les presets d'un actif = ne plus le suivre.
- Contrat pour la page (détail dans `rest-endpoints.md`) : `GET /defaults`, `GET/POST /presets`, `GET/PUT/DELETE /presets/{id}`, `GET /presets/{id}/runs[?from&to]` (`RunDto` : `day, pass2355?, pass0005?, deltaActionDiffers, configHash, configChanged, changedParams[]`), `GET /presets/{id}/performance[?to]` (`metrics{pnlPercent, fixedPnlPercent, outperformancePoints, …}`, `wallet{equityUsdc, pnlPercent}`, `series[{day, close, zone, invested, saleProceeds, currentValue, walletEquity, fixedValue, fixedInvested}]`, `markers[{day, type, price, amountUsdc, quantity}]`, `configMarkers[{day, changedParams}]`), `GET /presets/{id}/delta[?from&to]`. `tuning`/`globals` = noms de champs de `RainbowAtrTuning`/`RainbowAtrGlobals`, enums en chaîne ; erreurs `{"error": …}`.
- Ajustements / à savoir pour l'étape 4 : DCA fixe = `baseAmount` du snapshot du run du jour à chaque jour avec bloc 23:55 (pas de comblement des jours manquants) ; la performance reflète le wallet mock (actions plafonnées), pas la position du rejeu moteur ; le snapshot de config d'un run est rafraîchi à chaque passe (une édition entre 23:55 et 00:05 marque donc le run du jour comme « config modifiée ») ; `GET /presets` est en écriture (seed lazy) ; aucune pagination (≈ 1 run/jour) ; pas de déclenchement manuel côté user.
- Tests : suite complète 769 tests, 2 échecs **préexistants et sans lien** (`DxyIndicatorCacheSharingTest`, cache Twelve Data DXY — échoue aussi isolément, aucun fichier d'indicateur modifié) ; tous les tests Rainbow verts.
- **Point**.

## Étape 4 — Front : `userPage`
- Liste presets par actif + CRUD, tableau/courbes achats-ventes fictifs et performance façon pine (hors graphique de prix), vue delta 23:55 vs 00:05.
- **Fait le 2026-10-03** (docs : `architecture/08-rainbow-bench-grandeur-nature.md` §Page web, `api/rest-endpoints.md`, `NOTICE`). Fichiers : `templates/userPage.html` (+ fragment `templates/fragments/rainbowLive.html`), `static/assets/js/rainbow-live.js`, `static/assets/css/rainbow-live.css`, `static/assets/vendor/lightweight-charts/` (v5.2.1 + `LICENSE`), test `MainControllerUserPageTest`.
- Écarts / ajouts backend (petits, testés, documentés) : `GET /defaults` expose `reentryModes` et `zones[{code,name,label}]` ; `DayPoint` de `/performance` expose `costBasis` (nécessaire à l'histogramme « coût »). Le contrat étape 3 est sinon inchangé.
- Vérification : suite complète 771 tests, 2 échecs préexistants sans lien (`DxyIndicatorCacheSharingTest`). Page vérifiée dans Chromium **avec une API simulée** (harnais jetable non commité : liste, création, duplication, 409, validation, édition, défaut de l'actif, bascule actif, suppression, graphiques, runs, delta, états vides) ; **non vérifiée sur l'application réelle** (MySQL + providers requis) : à faire à la mise en service.
- **Point**.

## Reste à faire / mise en service
- Activer les crons 23:55 / 00:05 UTC (`tradeio.rainbow-live.pass-2355-cron` = `0 55 23 * * *`, `pass-0005-cron` = `0 5 0 * * *`) dans `application-prod.properties` (+ dev si voulu) sur le VPS.
- Vérifier le chargement D1 en réel : providers retenus, temps de chargement, WARN de trous de bougies.
- Première mesure réelle du delta 23:55 vs 00:05 (jamais réalisée) via la page.
- Vérifier la page sur l'application réelle (déclencher `POST /api/admin/rainbow-live/run?pass=T2355&day=…` puis `T0005`).
- Le slider « Taux d'exposition » reste une étude séparée (cf. `prompt-analyse-rainbow-atr-bench-grandeur-nature.md` §4ter).
