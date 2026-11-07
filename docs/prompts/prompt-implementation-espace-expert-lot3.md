# Prompt d'implémentation — Espace Expert, lot 3 : moteur de simulation

Contexte : TradeIO-5 (Spring Boot / JPA / Thymeleaf / Lombok, Java 21, Maven). Lire dans cet ordre avant toute chose :
1. `docs/README.md` (la doc doit rester à jour : tout ajout, suppression ou modification d'architecture est documenté, sans historique ; ce qui n'est plus valable est supprimé).
2. `docs/etudes/etude-espace-expert.md` (décisions, simulation, tableau des lignes du pine, graphique de référence).
3. `docs/prompts/prompt-roadmap-espace-expert.md` (règles transverses ; tu fais le **lot 3**).
4. `docs/architecture/04-market-data.md` (providers, plafonds, pagination, cache DB, chemin D1 natif), `docs/architecture/08-rainbow-live.md` (modèle de presets, Affectations) et `docs/api/rest-endpoints.md` (API `/api/expert/*` livrée au lot 2).
5. `docs/known-gaps/nettoyages.md` (y renvoyer tout nettoyage différé).

## Objectif

Produire le moteur de simulation de l'Espace Expert et son API : rejouer sur un actif, un provider et une plage choisis, soit un **Trend** seul, soit un **Rainbow** sur son seul régime, soit une **Affectation complète**, en réutilisant le cœur du rejeu Java existant. Résultat = tout ce que le graphique et la fenêtre de résultats du lot 4 afficheront (aucun recalcul en JS). **Aucun écran** dans ce lot.

## État de départ (lots 1 et 2 livrés)

- Modèle `TrendPreset` / `RainbowPreset` / `AssetAffectation` ; API `/api/expert/*` (assets, presets, affectations) protégée par `ExpertAccessGuard` (authentifié + `displayMode=EXPERT`) ; validation des réglages dans `ExpertPresetValidator` ; paquet `service/expert`. `ExpertAssetService` renvoie déjà un champ d'éligibilité à `null` : **ce lot le renseigne**.
- Cœur de calcul : `RainbowAtrReplay.run/runFull(ds, sets, setOfBar, startIdx, endIdx)` (une `DayRow` par bougie rejouée : bornes, ATH, facteurs, signaux achat / vente, moon, position, coût, investi, vendu, gains, DCA fixe, réserve), `RainbowSetSelector.select(régimes, rangeMapping)`, `TrendMixCalculator.compute(candles, sma, atr, Params)` (+ `TrendPreset.toParams()`), `RainbowAtrDataset.fromMarketData(...)` / `warmup(sma, atr)` / `recommendedWindow`, `RainbowAtrResult` (agrégats du pine). Comparaison de référence : `RainbowAtrReplayMain` (colonnes exportées dans `COLS`, gabarit `tools/rainbow-replay/replay-template.html`, données `tools/rainbow-replay/data/d1_*.csv`).
- Données : `MarketDatasetEngine.getDailyDatasetForAsset(symbol, lookBack, endTime, preferred)` (chemin D1 natif, cache DB des bougies, fallback, `DailyDataset.substituted()`), `getDailyDatasetOnSource(...)`. Pagination Binance en place (`CandlePaginator`). Kraken : 720 dernières bougies D1 au plus. Aucun exécuteur asynchrone dédié n'existe encore.

## Décisions déjà prises (ne pas rouvrir)

