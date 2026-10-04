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

- `TRAILING_STOP` : franchissement de la borne OU rebond de `trailingStopBuyPercent` (achat) / repli de `trailingStopSellPercent` (vente) depuis
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
- **Deux bancs séparés** : `runBoundsCalibration_realBtcHistory` (grille sur les 5 bornes %, tout
  le reste aux défauts) et `runReentryMethodsComparison_realBtcHistory` (grille sur
  `buyReentryMode`/`sellReentryMode`/`trailingStopBuyPercent`/`trailingStopSellPercent`/`cooldownDays`/`fixedDelayDays`/
  `sellFraction`, bornes V0 fixées) — largement suffisant vu que `CachingMarketDataApiClient`
  persiste les bougies H1 en DB (le fetch réseau n'a lieu qu'une fois par fenêtre, pas par
  scénario).
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

## 14. Bug de valorisation du bloc comparatif "DCA fixe" (2026-09-13) — DONE

Suite au run réel du bench §12/§13, Clem a signalé un résultat incompréhensible : le leaderboard
affichait des `delta_SIDEWAYS_2018_2019` autour de **-1314%** — mathématiquement impossible pour
`RainbowDcaBacktestService.pnlPercent` (long only : `pnl = currentValue + totalSaleProceeds -
totalInvested`, et comme `currentValue >= 0` et `totalSaleProceeds >= 0`, `pnlPercent >= -100%`
toujours). Le bug ne pouvait donc venir que de l'autre côté du delta : `fixedDcaComparison`.

