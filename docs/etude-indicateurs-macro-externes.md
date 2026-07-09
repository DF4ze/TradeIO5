# Étude — Indicateurs macro/externes listés dans `docs/Indicateurs.odt`

Objectif : reprendre un par un les indicateurs notés dans `Indicateurs.odt` (liste brute + section "Cryptolyze" + note sur la qualification d'un mouvement) et identifier concrètement ce qu'il faut pour les implémenter dans TradeIO5 — source de données, point d'intégration dans l'architecture existante, et difficultés propres à chacun.

Comme les deux études précédentes (`etude-indicateurs-strategies-opinions.md`, `etude-extension-risk-macro-external.md`), ceci est une étude, pas un ticket : aucune modification de code n'a été faite. Le contenu de `Indicateurs.odt` a été extrait directement (le fichier était présent mais non ouvrable tel quel par les outils standards — central directory ZIP absente ; extrait manuellement flux par flux). L'architecture ci-dessous a été vérifiée contre le code réel (`IndicatorType`, `Indicator`, `AbstractExternalIndicator`, `FearAndGreedIndicator`/`FearAndGreedProvider`, `IndicatorCredentialResolver`, `WebProviderCode`, `GlobalMarketOpinion`, `ExternalMarketOpinion`, `OpinionScope`, `BinanceMarketDataApiClient`, `Bucket`/`MarketData`) au moment de la rédaction.

## 0. Le patron d'intégration déjà disponible

Tous les indicateurs listés dans le document sont des **valeurs externes** (aucun ne se calcule à partir des chandelles déjà en base, sauf les deux derniers) — ce n'est donc jamais le patron `SmaIndicator`/`RsiIndicator`/`AdxIndicator` qui s'applique, mais celui déjà posé pour `FEAR_GREED` :

- `IndicatorType` (enum) reçoit une nouvelle valeur.
- Un `Indicator` (`@Component`) l'implémente : `getType()`, `getRequiredData()` (0 pour une valeur externe, pas de warmup sur bougies), `compute(IndicatorContext, IndicatorParameters)`.
- Un client dédié (`extends AbstractExternalIndicator`, qui fournit `getWebClient(ApiCredentialDTO)` avec cache de `WebClient` par provider) appelle l'API externe et retourne un DTO de réponse propre (`XxxResponse`, avec un `invalid()` statique pour le cas panne/timeout — jamais de `NullPointerException` laissé remonter, c'est le point que corrige explicitement le commentaire dans `FearAndGreedIndicator`).
- `WebProviderCode` reçoit une valeur si le fournisseur n'existe pas déjà (aujourd'hui : `BINANCE`, `BINANCE_TESTNET`, `KRAKEN`, `LEDGER`, `COINSTATS`, `METAMASK`).
- `IndicatorCredentialResolver.resolve(IndicatorType)` reçoit une nouvelle branche dans son `switch` pour mapper le nouveau `IndicatorType` vers son `WebProviderCode`. Les credentials elles-mêmes vivent en base (`ApiCredentialRepository`, utilisateur technique `"System"` — voir `ApiCredentialInitializer`), pas dans `application-*.properties` : un indicateur externe qui a besoin d'une clé API demande donc aussi une ligne d'initialisation (ou une insertion manuelle) pour ce provider.
- Consommation : soit via `GlobalMarketOpinion` (scope `GLOBAL`, lecture directe par `IndicatorEngine.execute(...)`, sans passer par `Strategy`) si la valeur n'a pas vocation à être agrégée avec d'autres signaux techniques — c'est le cas de la quasi-totalité de cette liste — soit via une nouvelle `Strategy` si l'indicateur doit se combiner avec des signaux techniques par-symbole.

Deux limites structurelles à garder en tête pour toute la liste :

- **`MarketContext.series` ne porte qu'un seul symbole par timeframe — nuance après relecture du code.** Ce n'est pas une impossibilité, seulement l'absence de support *déclaratif* pour le multi-symbole : `IndicatorKey` (`type`, `timeFrame`, `params`) ne porte pas de symbole, et le chemin normal (`AbstractStrategy`/`StrategyParameters.getIndicatorParameters()`) résout toujours l'indicateur contre `context.symbol()`. Mais rien n'empêche une `Strategy` d'injecter `IndicatorEngine` directement (`TrendConfirmationStrategy` le fait déjà) et de construire elle-même un second `IndicatorContext` avec un symbole différent codé en dur — exactement comme `GlobalMarketOpinion` le fait pour FEAR_GREED, en dehors de toute `Strategy`. Le vrai coût de cette approche n'est pas "impossible", c'est que le second symbole perd le préchargement automatique de bougies (`getRequiredCandles()` ne le voit pas, puisqu'il n'itère que sur `parameters.getIndicatorParameters()`). Pour cette liste précise, la question ne se pose pas : aucun des 12 indicateurs n'est un ratio/comparaison entre deux actifs — ce point reste donc informatif, pas bloquant.
- **`GLOBAL` et `MACRO` sont aujourd'hui fusionnés** dans `GlobalMarketOpinion` faute d'un deuxième indicateur macro-économique réel (`etude-extension-risk-macro-external.md`, §3.3). Cette liste en fournit plusieurs candidats sérieux (DXY, composantes GLI, market cap stablecoins, calendrier macro) — le moment de trancher la séparation `GLOBAL` (sentiment marché crypto) vs `MACRO` (économie/liquidité/taux) redevient concret. Voir §13.

