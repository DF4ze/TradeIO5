# Étude — Tool MCP de suivi DCA (prix moyen, total investi, PnL)

Objectif : exposer un tool MCP (dans l'esprit de `get_indicator`/`evaluate_strategy`/`get_opinion` de `TreeAnalysisMcpTools`) qui, à partir d'une date de début, une date de fin, une fréquence et une heure d'achat, calcule le prix moyen d'achat, le total investi et le PnL à date pour une stratégie DCA (Dollar-Cost Averaging).

## 1. Verdict rapide

Faisable, et ça ne doit **pas** passer par `MarketDatasetEngine`/`Bucket` : cette chaîne est conçue pour un cache glissant ancré sur "maintenant" (rolling window), pas pour des points historiques épars sur plusieurs mois ou années. Le bon patron est d'appeler directement un `MarketDataApiClient` existant (Binance recommandé), en pagination sur H1, puis de filtrer aux instants d'achat voulus — exactement comme `TreeAnalysisFacade` le fait déjà pour le prix courant, mais côté historique cette fois.

Point bloquant côté demande : **le montant investi par échéance manque**. Sans lui, ni le total investi ni le prix moyen pondéré ne peuvent être calculés (cf. section 2).

## 2. Ce qui manque dans la demande initiale

| Paramètre manquant | Pourquoi c'est nécessaire |
|---|---|
| Montant par achat (fixe ? variable ? budget total réparti sur N échéances ?) | Sans montant, impossible de calculer `totalInvested` et `avgPrice` (moyenne pondérée par les montants, pas moyenne simple des prix) |
| Frais (fee %) | Un DCA réel a des frais d'exécution ; à inclure dans le total investi/PnL ou explicitement ignorer |
| Source de prix (Binance/Kraken/OKX) | Impacte fortement la profondeur d'historique disponible (section 4) |
| Fuseau horaire de "l'heure d'achat" | Le reste du système raisonne en UTC (`TimeFrame.DEFAULT_ZONE`) ; à confirmer si l'heure donnée est locale (Europe/Paris) ou déjà UTC |
| Comportement si la période précède l'historique disponible | Échec explicite vs. troncature silencieuse (impacte la fiabilité du calcul, cf. section 7) |

## 3. Génération du calendrier d'achats — réutiliser `TimeFrame`

Pas besoin d'un nouvel enum "fréquence" : `TimeFrame` a déjà les valeurs calendaires qu'il faut (`D1, W1, W2, M1, M2, M3, M6`) et une méthode `addTo(Instant, quantity)` qui fait exactement de la génération de séquence :

```java
Instant current = firstOccurrence; // date de début + heure d'achat, combinées en un seul Instant
List<Instant> schedule = new ArrayList<>();
while (!current.isAfter(endInstant)) {
    schedule.add(current);
    current = frequency.addTo(current, 1); // frequency = TimeFrame.D1, W1, M1...
}
```

Comme `addTo` fait de l'arithmétique `Instant.plus(amount, unit)` en zone UTC (pas de DST en UTC), l'heure d'achat est automatiquement préservée à chaque pas — pas besoin de la réinjecter à chaque itération. Seul point d'attention : `ChronoUnit.MONTHS` sur une date de fin de mois (ex: 31 janvier + 1 mois) est clampé par `java.time` au dernier jour du mois suivant (28/29 février) — comportement standard, mais à documenter dans la réponse du tool si le point de départ est un 29/30/31.

À restreindre côté validation : n'accepter que les `TimeFrame` de type `CALENDAR` (`D1, W1, W2, M1, M2, M3, M6`) comme fréquence — les valeurs `FIXED` (H1, H4, MIN1, MIN5) n'ont pas de sens comme cadence DCA typique, même si `addTo` les accepterait techniquement.

## 4. Récupération des prix historiques

Les 3 `MarketDataApiClient` existants (`BinanceMarketDataApiClient`, `KrakenMarketDataApiClient`, `OkxMarketDataApiClient`) ne savent nativement récupérer que `TimeFrame.H1` (`NATIVE_INTERVALS`/`NATIVE_BARS` ne contiennent que cette entrée dans les 3 classes) — cohérent avec `Bucket.BASE_TIME_FRAME = H1`. Conséquence pour le DCA : quelle que soit la fréquence demandée (journalière, hebdo, mensuelle), le prix d'une échéance s'obtient toujours en allant chercher la bougie H1 dont l'heure d'ouverture correspond à l'heure d'achat du jour concerné.

| | Binance | Kraken | OKX |
|---|---|---|---|
| Endpoint utilisé | `GET /api/v3/klines` | `GET /0/public/OHLC` | `GET /api/v5/market/candles` |
| Backfill profond (années) | Oui — pagination normale `startTime`/`endTime`, max 1000/appel | **Non** — l'API ne renvoie que les ~720 dernières bougies H1 (~30 jours), quel que soit `since` (déjà documenté dans `etude-tick-retrieval.md`) | À vérifier — le client actuel utilise `/market/candles` (fenêtre récente), pas `/market/history-candles` (le seul qui permet de remonter loin chez OKX). En l'état, probablement limité à quelques centaines de bougies en pratique |
| Adapté à un DCA de plusieurs mois/années | **Oui** | Non | Non, sans modification du client |

Recommandation : **Binance par défaut** pour ce tool, comme le fait déjà `TreeAnalysisFacade` pour `get_indicator`/`evaluate_strategy`/`get_opinion`. Kraken/OKX peuvent rester des options pour des DCA très récents (< 30 jours), mais échoueront silencieusement (liste vide, pas d'exception — cf. `catch (Exception e) { logger.warn(...); } return List.of();` dans les 3 clients) sur des horizons plus longs.

**Stratégie de récupération** : ne pas faire un appel API par échéance (N occurrences = N appels réseau séquentiels, coûteux pour un DCA journalier sur plusieurs années — ex: ~1095 appels sur 3 ans). Mieux : un fetch en bloc de la série H1 complète sur `[premièreÉchéance, dernièreÉchéance]` avec pagination (max 1000 bougies/appel Binance), puis filtrage local à la bougie dont le `timestamp` correspond à chaque échéance du calendrier généré en section 3. Pour un DCA journalier sur 3 ans, ça donne ~26 300 bougies H1 à paginer (~27 appels) au lieu de ~1095 appels individuels — un ordre de grandeur de moins.

Reste une question de choix (à valider) : quel prix de la bougie H1 utiliser pour représenter "le prix à l'heure d'achat" — `open` (prix au tout début de l'heure, le plus proche sémantiquement d'un ordre déclenché pile à l'heure choisie) ou `close` ? Recommandation : `open`, à confirmer.

**Prix courant pour le PnL** : même mécanique que `TreeAnalysisFacade.extractLastPrice` — dernière bougie H1 disponible (`endTime = now`, `limit = 1`).

## 5. Formules

- `N` = nombre d'échéances entre début et fin selon la fréquence (section 3)
- `totalInvested = Σ montant_i` (= `N × montant` si montant fixe)
- `totalQuantity = Σ (montant_i / prix_i)`
- `avgPrice = totalInvested / totalQuantity` — **moyenne pondérée par les montants, pas moyenne simple des prix** (erreur classique à éviter : un DCA investit le même montant à chaque échéance, donc achète plus d'unités quand le prix est bas — la moyenne simple des prix surestime le coût réel)
- `currentValue = totalQuantity × prixCourant`
- `pnl = currentValue − totalInvested`
- `pnlPercent = pnl / totalInvested × 100`

## 6. Où brancher ça dans le code

Nouveau package `service/dca`, indépendant de la chaîne `Indicator → Strategy → Opinion` (le DCA n'en fait pas partie conceptuellement — c'est une simulation historique, pas une lecture de marché en direct) :

- `DcaMcpTools` (`@Component`, même patron que `TreeAnalysisMcpTools` : retour `String` JSON via `toJsonOrError`, pas de `Map` brute — pour les mêmes raisons déjà documentées dans cette classe concernant spring-ai-starter-mcp-server-webmvc 1.0.9)
- `DcaCalculatorService` (ou `DcaFacade`) : génère le calendrier (section 3), appelle directement le `MarketDataApiClient` choisi — **sans passer par `MarketDatasetEngine`/`MarketDatasetCache`/`Bucket`**, comme `BinanceMarketDataApiClient` est déjà injectable directement dans `TreeAnalysisFacade`
- DTOs : `DcaRequest` (symbol, startDate, endDate, frequency: TimeFrame, heureAchat, montant, source), `DcaOccurrence` (timestamp, prix, quantité achetée), `DcaResult` (avgPrice, totalInvested, totalQuantity, currentPrice, pnl, pnlPercent, liste des occurrences)

Réutilise `MarketDataSource` et `MarketDataApiClient` déjà en place — aucune nouvelle abstraction de provider nécessaire.

## 7. Risques et points d'attention

- **Kraken/OKX inadaptés** aux DCA de plus de ~30 jours en l'état des clients actuels (section 4) — Binance doit être la source par défaut, sinon le calcul sera silencieusement tronqué (liste vide sur les échéances trop anciennes, pas d'erreur levée par le client).
- **Occurrence sans prix trouvé** (symbole pas encore listé à cette date, ou échec réseau ponctuel) : les 3 clients renvoient une liste vide plutôt qu'une exception. Le futur `DcaCalculatorService` doit détecter et signaler explicitement les échéances sans prix dans la réponse (champ `missingOccurrences` par ex.), plutôt que de fausser silencieusement `totalInvested`/`avgPrice` en les ignorant.
- **Heure d'achat en UTC par convention** (`TimeFrame.DEFAULT_ZONE`) — à clarifier si l'utilisateur raisonne en heure locale.
- **Performance sur horizons longs** : DCA journalier sur 5 ans ≈ 1825 échéances / ~43 800 bougies H1 à paginer (~44 appels séquentiels) — de l'ordre de quelques dizaines de secondes, acceptable pour un tool appelé à la demande, mais pas pour un usage très fréquent sans mise en cache.
- **Précision `BigDecimal`** : rester cohérent avec le reste du modèle (`precision = 30, scale = 10`, comme `Transaction.quantity`/`Transaction.price`).

## 8. Prochaine étape

Trancher la section 2 (surtout : montant par achat, fixe ou variable) avant toute implémentation. Une fois ça fait, l'implémentation suit le même patron que `TreeAnalysisMcpTools`/`TreeAnalysisFacade`, en bypassant volontairement `MarketDatasetEngine`/`Bucket`, plus simple, et ça évite le bug de cache déjà documenté dans `etude-tick-retrieval.md` (`MarketDatasetCache` ne fait jamais de cache-hit en usage réel, non pertinent ici puisqu'on ne veut justement pas de cache glissant pour ce cas d'usage).

## 9. Statut d'implémentation (2026-07-07) — DONE

Implémenté et vérifié en conditions réelles (tool `calculate_dca` appelé via MCP sur BTCUSDT). Décisions tranchées avec l'utilisateur pour lever le point bloquant de la section 2 :

- **Montant par achat : fixe** (pas de répartition d'un budget total sur N échéances).
- **Frais : paramètre `feePercent` optionnel** (défaut 0), déduit du montant avant conversion en quantité (`quantity = (montant - fee) / price`), donc bien reflété dans `avgPrice`.

Écarts par rapport à l'étude d'origine, découverts pendant l'implémentation :

- **`D1` est `FIXED`, pas `CALENDAR`** dans `TimeFrame` (seuls `Y1, Y3, M1, M2, M3, M6, W1, W2` sont `CALENDAR`). La restriction de validation a donc été élargie à `isCalendar() || frequency == D1` — sinon un DCA journalier (l'exemple même de cette étude) aurait été rejeté.
- **`TreeAnalysisFacade` n'appelle pas directement `MarketDataApiClient`** pour le prix courant (`extractLastPrice` lit un `MarketDataset` déjà résolu par `MarketDatasetEngine`). Le nouveau `DcaCalculatorService`, lui, appelle bien directement le client injecté, conformément à la recommandation de bypass de cette étude.
- **Un cache DB des bougies H1 existe déjà** (`CachingMarketDataApiClient`, cf. `etude-cache-db-candles-h1.md`, implémenté indépendamment de cette étude) : `DcaCalculatorService` en bénéficie gratuitement en injectant `cachingBinanceMarketDataApiClient` — les rejeux d'un même DCA ne re-tapent donc pas Binance pour les échéances déjà closes et déjà persistées.

Fichiers créés :

- `exceptions/DcaException.java`
- `model/dto/dca/DcaOccurrence.java`, `model/dto/dca/DcaResult.java`
- `service/dca/DcaCalculatorService.java` (pagination par blocs de 1000 bougies H1, échec explicite si source non-Binance sur un horizon > 25 jours, `missingOccurrences` explicites)
- `service/dca/DcaMcpTools.java` (tool `calculate_dca`, patron `toJsonOrError` identique à `TreeAnalysisMcpTools`)

Fichier modifié :

- `configuration/McpServerConfig.java` (bean `dcaToolCallbackProvider`)
- `service/connector/apiclient/marketdata/CachingMarketDataApiClient.java` : ajout de logs INFO (`Cache HIT complet` / `Cache PARTIEL` / `Appel réseau ...`) pour rendre observable, sans outillage externe, si un appel réseau a réellement eu lieu ou si la lecture vient entièrement du cache DB.

Non couvert par cette implémentation (hors scope de la demande initiale) : ajustement dynamique du montant selon RSI/Rainbow (cf. `BackLog.ods` item 3.2 "DCA intelligent", distinct de ce tool qui simule un DCA à montant fixe).

## 10. Complément 2026-09-12 — le mécanisme alternatif Bucket/MarketDatasetEngine, et pourquoi on ne s'en sert pas ici

En travaillant sur une variante Rainbow de ce DCA (session Cowork, cf. mémoire projet
`tradeio5_dca_rainbow_decoupled_architecture_2026-09-12.md`), deux mécanismes distincts existent
dans le code pour obtenir des données de marché — à ne pas confondre :

**A. Le chemin direct (celui de ce tool, section 4/6 ci-dessus)** : `MarketDataApiClient` brut
(Binance/Kraken/OKX), qui ne sait QUE fetcher du H1 natif. `DcaCalculatorService` l'utilise
directement avec sa propre pagination, sans passer par un cache "rolling". Simple, adapté à un
calcul one-shot sur une période arbitraire (ce que fait un backtest).

**B. Le chemin production (live)** : `MarketDatasetEngine` + `MarketDataProviderRegistry` +
`Bucket` (`service/market/dataset/`). C'est la chaîne utilisée par `get_indicator`/`evaluate_strategy`/
`get_opinion` pour alimenter les indicateurs en continu :
- `MarketDatasetEngine.getDatasetForAsset(symbol, timeFrame, lookBack, endTime)` résout
  automatiquement le provider via `asset_provider` (ordonné par `priority`, filtré par
  `maxHorizonDays`), avec **fallback automatique** sur le candidat suivant si
  `SymbolNotFoundException`/`ProviderUnavailableException` est levée par un provider.
- En interne, il fetch toujours au `baseTimeFrame` du `Bucket` (H1) puis agrège vers le
  `TimeFrame` cible demandé (D1, W1...) via `Bucket.view(targetTimeFrame, now)` — **agrégation
  OHLCV réelle** (open=premier H1, close=dernier H1, high=max, low=min, volume=somme),
  pas une simple sélection d'une bougie H1 comme le fait ce tool DCA.
- `Bucket` est plafonné à `BASE_MAX_ITEMS = 50 000` bougies H1 (~5-6 ans) et a une logique
  `shouldFetch` distinguant source "live" (refetch si la fenêtre a expiré) de source
  historique/backtest (fetch une seule fois) — pensé pour un usage continu "ancré sur maintenant",
  pas pour naviguer librement dans le passé.

**C. Précédent déjà établi pour réutiliser B dans un contexte de backtest** :
`tools/calibration/BucketResample.java` (demande explicite de Clem, 2026-07-09, cf.
`docs/calibration/calibration-rejection-zone.md`) instancie un `Bucket` directement (sans passer
par `MarketDatasetEngine`), lui injecte un gros import H1 (CSV), et appelle `.view(TimeFrame.D1, now)`/
`.view(TimeFrame.W1, now)` pour obtenir du vrai D1/W1 historique — réutilise l'agrégation de
production sans réimplémenter l'OHLCV, et sans les contraintes "live" de `MarketDatasetEngine`.

**Pourquoi on garde A (ce tool) + `Bucket` nu (comme C), pas B en entier, pour le backtest
Rainbow** : `MarketDatasetEngine` résout un vrai besoin (choix/fallback de provider pour l'usage
live), mais ajoute une couche (cache par `BucketKey`, logique live/backtest, `asset_provider`)
inutile pour un calcul one-shot sur tout l'historique d'un seul actif (BTC) avec une seule source
(Binance) déjà connue. Le patron retenu : fetch H1 en bloc comme le fait déjà ce tool (section 4),
puis `new Bucket(TimeFrame.H1, maxSize).append(...)` + `.view(TimeFrame.D1, now)` comme le fait déjà
`BucketResample.java` — pour obtenir de vraies clôtures journalières (pas juste la bougie H1 d'une
heure donnée) sans réimplémenter l'agrégation ni passer par la machinerie provider-fallback.

## 11. Statut d'implémentation Rainbow V0 (2026-09-13) — DONE

Implémenté selon `docs/prompts/prompt-implementation-dca-rainbow-v0.md`, en résolution D1 pure,
BTC uniquement. Le tableau des zones intermédiaires du prompt s'est révélé incomplet en cours
d'implémentation (2 intervalles non couverts) : Clem a donné la règle exacte à 6 zones qui fait
foi, cf. mémoire projet `tradeio5_dca_rainbow_v0_zone_table_clarification_2026-09-12.md` — elle
remplace le tableau du prompt original.

Fichiers créés (package `service/dca/`, à côté de `DcaCalculatorService`) :

- `RainbowZone.java` (enum à 6 zones + `classify` statique)
- `RainbowDcaBacktestRequest.java` / `RainbowDcaOccurrence.java` / `RainbowDcaBacktestResult.java` (DTOs)
- `RainbowDcaBacktestService.java` : fetch H1 Binance direct + cache DB
  (`cachingBinanceMarketDataApiClient`, même patron de pagination que `DcaCalculatorService`) →
  agrégation D1 via `Bucket` nu (même patron que `tools/calibration/BucketResample.java`, cf.
  section 10) → SMA causale → machine à état achat (activation sous `percdown2`, déclenchement par
  franchissement ou trailing stop 5%, achat x3) et vente (activation au-dessus de `percup3`,
  déclenchement sous `percup3`/`percup2`, vente d'une fraction de la position, cooldown bloquant
  tout achat pendant N jours) → bloc comparatif via `DcaCalculatorService.calculate` existant
  (même période, même `baseAmount`, fréquence D1).

Tests :

- `RainbowDcaBacktestServiceTest.java` (6 tests unitaires, mockés) : classification des 6 zones ;
  armé→déclenché achat par trailing stop puis reprise de la zone normale au tick suivant ;
  armé→déclenché vente + cooldown bloquant l'achat du même jour et du jour suivant ; cadence > 1
  jour ; historique de warm-up insuffisant → `DcaException`.
- `RainbowDcaBacktestManualRunnerTest.java` — `@Disabled` par défaut (même convention que
  `YoutubeManualNetworkTest`, à décommenter pour lancer en réel) : pas de tool MCP ni d'endpoint
  REST dans ce lot (coût d'itération d'un tool MCP = un appel LLM par essai, décision explicite du
  prompt), un runner Maven manuel suffit pour itérer sur plusieurs jeux de paramètres en un run.

Suite complète (`test:tradeio-5`) : 581 tests, 2 échecs — les mêmes pré-existants et sans rapport
(`DxyIndicatorCacheSharingTest`, cf. mémoire projet `tradeio5_decision_reason_apikey_2026-09-03.md`)
déjà documentés le 2026-09-03. 0 régression introduite par ce lot.

**Résultats du run manuel réel (2026-09-13, BTC, 3 dernières années jusqu'à aujourd'hui, `baseAmount=100`)** :

| Scénario | Investi | Achats zone | Achats x3 | Ventes | Prix moyen | PnL % | DCA fixe : investi | DCA fixe : PnL % |
|---|---|---|---|---|---|---|---|---|
| Défauts V0 (cooldown 3j, sellFrac 1/4, cadence 1j) | 94 400 | 842 | 23 | 10 | 68 443,33 | 7,42 % | 109 600 | 15,06 % |
| cooldown 7j | 92 750 | 820 | 22 | 10 | 68 686,50 | 7,11 % | 109 600 | 15,06 % |
| sellFrac 1/2 | 94 400 | 842 | 23 | 10 | 68 443,33 | 5,66 % | 109 600 | 15,06 % |
| cadence 3j | 35 550 | 280 | 23 | 10 | 68 443,90 | 8,49 % | 109 600 | 15,06 % |

Constat brut (un seul run, pas un verdict de calibration walk-forward) : sur cette fenêtre (BTC en
tendance haussière forte sur 3 ans), le DCA Rainbow V0 investit systématiquement moins que le DCA
fixe (la règle réduit l'exposition dans les zones hautes et vend une partie de la position dans les
pics) et affiche un PnL % inférieur au DCA fixe sur les 4 jeux de paramètres testés — cohérent avec
le fait qu'un DCA à montant constant capture mécaniquement toute la hausse, alors que Rainbow retire
des billes en cours de route. Aucun verdict de robustesse à ce stade (pas de walk-forward, pas de
test multi-période/multi-actif) : ce backtest vérifie que la mécanique tourne et produit des
chiffres exploitables, conformément au périmètre V0 ("teste la règle Rainbow pure", pas une
calibration).

Non couvert par ce lot (explicitement hors scope V0, cf. prompt) : lecture de wallet réel (V1),
curseur d'exposition stablecoin/crypto (V2), frein basé sur le risque Decision/Scenario (V3),
exécution réelle d'ordre, multi-actif, tool MCP/endpoint REST.

**Ce constat a été identifié début §12 comme un biais de protocole (une seule fenêtre bull) plutôt
qu'un verdict sur la stratégie — voir §12 pour la correction.**

## 12. Réécriture du runner manuel + ReentryMode (2026-09-13) — DONE

Suite à une revue du bench §11 avec Clem : le constat "aucune combinaison ne bat le DCA fixe" venait
d'un biais de protocole, pas d'un verdict sur la stratégie — une seule fenêtre testée (BTC, 3
dernières années glissantes, bull run quasi continu), sur laquelle une stratégie qui désinvestit en
zone haute perd mécaniquement contre un DCA qui reste engagé à 100 %. Décision de Clem : rester sur
BTC uniquement (objectif = trouver les bornes % valides, pas encore multi-actif), mais corriger le
protocole de test et explorer plusieurs méthodes de sortie des états ARMÉ (haut et bas).

**`ReentryMode.java` (nouveau, `service/dca/`)** : enum à 3 valeurs (`TRAILING_STOP`, `IMMEDIATE`,
`FIXED_DELAY`) qui factorise la condition de déclenchement des deux machines à état ARMÉ (achat
sous `percdown2`, vente au-dessus de `percup3`) :

- `TRAILING_STOP` : franchissement de la borne OU rebond/repli de `trailingStopPercent` depuis
  l'extrême atteint pendant l'armement (comportement V0 d'origine côté achat).
- `IMMEDIATE` : franchissement pur de la borne uniquement, ignore tout rebond intermédiaire
  (comportement V0 d'origine côté vente — le V0 n'avait pas de mécanisme de repli symétrique côté
  vente, `TRAILING_STOP` y est donc réellement nouveau).
- `FIXED_DELAY` (nouveau des deux côtés) : déclenche automatiquement après
  `RainbowDcaBacktestRequest.fixedDelayDays` jours d'armement, quel que soit le prix.

`RainbowDcaBacktestRequest` gagne `buyReentryMode` (défaut `TRAILING_STOP`), `sellReentryMode`
(défaut `IMMEDIATE`) et `fixedDelayDays` (défaut 10) — ces défauts reproduisent le comportement V0
exact bit à bit, donc aucune régression sur les 6 tests historiques de
`RainbowDcaBacktestServiceTest` (vérifié). `RainbowDcaBacktestService` gagne les compteurs
`buyArmedDays`/`sellArmedDays` et deux méthodes `buyTriggered`/`sellTriggered` qui branchent sur le
mode ; `RainbowZone` et le reste de la mécanique (multiplicateurs, cooldown, cadence) sont inchangés.
3 tests ajoutés à `RainbowDcaBacktestServiceTest` (IMMEDIATE réagit plus tard que TRAILING_STOP sur
la même séquence de prix ; FIXED_DELAY déclenche après N jours des deux côtés, prix inchangé).

**`RainbowDcaBacktestManualRunnerTest.java` réécrit intégralement** (l'ancien runner à un seul test
brute-force, 157 500 combinaisons sur une seule fenêtre, colonnes de log désalignées entre l'en-tête
et les lignes de données, est remplacé) :

- **3 fenêtres historiques fixes** (dates explicites, reproductibles) : `BULL_2023_2024`
  (2023-01-01 → 2024-12-31), `BEAR_2021_2022` (2021-11-10 → 2022-12-31, top → bottom du cycle),
  `SIDEWAYS_2018_2019` (2018-07-01 → 2019-06-30). La proposition de valeur de Rainbow (vendre en
  haut, racheter plus bas) ne peut se matérialiser que si le prix repasse sous la SMA après être
  monté — jamais vérifiable sur un bull pur, d'où le constat trompeur du §11.
- **Deux bancs séparés** : `runBoundsCalibration_realBtcHistory` (grille sur les 5 bornes %, 243
  combos ordonnés, tout le reste aux défauts) et `runReentryMethodsComparison_realBtcHistory`
  (grille sur `buyReentryMode`/`sellReentryMode`/`trailingStopPercent`/`cooldownDays`/
  `fixedDelayDays`/`sellFraction`, 324 combos, bornes V0 fixées). ~1700 runs au total (au lieu de
  157 500) — largement suffisant vu que `CachingMarketDataApiClient` persiste les bougies H1 en DB
  (le fetch réseau n'a lieu qu'une fois par fenêtre, pas par scénario).
- **Classement par robustesse** : pour chaque jeu de paramètres, delta de PnL% vs le DCA fixe
  calculé sur chacune des 3 fenêtres, classé par le MINIMUM des 3 deltas (pas la moyenne, pas le
  meilleur cas) — retient les paramètres qui ne s'effondrent dans aucun régime plutôt que ceux qui
  n'ont brillé qu'une fois par hasard (même défaut méthodologique que la calibration rejection-zone
  avant son walk-forward, cf. mémoire projet).
- **Max drawdown** de l'exposition marché (`position × close`, pic-à-creux) calculé dans le runner
  (pas ajouté à `RainbowDcaBacktestResult`, qui n'en a pas besoin ailleurs) : une stratégie qui
  désinvestit en haut de cycle peut perdre du PnL brut tout en réduisant le risque — invisible en ne
  regardant que le PnL final sur un bull run.
- **Exécution parallélisée** (pool de threads = nb de coeurs, `ExecutorService.invokeAll`) : chaque
  (jeu de paramètres × fenêtre) est un backtest indépendant, sans état mutable partagé dans
  `RainbowDcaBacktestService#backtest` (uniquement des variables locales) — parallélisable sans
  synchronisation. Un `warmUpCache` séquentiel (un backtest par fenêtre, avant de paralléliser)
  évite que plusieurs threads déclenchent en même temps le même fetch réseau + la même insertion DB
  pour une fenêtre pas encore en cache (la plage H1 fetchée ne dépend que de
  `startDate`/`endDate`/`smaPeriod`, identiques pour tous les jeux de paramètres d'un même banc).
  Diviseur de temps d'exécution proche du nombre de coeurs disponibles une fois le cache chaud.
- **Sorties** : CSV détail (une ligne par scénario × fenêtre) + CSV agrégé (une ligne par jeu de
  paramètres, deltas par fenêtre + min + moyenne) sous `target/rainbow-dca-bench/`, séparateur `;`
  (Excel FR) ; résumé console (top 15 par robustesse) avec largeur de colonne calculée sur le
  contenu réel — corrige le bug de désalignement de l'ancien format (en-tête à largeur fixe `%-28s`,
  lignes de données à largeur fixe `%-50s`, différentes l'une de l'autre).

Volontairement pas fait dans ce lot (cf. `docs/CODING_RULES.md` — "ne pas coder ce dont on n'a pas
besoin") : rendement pondéré dans le temps (XIRR) pour comparer des calendriers de cash-flows
différents entre Rainbow et le DCA fixe (Rainbow investit systématiquement moins, cf. §11 —
comparer des PnL% sur des montants investis différents n'est pas une comparaison à iso-capital),
multi-actif (ETH), split in-sample/out-of-sample formel au-delà des 3 fenêtres fixes.

Suite complète (`test:tradeio-5`) rejouée après ce lot : 587 tests, 2 échecs — les mêmes
pré-existants et sans rapport (`DxyIndicatorCacheSharingTest`) déjà documentés le 2026-09-03. 0
régression introduite (les 9 tests de `RainbowDcaBacktestServiceTest`, dont les 3 nouveaux, passent).

**Usage du bench (comment lancer, lire les CSV, méthodologie détaillée) : voir
`docs/calibration/calibration-dca-rainbow-bounds-multipliers.md`** — ce document-ci reste le journal
de conception/implémentation, ce document de calibration documente l'usage et accueillera les
résultats du premier run réel.

## 13. Mémoïsation réseau des trous H1 définitifs dans `CachingMarketDataApiClient` (2026-09-13) — DONE

En lançant le bench §12 en réel (Clem, `SIDEWAYS_2018_2019`), avalanche de WARN
`CachingMarketDataApiClient` "Mismatch bougies attendues/reçues... reçu=0" sur 2-3 plages horaires
précises (ex: 2018-11-14, 2019-03-12) — des trous réels et définitifs côté Binance sur ces heures,
pas une anomalie du bench.

Diagnostic (remarque de Clem, cf. mémoire projet
`tradeio5_provider_warn_gap_ratelimit_vigilance_2026-09-13.md`, à garder en tête pour toute lecture
future de logs sur ce projet) : **un WARN de données manquantes signale un appel réseau Provider
déclenché ; si le même WARN boucle, c'est le signe qu'on re-sollicite le Provider pour la même chose
de façon répétée — donc un risque réel de rate limit, qui peut devenir bloquant en production**, pas
seulement un problème de bruit de logs. C'est exactement ce qui se produisait ici : le
`warmUpCache` du §12 ne fait qu'UN backtest par fenêtre avant de paralléliser ; comme Binance
renvoie systématiquement 0 sur ce trou, rien n'est jamais persisté en DB, donc CHAQUE combo
parallèle qui retouche cette fenêtre (jusqu'à ~250-300 sur `SIDEWAYS_2018_2019`) retentait le même
appel réseau vers Binance.

**Fix appliqué dans `CachingMarketDataApiClient`** (classe de production, pas seulement le bench —
le même risque existe pour tout appelant qui interroge plusieurs fois une plage historique contenant
un trou définitif) : mémoïsation en mémoire des résultats de fetch réseau par trou exact
(`source, symbol, timeFrame, since, until`), dans un `ConcurrentHashMap<GapKey, List<MarketData>>`
tenu par l'instance (bean singleton, cf. `MarketDataCachingConfig` — un cache par exchange, vivant
pour la durée du contexte Spring). `computeIfAbsent` garantit en prime qu'un seul thread fetch
réellement le réseau si plusieurs tombent simultanément sur le même trou (single-flight). Exclu de
cette mémoïsation : le trou qui touche la borne haute demandée alors que la dernière bougie n'est
pas encore close (la "bougie en cours" en usage live) — ses valeurs peuvent encore changer d'un
appel à l'autre, la mémoïser casserait la fraîcheur des données live.

2 tests ajoutés à `CachingMarketDataApiClientTest` : même trou historique demandé deux fois → un
seul appel réseau (`verify(delegate, times(1))`) ; trou touchant la bougie en cours demandé deux
fois → réseau rappelé à chaque fois (pas de régression sur le comportement live).

Suite complète (`test:tradeio-5`) rejouée : 589 tests (+2), 2 échecs — toujours les mêmes
pré-existants et sans rapport (`DxyIndicatorCacheSharingTest`). 0 régression.

À faire par Clem : décommenter `@Disabled` sur `RainbowDcaBacktestManualRunnerTest` et lancer
`test:tradeio-5` (ou directement les 2 méthodes de test) pour obtenir les CSV et la première lecture
réelle des résultats — en cours au moment de la rédaction de ce §13 (le run qui a révélé le besoin
du fix ci-dessus était déjà un run réel de Clem sur `SIDEWAYS_2018_2019`).
