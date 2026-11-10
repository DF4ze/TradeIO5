# Espace Expert — paramétrage Trend / Rainbow par actif

Vérifié le : 2026-11-08 (`controller/Expert*`, `service/expert/**`, `model/dto/expert/*`, `controller/AppPageController`, `static/assets/app/js/expert-*.js`, `tools/rainbow-replay/`). Modèle des presets et règles de gestion : [`08-rainbow-live.md`](08-rainbow-live.md) ; contrat REST : [`../api/rest-endpoints.md`](../api/rest-endpoints.md) ; écrans : [`11-front-app.md`](11-front-app.md).

Paramétrage uniquement : aucune notion de passe, plan, exécution. Un USER consulte, simule et duplique les presets SYSTEM ; il modifie librement les siens ; l'ADMIN seul modifie les SYSTEM.

## Accès et sécurité

- `ExpertAccessGuard#require` (appelé en tête de chaque endpoint, pas d'intercepteur) : utilisateur authentifié dont `displayMode` = EXPERT (ADMIN compris), sinon 403 `EXPERT_MODE_REQUIRED` ; renvoie `ExpertContext(user, admin)`. Les pages `/app/expert/**` appliquent la même condition (redirection `/app`), le menu n'affiche l'entrée qu'en mode EXPERT.
- `/api/expert/**` : `@PreAuthorize("isAuthenticated()")` ; tout est scopé à l'utilisateur connecté, preset ou simulation d'autrui ⇒ 404.
- Modification d'un preset SYSTEM par un ADMIN tracée dans `admin_action_log` (`AdminAction.PRESET`).
- Erreurs : `ExpertApiException` (code + statut), `ExpertValidationException` (`VALIDATION`, détails par champ), mappées par `ExpertControllerAdvice`.

## API (`/api/expert/**`)

| Ressource | Opérations |
|---|---|
| `assets` | `GET` : catalogue (statut découvert / simulable / exploitable, providers, éligibilité) |
| `trend-presets`, `rainbow-presets` | liste par actif, `GET /{id}`, `GET /{id}/impact` (ADMIN), création, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/duplicate`, `POST /{id}/save-as-system` (ADMIN, depuis un SYSTEM : nouveau preset SYSTEM aux réglages saisis, original inchangé, journalisé) |
| `affectations` | `GET`, `GET|PUT /{asset}` (écart de provider renvoyé en information) |
| `simulations` | `POST` (202 + id), `GET /{id}`, `DELETE /{id}` |

Services : `AbstractExpertPresetService` (presets, cf. 08), `ExpertAffectationService` (lecture + `assign`), `ExpertAssetService`, `ExpertAssets` (actifs autorisés = `RainbowLiveDefaultPresets.ASSETS`).

## Simulation asynchrone (`service/expert/simulation/`)

Types : `TREND` (Trend seul), `RAINBOW` (un preset Rainbow sur le seul régime UP ou DOWN d'un Trend de référence), `AFFECTATION` (Trend + Rainbow UP + DOWN, comme le live). Chaque référence est un preset par `id` ou un **brouillon** non enregistré ; référence omise ⇒ celle de l'Affectation de l'utilisateur, à défaut le preset de base. Plage par défaut : 24 derniers mois ; ATH et automate moon calculés sur tout l'historique, démarrage à froid en début de plage.

- `ExpertSimulationService` : validation synchrone (droits, `SimulationRequestResolver`), puis exécution sur un `ThreadPoolExecutor` borné. État `QUEUED|LOADING_DATA|COMPUTING|DONE|FAILED` avec progression `0..1`, annulation par `DELETE`.
- **Garde-fous** (`ExpertSimulationSettings`, `tradeio.expert.simulation.*`) : une simulation active par utilisateur (409 `SIMULATION_ALREADY_RUNNING`), limite de débit par utilisateur (10 demandes / 60 s, 429 `SIMULATION_RATE_LIMITED`), pool de 2 threads et file de 10 (429 `SIMULATION_BUSY`), résultats en **mémoire** du processus : TTL 30 min, 5 par utilisateur au plus (le plus ancien est libéré), remplacement par (utilisateur, type, actif).
- `DailyDataLoader` : chargement D1 **uniquement** par `MarketDatasetEngine#getDailyDatasetForAsset` (cache DB, fallback), à la profondeur maximale `MarketDataProviderLimits#MAX_D1_HISTORY_CANDLES`, **single-flight** par (actif, source demandée) ; la plage maximale simulable est donc celle des bougies D1 en base après ce chargement. Repli sur un autre provider : signalé dans `meta` (`substituted`, `requestedProvider` ≠ `usedProvider`) et dans `warnings`. Bougie du jour (UTC) écartée.
- `SimulationEligibility` : points D1 requis = max(chaud Rainbow SMA/ATR + 1, chaud Trend, fenêtre d'analyse × 30 j), jugés par provider (bougies en cache, `MarketDataProviderLimits#maxD1Candles`). Insuffisant ⇒ refus explicite ; aucun indicateur n'est calculé avec moins de données que sa période.
- `SimulationEngine` (pur, sans I/O) : Trend Mix → régimes (`SimulationRegimes` : phases, bascules, métriques) → `RainbowAtrReplay.run` → graphique + 14 lignes de la fenêtre de résultats (`RainbowAtrResult#fromRows`, tableau du pine `rainbow_dca_v3_atr_moon.pine`).
- **Barre inactive** (hors régime) : `RainbowAtrReplay.inactive(k)` + `RainbowAtrStrategy.step(..., allowNewEntries=false)` gèlent armements, achats de zone, entrées To the moon, DCA fixe et capital ajouté ; sorties moon et stops déjà armés continuent. **Prix de valorisation** (`DayRow#valuationPrice`) = clôture suivie en proportion (mêmes rendements) sur les barres actives, figée sur les inactives : plat hors régime, sans rattrapage à la reprise. Ordres, bag, PnL (potentiel / total), exposition (wallet) et DCA fixe sont évalués à ce prix (les zones et stops restent décidés sur les vrais cours) : les chiffres ne contiennent que les mouvements des phases du régime testé. Sans barre inactive, valorisation = clôture et le rejeu est identique à celui d'avant.

### Résultat (`SimulationResultDto`)

`meta` (type, actif, providers demandé / utilisé / symbole, `substituted`, plage, libellés, `warnings`), `eligibility` (points requis, par provider : disponibles, profondeur, éligible, chargeable), `phases`, `switches`, `trendMetrics`, `lines` (14 lignes : paramétrage, plage, investi, vendu, restant, gain total, gains réalisés, PnL % réel / potentiel / DCA fixe, modulation ATH, To the moon, bag protégé, réserve moon) et `chart` (`ChartDto`).

**Courbe DCA fixe du PnL** : investit `base` à chaque barre active ; le front la met à budget égal (gain × capital Rainbow / total investi du DCA fixe, colonne `fixedInvested`), donc comparable au Rainbow en $ et en % du capital.

**`ChartDto`** : une valeur par bougie rejouée — `d` (dates), `trend` (0 aucun, 1 UP, 2 DOWN), `active` (0/1), `cols` (colonnes de `RainbowReplayColumns.COLS`, mêmes noms que `replay-template.html` : o, h, l, c, bornes ATR, zones, ath, marqueurs achat / vente / Bull-Bear / moon, `pos` = taille du bag, `bagValue` = bag en $ (au prix de valorisation), `reserve`, `realized`, `potential`, `total`, `fixedGain`…), `wallet` (capital fermé : actif, stable, total ; nul pour TREND), `sets` (jeux rejoués).

Pour une simulation `TREND`, `cols` contient en plus de `o,h,l,c` (noms distincts des colonnes Rainbow pour que le front distingue les deux types de graphique) : `tSma`, `tUp`, `tDn` (SMA et SMA ± k × ATR, `null` avant le warmup) ; `tReg` (régression après confirmation), `tRaw` (régression brute avant confirmation) et `tSmaReg` (SMA), codés 0 non défini, 1 UP, 2 DOWN, 3 RANGE ; `tScore` (score composite [-1, 1]) et `tSub0..2` (contribution des fenêtres courte / moyenne / longue = tanh(pente normalisée × échelle) × r², dont `tScore` est la moyenne) ; `tOlsS0..2` / `tOlsE0..2` (valeur de la droite des moindres carrés de chaque fenêtre à son début et à sa fin, pour la bougie considérée). `trendParams` (nul hors TREND) porte les fenêtres, `enter`, `exit`, `confirm`, `atrMultiplier` utilisés pour les seuils et les libellés.

## Outil de rejeu `tools/rainbow-replay/`

`RainbowAtrReplayMain` (test-scope, **coûteux : ne pas relancer sans raison**) génère le graphique de référence à partir de `replay-template.html` (Lightweight Charts embarqué, CSV D1 dans `data/`). C'est la référence de parité de la simulation : le moteur reste en Java, le front n'en recalcule rien.

## Front (`static/assets/app/js/`)

`expert-common.js` (`TIO.expert` : requêtes, formulaire généré, sélecteurs, plage, simulation + polling, fenêtre de résultats), `expert-preset-screen.js` (écran Trend / Rainbow), `expert-chart.js` (graphiques synchronisés : prix + bandes + marqueurs + phases, puis poche, PnL, exposition pour le Rainbow ; pour le Trend, SMA et bandes ATR sur le prix, panneau du score de régression (histogramme coloré par régime, seuils ENTER / EXIT, sous-scores optionnels), pistes de régime brut / appliqué / SMA, et droites OLS des 3 fenêtres au survol (primitive canvas, sans `setData`) ; seuils du score en lignes sans étiquette d'axe (valeurs dans le titre du panneau), échelle du score ajustée aux valeurs ; logo TradingView masqué (`attributionLogo: false`), crédit texte « TradingView Lightweight Charts™ » sous le graphique ; interrupteurs groupés par catégorie Prix / Trend / Rainbow ; options mémorisées par type dans `localStorage`), `page-expert-{trend,rainbow,affectation}.js`. Mise en page de l'écran preset (`expert-preset-screen.js`) : pages Expert pleine largeur (`tio-main--wide`) ; dès 1200 px, graphique / sélection / simulation à gauche et réglages du preset à droite (panneau sticky à défilement propre, barre d'actions et bouton « Simuler » synchronisé en tête), séparateur redimensionnable (glisser, flèches ← →, double-clic = défaut ; largeur 300 px – 70 % mémorisée dans `localStorage` `tio.expert.sidewidth`) et bouton « Masquer / Afficher les réglages » (`tio.expert.sidehidden`) ; en dessous de 1200 px, empilement sélection → réglages → simulation. Sections de réglages repliées par défaut (Trend et Rainbow). Formulaire Trend en 3 sections repliables (Régression linéaire, SMA et bandes ATR, Composition ; état ouvert/fermé mémorisé dans `localStorage`). Bandeau « Vue incomplète sur téléphone » sous 700 px. Aucun appel en `?demo=1`.