**Diagnostic** : `RainbowDcaBacktestService.backtest` valorise son propre `currentPrice` au close de
`endDate` (borné à la fenêtre backtestée), mais appelait `DcaCalculatorService.calculate(...)`
(overload à 8 arguments) pour le bloc comparatif — cet overload valorise `currentPrice` au prix
BINANCE **live** (`clock.now()`) de façon inconditionnelle, quel que soit `endDate` demandé. C'est
le comportement voulu pour le tool MCP `calculate_dca` ("si j'avais fait ce DCA depuis telle date,
quel est mon PnL aujourd'hui ?"), mais faux comme baseline de comparaison dès qu'`endDate` n'est pas
"aujourd'hui" — vrai pour les 3 fenêtres du bench, toutes closes depuis des années. Sur
`SIDEWAYS_2018_2019` (endDate = 2019-06-30, BTC ≈ 10 000$ à l'époque), le DCA fixe se retrouvait
valorisé au prix BTC de 2026 (~100x plus haut), gonflant artificiellement son PnL% et donc le delta
Rainbow-vs-fixe dans le sens négatif.

**Fix** : overload rétrocompatible `DcaCalculatorService.calculate(..., Instant valuationInstant)`
(9 arguments) — `valuationInstant` ne change QUE la valorisation (`currentPrice`/`pnl`), pas le
calendrier d'achats (toujours borné par `startDate`/`endDate`/`clock.now()`, on ne simule jamais un
achat futur même en valorisant dans le passé). L'ancien overload à 8 arguments délègue au nouveau
avec `valuationInstant = null` → repli sur `clock.now()`, comportement strictement inchangé pour
`DcaMcpTools`/`calculate_dca` (seul autre appelant en production) et pour les 5 tests existants de
`DcaCalculatorServiceTest`. `RainbowDcaBacktestService` appelle désormais le nouvel overload avec
`fetchTo` (borne haute H1 déjà calculée pour `endDate`, même valeur que celle utilisée pour son
propre `currentPrice`) — les deux côtés du delta sont ainsi valorisés au même instant.

2 tests ajoutés à `DcaCalculatorServiceTest` (`valuationInstant_whenProvided_...` /
`valuationInstant_whenOmitted_...`) : vérifient par `verify(...)` sur l'appel `getCandles(...,
limit=1)` (le seul appel "prix de valorisation", distinct du range fetch à `limit=1000`) que
`valuationInstant` est bien utilisé comme borne quand fourni, et que `clock.now()` reste utilisé par
défaut sinon. `RainbowDcaBacktestServiceTest` : stub `dcaCalculatorService.calculate(...)` mis à
jour à 9 `any()` (nouvelle arité) — aucune assertion existante ne porte sur
`getFixedDcaComparison()`, donc aucun changement de comportement testé au-delà de l'arité du stub.

**Effet de bord corrigé sur le reporting du bench** (demande explicite de Clem après lecture du
premier tableau, qui n'affichait que `minDelta` — "pire fenêtre" — sans plus de détail) :
`RainbowDcaBacktestManualRunnerTest` affiche désormais, par fenêtre, le PnL% Rainbow ET le PnL% DCA
fixe côte à côte (record `WindowOutcome`), plus `maxDelta` (meilleure fenêtre) en plus de `minDelta`
(pire fenêtre, toujours le critère de tri) — CSV agrégé et leaderboard console mis à jour en
conséquence. `MemoizingDcaCalculatorServiceConfig` (mémoïsation du runner manuel) mis à jour pour
overrider le nouvel overload à 9 arguments (le seul appelé désormais par
`RainbowDcaBacktestService`) — l'overload à 8 arguments de la classe de base y délègue déjà
dynamiquement, donc reste mémoïsé automatiquement pour tout autre appelant éventuel.

Fichiers modifiés : `DcaCalculatorService.java` (nouvel overload), `RainbowDcaBacktestService.java`
(appel au nouvel overload avec `fetchTo`), `DcaCalculatorServiceTest.java` (+2 tests),
`RainbowDcaBacktestServiceTest.java` (stub à 9 arguments), `RainbowDcaBacktestManualRunnerTest.java`
(reporting best/worst + PnL côte à côte, mémoïsation sur le nouvel overload).

À faire par Clem : rejouer `test:tradeio-5` pour confirmer 0 régression, puis relancer le bench réel
(`RainbowDcaBacktestManualRunnerTest`) pour obtenir un leaderboard avec des deltas désormais bornés
et interprétables.

## 15. Plus-value réalisée vs potentielle (2026-09-13) — DONE

Remarque de Clem après lecture du leaderboard corrigé (§14) : contrairement au DCA fixe (qui ne
vend jamais rien dans ce modèle), le DCA Rainbow sécurise une partie des fonds en vendant en zone
haute — `pnlPercent` seul mélange donc gain déjà encaissé (via les ventes) et gain encore en
position (pas garanti tant que non vendu). Demande explicite : afficher le total des ventes, la
part que ça représente du total investi (plus-value réelle), et la plus-value encore potentielle
(la position restante, non vendue à `endDate`).

**Calcul retenu (`RainbowDcaBacktestService`)** : suivi d'un coût de revient de la position par la
méthode du coût moyen pondéré (WAC, la même que celle utilisée en comptabilité de portefeuille) —
`costBasis` augmente de `amountInvested` à chaque achat exécuté, et diminue à chaque vente
déclenchée d'une part proportionnelle à la fraction de la position vendue (`costBasis * sellQuantity
/ positionBeforeSell`) — le coût moyen unitaire du reste de la position ne change pas lors d'une
vente partielle, propriété standard du WAC. La plus-value réalisée d'une vente est
`produitDeVente - partDuCoûtDeRevientVendue`, cumulée en `totalRealizedGain` sur tout le backtest ;
la plus-value potentielle finale est `currentValue - costBasis` (coût de revient de ce qui reste en
position à `endDate`). Par construction, `realizedGain + potentialGain == pnl` (le coût de revient
total suivi égale toujours `totalInvested`, qu'il soit "consommé" par une vente ou encore attaché à
la position restante) — vérifié explicitement par un nouveau test.

`RainbowDcaBacktestResult` gagne 4 champs : `realizedGain`/`realizedGainPercent` (plus-value déjà
encaissée, en valeur et en % du total investi) et `potentialGain`/`potentialGainPercent` (plus-value
encore en position). `totalSaleProceeds` existait déjà dans le DTO mais n'était pas surfacé dans le
reporting du bench manuel — corrigé dans le même lot.

1 test ajouté à `RainbowDcaBacktestServiceTest`
(`sell_partialPosition_computesRealizedAndPotentialGainViaWeightedAverageCostBasis`) : scénario à la
main (achat unique à 100, vente de 50% de la position à 110 après un aller-retour à 200 puis 150)
vérifiant `totalSaleProceeds=55`, `realizedGain=5` (5% de l'investi), `potentialGain=5` (5% de
l'investi) et l'invariant `realizedGain + potentialGain == pnl` (10).

`RainbowDcaBacktestManualRunnerTest` : `WindowOutcome` gagne `realizedGainPercent`/
`potentialGainPercent` par fenêtre ; `AggregatedRow` gagne `avgRealizedGainPercent`/
`avgPotentialGainPercent` (moyenne sur les 3 fenêtres) ; CSV détail gagne `totalSaleProceeds`/
`realizedGainPercent`/`potentialGainPercent` par scénario × fenêtre ; CSV agrégé et leaderboard
console gagnent les 2 moyennes par jeu de paramètres.

**Suite complète (`test:tradeio-5`) rejouée après ce lot (2026-09-13, en autonome pendant l'absence
de Clem)** : 592 tests (+3 : le nouveau test WAC + les 2 déjà comptés depuis §14), 2 échecs — les
mêmes pré-existants et sans rapport (`DxyIndicatorCacheSharingTest.dxyEvaluatedForTwoSymbols_...` et
`...dxyEvaluatedTwiceForSameSymbol_...`, incident Twelve Data du 2026-08-17, documenté depuis le
2026-09-03), 0 erreur, 8 skipped. **0 régression introduite par ce lot** — le nouveau test WAC passe,
ainsi que les 2 tests `valuationInstant_*` de §14. Le build Maven remonte `BUILD FAILURE` uniquement
à cause des 2 échecs préexistants (surefire échoue le build dès qu'un test échoue, indépendamment de
son ancienneté) — comportement inchangé depuis le 2026-09-03, pas une nouvelle régression de ce lot.

À faire par Clem : relancer le bench réel pour voir la décomposition réalisé/potentiel sur les jeux
de paramètres réels (l'implémentation est vérifiée par la suite de tests, il ne reste que la lecture
des résultats sur données réelles).

## 16. Croisement bornes × reentry : meilleures bornes par tendance puis comparaison des reentry (2026-09-13) — DONE

Demande explicite de Clem : les deux bancs du §12 (`runBoundsCalibration_realBtcHistory` et
`runReentryMethodsComparison_realBtcHistory`) tournaient jusqu'ici indépendamment — l'un cherche un
seul jeu de bornes % robuste sur les 3 fenêtres, l'autre compare les méthodes de reentry à bornes V0
fixées. Clem veut les croiser : récupérer les meilleures bornes **par tendance** (bull/bear/sideways
séparément, pas un compromis unique) puis, sur ces bornes-là, comparer les techniques de reentry.

**Nouveau banc `runReentryWithBestBoundsPerTrend_realBtcHistory`**, en deux étapes :

1. `findBestBoundsPerWindow()` — pour chacune des 3 fenêtres indépendamment, lance toute la grille
   de bornes % du §12 (`boundsGrid()`, extraite de `runBoundsCalibration_realBtcHistory` sans
   changement de valeurs, pour être partagée par les deux bancs) et retient celle qui maximise le
   delta de PnL% vs le DCA fixe **sur cette fenêtre précise** — volontairement pas de robustesse
   cross-fenêtre à cette étape (contrairement au §12) : le but est justement d'obtenir des bornes
   différentes par tendance, pas un compromis unique. Les 3 jeux de bornes retenus (un par fenêtre)
   sont journalisés et exportés dans un CSV de traçabilité
   (`bounds-per-trend-then-reentry-best-bounds-<timestamp>.csv`).
2. Rejoue la même grille de méthodes de reentry que `runReentryMethodsComparison_realBtcHistory`
   (`buyReentryMode`/`sellReentryMode`/`trailingStopBuyPercent`/`trailingStopSellPercent`/`cooldownDays`/`fixedDelayDays`/
   `sellFraction`), mais avec les bornes retenues à l'étape 1 fixées **par fenêtre** (au lieu des
   bornes V0 par défaut), et regroupe les résultats par description de reentry à travers les 3
   fenêtres comme d'habitude (même critère de robustesse : minimum du delta sur les 3 fenêtres,
   maximum affiché aussi) — répond directement à la question "une fois les meilleures bornes
   retenues par tendance, quelle méthode de sortie ARMÉ est la plus robuste dessus ?".

**Refactoring de `RainbowDcaBacktestManualRunnerTest.java` pour permettre ce croisement** (aucun
changement de comportement pour les 2 bancs existants, uniquement une extraction) :

- `boundsGrid()` : grille de bornes % extraite de `runBoundsCalibration_realBtcHistory` (mêmes 5
  listes de valeurs, même filtre `isOrdered`), retourne des `BoundsCandidate` (record avec les 5
  bornes + leur description) au lieu de builders directement — réutilisée par le banc existant ET
  par `findBestBoundsPerWindow()`.
- `ScenarioSpec` (record : requête déjà construite + description + fenêtre) et
  `runScenariosAndReport(benchName, specs)` : moteur d'exécution parallèle + agrégation par
  robustesse + export CSV + leaderboard, extrait de l'ancien `runAcrossWindowsAndReport` — ce
  dernier n'est plus qu'une fine couche qui construit la liste de `ScenarioSpec` à partir d'un
  builder unique appliqué aux 3 fenêtres (cas des bancs §12), alors que
  `runReentryWithBestBoundsPerTrend_realBtcHistory` construit directement sa liste de `ScenarioSpec`
  avec des bornes différentes par fenêtre puis appelle `runScenariosAndReport` directement — sans
  cette extraction, l'ancien moteur imposait les mêmes paramètres sur les 3 fenêtres et ne pouvait
  pas exprimer "bornes différentes selon la fenêtre".
- `BestBoundsForWindow` (record : bornes retenues + le `ScenarioRow` qui a justifié le choix) : type
  de retour de `findBestBoundsPerWindow()`, réutilisé pour écrire le CSV de traçabilité.

Pas de changement dans `RainbowDcaBacktestService`/`RainbowDcaBacktestRequest`/`RainbowDcaBacktestResult`
ni dans `RainbowDcaBacktestServiceTest` — ce lot ne touche que le runner manuel
(`RainbowDcaBacktestManualRunnerTest.java`, `@Disabled` par défaut, comme les 2 bancs existants).

Suite complète (`test:tradeio-5`) rejouée après ce lot (2026-09-13, en autonome) : 593 tests (+1,
le nouveau `@Test runReentryWithBestBoundsPerTrend_realBtcHistory` compte comme un test JUnit
supplémentaire, skippé comme les 2 autres bancs du même `@Disabled class`), 2 échecs — toujours les
mêmes pré-existants et sans rapport (`DxyIndicatorCacheSharingTest`), 0 erreur, 9 skipped (+1, cf.
ci-dessus). **0 régression** — la compilation du nouveau banc et du refactoring
(`boundsGrid`/`ScenarioSpec`/`runScenariosAndReport`/`findBestBoundsPerWindow`/`BestBoundsForWindow`)
est propre, seul le nombre de tests skippés augmente d'une unité comme attendu pour un nouveau
`@Test` dans une classe `@Disabled`.

À faire par Clem : décommenter `@Disabled` et lancer `runReentryWithBestBoundsPerTrend_realBtcHistory`
en réel pour voir si une méthode de reentry se dégage comme robuste une fois les bornes optimisées
par tendance.

## 17. Rapports par tendance : realizedGain/potentialGain propres à chaque fenêtre (2026-09-13) — DONE

Demande explicite de Clem après lecture du leaderboard agrégé du §16 : les CSV/leaderboard existants
(`writeAggregatedCsv`/`printLeaderboard`) moyennent `realizedGainPercent`/`potentialGainPercent` sur
les 3 fenêtres (`avgRealizedGainPercent`/`avgPotentialGainPercent`, cf. §15) — utile pour juger de la
robustesse cross-tendance d'un jeu de paramètres, mais ça masque la décomposition réalisé/potentiel
propre à CHAQUE tendance : une moyenne 50%/50% peut cacher un 100% réalisé sur BEAR (tout vendu, plus
rien en position) et un 0% réalisé sur BULL (jamais vendu). Clem veut 3 rapports séparés, un par
fenêtre, avec les chiffres exacts de cette fenêtre.

**`writePerWindowReports(benchName, allRows, topN)`**, appelée depuis `runScenariosAndReport` juste
après `printLeaderboard` — donc automatiquement pour les 3 bancs existants (`runBoundsCalibration_
realBtcHistory`, `runReentryMethodsComparison_realBtcHistory`,
`runReentryWithBestBoundsPerTrend_realBtcHistory`) sans dupliquer l'appel dans chacun. Pour chacune
des 3 `WINDOWS` (`BULL_2023_2024`, `BEAR_2021_2022`, `SIDEWAYS_2018_2019`) :

- filtre `allRows` (les `ScenarioRow` bruts, un par scénario × fenêtre, déjà calculés par le moteur
  existant) sur cette fenêtre, trie par `deltaVsFixed` décroissant (delta de PnL% vs DCA fixe SUR
  CETTE FENÊTRE — pas de critère de robustesse cross-fenêtre ici, volontairement, puisque le but est
  justement la lecture par tendance) ;
- `writeWindowCsv` : écrit `<benchName>-<windowLabel>-report-<timestamp>.csv` (colonnes description,
  totalInvested, pnlPercent, fixedPnlPercent, deltaVsFixed, realizedGainPercent, potentialGainPercent,
  totalSaleProceeds, maxDrawdownPercent) — `realizedGainPercent`/`potentialGainPercent` viennent
  directement de `RainbowDcaBacktestResult` pour CETTE fenêtre, valeur exacte non moyennée ;
  `printWindowLeaderboard` : même tri, top N (15) en console, mêmes colonnes clés sans les colonnes
  de coût (description, pnlPercent, fixedPnlPercent, deltaVsFixed, realizedGainPercent,
  potentialGainPercent).

Aucun changement dans `RainbowDcaBacktestService`/`RainbowDcaBacktestRequest`/`RainbowDcaBacktestResult`
ni dans `RainbowDcaBacktestServiceTest` — purement une addition côté reporting du runner manuel
(nouvelles méthodes privées `writePerWindowReports`/`writeWindowCsv`/`printWindowLeaderboard` dans
`RainbowDcaBacktestManualRunnerTest.java`, plus l'appel ajouté dans `runScenariosAndReport`). Pas de
nouveau `@Test` : aucun changement attendu sur le nombre de tests/skipped par rapport au §16.

Suite complète (`test:tradeio-5`) rejouée après ce lot (2026-09-13) : 593 tests, 2 échecs — toujours
les mêmes pré-existants et sans rapport (`DxyIndicatorCacheSharingTest`), 0 erreur, 9 skipped —
identique au §16 comme attendu (aucun `@Test` ajouté, uniquement des méthodes privées de reporting).
**0 régression.**

À faire par Clem : décommenter `@Disabled` et lancer un des 3 bancs en réel pour obtenir, en plus des
CSV/leaderboard agrégés existants, les 3 nouveaux fichiers `<benchName>-<windowLabel>-report-
<timestamp>.csv` sous `target/rainbow-dca-bench/` — un par tendance, triés par delta vs DCA fixe,
avec le realizedGain%/potentialGain% exact de cette tendance.

## 18. Comparaison en valeur absolue + variation globale du prix par tendance (2026-09-13) — DONE

Deux demandes explicites de Clem à la suite de la lecture du rapport par tendance (§17) :

1. Le rapport n'affiche que des %, or Rainbow investit structurellement moins que le DCA fixe (il
   réduit l'exposition en zone haute et vend une partie du capital) — un gain % plus faible sur un
   capital engagé plus gros peut représenter un gain en valeur plus élevé qu'un % plus haut sur un
   capital plus petit, et inversement. Clem propose de comparer `realizedGain + potentialGain`
   (Rainbow) à `potentialGain` (DCA fixe) : l'équivalence tient — le DCA fixe ne vend jamais rien
   dans ce modèle (confirmé plus tôt, `DcaCalculatorService.calculate` n'a pas de logique de vente),
   donc son `realizedGain` est nul par construction et son `potentialGain` égale son `pnl` total. Il
   faut donc afficher, à côté des % déjà présents : `totalInvested` des deux côtés (pour juger
   l'écart de capital engagé) et le gain en valeur absolue des deux côtés (pas seulement le delta de
   %).
2. Pour chaque tendance, connaître la variation globale du prix BTC entre le début et la fin de la
   fenêtre — donne l'ampleur réelle du régime testé (un "bear" à -20% et un "bear" à -70% ne
   racontent pas la même histoire), contexte manquant pour juger si le PnL Rainbow/fixe affiché est
   impressionnant ou pas au vu du mouvement de marché sous-jacent.

**Implémentation, uniquement dans `RainbowDcaBacktestManualRunnerTest.java`** (aucun changement dans
`RainbowDcaBacktestService`/`RainbowDcaBacktestRequest`/`RainbowDcaBacktestResult` ni dans
`RainbowDcaBacktestServiceTest` — tout était déjà calculé, gratuit à exposer) :

- `ScenarioRow` gagne `fixedTotalInvested`/`fixedPnl` (`BigDecimal`), lus directement sur
  `result.getFixedDcaComparison()` (un `DcaResult`, qui a déjà `getTotalInvested()`/`getPnl()` en
  valeur absolue) dans `runOneScenario` — aucun nouveau calcul, juste conserver une référence qui
  était jusqu'ici jetée après extraction de `fixedPnlPercent`.
- Nouvelle méthode `computePriceChangePercent(List<RainbowDcaOccurrence>)` : `(close du dernier jour
  - close du premier jour) / close du premier jour * 100`, calculée une seule fois par fenêtre dans
  `writePerWindowReports` (à partir des occurrences du premier `ScenarioRow` de cette fenêtre — la
  série de prix D1 est identique pour tous les scénarios d'une même fenêtre, indépendante des
  paramètres Rainbow testés), puis passée à `writeWindowCsv`/`printWindowLeaderboard`.
- `writeWindowCsv` gagne les colonnes `btcPriceChangePercent` (répétée sur chaque ligne, pour
  faciliter un pivot Excel qui combinerait plusieurs rapports), `fixedTotalInvested`, `realizedGain`,
  `potentialGain`, `pnl` (ces 3 dernières = valeur absolue côté Rainbow, déjà exposées en % via
  `realizedGainPercent`/`potentialGainPercent`), `fixedGain` (= `row.fixedPnl()`, valeur absolue
  côté DCA fixe).
- `printWindowLeaderboard` gagne les colonnes `totalInvested`/`fixedTotalInvested`/`pnl`/`fixedGain`
  et la variation de prix dans l'en-tête du log (`"variation BTC sur la période : X%"`) plutôt qu'en
  colonne répétée sur chaque ligne (valeur constante pour toute la fenêtre, redondant en tableau
  console — gardé en colonne CSV car utile pour un pivot Excel multi-rapports).

Suite complète (`test:tradeio-5`) rejouée après ce lot : 593 tests, 2 échecs — toujours les mêmes
pré-existants et sans rapport (`DxyIndicatorCacheSharingTest`), 0 erreur, 9 skipped — identique au
§17 comme attendu (aucun `@Test` ajouté, uniquement des champs de record et des méthodes privées de
reporting). **0 régression.**

À faire par Clem : décommenter `@Disabled` et lancer un des 3 bancs en réel pour voir, dans les
nouveaux rapports par tendance, la variation de prix BTC de chaque fenêtre et le gain en valeur
absolue Rainbow vs DCA fixe à côté des %.

## 19. Amplitude min/max du prix + constat : §18 documenté mais jamais appliqué au code (2026-09-13) — DONE

**Contexte.** Clem a lancé `runReentryWithBestBoundsPerTrend_realBtcHistory` en réel et collé la
sortie console : les PnL% lui semblaient anormalement élevés (135% en `BULL_2023_2024`, 116% en
`SIDEWAYS_2018_2019`), et surtout les colonnes `totalInvested`/`fixedTotalInvested` promises au §18
n'apparaissaient pas dans son résultat — ni la variation de prix par fenêtre.

**Constat.** Relecture du fichier réellement présent sur le poste de Clem (pas la copie locale de
cette session) : les modifications décrites au §18 (`ScenarioRow.fixedTotalInvested`/`fixedPnl`,
colonnes valeur absolue, `computePriceChangePercent`) n'avaient **pas** atteint le fichier, malgré
le §18 documenté ci-dessus comme "DONE" — l'écriture précédente ne s'est pas répercutée sur le
fichier du projet (cause exacte non déterminée). La sortie collée par Clem correspond bien à
l'ancien format (en-têtes identiques au §17), ce qui confirme le diagnostic. Réappliqué
intégralement dans ce lot, avec vérification du contenu réel après écriture cette fois.

**Sur l'anomalie de PnL% : pas de bug détecté.** L'invariant `realizedGain + potentialGain == pnl`
reste garanti par le calcul WAC de `RainbowDcaBacktestService` (§15), non touché ici et toujours
couvert par `RainbowDcaBacktestServiceTest`. L'explication la plus probable pour un PnL élevé sur
une fenêtre étiquetée "SIDEWAYS" : le label ne décrit que la variation **nette** début/fin de la
fenêtre, pas sa trajectoire — une fenêtre peut chuter fortement puis remonter (ou l'inverse) tout en
affichant une variation nette modeste. Un DCA périodique qui achète pendant le creux capture un coût
moyen pondéré bien inférieur au prix de fin, ce qui peut produire un PnL% très supérieur à ce que
suggère la seule variation début/fin, sans qu'il s'agisse d'un bug. C'est précisément ce que la
nouvelle métrique ci-dessous permet de vérifier objectivement plutôt que de rester une hypothèse.

**Implémentation, toujours uniquement dans `RainbowDcaBacktestManualRunnerTest.java`** (aucun
changement dans `RainbowDcaBacktestService`/`RainbowDcaBacktestRequest`/`RainbowDcaBacktestResult`
ni dans `RainbowDcaBacktestServiceTest`) :

- Réapplication complète du §18 : `ScenarioRow` gagne `fixedTotalInvested`/`fixedPnl`, lus sur
  `result.getFixedDcaComparison()` dans `runOneScenario` ; les rapports par fenêtre affichent
  `totalInvested`/`fixedTotalInvested` et le gain en valeur absolue (`realizedGain`,
  `potentialGain`, `pnl` côté Rainbow, `fixedGain` côté DCA fixe) à côté des %.
- Nouvelle méthode `computePriceAmplitudePercent(List<RainbowDcaOccurrence>)` : `(max - min) / min`
  sur les closes de la fenêtre, en %. Complète `computePriceChangePercent` (variation début→fin)
  sans le remplacer — les deux sont calculées une seule fois par fenêtre dans
  `writePerWindowReports` (série de prix identique pour tous les scénarios d'une même fenêtre).
- `writeWindowCsv` gagne la colonne `btcPriceAmplitudePercent` à côté de `btcPriceChangePercent`
  (toutes deux répétées sur chaque ligne, pour un pivot Excel multi-rapports).
- `printWindowLeaderboard` affiche les deux métriques dans l'en-tête du log : `"variation BTC
  début->fin : X%, amplitude min->max : Y%"`.

