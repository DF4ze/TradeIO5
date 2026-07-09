# Prompt d'implémentation — Lot 2 (indicateurs macro/externes)

Prompt autonome, à la suite de `docs/prompt-implementation-lot1-indicateurs.md`. Couvre le **Lot 2** défini dans `docs/etude-indicateurs-macro-externes.md` (§14) : DXY, S&P500/Nasdaq, calendrier macro, et la Strategy de qualification de mouvement.

**Prérequis strict** : le Lot 1 doit être terminé et mergé avant de commencer — l'item H ci-dessous consomme directement `OpenInterestIndicator`/`FundingRateIndicator` (Lot 1, item C) et `ObvIndicator` (déjà existant avant le Lot 1). Vérifier leur présence et leur contrat exact (`IndicatorType`, `IndicatorResult.values`) avant de commencer l'item H — ne pas supposer, relire le code livré au Lot 1.

Lire avant de commencer :
1. `docs/etude-indicateurs-macro-externes.md` — §3, §4, §8, §12.
2. `docs/prompt-implementation-lot1-indicateurs.md` — pour connaître exactement ce qui a été livré au Lot 1 (noms de classes, DTOs, packages) : la nomenclature ci-dessous part du principe qu'elle a été suivie telle quelle ; adapter si l'implémentation réelle a divergé.
3. `service/tree/strategy/impl/TrendConfirmationStrategy.java` et `service/tree/strategy/AbstractStrategy.java` — patron de référence pour l'item H (Strategy qui combine plusieurs `IndicatorType`).
4. `service/tree/indicator/impl/ObvIndicator.java` — **à lire en détail avant l'item H**, ce document ne présume pas de sa forme de sortie exacte (valeur cumulative brute ? pente ? à vérifier dans le code, pas ici).
5. `service/tree/strategy/StrategyAggregator.java` et `model/dto/tree/strategy/StrategySignal.java` — pour savoir comment le score produit par l'item H sera consommé en aval.

---

## Item E — DXY (Twelve Data, calcul synthétique)

**Prérequis humain** : compte gratuit Twelve Data (twelvedata.com), clé API en query param `apikey`.

**Source** : pas de ticker DXY fiable garanti sur le palier gratuit — calcul à partir de la formule officielle et de 6 paires forex, toutes disponibles nativement chez Twelve Data :

```
DXY = 50.14348112 × EURUSD^-0.576 × USDJPY^0.136 × GBPUSD^-0.119 × USDCAD^0.091 × USDSEK^0.042 × USDCHF^0.036
```

**Appel groupé** : `GET https://api.twelvedata.com/price?symbol=EUR/USD,USD/JPY,GBP/USD,USD/CAD,USD/SEK,USD/CHF&apikey={key}` — un seul appel, chaque symbole consomme un crédit côté quota (donc 6 crédits, pas 6 appels réseau). D'après le comportement documenté du client officiel (confirmé indirectement, à valider par un appel réel avec la clé une fois créée) : la réponse à une requête multi-symboles est un **objet JSON keyé par symbole**, chaque valeur étant de la forme `{"price": "1.1740"}` (comme pour un symbole seul, mais imbriqué sous la clé du symbole plutôt que retourné à plat). **Vérifier ce format exact au premier appel réel** avant de figer le DTO de désérialisation — ne pas committer un mapping non testé contre une vraie réponse.

**À faire** :
1. `WebProviderCode.TWELVE_DATA` — nouvelle valeur.
2. `IndicatorType.DXY` — nouvelle valeur.
3. Package `service/tree/indicator/external/twelvedata/` :
   - `TwelveDataForexProvider` (interface) + `TwelveDataForexClient extends AbstractExternalIndicator` : une méthode qui prend une liste de paires et retourne leurs prix (`Map<String, Double>`), mêmes garde-fous que les clients déjà livrés (`invalid()`/DTO propre sur 4xx/5xx/timeout, jamais d'exception qui remonte).
   - DTO de réponse à adapter à la forme réelle constatée à l'appel (objet keyé par symbole — utiliser `Map<String, PriceEntry>` avec Jackson plutôt qu'une liste, sauf si le test réel montre une autre forme).