**Types de simulation**
- `TREND` : preset Trend sur l'actif → phases UP / DOWN (sortie finale **UP / DOWN uniquement**, `rangeMapping` appliqué en interne ; barres sans régime résolu = inactives), bascules, métriques (nombre de bascules, durée moyenne de segment, etc., dans l'esprit de `RainbowAtrTrendBenchMain`). Pas de Rainbow.
- `RAINBOW` : un preset Rainbow (neutre) simulé sur **un seul régime** (signal `UP` ou `DOWN`) d'un Trend de référence au choix (preset Trend + signal ; par défaut le Trend et le rôle de l'Affectation de l'utilisateur sur l'actif). Barres hors régime = **inactives** : position gelée (ni achat, ni vente, aucun capital ajouté). Résultats limités à ce preset.
- `AFFECTATION` : Trend + Rainbow UP + Rainbow DOWN ; chaque barre active avec le jeu du régime, état de la machine conservé au changement de jeu (comme le mode Trend de `RainbowAtrReplayMain`). Sert à l'onglet Affectation.
- Brouillon : Trend et Rainbow peuvent être fournis soit par `id` de preset (SYSTEM ou propre à l'utilisateur), soit en **brouillon** (réglages non enregistrés, validés par `ExpertPresetValidator`). Aucune écriture de preset par la simulation.

**Plage et historique**
- Plage fixe définie par l'utilisateur (début / fin), comme le pine : état de la machine à froid au début de plage, mais **ATH et automate To the moon calculés sur tout l'historique disponible** (comme `RainbowAtrReplay`). Plage par défaut si absente : à valider (voir ci-dessous).
- Profondeur d'historique : **maximum du provider**, mis en cache DB (Binance en pagination complète, Kraken limité à 720 j). Avertissement dans le résultat si le provider est court. Constante de profondeur unique et mutualisée (pas de nombre magique dupliqué).

**Provider**
- Le provider choisi est tenté en premier ; en cas d'indisponibilité ou d'actif absent : **fallback signalé** (le résultat indique le provider demandé, le provider réellement utilisé et un avertissement visible, via `DailyDataset.substituted()`). Sans provider choisi : provider de référence du preset, sinon défauts de l'actif.
- Le cache DB et le fallback existants font foi : aucune requête exchange hors pipeline `MarketDatasetEngine`.

**Éligibilité**
- Dérivée du preset, jamais figée : historique requis = max(périodes SMA / ATR du Rainbow (`RainbowAtrDataset.warmup`), `TrendMixCalculator.Params.warmup()`, fenêtre d'analyse du preset). Simulation **refusée avec message explicite** (points requis / disponibles, par provider) tant que les points disponibles sont insuffisants ; aucun indicateur n'est calculé avec moins de données que sa période. Tient compte de `AssetProvider.maxHorizonDays`.
- Le champ d'éligibilité de `GET /api/expert/assets` est renseigné (par provider, sans déclencher de fetch : à partir des données déjà en cache).

**Garde-fous (ne pas déclencher de bannissement)**
- Simulation **asynchrone** : exécuteur borné dédié (pool + file limités), états `QUEUED → LOADING_DATA → COMPUTING → DONE | FAILED`, progression lisible.
- Un seul chargement de données à la fois par (actif, source) (verrou / single-flight : les demandes concurrentes attendent le même chargement) ; une seule simulation active par utilisateur ; limite de débit par utilisateur (valeurs en propriétés de configuration, avec défauts sûrs) ; plafond de simulations conservées.
- Résultats conservés **en mémoire avec durée de vie limitée** (pas de table en base), visibles de leur seul créateur (404 pour tout autre).

## Points à valider avec Clem avant de coder (poser les questions, ne pas deviner)

1. Barre inactive et automate **To the moon** : défaut proposé = état de la machine **et** automate moon gelés (rien ne se déclenche hors régime), ATH observé en continu. Alternative : l'automate moon continue d'avancer avec les réglages du preset.
2. Comparateur **DCA fixe** du résultat : défaut proposé = n'investir qu'aux barres actives (comparable au même capital). Alternative : sur toute la plage.
3. **Plage par défaut** sans dates : défaut proposé = 24 derniers mois (le pine rejoue sur la plage visible).
4. Durée de vie et nombre de simulations conservées par utilisateur (défaut proposé : 30 min, 5).

## Ce que tu dois faire

1. **Barre inactive dans `RainbowAtrReplay`** : support d'un jeu « inactif » par barre (par ex. index négatif dans `setOfBar`), sans casser les appelants existants (bench, exécution quotidienne, `RainbowAtrReplayMain`). Une ligne par barre reste émise (flag actif / inactif) pour que les courbes (PnL, exposition, taille du bag) continuent. Non-régression : sans barre inactive, sorties identiques à l'existant (0 écart).
2. Construction du régime : Trend → tableau de régimes UP / DOWN (+ inactif), phases (début, fin, type), bascules ; réutiliser `TrendMixCalculator` et `RainbowSetSelector`.
3. Service de simulation (`service/expert/simulation` ou équivalent) : résolution des presets (id ou brouillon, droits comme au lot 2), éligibilité, chargement des données (single-flight, fallback signalé), calcul pour les trois types, assemblage du résultat.
4. Résultat (DTO) : méta (actif, provider demandé / utilisé, substitution, première / dernière bougie, plage, avertissements), éligibilité, phases et bascules, séries du graphique (candles, bornes ATR / zones / ATH, marqueurs achat / vente / Bull-Bear / To the moon / stop moon, PnL, exposition actif / stable / total, **taille de position (bag)** et réserve moon, détail par jour), et les **14 lignes du tableau du pine** (cf. étude) calculées depuis `RainbowAtrResult` + config du preset. Reprendre les colonnes de `RainbowAtrReplayMain` (`COLS`) pour que le lot 4 réutilise le gabarit sans retraduction.
5. API : `POST /api/expert/simulations` (corps : type, actif, provider, plage, références Trend / Rainbow par `id` ou brouillon, signal pour `RAINBOW`) → `202` + identifiant (ou erreur d'éligibilité / de débit / de droits) ; `GET /api/expert/simulations/{id}` (progression puis résultat) ; éventuellement `DELETE /{id}` (annulation / libération). Erreurs dans le style de `ExpertControllerAdvice` (403 non EXPERT, 404 hors périmètre, 409 simulation déjà en cours, 422 éligibilité insuffisante, 429 débit, 400 validation).
6. Renseigner l'éligibilité dans `GET /api/expert/assets`.
7. Tests : barre inactive (position gelée, aucun capital ajouté, parité sans inactif), phases / bascules, éligibilité (cas limites : exactement la période, période − 1), single-flight (chargements concurrents = un seul appel), fallback signalé, limites de débit et simulation unique par utilisateur, cycle de vie asynchrone (états, TTL), droits (preset d'un autre utilisateur refusé, simulation d'un autre invisible), **parité avec `RainbowAtrReplayMain`** sur les fixtures `tools/rainbow-replay/data/d1_*.csv` pour une Affectation de base. Aucun appel réseau dans les tests.
8. Doc : `docs/api/rest-endpoints.md`, `docs/architecture` (moteur de simulation, barre inactive, garde-fous, éligibilité), étude (points tranchés, éligibilité, résultat), sans historique.

## Hors périmètre

Écrans, menu, graphique et sélecteurs (lot 4), branchement live / nettoyage final (lot 5), découverte et création d'actifs (chantier à part : seuls BTC / ETH / PAXG sont proposés), vue Admin des Affectations. Ne pas relancer `RainbowAtrReplayMain` en réel sans accord de Clem (coûteux) : la parité se teste sur les fixtures versionnées.

## Critères d'acceptation

- Build vert (MCP JetBrains), tests verts ; annoncer le résultat réel, ne pas supposer.
- Une Affectation de base simulée sur les fixtures donne les mêmes lignes que `RainbowAtrReplayMain` (0 écart) ; un Rainbow sur un seul régime ne produit aucun achat / vente hors régime.
- Les trois types de simulation fonctionnent via l'API, avec le provider réellement utilisé et ses avertissements dans la réponse.
- Données insuffisantes ⇒ refus explicite avec points requis / disponibles ; aucune requête exchange hors pipeline de cache.
- Doc à jour, rien d'historisé, nettoyages différés consignés dans `docs/known-gaps/nettoyages.md`.

## Style (préférences de Clem)

`isEmpty()` / `getFirst()` / `getLast()` ; `assertTrue` / `assertFalse` directs ; pas de variables locales redondantes ; constantes métier mutualisées plutôt que dupliquées ; valeurs par défaut uniquement dans les Initializers.