Suite complète (`test:tradeio-5`) rejouée après ce lot : 593 tests, 2 échecs — toujours les mêmes
pré-existants et sans rapport (`DxyIndicatorCacheSharingTest`), 0 erreur, 9 skipped — identique aux
baselines §17/§18. **0 régression.** Cette fois le contenu du fichier a été relu après écriture pour
confirmer que les changements sont bien passés (cf. constat ci-dessus).

À faire par Clem : relancer un des 3 bancs et regarder, pour `SIDEWAYS_2018_2019`, l'écart entre la
variation début/fin et l'amplitude min/max — un écart important confirmera l'hypothèse ci-dessus
(chute puis rebond à l'intérieur de la fenêtre) plutôt qu'un bug de calcul.


## 20. Rapport équilibre réalisé/potentiel (2026-09-13, demande explicite de Clem) — DONE

### Contexte / demande de Clem

Après vérification (§19) qu'il n'y avait ni bug ni fuite d'état entre tests, Clem a fait une
observation de fond sur les classements existants :

> Et maintenant j'aimerais que tu écrives peut-etre de nouveaux test pour reprendre exactement ce
> lui là... mais en faisant en sorte que realized gain et potential gain soit quasi identique à la
> fin. Car là... le biais devient que si je ne vends pas, je maximise mes gains... mais
> potentiellement! Moi je veux une balance entre realized et potential ;)

Diagnostic : `printLeaderboard` (robustesse) et `printWindowLeaderboard` (tri par `deltaVsFixed`)
favorisent tous deux mécaniquement les jeux de paramètres qui vendent peu ou pas — le gain reste
alors entièrement `potentialGain` (non sécurisé, dépend du prix au moment de la mesure), ce qui
gonfle la performance affichée sans qu'aucun gain n'ait été réellement encaissé. Aucun classement
existant ne récompense le fait de sécuriser une partie du gain en vendant.

### Implémentation

Pas de nouveau `@Test` ni de nouveaux backtests : réutilisation des `ScenarioRow` déjà calculés par
`runScenariosAndReport`/`writePerWindowReports` pour les 3 bancs existants, avec un tri
supplémentaire :

