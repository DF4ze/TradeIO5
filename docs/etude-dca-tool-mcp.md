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

Trancher la section 2 (surtout : montant par achat, fixe ou variable) avant toute implémentation. Une fois ça fait, l'implémentation suit le même patron que `TreeAnalysisMcpTools`/`TreeAnalysisFacade`, en bypassant volontairement `MarketDatasetEngine`/`Bucket` — plus simple, et ça évite le bug de cache déjà documenté dans `etude-tick-retrieval.md` (`MarketDatasetCache` ne fait jamais de cache-hit en usage réel, non pertinent ici puisqu'on ne veut justement pas de cache glissant pour ce cas d'usage).

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