4. `DxyIndicator implements Indicator` dans `service/tree/indicator/external/` : `getType() = DXY`, `getRequiredData() = 0`, appelle `TwelveDataForexProvider` pour les 6 paires en un appel, applique la formule, retourne `IndicatorResult.value = dxy`. Si une seule des 6 paires manque/est invalide, retourner `IndicatorResult.invalid()` plutôt que de calculer avec une valeur par défaut arbitraire (une formule à 6 facteurs avec un facteur manquant ne peut pas être silencieusement approximée).
5. `IndicatorCredentialResolver.resolve(...)` : `case DXY -> WebProviderCode.TWELVE_DATA;` (et voir item F pour les deux autres cas sur le même provider).
6. `WebProviderInitializer`/`ApiCredentialInitializer` : provider `TWELVE_DATA` (base URL `https://api.twelvedata.com`) + credential `"System"` avec la clé réelle (jamais committée en clair, cf. mémoire projet).

**Tests attendus** : calcul de la formule sur un jeu de 6 valeurs connues (vérifier le résultat contre une valeur DXY de référence trouvée manuellement à la même date/heure — tolérance à définir, l'indice réel bouge en continu) ; comportement `invalid()` si une des 6 paires manque ; mapping JSON → `Map<String, Double>` sur la forme de réponse réellement constatée.

## Item F — S&P500 / Nasdaq (même provider Twelve Data)

**Source** : `GET https://api.twelvedata.com/price?symbol=SPX,IXIC&apikey={key}` (tickers à confirmer au premier appel réel — Twelve Data peut exposer l'indice sous `SPX`, `GSPC` ou un symbole spécifique à son catalogue ; utiliser leur outil de recherche de symboles si `SPX`/`IXIC` ne répondent pas). Mutualiser avec l'item E : soit un seul appel groupé aux 8 symboles (6 paires forex + 2 indices) si l'usage temporel le permet (même fréquence de rafraîchissement), soit deux appels séparés si les besoins de fraîcheur diffèrent — trancher à l'implémentation, documenter le choix.

**À faire** :
1. `IndicatorType.SP500`, `IndicatorType.NASDAQ` — deux nouvelles valeurs.
2. Deux `Indicator` (`Sp500Indicator`, `NasdaqIndicator`) dans `service/tree/indicator/external/`, réutilisant `TwelveDataForexProvider`/`TwelveDataForexClient` de l'item E (généraliser le nom si besoin, ex. `TwelveDataQuoteProvider`, puisqu'il ne s'agit plus seulement de forex) — **pas de nouveau client HTTP**, un seul point d'entrée Twelve Data pour E+F.
3. `IndicatorCredentialResolver.resolve(...)` : `case SP500, NASDAQ -> WebProviderCode.TWELVE_DATA;`.

**Point d'attention à coder explicitement** (signalé dans l'étude, ne pas l'oublier) : les indices actions ne tradent pas 24/7. Le DTO de réponse Twelve Data porte normalement un timestamp/une donnée de fraîcheur — l'exposer dans `IndicatorResult.values` (ex. `"lastTradeTime"`) plutôt que de ne renvoyer qu'un `value` nu, pour qu'un consommateur en aval (une future `MarketOpinion`) puisse distinguer une valeur fraîche d'une clôture de vendredi soir reconduite telle quelle pendant tout le week-end.

**Tests attendus** : mapping JSON → valeur ; présence de l'indicateur de fraîcheur dans `values`.

---

## Item G — Calendrier macro (Finnhub + ForexFactory)

**Nature différente des autres items** : ce n'est pas un `IndicatorType`/`Indicator` classique (`compute()` mesure un état "maintenant", pas une liste d'événements futurs) — c'est un **service de lecture d'événements datés**, à construire à part.

### Sources

**ForexFactory (complément, déjà vérifié en direct — utilisable tel quel)** : `GET https://nfs.faireconomy.media/ff_calendar_thisweek.json`, aucune authentification, format JSON confirmé :

```json
[
  {
    "title": "ISM Services PMI",
    "country": "USD",
    "date": "2026-07-06T10:00:00-04:00",
    "impact": "High",
    "forecast": "54.2",
    "previous": "54.5"
  }
]
```

`impact` ∈ `{"Low", "Medium", "High", "Holiday"}`. `date` est une chaîne ISO-8601 avec offset (pas UTC directement — à convertir). Limite d'usage : 2 téléchargements/5 minutes max côté serveur — largement suffisant pour un rafraîchissement horaire, mais mettre un cache/throttle côté client pour ne jamais dépasser cette limite même en cas de bug d'appel en boucle.

**Finnhub (source principale, API officielle)** : base `https://finnhub.io/api/v1`, endpoint pressenti `/calendar/economic` (**à confirmer à l'implémentation** — la doc publique n'a pas pu être récupérée en contenu texte lors de cette étude, seulement la structure de la page ; ouvrir `https://finnhub.io/docs/api/economic-calendar` directement dans un navigateur, ou consulter `https://finnhub.io/docs/api` pour la liste d'endpoints, avant de coder le DTO), auth via `token` en query param ou header `X-Finnhub-Token`. Créer un compte gratuit et vérifier au premier appel réel : le nom exact des champs (probablement proche de `event`/`country`/`time`/`impact`/`actual`/`estimate`/`prev`, mais à confirmer contre une vraie réponse, pas à deviner).

### À faire

1. `WebProviderCode.FINNHUB` et `WebProviderCode.FOREXFACTORY` — deux nouvelles valeurs (`FOREXFACTORY` sans vraie clé, même traitement que `DEFILLAMA` au Lot 1 : credential avec `apiKey` vide, seul le `baseUrl` compte).
2. `model/dto/.../MacroEvent.java` — DTO normalisé commun aux deux sources : `title`, `country`, `dateTime` (`Instant`, converti depuis le format source), `impact` (enum `MacroEventImpact { LOW, MEDIUM, HIGH, HOLIDAY }`), `source` (`FINNHUB`/`FOREXFACTORY`), et les champs bruts disponibles (`forecast`/`previous`/`actual` en `String`, les formats de valeur variant trop selon l'événement — `"54.2"`, `"-78.3B"`, `"2.50%"` — pour les typer numériquement sans une logique de parsing par unité, hors scope de ce lot).
3. Deux providers (`FinnhubEconomicCalendarClient`, `ForexFactoryCalendarClient`), tous deux `extends AbstractExternalIndicator`, retournant chacun `List<MacroEvent>` sur une fenêtre de dates donnée. Mêmes garde-fous habituels : liste vide en cas d'erreur, jamais d'exception qui remonte.
4. `MacroEventCalendarService` (nouveau, `service/tree/macro/` ou équivalent à trancher selon la convention du projet) : agrège les deux sources, dédoublonne (heuristique proposée : même `country` + `impact` + `dateTime` à quelques minutes près + titre approchant → considérer comme le même événement, garder la version Finnhub si les deux sont présentes puisqu'elle porte potentiellement `actual` en plus de `forecast`/`previous`), expose au minimum une méthode `List<MacroEvent> getEvents(Instant from, Instant to)` et une méthode utilitaire `boolean isWithinRiskWindow(Instant now, Duration window, MacroEventImpact minImpact)`.
5. `IndicatorCredentialResolver` n'est **pas** le bon endroit pour brancher ce service (il résout des credentials par `IndicatorType`, pas applicable ici) — les deux clients résolvent leur credential directement via `ApiCredentialRepository`/l'utilisateur `"System"`, sur le même principe mais sans passer par `IndicatorCredentialResolver` tel quel (ou l'étendre pour accepter une clé différente d'un `IndicatorType`, à trancher).
6. `WebProviderInitializer`/`ApiCredentialInitializer` : `FINNHUB` (base URL `https://finnhub.io/api/v1`, clé réelle après création de compte) et `FOREXFACTORY` (base URL `https://nfs.faireconomy.media`, pas de vraie clé).

**Décision explicitement hors scope de ce lot** : le branchement de `MacroEventCalendarService.isWithinRiskWindow(...)` dans `DecisionEngine`/`Scenario` pour suspendre ou pondérer une décision d'entrée près d'un événement à risque. Ce lot livre le service interrogeable et testé ; le brancher dans le pipeline de décision est un choix d'architecture à part (impact sur `DecisionEngine`, pas seulement sur la couche indicateur) — à traiter dans un lot ultérieur une fois le service validé en usage read-only.

**Tests attendus** : parsing des deux formats source vers `MacroEvent` (au moins un exemple par source, avec le JSON ForexFactory déjà fourni ci-dessus comme fixture de test valide) ; dédoublonnage sur un cas où les deux sources rapportent le même événement FOMC ; `isWithinRiskWindow` sur un cas dans la fenêtre et un cas hors fenêtre.

---

## Item H — Strategy de qualification de mouvement (OI + Funding + OBV)

**Dépend strictement du Lot 1 (item C)** : `OpenInterestIndicator`, `FundingRateIndicator` doivent exister et être fonctionnels avant de commencer. Dépend aussi d'`ObvIndicator`, déjà existant — **relire son code avant d'écrire une ligne**, ce document ne présume pas de la forme exacte de son `IndicatorResult`.

### Écart à combler par rapport au Lot 1 : l'Open Interest a besoin d'un delta, pas seulement d'une valeur ponctuelle

Le critère "chute brutale d'OI" (signature d'une cascade de liquidations) ne peut pas se lire sur une seule valeur instantanée. Si `OpenInterestIndicator` livré au Lot 1 n'expose que `IndicatorResult.value` (valeur courante), **l'étendre ici** avant d'écrire la Strategy : utiliser l'endpoint déjà identifié `GET /v1/open-interest-history` (Coinalyze, voir Lot 1 item C) avec 2 points (période courante + période précédente, `interval` aligné sur le besoin — ex. `1hour`) plutôt que `GET /v1/open-interest` (valeur unique), et exposer `IndicatorResult.values = Map.of("current", ..., "previous", ...)` — exactement le même principe que `FearAndGreedResponse.now`/`yesterday` déjà en place. Documenter ce changement s'il modifie le contrat déjà livré au Lot 1 (`values` au lieu de `value` simple).

Le funding rate, lui, n'a pas besoin de delta pour ce diagnostic : un niveau absolu élevé (positif ou négatif) est déjà significatif en soi (positionnement long/short surchargé) — la valeur ponctuelle déjà livrée au Lot 1 suffit.

### Conception de la Strategy

**Fichier** : `service/tree/strategy/impl/MovementQualificationStrategy.java` (nommage indicatif), `extends AbstractStrategy`, patron `TrendConfirmationStrategy` (injection `IndicatorEngine`, construction d'`IndicatorContext` par entrée de `parameters.getIndicatorParameters()`, discrimination par `IndicatorKey.getType()`).

**Entrées attendues** : 3 `IndicatorKey` — `OPEN_INTEREST`, `FUNDING_RATE`, `OBV` (même TimeFrame). Suivre le même garde-fou que `TrendConfirmationStrategy.accepts(...)` : vérifier via `accepts()` que les 3 types sont bien présents avant de matcher cette Strategy plutôt qu'une autre au même `StrategyType`.

**Logique de score (à ajuster empiriquement, ceci est un point de départ, pas une formule figée)** :
1. `oiDelta = (current - previous) / previous` depuis `OPEN_INTEREST.values`.
2. `fundingSignal` : funding fortement positif (au-dessus d'un seuil paramétrable) = sur-effet-de-levier long ; fortement négatif = sur-effet-de-levier short ; proche de zéro = neutre. Normaliser en `[-1, 1]` de façon symétrique aux deux seuils, sur le même principe que `TrendConfirmationStrategy.adxFactor` (interpolation linéaire entre deux bornes).
3. `volumeConfirmation` : à définir précisément une fois `ObvIndicator` relu (proposition de départ : comparer l'OBV courant à sa propre moyenne récente, ou utiliser son signe/sa pente si l'indicateur l'expose déjà — **ne pas deviner un champ qui n'existe pas dans `ObvIndicator`**, adapter cette étape au contrat réel).
4. Score final, proposition : si `oiDelta` fortement négatif pendant un mouvement de prix marqué (a accès à `context.series()`/`context.lastPrice()` pour évaluer l'amplitude du mouvement récent) → score orienté négatif (« cascade de liquidations, mouvement probablement peu durable »). Si `oiDelta` stable/positif + `fundingSignal` proche de zéro + `volumeConfirmation` positive → score orienté positif (« mouvement de conviction, spot »). Si `fundingSignal` fortement positif et `oiDelta` en forte hausse pendant une hausse de prix → score négatif d'alerte (« sur-effet-de-levier long en construction, risque de cascade à venir ») — un cas distinct du premier (celui-là regarde en arrière, celui-ci regarde un risque en formation).

**Limite déjà connue et acceptée pour ce lot** (signalée dans l'étude précédente, §4.1 de `etude-indicateurs-strategies-opinions.md`) : cette Strategy sera agrégée par `StrategyAggregator` de la même façon qu'une Strategy directionnelle classique (`ENTRY`), alors qu'elle joue conceptuellement un rôle de **modulateur de confiance** plutôt que de générateur de signal indépendant (dans le même esprit que Fear & Greed vis-à-vis d'un signal technique). Le mécanisme de modulation dédié n'existe pas encore dans le code (l'étude le documente comme un manque, pas comme un bug à corriger dans ce lot) — traiter cette Strategy comme `StrategyType.ENTRY` classique pour ce lot, agrégée normalement, et noter explicitement dans le commentaire de la classe que ce traitement est une simplification en attendant un vrai mécanisme de modulation.

**À faire** :
1. `getType() = Set.of(StrategyType.ENTRY)`.
2. `accepts(...)` : vérifie la présence exacte des 3 `IndicatorType` attendus (`OPEN_INTEREST`, `FUNDING_RATE`, `OBV`), sur le patron de `TrendConfirmationStrategy.accepts(...)`.
3. Seuils (`oiDelta` critique, bornes `fundingSignal`) exposés en `StrategyParameters.numericParams`, avec des valeurs par défaut documentées en constantes (même patron que `P_ADX_LOW_THRESHOLD`/`DEFAULT_ADX_LOW_THRESHOLD` dans `TrendConfirmationStrategy`).
4. `StrategySignal` retourné avec `score`/`confidence`/`type` via `MarketOpinionHelper.scoreToConfidenceAndSignalType(score)`, comme les deux Strategies existantes.

**Tests attendus** : un cas "cascade de liquidations" (OI en forte baisse, prix en mouvement marqué) → score négatif ; un cas "conviction spot" (OI stable/en hausse, funding neutre, volume confirmé) → score positif ; un cas "sur-effet-de-levier en construction" (funding et OI tous deux en forte hausse pendant une hausse de prix) → score négatif d'alerte, distinct du premier cas dans le `reason` du signal ; un cas avec un des 3 indicateurs invalide → `StrategySignal.notValid(...)`, comme le fait déjà `TrendConfirmationStrategy` quand une entrée manque.

---

## Definition of done (les 4 items)

- Compilation propre, aucun test existant (Lot 1 inclus) cassé.
- Item E/F : formule DXY validée contre une source de référence externe à un instant donné (pas juste un test unitaire avec des chiffres inventés).
- Item G : service interrogeable en lecture, **non branché** dans `DecisionEngine`/`Scenario` (décision volontairement reportée, voir plus haut) — ne pas le faire "en plus" sans en discuter, ça change le comportement de décision du système en dehors du périmètre annoncé de ce lot.
- Item H : ne pas démarrer avant d'avoir confirmé/étendu le contrat exact d'`OpenInterestIndicator` (delta current/previous) et relu `ObvIndicator` — les deux sont des prérequis silencieux, pas des détails.
- Comme au Lot 1 : chaque nouveau client réseau retombe proprement sur une liste vide / `invalid()` en cas de panne, jamais une exception qui remonte à l'appelant.