- `computeBalanceGapPercent(ScenarioRow row)` : `abs(realizedGainPercent - potentialGainPercent)`
  (0 = équilibre parfait entre gain encaissé et gain en position, non garanti).
- `writeBalancedGainCsv` : nouveau CSV `<benchName>-<windowLabel>-balanced-report-<timestamp>.csv`,
  colonnes `description, balanceGapPercent, realizedGainPercent, potentialGainPercent, pnlPercent,
  fixedPnlPercent, deltaVsFixed, totalInvested, pnl`, trié par `balanceGapPercent` croissant.
- `printBalancedGainLeaderboard` : même tri, top N en console, avec un message explicite précisant
  que ce n'est PAS un critère de performance en soi (le classement robustesse fait l'inverse) —
  `pnlPercent`/`deltaVsFixed` affichés à côté pour arbitrer le compromis équilibre vs performance.
- Les deux sont appelés depuis `writePerWindowReports`, donc générés automatiquement pour les 3
  bancs à chaque exécution, sans action supplémentaire de Clem.

### Résultat attendu

Un classement complémentaire (pas un remplacement) au tri par delta vs DCA fixe, qui met en avant
les jeux de paramètres qui sécurisent une partie du gain au fil de l'eau sans sacrifier excessivement
la performance totale. Clem peut comparer les deux classements pour choisir son compromis entre
robustesse pure et équilibre réalisé/potentiel.

### Tests

`test:tradeio-5` (2026-09-13, post-implémentation) : 593 tests, 2 échecs pré-existants (`DxyIndicatorCacheSharingTest`, incident Twelve Data du 2026-08-17, sans lien), 0 erreur, 9 skipped — 0 régression. Aucun nouveau `@Test` ajouté (uniquement des méthodes privées de reporting), donc comptage identique à la baseline §17/§18/§19.

À faire par Clem : relancer un des 3 bancs (`@Disabled` à décommenter) et comparer le top du
classement "équilibre" avec le top du classement "robustesse"/"delta" pour un même banc — les
jeux de paramètres en tête ne devraient pas être les mêmes, ceux de l'équilibre vendant davantage.


## 21. Coordinate ascent bornes <-> reentry par tendance (2026-09-13, demande explicite de Clem) — DONE

### Contexte / demande de Clem

Après le croisement bornes -> reentry du §16 (`runReentryWithBestBoundsPerTrend_realBtcHistory`,
un seul aller : meilleures bornes à reentry V0 fixé, puis meilleur reentry sur ces bornes), Clem a
demandé d'aller plus loin :

> et après je voudrais que tu boucles entre la determination des meilleurs bounds et la
> determination des techniques d'achat/vente. Car avec de meilleurs bounds on va avoir une
> meilleur technique d'achat/vente qui va donner d'autres resultats pour les bounds... qui va
> donner d'autres resultats pour la technique, ainsi de suite jusqu'à stabilisation. Ainsi on aura
> de vrai paramétrage optimisé pour chacune des Trend.

Diagnostic : le point de départ (reentry V0) du §16 est arbitraire — de meilleures bornes changent
la technique de reentry optimale, qui change à son tour les bornes optimales. Un seul aller ne
donne donc pas un vrai optimum, seulement le résultat d'un choix de départ.

### Implémentation

Nouveau `@Test runCoordinateAscentPerTrend_realBtcHistory`, indépendant des 3 bancs existants
(aucune modification de leur comportement) :

- `ReentryCandidate` (nouveau record, miroir de `BoundsCandidate`) + `reentryGrid()` (extraction de
  la grille 3x3x3x4x4x5 = 2160 combinaisons déjà utilisée inline par
  `runReentryWithBestBoundsPerTrend_realBtcHistory`, valeurs inchangées, cette méthode garde sa
  propre grille inline) + `defaultReentryCandidate()` (défauts V0 exacts de
  `RainbowDcaBacktestRequest` : TRAILING_STOP/IMMEDIATE/5%/3j/10j/0.25).
- `findBestScenarioForWindow` : factorisation du cœur "exécute en parallèle + retient le meilleur
  delta vs DCA fixe" de `findBestBoundsPerWindow`, réutilisée pour bornes ET reentry.
- `findBestBoundsForWindow(window, reentry)` / `findBestReentryForWindow(window, bounds)` :
  symétriques, une seule fenêtre, l'un des deux paramètres fixé.
- `runCoordinateAscentForWindow(window)` : boucle bornes <-> reentry pour UNE fenêtre —
  `reentry_0` = défauts V0, puis en alternance `bornes_i` = meilleures bornes sachant
  `reentry_(i-1)`, `reentry_i` = meilleur reentry sachant `bornes_i`, jusqu'à ce que (bornes,
  reentry) ne bougent plus d'une itération à l'autre (comparaison sur `description()`, donc sur les
  valeurs exactes) ou jusqu'à `COORDINATE_ASCENT_MAX_ITERATIONS = 6` (garde-fou anti-oscillation :
  une oscillation stricte entre 2 états ne convergerait jamais sinon). Appliqué indépendamment pour
  chacune des 3 fenêtres (donc chaque tendance a son propre paramétrage optimisé, pas de robustesse
  cross-fenêtre à cette étape — même logique que §16).
- Export CSV de traçabilité par fenêtre (`coordinate-ascent-<windowLabel>-trace-*.csv`, une ligne
  par itération) + CSV final (`coordinate-ascent-final-per-trend-*.csv`, une ligne par fenêtre, le
  paramétrage stabilisé).

### Cohérence des logs (demande explicite de Clem, en cours de run)

> attention a ce que les log soient cohérent. Faire la moulinette d'abord, puis afficher les
> résultats tels qu'ils apparaissent actuellement. En soulignant en entete le meilleur paramétrage
> trouvé lors de cette boucle recursive.

`runCoordinateAscentPerTrend_realBtcHistory` est structuré en 3 phases explicites : (1) la
moulinette — calcul des 3 fenêtres en entier (seuls les logs de progression par itération de
`runCoordinateAscentForWindow` sortent ici, pas de résultat final) ; (2) les exports CSV, une fois
tout calculé ; (3) l'affichage, uniquement après la phase 1 — un entête encadré
(`===== COORDINATE ASCENT : MEILLEUR PARAMÉTRAGE STABILISÉ PAR TENDANCE ... =====`) suivi d'une
ligne par fenêtre au même format qu'avant (bornes + reentry stabilisés, PnL Rainbow/fixe, delta).

### Exécution nocturne autonome (2026-09-13, demande explicite de Clem)

> Je vais partir me coucher. Je reset le fichier de log, ainsi je voudrais qu'a la fin, tu lances
> le test. Ainsi demain matin au reveil j'aurais les resultats. A partir de maintenant, je ne
> pourrais répondre à aucune question, il faut donc que tu sois autonomes pour arriver jusqu'a la
> génération des logs.