---

## 1. Flow ETF (BTC/ETH) — monte/descend, positif/négatif

**Besoin** : signe (et idéalement magnitude) du flux net journalier des ETF spot BTC/ETH.

**Source de données** : confirmé — CoinStats (déjà provider intégré pour Fear & Greed) n'expose aucun endpoint ETF flow dans sa documentation publique (recherche ciblée sans résultat concluant). Aucune des trois options gratuites n'est une vraie API officielle :
- Farside Investors publie les données en tableau HTML, sans API officielle (un accès structuré existe via un service tiers non officiel, type Parse).
- SoSoValue expose un dashboard public mais pas d'API self-service gratuite documentée.
- CoinGlass a un endpoint ETF (flux net BTC/ETH) mais dans son offre payante (plan API le moins cher observé autour de 29$/mois, pas de palier gratuit pour l'API).

**Décision retenue** : scraping du tableau HTML Farside. C'est effectivement un tableau simple (une ligne par jour, une colonne par émetteur, un total) — pas de JavaScript côté rendu à priori, donc parsable avec un client HTTP classique + un parseur HTML (ex. Jsoup, pas encore une dépendance du projet, à vérifier/ajouter) plutôt qu'un navigateur headless. Pas de facteur commun avec le calendrier macro (§8) : ce dernier est finalement couvert par des sources JSON structurées (Finnhub / ForexFactory, voir §8 mis à jour) plutôt que par du scraping HTML — le parseur Farside restera donc le seul de ce genre dans le projet pour l'instant, pas la peine de construire une abstraction générique en avance de phase.

**Intégration** : nouveau `IndicatorType.ETF_FLOW`, nouveau `WebProviderCode` (ex. `FARSIDE`), mais **pas** le patron `AbstractExternalIndicator` standard tel quel (celui-ci suppose une réponse JSON désérialisable directement en DTO via `WebClient` — ici il faut d'abord parser du HTML). Prévoir une étape de parsing dédiée entre la récupération du corps de page et la construction du DTO `EtfFlowResponse`, avec le même filet de sécurité que `FearAndGreedIndicator` : toute erreur de parsing (structure de tableau changée, colonne manquante) doit retomber sur `invalid()`, jamais sur une exception qui remonte.

**Point dur réel** : pas technique au sens strict (le tableau est simple à parser), mais fiabilité — c'est une page HTML publique non versionnée, qui peut changer de structure sans préavis (renommage de colonne, ajout d'un émetteur ETF, changement de mise en page) et casser le parseur silencieusement si le fallback `invalid()` n'est pas scrupuleusement appliqué à chaque étape du parsing, pas seulement à l'appel réseau.

## 2. Fear & Greed — regarder l'évolution, pas juste le niveau

**Besoin explicite du document** : « Si monte d'un coup : pas bon, il risque de retomber » — donc une lecture de **vitesse de variation**, pas seulement de niveau absolu.

**État actuel** : déjà largement là. `FearAndGreedResponse` porte déjà `now`/`yesterday`/`lastWeek` (vérifié dans `FearAndGreedIndicator.compute()`), mais `GlobalMarketOpinion.decide()` n'utilise aujourd'hui que `now` (via `MarketOpinionHelper.computeRsiScore`, seuils contrarian fixes 25/75) — la donnée pour détecter "une hausse brutale" existe déjà en snapshot, seule la règle qui l'interprète manque.

**Ce qu'il faut ajouter** : une règle explicite dans `GlobalMarketOpinion` (ou une nouvelle méthode dans `MarketOpinionHelper`) du type : si `now - yesterday` dépasse un seuil (ex. +15 points en 24h) alors que le niveau `now` est déjà élevé, dévaloriser la confiance du signal contrarian BUY/pondérer vers NEUTRAL plutôt que de suivre le score brut. C'est un ajustement de logique, pas un nouvel indicateur ni une nouvelle source de données — le point le moins coûteux de toute la liste.

**Point d'attention** : `lastWeek` n'est capturé nulle part actuellement (ni loggé, ni exposé) — si la règle doit comparer sur 7 jours plutôt que sur 24h, il faut vérifier que `now`/`yesterday`/`lastWeek` correspondent bien à des points fixes (J, J-1, J-7) et pas à une fenêtre glissante recalculée par CoinStats, pour éviter une fausse impression de "hausse brutale" due à un artefact de fenêtre.

## 3. DXY (indice dollar)

**Besoin** : valeur/tendance du Dollar Index.

**Source de données — recommandation tranchée : Twelve Data.** Ni Twelve Data ni Alpha Vantage ne confirment publiquement et explicitement porter le ticker propriétaire ICE `DXY` en tant que tel dans leur catalogue (les deux mettent en avant des indices actions - Dow Jones, S&P, FTSE - pas l'indice dollar spécifiquement). Plutôt que parier sur la présence du ticker, la voie fiable est de **calculer le DXY soi-même** à partir de sa formule officielle (moyenne géométrique pondérée, publique) :

```
DXY = 50.14348112 × EURUSD^-0.576 × USDJPY^0.136 × GBPUSD^-0.119 × USDCAD^0.091 × USDSEK^0.042 × USDCHF^0.036
```

Les 6 paires forex nécessaires (EUR/USD, USD/JPY, GBP/USD, USD/CAD, USD/SEK, USD/CHF) sont un produit de base pour les deux fournisseurs — aucun risque de non-couverture ici, contrairement au ticker DXY lui-même. Entre les deux :
- **Twelve Data** : palier gratuit ~800 requêtes/jour, et surtout un endpoint de cotation **groupée multi-symboles en un seul appel** (`/quote` ou `/price` avec plusieurs symboles séparés par virgule) — les 6 paires tiennent dans **1 seul appel API**, donc 1 crédit consommé par calcul de DXY.
- **Alpha Vantage** : palier gratuit nettement plus serré (5 req/min, de l'ordre de quelques centaines/jour selon la source) et son endpoint forex (`FX_INTRADAY`/`CURRENCY_EXCHANGE_RATE`) est **un symbole par appel** — calculer le DXY demanderait 6 appels à chaque rafraîchissement, ce qui mange le quota beaucoup plus vite pour le même résultat.

**Twelve Data est donc le meilleur choix pour ce cas précis** (quota plus généreux + requête groupée qui réduit la consommation par calcul à 1/6e de celle d'Alpha Vantage) — recommandation : créer le compte gratuit chez Twelve Data plutôt que Alpha Vantage.

**Intégration** : nouveau `IndicatorType.DXY`, nouveau `WebProviderCode.TWELVE_DATA`, patron `AbstractExternalIndicator` — mais le `compute()` doit appliquer la formule ci-dessus après réception des 6 cotations, pas juste relayer une valeur brute (différence avec Fear & Greed, qui relaie une valeur déjà calculée par CoinStats). Fréquence de rafraîchissement à calibrer sur le quota gratuit (ex. 1 appel/heure largement suffisant pour un usage macro, très loin des limites).

**Point dur** : aucun bloquant technique — juste un nouveau provider à câbler bout en bout (client + credential + resolver) plus une formule à coder et tester (facile à valider : comparer le résultat au DXY affiché sur TradingView à un instant donné). Se combine naturellement avec §11 (GLI, postposé) qui a besoin du DXY comme composante.

## 4. S&P500 (+ Nasdaq, mentionné dans la section Cryptolyze)

**Besoin** : niveau/tendance des indices actions US, comme proxy de risk-on/risk-off pour la corrélation crypto-actions.

**Source de données** : mêmes fournisseurs que le DXY (Twelve Data / Alpha Vantage couvrent indices actions US). Peut être mutualisé avec §3 sur le même `WebProviderCode`/client si le même fournisseur est retenu — un seul client HTTP, deux `IndicatorType` différents (`SP500`, `NASDAQ`) qui appellent des tickers différents (`SPX`/`^GSPC`, `IXIC`/`^NDX` selon le fournisseur).

**Intégration** : identique à §3.

**Point d'attention** : les indices actions ne tradent pas 24/7 (contrairement à la crypto) — un indicateur `SP500`/`NASDAQ` doit exposer clairement s'il retourne la dernière clôture ou une valeur "stale" pendant les heures de fermeture, sinon une opinion `GLOBAL` risque d'interpréter une valeur figée du week-end comme un signal stable à tort.

## 5. Zones de rejets (rejection zones)

**Besoin** : détecter les niveaux de prix où le marché a déjà "rejeté" un mouvement (mèches longues, retour rapide après un test de niveau).

**Source de données** : aucune — c'est le seul indicateur (avec §6 et §12) qui se calcule entièrement à partir des données déjà en base (`MarketData` : open/high/low/close/volume via `Bucket`/`MarketDataset`), pas d'appel externe.

**Intégration** : nouveau `IndicatorType` interne (ex. `REJECTION_ZONE`), classe dans `service/tree/indicator/impl/` (comme `AdxIndicator`), pas dans `external/`. Logique candidate : détecter les bougies dont la mèche (high−close ou open−low selon le sens) dépasse un multiple du corps réel et/ou de l'ATR courant, sur une fenêtre glissante — proche dans l'esprit d'un pivot high/low mais avec un critère de "rejet" (mèche) plutôt qu'un simple extremum local.

**Point dur réel** : c'est un problème de **définition**, pas d'implémentation — "zone de rejet" n'a pas de formule standard universelle (contrairement à RSI/ADX). Il faut d'abord figer une règle précise (seuil de ratio mèche/corps, fenêtre de lookback, faut-il pondérer par le volume de la bougie) avant d'écrire le code, sinon l'indicateur produira des zones incohérentes d'un réglage à l'autre. C'est exactement la catégorie "structure de marché / support-résistance" déjà signalée comme la plus complexe à fiabiliser dans `etude-indicateurs-strategies-opinions.md` (tableau §2, dernière ligne).

## 6. Zone de liquidités

**Besoin** : niveaux de prix où se concentrent des ordres/liquidations (zones où le prix a statistiquement tendance à être "aimanté").

**Clarification terminologique importante** : « carnet d'ordres » (order book) désigne les **ordres à cours limité en attente** (bid/ask déjà posés sur l'exchange, visibles avant exécution) — ça n'a rien à voir avec les ordres "market" (qui s'exécutent immédiatement et ne stationnent jamais dans le carnet, donc n'y sont jamais visibles) ni avec l'effet de levier (le carnet d'ordres spot ne porte aucune notion de levier). Ce sont bien **deux indicateurs différents et complémentaires**, comme le pressent la remarque de l'utilisateur — ce document les traite maintenant séparément :

**6a. Carnet d'ordres ("Market")** — liquidité réellement placée, sans notion de levier. Disponible via l'API publique Binance `/api/v3/depth`, gratuite, sans authentification — pas encore intégré dans TradeIO5 (`BinanceMarketDataApiClient` ne couvre aujourd'hui que les klines). Intégration : nouvel endpoint sur un client Binance étendu (ou nouveau client dédié), pas de nouveau `WebProviderCode` nécessaire (`BINANCE` existe déjà). Limite intrinsèque : ne montre que la liquidité *actuellement affichée*, qui peut être fine/volatile sur les paires moins liquides — pas les zones où le prix est statistiquement "aimanté" par des positions à effet de levier, qui est l'autre moitié du besoin.

**6b. Carte de liquidations ("Leviers")** — recherche approfondie effectuée, la conclusion initiale (CoinGlass payant, seule option) était incomplète. Deux alternatives gratuites identifiées :
- **Coinalyze** : API gratuite, **sans inscription requise pour les fonctionnalités de base**, 40 appels/minute par clé. Couvre l'historique d'open interest, de funding rate, de **liquidations**, et de long/short ratio, agrégés sur ~25 exchanges. C'est la meilleure option gratuite trouvée pour cette section — et elle couvre *aussi* §9 (Open Interest) et §12 (funding rate) en un seul provider (voir §9 mis à jour).
- **Hyblock Capital** : propose également un plan "Free" avec heatmap de liquidation prédictive, mais son offre est structurée autour de plans payants (Professional/Advanced) pour l'essentiel des 100+ endpoints — moins évident que Coinalyze comme point d'entrée gratuit simple.

**Recommandation** : Coinalyze pour la version "leviers" (6b), endpoint `/api/v3/depth` de Binance pour la version "market" (6a) — les deux gratuits, aucun blocage budgétaire contrairement à ce que la première passe de cette étude indiquait.

**Intégration** : 6a étend `BinanceMarketDataApiClient` (ou un client dédié au carnet d'ordres) sans nouveau provider. 6b suit le patron `AbstractExternalIndicator` avec un nouveau `WebProviderCode.COINALYZE`, mutualisé avec §9/§12.

**Point dur** : plus vraiment un problème de sourcing (les deux sont gratuits) — le point d'attention devient la définition de la "zone" elle-même à partir des données brutes (regrouper des niveaux de prix proches en zones, pondérer par volume/taille) pour 6a, similaire en nature au problème de définition de §5 (zones de rejet) même si les données sources diffèrent.

## 7. Market Cap des Stablecoins (injection de liquidités)

**Besoin** : suivre la capitalisation totale des stablecoins comme proxy d'entrée/sortie de liquidités dans la crypto.

**Source de données** : DefiLlama expose un endpoint stablecoins dédié, **gratuit et sans clé API** pour l'accès de base (`stablecoins.llama.fi`) — confirmé, c'est la source la plus simple de toute cette liste à intégrer.

**Intégration** : nouveau `IndicatorType.STABLECOIN_MARKET_CAP`, nouveau `WebProviderCode` (ex. `DEFILLAMA`), patron `AbstractExternalIndicator` — mais comme l'accès de base ne demande pas de clé API, `ApiCredentialDTO` peut être construit avec `apiKey`/`secretKey` vides et seulement un `baseUrl`, ce qui est déjà toléré par le contrat actuel (`credential` reste un paramètre nommé mais son contenu n'est pas forcément utilisé par le client, cf. `getWebClient` qui ne lit que `credential.provider()`/`baseUrl()`).

**Point dur** : aucun bloquant. C'est le meilleur rapport effort/valeur de toute la liste — source fiable, gratuite, sans authentification, format JSON stable.

## 8. Calendrier macro (dates de discours, chiffres — emploi, FED, etc.)

**Besoin** : connaître à l'avance les dates d'événements macro à risque (NFP, décisions FOMC, CPI, discours de gouverneurs de banque centrale).

**Recherche approfondie effectuée** — la conclusion initiale ("pas de source gratuite complète") était trop pessimiste. Deux sources gratuites concrètes identifiées, de nature différente :
- **Finnhub** : API financière généraliste (déjà connue pour ses données actions/forex/crypto) qui documente officiellement un endpoint `economic-calendar` dédié, couverture globale (pas seulement US), palier gratuit à 60 appels/minute. C'est une **vraie API officielle avec JSON structuré** — pas du scraping — donc la source la plus solide/pérenne des deux.
- **ForexFactory** : le flux `https://nfs.faireconomy.media/ff_calendar_thisweek.json` (et variantes `.xml`/`.csv`/`.ics`) est un fichier JSON statique librement accessible, utilisé de longue date par la communauté trading algorithmique (indicateurs MT4/MT5) — pas une API "officielle" documentée par ForexFactory, mais un fichier stable, pas du HTML à parser. Seule limite connue : 2 téléchargements/5 minutes, largement suffisant pour un usage macro (rafraîchissement horaire ou moins). Complément utile à Finnhub pour la granularité NFP/CPI/discours spécifiquement suivie par les traders retail.

**Sur le scope** : confirmé — c'est un indicateur **`GLOBAL`** (aucune notion de symbole, l'info est la même pour tout le portefeuille), comme le pressent la remarque de l'utilisateur.

**Sur la factorisation avec §1 (ETF flow)** : finalement peu pertinente. Les deux sources retenues ici (Finnhub JSON via API officielle, ForexFactory JSON via fichier statique) ne demandent **aucun parsing HTML** — contrairement à Farside (§1), qui reste le seul scraping de tableau HTML de toute la liste. Le patron de désérialisation JSON classique (`WebClient` + DTO, identique à Fear & Greed) suffit pour le calendrier ; pas de parseur commun à construire entre §1 et §8.

**Intégration** : structurellement différent des autres indicateurs malgré tout — ce n'est pas une valeur numérique à faire rentrer dans `IndicatorResult` (`value`/`values`), c'est une **liste d'événements datés**. Reste vrai indépendamment de la source retenue : se rapproche plus d'un service "risk calendar" consommé par `DecisionEngine`/`Scenario` qu'un `Indicator` au sens strict (`compute()` mesure l'état du marché "maintenant", pas un événement futur). Le nouveau `WebProviderCode.FINNHUB` (et éventuellement `FOREXFACTORY`) suit quand même le patron `AbstractExternalIndicator` pour la partie appel réseau/`invalid()`, mais le DTO de sortie est une liste d'événements, pas un `IndicatorResult` classique — probablement un service dédié plutôt qu'un `IndicatorType` de plus.

**Point dur** : redevient surtout un point d'architecture (où vit une "fenêtre de risque événementiel" dans le pipeline `Strategy`/`Opinion`/`DecisionEngine` actuel, qui n'a pas cette notion aujourd'hui) plutôt qu'un problème de sourcing — les deux sources sont maintenant identifiées et gratuites.

## 9. Open Interest

**Besoin** : suivre l'open interest (positions ouvertes agrégées) pour distinguer un mouvement porté par du spot d'un mouvement porté par du levier.

**Source de données — deux options gratuites, à choisir selon l'angle voulu** :
- **Binance Futures natif** : endpoint public gratuit, sans clé API (`GET /fapi/v1/openInterest`), même esprit que l'endpoint klines déjà utilisé par `BinanceMarketDataApiClient` (`/api/v3/klines`, également public). Confirmé : aucune trace actuelle de client Futures dans le projet (recherche exhaustive "openInterest"/"futures" dans `src/main/java` : aucun résultat). Donne l'OI **sur Binance seulement**.
- **Coinalyze** (voir §6b) : même provider déjà retenu pour la carte de liquidations, gratuit, sans inscription pour les fonctionnalités de base, 40 appels/min, OI **agrégé sur ~25 exchanges** — image plus représentative du "marché" que Binance seul, et un seul provider à câbler pour couvrir OI + liquidations + funding rate (§12) d'un coup plutôt que jongler entre Binance natif et un tiers.

**Recommandation** : Coinalyze comme fournisseur principal pour OI/funding/liquidations (une seule intégration pour 3 besoins), avec le fallback natif Binance gardé en tête si une dépendance tierce de moins est préférée pour l'OI spécifiquement (le prix/les bougies viennent déjà de Binance).

**Intégration** : nouveau `IndicatorType.OPEN_INTEREST`. Suit le patron `AbstractExternalIndicator` avec `WebProviderCode.COINALYZE` (ou `BINANCE_FUTURES` si l'option native est retenue à la place). Contrairement à Fear & Greed, l'Open Interest est **par symbole** (BTC/USDT, ETH/USDT séparément), donc plus naturel comme indicateur `LOCAL` (avec `context.symbol()`) que `GLOBAL`.

**Point dur** : faible — dans les deux cas, source gratuite et officielle/documentée. Le seul vrai travail est l'intégration elle-même (nouveau client HTTP, nouveau `WebProviderCode`, et pour l'option Binance native, une URL de base différente du spot — `fapi.binance.com` vs `api.binance.com`).

## 10. Récupération macro depuis des canaux Telegram

**Besoin** : extraire de l'information macro (news, discours, chiffres) publiée sur des canaux Telegram spécifiques.

**Nature du problème** : ce n'est pas un "indicateur" au sens du patron `Indicator`/`IndicatorResult` — c'est un pipeline d'ingestion + extraction distinct :
1. **Lecture Telegram** : l'API Bot Telegram ne permet de lire que les messages envoyés à un bot ou dans un groupe où le bot est membre — elle ne permet **pas** de lire passivement des canaux publics tiers auxquels on n'est qu'abonné. Pour ça, il faut un client "utilisateur" (protocole MTProto, ex. bibliothèques type Telethon/GramJS) qui s'authentifie comme un compte Telegram réel, pas comme un bot — implique de gérer un numéro de téléphone/session, une contrainte opérationnelle différente de tout ce que le projet fait aujourd'hui (aucune dépendance Telegram actuelle dans le projet, à vérifier mais rien dans les clients existants n'indique ce protocole).
2. **Extraction** : une fois le texte brut récupéré, en extraire une information exploitable (ex. "chiffre d'emploi publié : X, attendu Y") demande soit des règles de parsing fragiles par canal (chaque canal a son propre format de message), soit un passage par LLM — ce qui rejoint directement l'infrastructure `DecisionAdvisor`/`OpenAIAdvisor` déjà présente dans le projet (`service/tree/opinion/advisor/`), potentiellement réutilisable pour résumer/structurer un message Telegram brut plutôt que construire un parseur dédié.

**Point dur** : le plus éloigné de l'architecture `Indicator` existante de toute la liste — nécessite une nouvelle dépendance technique (client Telegram MTProto), une nouvelle couche d'ingestion asynchrone (les messages arrivent en flux, pas à la demande comme un appel REST classique), et une étape d'extraction sémantique. À traiter comme un chantier à part, pas comme un `IndicatorType` de plus.

## 11. Flux de liquidité mondiale (GLI)

**Besoin, avec la capture jointe au document** : un indice composite influencé par la FED (QE/QT — M2 US, reverse repo, shadow banking), la Bank of China, la volatilité (MOVE index), et le dollar.

**Source de données** : le "GLI" tel que popularisé (vidéo YouTube citée dans le document) est un indice **propriétaire** de Michael Howell/CrossBorder Capital, construit par agrégation en z-scores sur ~80 économies (banques centrales + secteur privé + flux transfrontaliers) — il n'existe pas d'API publique qui reproduise cet indice exact. Ce que confirme d'ailleurs la note du document lui-même : « je ne trouve pas dans TradingView ». Ce n'est donc pas un indicateur "à sourcer", c'est un indicateur **à approximer** à partir de composantes publiques :
- Bilan de la Fed (`WALCL`) et Reverse Repo (`RRPONTSYD`) et M2 US (`M2SL`) : disponibles gratuitement via l'API FRED (Federal Reserve Bank of St. Louis), clé API gratuite après inscription.
- Bilan de la banque centrale chinoise : nettement moins accessible en self-service gratuit (pas identifié de série FRED directe équivalente fiable et à jour — à creuser séparément, potentiellement hors périmètre gratuit).
- MOVE index (volatilité obligataire, ICE BofA) : généralement disponible via les mêmes fournisseurs que DXY/S&P500 (Twelve Data/Alpha Vantage le couvrent selon leur catalogue d'indices, à vérifier ticker par ticker) plutôt que via FRED.
- Dollar : déjà couvert par §3 (DXY).

**Intégration** : le plus gros indicateur de la liste en termes de portée — probablement pas un seul `IndicatorType` mais **un indicateur composite** (`IndicatorType.GLOBAL_LIQUIDITY`) qui agrège en interne plusieurs sous-indicateurs déjà individuellement définis (`FRED_M2`, `FRED_REVERSE_REPO`, `FRED_FED_BALANCE_SHEET`, `MOVE_INDEX`, et `DXY` de §3), chacun étant lui-même un petit `Indicator` externe suivant le patron habituel, combinés par une formule à définir (pas de formule Howell disponible publiquement — un score composite simplifié maison serait la seule option réaliste, ex. z-score des variations des composantes FED pondérées).

**Point dur réel** : double. (1) Sourcing incomplet — la composante Chine manque, ce qui limite la fidélité à un vrai GLI dès le départ ; il faut décider si un "GLI simplifié US + volatilité + dollar" est acceptable comme proxy ou si le projet attend la couverture complète avant de livrer quoi que ce soit. (2) Pas de formule de référence publique à répliquer — contrairement à RSI/MACD/ADX qui ont une définition mathématique standard, la pondération des composantes GLI est à inventer, donc à valider empiriquement (backtest de corrélation avec le comportement du marché crypto) plutôt qu'à implémenter directement. C'est le seul indicateur de la liste où le travail de recherche quantitative pèse plus lourd que le travail d'intégration technique.

## 12. Qualifier un mouvement : achat spot ("market") vs cascade de liquidations à effet de levier (short squeeze)

**Besoin** : distinguer si un mouvement de prix est porté par de la conviction réelle (achats/ventes spot) ou par une cascade de liquidations à effet de levier.

**Confirmé nécessaire (retour utilisateur) : le funding rate doit être analysé comme troisième entrée**, aux côtés de l'Open Interest et du volume/OBV — c'est effectivement l'indicateur classique manquant pour ce diagnostic (un funding très positif et croissant pendant une hausse de prix est un signe classique de sur-effet-de-levier long, terrain fertile pour une cascade de liquidations/long squeeze inversé ; un funding proche de zéro pendant le même mouvement pointe vers du spot).

**Source de données** : aucune nouvelle par rapport à §9/§6b — c'est un indicateur **dérivé**, qui combine des signaux déjà couverts ailleurs dans ce document plutôt qu'une nouvelle donnée brute :
- **Open Interest** (§9) : une chute brutale d'OI pendant un mouvement de prix fort est la signature d'une cascade de liquidations ; un OI stable ou en hausse pendant le même mouvement suggère du spot/de la conviction.
- **Volume** : déjà disponible nativement dans `MarketData`/`Bucket`, et `OBV` déjà implémenté.
- **Funding rate des perpétuels** : disponible via le même provider que §9/§6b si Coinalyze est retenu (funding rate history déjà dans son catalogue d'endpoints), ou nativement via Binance Futures (`GET /fapi/v1/premiumIndex` pour le taux courant, `GET /fapi/v1/fundingRate` pour l'historique) si l'option Binance-natif est retenue à la place. Les deux sont gratuits ; le choix suit celui déjà fait pour §9 (cohérence : un seul provider pour OI+funding+liquidations plutôt que mélanger les deux).

**Intégration** : ce n'est pas un `Indicator` de plus mais une **Strategy** (au sens déjà défini dans le projet, `service/tree/strategy/impl/`) qui consomme plusieurs `IndicatorType` en entrée (Open Interest + Funding Rate + Volume/OBV) — exactement le même patron que `TrendConfirmationStrategy` (EMA + ADX + RSI) déjà présent, mais avec une combinaison différente. Logique candidate : mouvement de prix fort + delta d'OI fortement négatif sur la même fenêtre → score orienté "cascade de liquidations" (donc probablement peu durable / candidat à un retournement) ; mouvement de prix fort + OI stable/en hausse + funding proche de zéro + volume confirmé → score orienté "achat/vente réel" ; funding fortement positif + OI en forte hausse pendant une montée de prix → signal d'alerte "sur-effet-de-levier long", risque de cascade à venir plutôt que déjà passée.

**Point dur** : dépend entièrement de §9 (Open Interest) et de son extension funding rate — ne peut pas être construit avant que ces deux `IndicatorType` existent. Pas de nouvelle difficulté de sourcing propre (le funding rate est couvert par le même provider que §9), seulement une nouvelle Strategy à écrire une fois les indicateurs sous-jacents disponibles — et un nouveau `IndicatorType.FUNDING_RATE` à ajouter en même temps que `OPEN_INTEREST`, vu qu'ils partagent le même provider/client.

## 13. Section "Cryptolyze" du document — recoupement, pas nouveaux besoins

La liste "Cryptolyze" (DXY, FearGreed, Liquidity, Market Volume, Nasdaq/S&P500) ne décrit pas de nouveaux indicateurs : c'est un recoupement de §2, §3, §4, §6 — plus "Market Volume" qui, faute de précision dans le document, est probablement soit le volume agrégé du marché crypto global (à distinguer du volume par-symbole déjà couvert par `OBV`), soit un simple rappel du volume déjà présent nativement dans `MarketData`. À clarifier avec l'utilisateur avant de créer un `IndicatorType` dédié — il y a un risque réel de dupliquer un signal déjà couvert (`OBV`) sous un autre nom.

**Confirmé par l'utilisateur** : c'était une prise de notes lors d'un visionnage d'une vidéo Cryptolyze (chaîne YouTube française finance/crypto/macro tenue par "Rifter") — ces indicateurs y sont utilisés comme base d'analyse. Pas une source de données à intégrer en tant que telle, juste le contexte d'où vient la liste. Aucune action supplémentaire nécessaire ici au-delà de ce qui est déjà couvert par §2/§3/§4/§6.

## 14. Roadmap d'implémentation par lots

Mise à jour après retour utilisateur : §10 (Telegram) et §11 (GLI) sont **reportés** (postpone explicite) — ni bloquants ni prérequis pour le reste, retirés des lots ci-dessous. Chaque lot suppose le précédent terminé, mais l'ordre à l'intérieur d'un même lot est libre (pas de dépendance croisée entre eux).

### Lot 1 — Fondations : gratuit, sans blocage, débloque le lot suivant

Aucun de ces quatre chantiers ne dépend d'un autre en dehors du lot, et à eux seuls ils couvrent déjà 5 des 12 points du document d'origine (§2, §6b, §7, §9, §12-fondation).

1. **§2 — Fear & Greed, règle d'évolution.** Zéro nouvelle source : la donnée (`now`/`yesterday`/`lastWeek`) existe déjà. Juste une règle à écrire dans `GlobalMarketOpinion`/`MarketOpinionHelper`. Le plus rapide de tout le programme.
2. **§7 — Market cap stablecoins (DefiLlama).** Gratuit, sans clé API, patron `AbstractExternalIndicator` standard. Meilleur rapport effort/valeur du document.
3. **§9 + §12-fondation + §6b — Open Interest, Funding Rate, Liquidations (Coinalyze).** Un seul nouveau provider (`WebProviderCode.COINALYZE`), une seule API gratuite (40 appels/min, sans inscription pour les endpoints de base), et il alimente trois `IndicatorType` d'un coup (`OPEN_INTEREST`, `FUNDING_RATE`, et la donnée liquidations pour §6b) — le meilleur rendement d'intégration de toute la liste : un seul client à écrire pour couvrir trois besoins.
4. **§6a — Carnet d'ordres Binance (`/api/v3/depth`).** Gratuit, sans clé, étend un client déjà existant (`BinanceMarketDataApiClient` ou un client dédié). Indépendant du reste du lot.

**Pourquoi ce lot en premier** : c'est le seul qui débloque directement un item du lot suivant (§12, la Strategy de qualification de mouvement, a besoin d'OI + funding + OBV, tous disponibles à la fin de ce lot) — le construire en premier n'est pas qu'une question de facilité, c'est aussi le chemin le plus court vers un indicateur composite exploitable.

### Lot 2 — Nouveaux providers TradFi + première Strategy dérivée

Complexité un cran au-dessus : nouveaux comptes/clés API à créer côté fournisseurs non-crypto, et une première Strategy (pas juste un Indicator) qui recombine le lot précédent.

5. **§3 — DXY (Twelve Data, calcul synthétique via 6 paires forex).** Twelve Data recommandé (voir §3 mis à jour) : compte gratuit à créer, formule DXY à coder et valider une fois contre une source de référence (TradingView).
6. **§4 — S&P500 / Nasdaq (même provider Twelve Data).** Mutualisable avec §3 sur le même client HTTP — à faire dans la foulée du point 5, pas une intégration séparée de zéro.
7. **§8 — Calendrier macro (Finnhub + ForexFactory JSON).** Scope `GLOBAL` confirmé. Finnhub en source principale (API officielle documentée), ForexFactory JSON en complément pour la granularité NFP/CPI. Nécessite en plus une petite décision d'architecture (où vit une "fenêtre de risque événementiel" dans le pipeline actuel) avant de coder — pas juste du câblage de provider comme §3/§4/§7 du Lot 1.
8. **§12 — Strategy de qualification de mouvement (OI + funding + OBV).** Débloqué automatiquement par le Lot 1 (point 3) : aucune nouvelle source de données, juste une nouvelle classe `Strategy` (patron `TrendConfirmationStrategy`) à écrire et calibrer (seuils de delta d'OI, de funding, de volume).

### Lot 3 — Complexes : scraping fragile, définition à inventer, choix de fond à trancher

Chantiers qui demandent soit une source non officielle (donc fragile par nature), soit un vrai travail de définition/R&D avant d'écrire la moindre ligne de code d'indicateur.

9. **§1 — Flow ETF (scraping Farside).** Techniquement pas très difficile (tableau HTML simple), mais c'est une source non officielle qui peut casser sans préavis — traiter dès le départ comme best-effort (`invalid()` systématique en cas d'écart de structure), pas comme un indicateur sur lequel une décision automatique peut s'appuyer sans supervision.
10. **§5 — Zones de rejet.** Pas de blocage de source (calcul interne sur `MarketData` déjà en base), mais **la définition doit être figée et validée avant tout code** — exactement le risque que l'utilisateur a déjà identifié en observant des indicateurs TradingView qui "font n'importe quoi" sur ce type de zone. Prévoir une phase de calibration empirique (visuelle d'abord, puis backtestée) avant de considérer l'indicateur fiable.

### Reporté (hors roadmap active)

- **§10 — Telegram** : chantier à part entière (client MTProto, ingestion asynchrone, extraction sémantique), sans lien technique avec le reste. Postposé sur demande explicite.
- **§11 — GLI** : bloqué par une composante manquante (bilan banque centrale chinoise) et l'absence de formule de référence publique à répliquer — nécessite un travail de recherche quantitative avant tout code. Postposé sur demande explicite. À reprendre une fois §3 (DXY) fait, puisqu'il en est une composante directe.