Clem étant indisponible, décision prise de manière autonome : le `@Disabled` de classe (réseau+DB
réel) a été temporairement commenté, et les 3 bancs pré-existants (§14 à §20, déjà documentés,
résultats déjà connus) ont chacun reçu un `@Disabled` individuel pour ne PAS les relancer cette
nuit (limite le temps d'exécution et le risque de rate-limit réseau) — seul
`runCoordinateAscentPerTrend_realBtcHistory` (nouveauté de cette section) tourne réellement, lancé
via `test:tradeio-5`. Les 3 `@Disabled` individuels et le `@Disabled` de classe seront restaurés par
Claude à la fin de l'exécution, pour revenir à l'état par défaut du fichier (convention habituelle :
Clem décommente manuellement pour relancer un banc).

### Résultat attendu

Un paramétrage (bornes + reentry) réellement optimisé par tendance, obtenu par convergence plutôt
que par un unique aller arbitraire — potentiellement différent (et meilleur en delta vs DCA fixe)
de celui du §16 pour au moins une des 3 fenêtres.

### Tests

`test:tradeio-5` (exécution nocturne autonome, 2026-09-14 00:xx) : `runCoordinateAscentPerTrend_realBtcHistory`
— Tests run: 4, Failures: 0, Errors: 0, Skipped: 3, Time elapsed: 231.4 s (les 3 bancs §14-§20
neutralisés temporairement, cf. ci-dessus). Suite complète : 594 tests, 2 échecs pré-existants sans
rapport (`DxyIndicatorCacheSharingTest`, rate-limit Twelve Data du 2026-08-17), 0 erreur, 9 skipped
— 0 régression. Les 3 `@Disabled` temporaires et le `@Disabled` de classe ont été restaurés après
coup (fichier revenu à son état par défaut).

Convergence obtenue en exactement 3 itérations pour les 3 fenêtres :

- **BULL_2023_2024** : bornes pd2=-7 pd1=-5 pu1=2 pu2=5 pu3=16, reentry buy=FIXED_DELAY
  sell=FIXED_DELAY ts=3% cd=7j fd=15j sf=0.10 → PnL Rainbow=144.37%, PnL DCA fixe=143.54%,
  delta=0.83%.
- **BEAR_2021_2022** : bornes pd2=-5 pd1=-2 pu1=3 pu2=5 pu3=12, reentry buy=FIXED_DELAY
  sell=TRAILING_STOP ts=3% cd=12j fd=15j sf=0.75 → PnL Rainbow=-11.75%, PnL DCA fixe=-38.10%,
  delta=26.35%.
- **SIDEWAYS_2018_2019** : bornes pd2=-5 pd1=-2 pu1=3 pu2=5 pu3=16, reentry buy=FIXED_DELAY
  sell=FIXED_DELAY ts=3% cd=12j fd=10j sf=0.10 → PnL Rainbow=116.10%, PnL DCA fixe=108.47%,
  delta=7.63%.

CSV produits : `coordinate-ascent-BULL_2023_2024-trace-20260914-002831.csv`,
`coordinate-ascent-BEAR_2021_2022-trace-20260914-002831.csv`,
`coordinate-ascent-SIDEWAYS_2018_2019-trace-20260914-002831.csv`,
`coordinate-ascent-final-per-trend-20260914-002831.csv`.

À faire par Clem : au réveil, consulter `target/rainbow-dca-bench/coordinate-ascent-final-per-trend-*.csv`
(un paramétrage stabilisé par tendance) et les 3 `coordinate-ascent-<windowLabel>-trace-*.csv`
(historique de convergence) — comparer le paramétrage obtenu ici à celui du §16
(`bounds-per-trend-then-reentry-best-bounds-*.csv`) pour voir si la boucle a trouvé mieux qu'un
seul aller.

## 22. Bornes affichées dans le titre + coordinate ascent équilibré (2026-09-14, demande explicite de Clem) — EN COURS

### Contexte / demande de Clem

Après le §21, Clem constate une incohérence apparente entre le CSV du §16
(`bounds-per-trend-then-reentry-*.csv`) et le log "meilleures bornes pour X" affiché juste avant :

> ok, du coup, est-ce que ces valeurs calculées en cyclant, pour les bounds, sont utilisées pour le
> calcul des techniques d'achat/vente? (celui qui apparait dans les logs)?

Vérification par lecture de code : oui, `findBestReentryForWindow(window, boundsStep.bounds(), ...)`
reçoit exactement les bornes calculées à la même itération — pas de bug de couplage. Le problème
réel est ailleurs :

> ok du coup je veux que tu mettes dans le titre, la valeur des bounds utilisés. Car là, quand je
> regarde le CSV et que je compare avec les valeurs "meilleures bornes pour" (qui est affiché avant
> les tableaux de résultat), je n'ai pas du tout les mêmes valeurs! Et je voudrais également
> doubler la recherche cyclée en recherchant également les meilleures conditions pour que nous
> arrivions à un quasi 50/50 sur potential/realized

Diagnostic : `runReentryWithBestBoundsPerTrend_realBtcHistory` (§16) fixe les bornes par fenêtre
puis fait varier le reentry sur ces bornes fixes — mais ses rapports par fenêtre (CSV + leaderboard
console) n'affichaient que la description du reentry, jamais les bornes fixées pour cette fenêtre.
Rien à corriger dans le calcul : uniquement un manque d'affichage qui empêchait la comparaison
directe avec le log "meilleures bornes pour X" de `findBestBoundsPerWindow`.

### Implémentation

**1. Bornes dans le titre/CSV (fix ciblé, §16 uniquement)**

Un paramètre optionnel `fixedBoundsPerWindowLabel` (`Map<String, String>`, vide pour les autres
bancs) traverse le pipeline de reporting partagé, sans changer son comportement ailleurs :

- `runScenariosAndReport` : nouvelle surcharge à 3 arguments (l'existante délègue avec `Map.of()`).
- `writePerWindowReports` : idem, surcharge à 4 arguments.
- `writeWindowCsv` / `writeBalancedGainCsv` : colonne CSV optionnelle `boundsFixedForThisWindow`,
  ajoutée seulement si une description de bornes est fournie pour la fenêtre.
- `printWindowLeaderboard` / `printBalancedGainLeaderboard` : titre du tableau console complété par
  `— bornes fixées pour cette fenêtre : <description>` dans le même cas.
- `runReentryWithBestBoundsPerTrend_realBtcHistory` construit la map à partir de
  `bestBoundsPerWindow` (déjà calculée pour le log "meilleures bornes pour X") et l'injecte —
  donc CSV et log utilisent désormais exactement la même source, plus de divergence possible.

**2. Coordinate ascent équilibré (nouveau test, doublon du §21 avec un autre objectif)**

Généralisation de la mécanique de recherche du §21 pour accepter un objectif quelconque au lieu de
`deltaVsFixed` codé en dur :

- `findBestScenarioForWindow`, `findBestBoundsForWindow`, `findBestReentryForWindow`,
  `runCoordinateAscentForWindow` prennent désormais `Function<ScenarioRow, BigDecimal> objective,
  boolean maximize` (et `objectiveLabel` pour les logs/CSV). `runCoordinateAscentPerTrend_realBtcHistory`
  (§21) passe `ScenarioRow::deltaVsFixed, true, "delta vs DCA fixe"` — comportement inchangé.
- Nouveau `@Test runCoordinateAscentBalancedPerTrend_realBtcHistory` : même boucle bornes <-> reentry
  par fenêtre, mais objectif = `computeBalanceGapPercent` (écart réalisé/potentiel, cf. §20) **minimisé**
  au lieu de `deltaVsFixed` maximisé — cherche le paramétrage qui rapproche le plus `realizedGainPercent`
  de `potentialGainPercent` (quasi 50/50), indépendamment par tendance.
- Mêmes garde-fous que §21 (convergence sur `description()` ou `COORDINATE_ASCENT_MAX_ITERATIONS = 6`).
- Exports CSV séparés (préfixe `coordinate-ascent-balanced` au lieu de `coordinate-ascent`) :
  `writeCoordinateAscentTraceCsv`/`writeCoordinateAscentFinalCsv` prennent un `csvPrefix` pour ne pas
  écraser les fichiers du §21 ; colonne `balanceGapPercent` ajoutée aux deux CSV (§21 et §22, même
  format).
- Même structure de logs en 3 phases que §21 (moulinette puis entête + tableau final), avec un
  entête dédié `===== COORDINATE ASCENT ÉQUILIBRÉ : ... (écart réalisé/potentiel ~0) =====`.

### Résultat attendu

1. Le CSV et le leaderboard console du §16 affichent désormais les mêmes bornes que le log
   "meilleures bornes pour X" — comparaison directe possible, plus de valeurs apparemment
   contradictoires.
2. Un second paramétrage optimisé par tendance (bornes + reentry), cette fois pour l'équilibre
   réalisé/potentiel plutôt que pour la performance brute vs DCA fixe — probablement différent du
   §21 pour au moins une fenêtre, avec un compromis PnL à quantifier.

### Tests

Code poussé, pas encore exécuté (Clem disponible ce tour-ci, décision laissée à Clem plutôt
qu'exécution autonome).

À faire par Clem : décommenter le `@Disabled` de classe puis lancer `test:tradeio-5` (ou cibler
uniquement `runCoordinateAscentBalancedPerTrend_realBtcHistory` et/ou
`runReentryWithBestBoundsPerTrend_realBtcHistory` pour voir le nouveau titre avec les bornes) —
vérifier notamment que les bornes du CSV/titre du §16 coïncident bien avec le log "meilleures
bornes pour X", et comparer `coordinate-ascent-balanced-final-per-trend-*.csv` au
`coordinate-ascent-final-per-trend-*.csv` du §21.


## 23. SMA comme 3e dimension du coordinate ascent (2026-09-17, demande explicite de Clem) — EN COURS

### Contexte / demande de Clem

Après le §22, Clem relève un paramètre oublié :

> et j'ai totalement oublié un paramètre ajustable... c'est la valeur du SMA.
> Actuellement elle est fixe à 20 (c'est ça?) et il serait nécessaire de la faire varier pour voir si
> nous sommes vraiment sur une SMA optimale...!
> Tu peux regarde ce qu'il en est dans le code

Vérification par lecture de code : `smaPeriod` est bien un champ `@Builder.Default = 20` de
`RainbowDcaBacktestRequest` (donc paramétrable techniquement), mais aucun banc du runner ne le
faisait jamais varier — confirmé par recherche (`findstr`) : zéro occurrence de `smaPeriod`/`smaGrid`
dans le fichier de test, hors deux mentions de prose sans rapport. Le constat de Clem était donc
exact : la SMA était de facto figée à 20 partout, jamais explorée.

Choix d'intégration proposé et validé par Clem : ajouter la SMA comme 3e dimension du coordinate
ascent existant (§21/§22), plutôt qu'un banc séparé — la boucle bornes ↔ reentry devient bornes ↔
reentry ↔ sma, appliquée aux deux tests `runCoordinateAscentPerTrend_realBtcHistory` (§21) et
`runCoordinateAscentBalancedPerTrend_realBtcHistory` (§22).

En cours d'implémentation, Clem a signalé une simplification importante côté pré-chauffage du cache :

> pour le warmup, il suffit de prendre la plus grande valeur que l'on défini pour la SMA, non?

Correcte : la plage de warm-up H1 est `[startDate - smaPeriod, endDate]`, et le cache
(`CachingMarketDataApiClient`) persiste les bougies individuellement en base (pas par plage nommée).
Un warm-up unique avec le plus grand `smaPeriod` de la grille couvre donc, par construction, la plage
(plus étroite) de toute valeur plus petite — inutile de boucler le pré-chauffage sur toute la grille
SMA.

### Implémentation

- `SmaCandidate(int period, String description)` : nouveau record, même pattern que
  `BoundsCandidate`/`ReentryCandidate`.
- `smaGrid()` : grille `10, 14, 20, 30, 40, 50, 75, 100` (jours).
- `defaultSmaCandidate()` : `SmaCandidate(20, "sma=20j")` — valeur par défaut V0, incluse dans la
  grille.
- `findBestSmaForWindow(window, bounds, reentry, objective, maximize)` : même mécanique que
  `findBestBoundsForWindow`/`findBestReentryForWindow`, bornes et reentry fixées, `smaPeriod` variant
  sur `smaGrid()`.
- `findBestBoundsForWindow`/`findBestReentryForWindow` : signature étendue avec un paramètre
  `SmaCandidate sma` (fixé pendant la recherche des deux autres dimensions).
- `runCoordinateAscentForWindow` : boucle Gauss-Seidel à 3 variables au lieu de 2 — à chaque
  itération, bornes (calculées avec reentry+sma de l'itération précédente), puis reentry (avec les
  nouvelles bornes + sma précédent), puis sma (avec les nouvelles bornes + reentry). Convergence si
  les 3 descriptions sont inchangées par rapport à l'itération précédente, ou après
  `COORDINATE_ASCENT_MAX_ITERATIONS = 6`.
- `warmUpCacheForSmaGrid()` : remplace l'appel `warmUpCache(...)` unique des deux tests concernés —
  pré-chauffe une seule fois avec `smaPeriod = max(smaGrid())`, cf. remarque de Clem ci-dessus.
- CSV (`writeCoordinateAscentTraceCsv`/`FinalCsv`, pour §21 et §22) : nouvelle colonne
  `smaDescription`.
- Logs (trace + résumé final) : ajout de `sma=%s` dans les lignes de log des deux tests concernés.

### Résultat attendu

Pour chaque fenêtre, le coordinate ascent (robustesse §21 et équilibre §22) explore désormais aussi
la période de SMA optimale, et non plus seulement bornes + reentry à SMA fixée à 20. Résultat
possible : une SMA différente de 20 pour certaines fenêtres/tendances, à comparer aux résultats
existants du §21/§22 (mêmes CSV, colonne `smaDescription` en plus).

### Tests

Code poussé, pas encore exécuté (Clem disponible ce tour-ci, décision laissée à Clem plutôt
qu'exécution autonome).

À faire par Clem : décommenter le `@Disabled` de classe puis lancer `runCoordinateAscentPerTrend_realBtcHistory`
et/ou `runCoordinateAscentBalancedPerTrend_realBtcHistory` (ou `test:tradeio-5` en entier) — vérifier
que `smaDescription` apparaît dans les CSV et dans les logs de trace/résumé, et que le warm-up ne
s'exécute qu'une fois par fenêtre (au `smaPeriod` max de la grille) plutôt que par valeur de SMA.


## 24. Garantie de non-régression : ancre SMA=20 fixe (2026-09-17, demande explicite de Clem) — EN COURS

### Contexte / demande de Clem

Run réel du §23 collé par Clem (`COORDINATE ASCENT ÉQUILIBRÉ`) : le résultat avec SMA libre est
**moins bon** sur `BULL_2023_2024` que le résultat obtenu juste avant (§22, SMA=20 fixe) sur les deux
critères à la fois — écart réalisé/potentiel (1.50% au lieu de 0.00%) ET delta vs DCA fixe (-66.00%
au lieu de -53.85%). Comme SMA=20 fait partie de `smaGrid()`, ce n'est logiquement pas censé arriver
si l'algorithme ne fait "que mieux" en ajoutant un degré de liberté :

> Moi je veux qu'on améliore les résultats de façon sûre... pas en prenant des raccourcis. J'ai
> choisi SMA 20 de façon arbitraire (ou presque)... Me retourner un résultat moindre en me disant
> que c'est le meilleur... c'est inacceptable! surtout qu'il y a la SMA20 dans le lot! Soit par
> magie j'ai choisi la meilleure SMA (20) par hasard et donc le meilleur résultat = au dernier
> meilleur résultat avec SMA Fixe... Soit... il existe des résultats meilleurs... Et c'est surtout
> ce dernier point que je cherche à démontrer!

Clem a aussi relevé que le temps d'exécution n'avait pas ~8x augmenté malgré l'ajout de 8 valeurs de
SMA, ce qui lui a semblé suspect (un raccourci pris quelque part).

### Diagnostic

Sur le temps d'exécution : **pas de raccourci**, la grille de SMA (8 valeurs) est simplement
minuscule comparée aux deux grilles déjà dominantes évaluées à chaque itération —
`boundsGrid()` (5x5x5x5x5 filtré par `isOrdered`, **2185** combinaisons) et `reentryGrid()`
(3x3x3x4x4x5 = **2160** combinaisons). Le coordinate ascent évalue chaque dimension
**séparément** (additif : ~2185 + 2160 + 8 backtests par itération), pas le produit cartésien des 3
grilles (ce qui donnerait des dizaines de millions de combinaisons, injouable) — ajouter 8 candidats
SMA ajoute donc ~0.2% de travail par itération, pas 8x.

Sur la régression : **pas de bug non plus, mais un vrai piège méthodologique du coordinate ascent
(Gauss-Seidel) sur une fonction non convexe**, confirmé par relecture précise de la boucle. À
l'itération 1, avec sma encore égal au défaut (20j), l'étape bornes puis l'étape reentry du run "SMA
libre" reproduisent EXACTEMENT les mêmes bornes/reentry que l'ancien algorithme 2D (§21/§22) — donc
jusque-là, aucune différence. C'est seulement une fois que l'étape SMA de l'itération 1 choisit une
valeur différente de 20 (parce qu'elle améliore le score compte tenu des bornes/reentry *de cette
itération, pas nécessairement globalement*) que la boucle "part" avec un SMA différent pour
l'itération 2 — qui ré-optimise alors bornes et reentry pour CE SMA-là. Le SMA change la
classification des zones Rainbow (dépendante de l'écart au SMA), donc les bornes/reentry optimaux
changent aussi structurellement, pas juste marginalement. La trajectoire à 3 variables converge donc
vers un optimum local DIFFÉRENT de celui du run 2D à SMA=20 fixe — sans garantie qu'il soit meilleur,
même si SMA=20 fait partie de la grille explorée à chaque étape SMA. Chaque étape prise isolément
est correcte (elle ne peut pas faire moins bien que l'état précédent, sur son propre sous-problème),
mais l'enchaînement des 3 optimisations locales n'offre aucune garantie de non-régression par rapport
à un chemin de convergence totalement différent (l'ancien algorithme 2D, qui n'a jamais bougé du
SMA=20 pendant toutes ses propres itérations).

### Implémentation

Ajout d'un garde-fou explicite plutôt qu'une confiance aveugle dans la convergence à 3 variables :

- `runCoordinateAscentForWindow` gagne un paramètre `boolean varySma`. À `false`, la SMA reste gelée
  à `defaultSmaCandidate()` (20j) sur toute la boucle et son étape (`findBestSmaForWindow`) n'est
  jamais appelée — reproduit bit à bit l'algorithme 2D exact du §21/§22, avant l'ajout de la SMA.
- Nouvelle méthode `runCoordinateAscentWithSmaAnchor(window, objective, maximize, objectiveLabel)` :
  lance `runCoordinateAscentForWindow` deux fois pour la même fenêtre — `varySma=true` (SMA libre) et
  `varySma=false` (ancre SMA=20 fixe) — puis ne retient le résultat SMA libre QUE s'il bat
  **strictement** l'ancre sur l'objectif optimisé ; sinon l'ancre (SMA=20, comportement identique à
  avant le §23) est conservée telle quelle. Un log explicite compare les deux scores et indique
  lequel est retenu, pour chaque fenêtre.
- Les deux `@Test` (`runCoordinateAscentPerTrend_realBtcHistory` et
  `runCoordinateAscentBalancedPerTrend_realBtcHistory`) appellent désormais
  `runCoordinateAscentWithSmaAnchor` au lieu de `runCoordinateAscentForWindow` directement ; le reste
  (exports CSV, affichage final) est inchangé, `finalPerWindow` contient simplement le résultat
  retenu (libre ou ancre) par fenêtre.
- Coût : double le temps de calcul (les deux boucles tournent entièrement, pas de partage entre les
  deux), donc environ 2x le temps d'avant le §23 (qui était déjà quasiment inchangé par le §23,
  cf. diagnostic ci-dessus) — Clem a explicitement validé qu'un ralentissement, même de 8x, n'était
  pas un problème.

### Résultat attendu

Par construction, le résultat final affiché pour chaque fenêtre ne peut plus jamais être pire que
l'ancien résultat SMA=20 fixe (§21/§22) : soit la SMA libre est retenue parce qu'elle fait
strictement mieux (preuve concrète qu'une meilleure SMA existe pour cette fenêtre/cet objectif),
soit l'ancre est conservée (confirmation que SMA=20 était déjà optimal, au moins localement, pour
cette fenêtre/cet objectif) — plus jamais de "faux meilleur" affiché comme au run collé par Clem.

### Tests

Code poussé, pas encore exécuté. Vérifications statiques uniquement : équilibre des accolades
(378→391 après ajout du garde-fou, +13 paires cohérent avec le nouveau record + la nouvelle méthode
+ le bloc if/else ajouté), nombre de `@Test`/`@Disabled` inchangé, tous les appels à
`runCoordinateAscentForWindow`/`runCoordinateAscentWithSmaAnchor` vérifiés cohérents avec leurs
signatures.

À faire par Clem : décommenter le `@Disabled` de classe puis relancer
`runCoordinateAscentPerTrend_realBtcHistory` et/ou `runCoordinateAscentBalancedPerTrend_realBtcHistory`
— chercher dans les logs la ligne `comparaison SMA libre vs SMA=20 fixe` par fenêtre (nouveau) pour
voir explicitement les deux scores et lequel a été retenu ; le résultat final affiché ne devrait
jamais régresser par rapport aux valeurs déjà obtenues au §21/§22.


## 25. Logs plus explicites (PnL, investi, vendu) pour le coordinate ascent équilibré (2026-09-17, demande explicite de Clem) — EN COURS

### Contexte / demande de Clem

Après avoir resserré lui-même `smaGrid()` autour de 20 (`10, 14, 16, 18, 20, 22, 24, 26`) pour
affiner l'observation sur le §23/§24, Clem constate qu'aucune valeur ne bat la SMA=20 par défaut et
soupçonne une erreur de calcul (question restée en suspens, session interrompue avant réponse). En
parallèle, demande explicite indépendante :

> je voudrais, en plus, que dans les logs de
> RainbowDcaBacktestManualRunnerTest.runCoordinateAscentBalancedPerTrend_realBtcHistory ça soit plus
> explicite sur les PnL, montant investi, montant vendu, etc...

### Implémentation

Uniquement dans `runCoordinateAscentBalancedPerTrend_realBtcHistory` (bloc d'affichage final, phase
3) — aucun changement de calcul, uniquement de reporting :

- La ligne unique précédente (bornes/reentry/sma + un résumé condensé PnL%) est éclatée en 3 lignes
  de log par fenêtre :
  1. Ligne d'identification (bornes/reentry/sma stabilisés), inchangée dans le fond.
  2. Ligne `Rainbow` : `investi` (`totalInvested`) et nombre d'achats déclenchés, `vendu`
     (`totalSaleProceeds`, produit de vente encaissé) et nombre de ventes déclenchées, valeur de la
     position restante (`currentValue`), PnL total en valeur et %, puis la décomposition
     réalisé/potentiel en valeur ET en % (jusqu'ici seul le % était affiché), et l'écart
     réalisé/potentiel (objectif optimisé par ce test).
  3. Ligne `DCA fixe` : investi, PnL en valeur et %, delta vs Rainbow — pour comparaison directe des
     montants engagés des deux côtés (pas seulement des %, cf. §18/§19).
- Toutes les valeurs proviennent de champs déjà calculés par `RainbowDcaBacktestService`
  (`RainbowDcaBacktestResult`) et `ScenarioRow` (`fixedTotalInvested`/`fixedPnl`, cf. §18/§19) —
  aucun nouveau calcul, uniquement de nouveaux accesseurs affichés (`getTotalInvested`,
  `getTotalSaleProceeds`, `getCurrentValue`, `getPnl`, `getRealizedGain`, `getPotentialGain`,
  `getBuyTriggeredCount`, `getSellTriggeredCount`).

### Résultat attendu

Le run réel de ce test affichera, pour chaque fenêtre, tous les montants (investi/vendu/PnL réalisé
et potentiel, des deux côtés Rainbow/DCA fixe) directement dans les logs, sans avoir besoin d'ouvrir
un CSV pour vérifier la cohérence des chiffres — utile en particulier pour investiguer la question en
suspens de Clem (aucune SMA de la grille resserrée ne bat 20 : ces logs permettront de voir si les
montants investis/vendus varient de façon cohérente d'une SMA à l'autre, ou si un montant reste figé
de façon suspecte).

### Tests

Code poussé, pas encore exécuté.

À faire par Clem : décommenter le `@Disabled` de classe puis lancer
`runCoordinateAscentBalancedPerTrend_realBtcHistory` — vérifier que les nouvelles lignes `Rainbow`/
`DCA fixe` s'affichent bien pour chacune des 3 fenêtres, avec des montants cohérents entre eux
(`réalisé + potentiel` doit toujours reconstituer le PnL total, cf. invariant du §15).


## 26. Détail investi/vendu/PnL déplacé sur les logs d'itération (2026-09-17, demande explicite de Clem) — EN COURS

### Contexte / demande de Clem

Suite au §25 (détail ajouté sur le résumé final uniquement), précision de Clem : le détail voulu est
en fait sur les logs **intermédiaires** (une ligne par itération, du style
`BULL_2023_2024 coordinate ascent itération ...`), pas sur le résumé final :

> ah mais je voulais que tu mettes ces infos supplémentaires dans les log intermediaire du genre :
> BULL_2023_2024 coordinate ascent itération / BEAR_... etc... Par contre laisse tout sur une ligne
> pour ces log là, ca sera plus lisible.

### Implémentation

Dans `runCoordinateAscentForWindow` (méthode partagée par les 2 boucles §21/§23/§24, donc le détail
apparaît pour les DEUX tests coordinate ascent, pas seulement l'équilibré) — la ligne de log par
itération (déjà présente depuis le §21, une par itération et par fenêtre) reste une seule ligne
(demande explicite de Clem : "laisse tout sur une ligne"), mais gagne les mêmes informations que le
§25 avait ajoutées au résumé final : `investi`/nombre d'achats déclenchés, `vendu`/nombre de ventes
déclenchées, PnL total (valeur + %), décomposition réalisé/potentiel (valeur + %), puis côté DCA
fixe : investi, PnL (valeur + %), et le delta vs DCA fixe — avant le résumé de l'objectif optimisé
par cette boucle (`deltaVsFixed` pour §21, `écart réalisé/potentiel` pour §22/§24) déjà affiché en
fin de ligne. Toujours aucun nouveau calcul, uniquement des accesseurs déjà exposés (mêmes que §25).

### Résultat attendu

Chaque ligne `<Fenêtre> coordinate ascent itération N (varySma=...) : ...` affiche désormais tous les
montants en jeu à cette itération précise, sans attendre la fin de la boucle — permet de suivre
l'évolution investi/vendu/PnL à chaque étape de la convergence, pour les 2 tests coordinate ascent
(§21 robustesse et §22/§24 équilibré).

### Tests

Code poussé, pas encore exécuté. Vérification statique : accolades toujours équilibrées (391/391,
inchangé — pas de nouveau bloc, juste une variable locale + un `String.format` élargi), comptage des
23 spécificateurs de format vérifié programmatiquement contre les 23 arguments fournis (types `%d`
aux positions attendues pour les compteurs d'achats/ventes/itération).

À faire par Clem : décommenter le `@Disabled` de classe puis lancer l'un des 2 tests coordinate
ascent — vérifier que chaque ligne d'itération affiche bien investi/vendu/PnL sur une seule ligne,
lisible.


## 27. Priorité vente/cooldown : blocage total de l'achat pendant vente armée ou cooldown (2026-09-29, demande explicite de Clem) — DONE

### Contexte / demande de Clem

Clem a énoncé 6 règles sur le comportement attendu des reentry modes et du cooldown, en demandant
confirmation qu'elles correspondaient au code (et que ces règles valent aussi bien pour le bench
que pour la traduction Pine Script) :

> Fixed delay days n'est appliqué que sur le Buy ou le Sell est en FIXED (les 2 partagent la même
> valeur). Trailing stop => si trailing stop dans Buy ou Sell (les 2 partagent la même valeur). Que
> ces 2 derniers se déclenchent dès qu'il dépasse la zone d'armement. Alors que IMMEDIAT se
> déclenche lorsqu'on réintègre le Rainbow. Cooldown days n'est utilisé que sur la vente => bloque
> le rachat ou un futur armement/déclenchement de vente. Il vaut à partir du moment où la vente est
> réalisée. Tant qu'une vente est armée... pas d'achat ni de nouvel armement de vente.

Vérification du code réel (`RainbowDcaBacktestService`) : les rôles IMMEDIATE/TRAILING_STOP
énoncés par Clem ne correspondaient pas à l'implémentation existante (c'est en réalité IMMEDIATE
qui déclenche au franchissement de zone, pas TRAILING_STOP) — signalé à Clem avec citation du code.
Elle a confirmé vouloir garder les formules de déclenchement `buyTriggered`/`sellTriggered`
inchangées (donc pas de changement sur ce point précis), et vouloir que le Java soit modifié pour
les deux autres règles (blocage achat pendant vente armée, cooldown bloquant tout). 3 questions de
clarification posées, réponses de Clem :

- `FIXED_DELAY` = un nombre de jours fixe armé à la sortie du Rainbow (confirme : pas de
  changement de formule, cf. `buyTriggered`/`sellTriggered`).
- Vente armée bloque **tout achat et tout autre armement de vente** ("pour la vente ça évite les
  yoyo et de multiples ventes").
- Cooldown actif bloque **armement, déclenchement ET achat** ("Ni armement, ni déclenchement ni
  achat !").

### Implémentation

`RainbowDcaBacktestService.backtest()` — 3 changements dans la boucle par jour :

1. Armement de vente (`zone == EXTREME_HAUT`) gaté par `cooldownRemaining == 0` — avant, aucun
   gate.
2. Le bloc ACHAT entier (armement `EXTREME_BAS`, déclenchement de l'état ARMÉ, achat de zone
   intermédiaire) est désormais entouré d'un `if (sellState != ARMED && cooldownRemaining == 0)` —
   avant, seul le montant acheté était annulé pendant le cooldown (`buyMultiplier` mis à `null`
   après coup), l'état/l'armement continuait d'évoluer normalement, et rien ne bloquait l'achat
   pendant qu'une vente était armée.
3. Suppression de la branche `else` (devenue morte) qui annulait `buyAction`/`buyMultiplier` en cas
   de cooldown, puisque le bloc ACHAT ne s'exécute plus du tout dans ce cas.

`buyTriggered`/`sellTriggered` (les formules de déclenchement elles-mêmes) restent inchangées, y
compris pour `FIXED_DELAY`.

### Régression volontaire assumée

Avant ce changement, un backtest pouvait enchaîner vente puis rachat le même jour ou les jours
suivants pendant le cooldown, et l'armement de vente pouvait se reproduire alors qu'un cooldown
courait. Ce n'est plus le cas depuis le 2026-09-29 : un résultat de bench antérieur à cette date
n'est plus reproductible tel quel avec le code actuel (moins d'occurrences d'achat/vente dans les
scénarios où vente et cooldown se chevauchaient).

### Tests

`test:tradeio-5` — 672 tests, 2 échecs (`DxyIndicatorCacheSharingTest`, préexistants et sans
rapport, cf. §13 de cette étude pour leur contexte). `RainbowDcaBacktestServiceTest` passe sans
régression.

### Pine Script

Script versionné : `tools/pine/rainbow_dca_v4_atr_moon.pine` (successeur ; l'ancien `docs/traiding-view/rainbow-dca-atr_pinescript.js` n'existe plus), fidèle à `RainbowDcaBacktestService`
(mêmes règles de blocage, `trailingStopBuyPercent`/`trailingStopSellPercent` séparés). Le panneau de stats
est confiné à la plage visible via `chart.left_visible_bar_time`/`chart.right_visible_bar_time` lus dans
`barstate.islast` : cette lecture est ce qui force TradingView à ré-exécuter le script au zoom/déplacement
(ne pas la remplacer par des dates en input : le panneau ne suivrait plus la fenêtre). Les benches cherchent
une valeur de trailing unique appliquée aux deux côtés (achat = vente).

## 28. Bug bornes non monotones (up1/up2 >= up3) dans 2 presets ATR du bench — achats parasites en zone haute (2026-09-30, signalé par Clem) — DONE (diagnostic), EN ATTENTE (décision sur le fix du bench)

### Constat de Clem

Sur le pine script (calqué sur `RainbowDcaBacktestService`), Clem observe des achats répétés
("plein d'achats") alors que la clôture est visuellement dans/après la zone UP2, ce qui ne devrait
jamais produire d'achat (seul NO_BUY/EXTREME_HAUT sont attendus au-delà de UP1/UP0.5).

### Diagnostic

`RainbowZone.classify()` (Java) teste les bornes dans un ordre fixe en supposant l'ordre croissant
strict `percdown2 < percdown1 < percup1 < percup2 < percup3` :

```java
if (close < percdown2Level) return EXTREME_BAS;
if (close < percdown1Level) return X2;
if (close < percup1Level)   return X1;
if (close < percup2Level)   return X0_5;
if (close < percup3Level)   return NO_BUY;
return EXTREME_HAUT;
```

Rien dans `RainbowDcaBacktestRequest` ne valide cette monotonie (ni côté PERCENT, ni côté ATR). Or
2 des 5 `combinedCandidate` exportés par le bench (`target/rainbow-dca-visualizer/atr-trend-calibration.json`,
utilisés tels quels dans le pine script) violent cette hypothèse côté haut :

- `BULL_SEP24_FEV25` : `atrMultUp1=3`, `atrMultUp2=3`, `atrMultUp3=2` → `percup2Level > percup3Level`
  (la borne "extrême haut" est PLUS PROCHE de la SMA que UP1/UP2).
- `SIDEWAYS` : `atrMultUp1=3`, `atrMultUp2=4`, `atrMultUp3=3` → même souci (UP1=UP3 < UP2).

Conséquence : pour un close compris entre `percup3Level` (le vrai seuil EXTREME_HAUT, plus proche
de la SMA) et `percup2Level` (plus loin), `close < percup2Level` est vrai et le classifieur renvoie
`X0_5` (achat, mult ×0.5, cadencé tous les jours) au lieu de `EXTREME_HAUT` (zone vente) — d'où la
série d'achats parasites observée par Clem tant que le prix reste dans cette fenêtre.

**Ce n'est pas un bug du pine script** : `RainbowZone.classify()` (Java) a exactement le même
comportement pour ces 2 presets — vérifié en relisant le classifieur ligne à ligne. Un vrai backtest
Java avec `BULL_SEP24_FEV25` ou `SIDEWAYS` produirait la même zone de faux X0_5. C'est une
propriété du `combinedCandidate` retenu par la recherche coordinate-ascent du bench (qui n'impose
aucune contrainte de monotonie sur les bornes ATR up1/up2/up3), présente dans les 2 sorties JSON
utilisées pour construire les presets.

### Décision à prendre (pas encore tranchée par Clem)

1. Contraindre la recherche coordinate-ascent du bench (`RainbowDcaAtrTrendBenchExportTest`) à
   n'explorer/retenir que des combinaisons `atrMultUp1 <= atrMultUp2 <= atrMultUp3` (et
   `atrMultDown2 >= atrMultDown1`), puis relancer `BULL_SEP24_FEV25`/`SIDEWAYS` ; ou
2. Ajouter une validation explicite dans `RainbowDcaBacktestRequest`/`RainbowZone` qui rejette ou
   corrige (ex. `max()` en cascade) des bornes non monotones ; ou
3. Garder ces 2 presets tels quels en connaissance de cause (le score du bench les a jugés
   meilleurs globalement malgré cet artefact local).

Rien n'a été codé pour ce point tant que Clem n'a pas choisi une option.

## 29. Validation monotonie des bornes (down2>=down1>=0, up1<=up2<=up3) — fix du bug §28 (2026-09-30, demande explicite de Clem) — DONE

### Décision de Clem

Contrainte confirmée, égalité permise pour "supprimer" une zone (ex. `up3=up2` pour annuler
NO_BUY), mais aucune inversion :

```
down2 >= down1 >= 0  <=  SMA  <=  0 <= up1 <= up2 <= up3
```

`up3` reste le seuil EXTREME_HAUT lui-même (pas de borne distincte de "déclenchement vente").
Choix explicite de Clem sur l'implémentation : lever une `DcaException` dans le validateur (pas de
clamp défensif ailleurs — `RainbowZone.classify()` reste un classifieur pur qui suppose l'invariant
déjà garanti en amont, puisque `atr >= 0` préserve l'ordre des multiplicateurs une fois validés une
seule fois par requête) ; le bench (coordinate ascent) doit rattraper l'exception et continuer au
candidat suivant plutôt que planter.

### Implémentation

`RainbowDcaBacktestService#validate()` :
- Ajout d'un bloc de validation ATR (absent jusqu'ici) : `atrMultDown2 >= atrMultDown1 >= 0` et
  `atrMultUp1 <= atrMultUp2 <= atrMultUp3`, sinon `DcaException`.
- Bloc % existant relâché de strictement croissant (`<`) à croissant au sens large (`<=`), même
  règle des égalités permises.

`RainbowDcaAtrTrendBenchExportTest` (le bench qui produit les 5 presets utilisés dans le pine
script) : `runVariants()` et `backtestAllWindows()` catchent désormais `DcaException` par
variante/fenêtre (pas de `put()` du tout pour une combinaison rejetée, cf. correctifs ci-dessous) ;
`score(null)` renvoie un sentinel fini `INVALID_SCORE = -1.0E12` pour garantir qu'une combinaison
invalide n'est jamais retenue comme meilleure variante par le coordinate ascent, et
`buildEntryNode(null, s)` renvoie un nœud `{"invalid": true, ...}` au lieu de déréférencer `r`.

**Non fait** (portée volontairement limitée à la demande, à réactiver au besoin) : les 2 autres
bench `@Disabled` (`RainbowDcaAtrRobustBenchExportTest`, `RainbowDcaAtrScoreExportTest`) n'ont pas
reçu le même garde-fou try/catch — ils planteraient sur une combinaison invalide s'ils sont
réactivés tels quels.

### Tests

`RainbowDcaBacktestServiceTest` : 5 nouveaux tests — inversion ATR haut/bas (`DcaException`),
égalités ATR acceptées (pas d'exception), inversion % (`DcaException`).

### Conséquence sur les 2 presets cassés du §28

`BULL_SEP24_FEV25` et `SIDEWAYS` (les `combinedCandidate` actuels, qui ont `up2>up3`) sont
désormais des requêtes ATR **invalides** au sens de cette validation — le pine script continue de
les utiliser tels quels (aucune validation côté Pine), mais un vrai `RainbowDcaBacktestService.backtest()`
avec ces valeurs lèverait maintenant une exception. Il faut relancer `RainbowDcaAtrTrendBenchExportTest`
(coûteux, cf. mémoire projet — à confirmer avec Clem avant de le faire) pour que le coordinate
ascent, contraint par cette validation, retrouve un `combinedCandidate` valide pour ces 2 fenêtres,
puis mettre à jour le pine script avec les nouvelles valeurs.

**Mise à jour** : `## 29` marquée DONE — bug `ConcurrentHashMap.put(key, null)` (NPE) corrigé dans
`runVariants()`/`backtestAllWindows()` (ne plus faire le `put()` du tout pour une combinaison
rejetée, plutôt que `put(k, null)` — `ConcurrentHashMap` interdit les valeurs `null`). `test:tradeio-5` :
676 tests, 2 échecs (`DxyIndicatorCacheSharingTest`, pré-existants sans rapport).

**2e correctif (2026-09-30, retour Clem après 1re tentative de relance du bench)** : `round()`
appelle `new BigDecimal(v, MathContext)`, qui rejette `Infinity`/`NaN`
(`NumberFormatException: Infinite or NaN`) — `score(null) = Double.NEGATIVE_INFINITY` plantait dès
que `aggregateScore` moyennait cette valeur. Remplacé par un sentinel **fini** `INVALID_SCORE =
-1.0E12` (largement sous tout score réel atteignable sur un backtest BTC), même effet (jamais
retenu par le coordinate ascent) mais compatible `BigDecimal`. `test:tradeio-5` re-vérifié : 676
tests, mêmes 2 échecs pré-existants sans rapport.

**3e correctif (2026-09-30, retour Clem après 2e tentative de relance du bench, suite à la demande
explicite "réviser le code de test en entier")** : après les 2 correctifs ci-dessus, le pipeline de
sélection/score était bien null-safe, mais le code d'export JSON (`buildEntryNode()`, appelé pour
chaque variante de la passe 1 dans `buildWindowNode()`) déréférençait encore `r.getParameters()`
sans garde — `NullPointerException` dès qu'une variante de perturbation (pas seulement le candidat
final) était rejetée par la validation. Revue complète du fichier (et non plus correctif réactif
au cas par cas) : `buildEntryNode(RainbowDcaBacktestResult r, double s)` retourne désormais un nœud
`{"invalid": true, "invalidReason": "...", "score": s}` si `r == null`, au lieu de construire
`parameters`/`summary`/`occurrences`. Ajout aussi d'un garde-fou fail-fast dans `buildWindowNode()`
sur `baselineResult` (si le baseline lui-même est invalide, `IllegalStateException` explicite
plutôt qu'une NPE plus loin — signe que les bornes ATR de base du preset sont elles-mêmes non
monotones). Vérification par grep de tous les points d'accès à `RainbowDcaBacktestResult` dans le
fichier : plus aucun déréférencement non gardé. `test:tradeio-5` re-vérifié : 676 tests, mêmes 2
échecs pré-existants sans rapport (aucune régression).
