# Prompt d'implémentation — Fallback multi-provider, étapes 0 à 3

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le contexte de la conversation de conception. Il couvre les **étapes 0 à 3** de la roadmap définie dans `docs/etude-fallback-multi-provider-marketdata.md` §3 — la fondation "qualification erreur vs vide" du mécanisme de fallback multi-provider (Binance/Kraken/OKX). Les étapes suivantes (table `asset_provider`, boucle de fallback, migration des call sites) sont hors scope de ce prompt, ne pas les anticiper.

Avant de commencer, lire dans l'ordre :
1. `docs/etude-fallback-multi-provider-marketdata.md` — §1 (diagnostic complet) et §2 (décisions), pour le contexte.
2. `service/connector/apiclient/marketdata/MarketDataApiClient.java` — l'interface actuelle (2 méthodes, `getSource()` + `getCandles(...)`).
3. Les 3 implémentations actuelles : `BinanceMarketDataApiClient.java`, `KrakenMarketDataApiClient.java`, `OkxMarketDataApiClient.java`.
4. Les 3 tests existants correspondants : `BinanceMarketDataApiClientTest.java`, `KrakenMarketDataApiClientTest.java`, `OkxMarketDataApiClientTest.java` — ils contiennent déjà des payloads d'erreur exploitables (voir étapes 1/2 ci-dessous).
5. `CachingMarketDataApiClient.java` — **ne pas modifier cette classe dans ce lot.** Elle décore `delegate.getCandles(...)` sans jamais catcher les exceptions du délégué (seul `DataIntegrityViolationException` lors de l'écriture DB est catché) — elle est donc déjà "exception-transparente" et laissera passer les nouvelles exceptions typées sans changement.

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher à `DcaCalculatorService`, `TreeAnalysisFacade`, `MarketDatasetEngine`, `MarketDataProviderRegistry` ni à quoi que ce soit lié à `Asset`/`asset_provider` — c'est le scope des étapes 4+ de la roadmap, traité dans une session ultérieure.

**Effet de bord attendu et volontaire** : aujourd'hui, `BinanceMarketDataApiClient`/`KrakenMarketDataApiClient`/`OkxMarketDataApiClient` avalent toute erreur réseau/provider et retournent `List.of()`. Après ce lot, ils lèveront une exception typée à la place. `DcaCalculatorService` et `TreeAnalysisFacade` ne sont pas mis à jour pour la catcher dans ce lot (ce sera fait aux étapes ultérieures) — c'est acceptable et attendu : le comportement runtime de ces deux services n'est pas dans le scope de vérification ici. Se contenter de vérifier qu'ils compilent toujours (une `RuntimeException` non catchée ne casse pas la compilation) et que leurs tests existants passent toujours (ils mockent `MarketDataApiClient` et ne testent a priori pas le chemin d'erreur réel des 3 clients).

---

## Étape 0 — Hiérarchie d'exceptions

**Nouveau package** : `fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception`.

**À créer** :

1. `MarketDataProviderException` — classe abstraite, `extends RuntimeException`. Porte `MarketDataSource source` et `String symbol` (getters), en plus du message/cause standard. Constructeur protégé `(MarketDataSource source, String symbol, String message, Throwable cause)`.
2. `SymbolNotFoundException extends MarketDataProviderException` — cas **permanent** : le provider indique explicitement que le symbole/la paire n'existe pas chez lui (ex: Binance code -1121, Kraken `"EQuery:Unknown asset pair"`, OKX code `"51001"`). Constructeur `(MarketDataSource source, String symbol, String message)`.
3. `ProviderUnavailableException extends MarketDataProviderException` — cas **transitoire** : erreur réseau, timeout, rate limit, 5xx, ou toute erreur provider qui n'est pas un symbole invalide. Constructeur `(MarketDataSource source, String symbol, String message, Throwable cause)`.

**Mettre à jour le Javadoc de `MarketDataApiClient.getCandles(...)`** pour documenter le nouveau contrat explicitement : la méthode doit lever une sous-classe de `MarketDataProviderException` si le provider signale une erreur explicite ; elle ne doit renvoyer une liste vide que lorsque l'appel a techniquement réussi et que le provider n'a legitimement aucune bougie à renvoyer sur la période demandée (ex: période antérieure au listing de l'actif). Ne pas ajouter `throws` sur la signature (exceptions unchecked, comme aujourd'hui) — le contrat est documentaire + vérifié par les tests de l'étape 3, pas par le compilateur.

**Tests attendus** : aucun test dédié requis pour ces classes elles-mêmes (simples porteurs de données), elles seront exercées par les tests des étapes 1/2/3.

---

## Étape 1 — Mapping erreur typée : Binance et Kraken

### Binance (`BinanceMarketDataApiClient`)

Contrairement à Kraken/OKX, l'erreur Binance survient **avant** tout mapping JSON — elle est levée par le SDK (`client.createMarket().klines(params)`) sous forme de `BinanceClientException`/`BinanceConnectorException`, pas dans `mapKlinesResponse`. Pour rester testable sans appel réseau réel (comme le fait déjà `mapKlinesResponse`), extraire la logique de traduction dans une méthode statique dédiée, ex:

```java
static MarketDataProviderException mapError(String symbol, Exception e) {
    // à écrire : distinguer symbole invalide (permanent) de tout le reste (transitoire)
}
```

**Avant d'écrire le mapping**, vérifier dans les sources/Javadoc réels de la dépendance `binance-connector-java` (voir `pom.xml` pour la version exacte) quelles méthodes `BinanceClientException` expose réellement pour identifier le code d'erreur Binance (`errorCode`/`errorMessage` a priori, noms exacts à confirmer — ne pas deviner l'API sans vérifier). Le code Binance `-1121` correspond à *"Invalid symbol"* (référence : documentation publique Binance Spot API, error codes) → `SymbolNotFoundException`. Tout autre `BinanceClientException`, ainsi que `BinanceConnectorException` (erreurs réseau/connexion) → `ProviderUnavailableException`. Toute autre `Exception` inattendue → `ProviderUnavailableException` également (ne rien laisser fuiter en RuntimeException non typée).

Modifier `getCandles(...)` : remplacer le `catch` actuel (lignes 70-74, qui logue en `warn` et retourne `List.of()`) par un appel à `mapError(...)` puis `throw`. Garder un `logger.warn(...)` juste avant le `throw`, pour ne pas perdre la visibilité opérationnelle actuelle.

**Tests attendus** (`BinanceMarketDataApiClientTest`) : construire des instances de `BinanceClientException`/`BinanceConnectorException` représentatives (symbole invalide vs erreur réseau — vérifier les constructeurs publics disponibles sur ces classes) et vérifier que `mapError(...)` retourne le bon type. Garder les tests existants (`mapKlinesResponse_mapsBinancePositionalArrayToMarketData`, `nativeInterval_*`) inchangés.

### Kraken (`KrakenMarketDataApiClient`)

`mapOhlcResponse` (lignes 94-142) détecte déjà une erreur provider via le champ `error` du JSON et lève `IllegalStateException` (ligne 98-100) — remplacer cette levée par les nouvelles exceptions typées. Le test existant `KrakenMarketDataApiClientTest.mapOhlcResponse_throwsOnKrakenError` utilise déjà le payload `{"error": ["EQuery:Unknown asset pair"], "result": {}}` : c'est exactement le cas *symbole invalide* (le préfixe `EQuery:` sur une erreur de paire signale une erreur de requête permanente côté Kraken) → `SymbolNotFoundException`. Tout autre préfixe (`EAPI:`, `EService:`, `EGeneral:`, etc.) → `ProviderUnavailableException`.

`mapOhlcResponse` a la signature `static List<MarketData> mapOhlcResponse(String body, String symbol, TimeFrame timeFrame) throws Exception` — elle a besoin de `MarketDataSource` pour construire l'exception ; ajouter le paramètre ou utiliser la constante `MarketDataSource.KRAKEN` directement (cohérent avec `getSource()` de cette classe qui retourne toujours `KRAKEN`).

Dans `getCandles(...)`, le `catch (Exception e)` actuel (ligne 84-86) doit : si `e` est déjà une `MarketDataProviderException` (levée par `mapOhlcResponse`), la relancer telle quelle ; sinon (erreur réseau WebClient, timeout, JSON malformé, etc.), l'envelopper dans une `ProviderUnavailableException`. Garder le `logger.warn(...)` avant le `throw`.

**Tests attendus** (`KrakenMarketDataApiClientTest`) : mettre à jour `mapOhlcResponse_throwsOnKrakenError` pour asserter `SymbolNotFoundException` au lieu d'`IllegalStateException` (même payload `ERROR_RESPONSE`). Ajouter un cas avec un préfixe d'erreur différent (ex: `{"error": ["EService:Unavailable"], "result": {}}`) asserté en `ProviderUnavailableException`. Garder les autres tests existants inchangés.

---

## Étape 2 — OKX : mapping erreur typée + routing `history-candles`

**Contexte** (cf. `docs/etude-fallback-multi-provider-marketdata.md` §1) : OKX est le seul des 3 providers dont la limite d'historique est un choix d'implémentation réparable, pas une contrainte d'exchange. `/api/v5/market/candles` (utilisé aujourd'hui) ne couvre qu'une fenêtre récente ; OKX expose un second endpoint, `GET /api/v5/market/history-candles`, avec curseurs `after`/`before`, jamais branché dans le projet. Cette étape traite les deux sujets ensemble puisqu'ils touchent la même classe.

### 2a. Mapping erreur typée

`mapCandlesResponse` (lignes 82-116) détecte déjà l'erreur via le champ `code` et lève `IllegalStateException` (ligne 86-88). Le test existant `OkxMarketDataApiClientTest.mapCandlesResponse_throwsOnOkxError` utilise déjà le payload `{"code": "51001", "msg": "Instrument ID does not exist", ...}` : c'est le cas *symbole invalide* → `SymbolNotFoundException`. Tout autre code non-`"0"` → `ProviderUnavailableException` (ex: rate limit `50011`, erreurs serveur `5xxxx`).

Même remarque que Kraken pour la signature (besoin de `MarketDataSource`/le contexte pour construire l'exception) et pour `getCandles(...)` (relancer telle quelle si déjà typée, sinon envelopper en `ProviderUnavailableException`).

**Tests attendus** : mettre à jour `mapCandlesResponse_throwsOnOkxError` pour asserter `SymbolNotFoundException` (même payload `ERROR_RESPONSE`, déjà code `51001`). Ajouter un cas avec un autre code (ex: `"50011"`) asserté en `ProviderUnavailableException`.

### 2b. Routing `history-candles`

**Avant d'écrire le code**, vérifier empiriquement (appel réel à `GET https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1H&limit=100` sans paramètre `after`/`before`) quelle profondeur réelle est accessible via cet endpoint seul — l'étude notait ce point comme non chiffré. Documenter ce qui est observé en commentaire dans le code (même succinct), ça manque aujourd'hui dans `etude-tick-retrieval.md`.

**Stratégie de routing recommandée** — adaptative plutôt que basée sur un seuil de jours codé en dur (le seuil exact non documenté officiellement par OKX peut changer) :

1. Appeler `/market/candles` avec les paramètres actuels (`instId`, `bar`, `limit`, `after`=`until`, `before`=`since` si fournis — attention, la convention `after`/`before` d'OKX est déjà utilisée à l'envers du sens intuitif dans le code actuel, lignes 57-61 : la relire attentivement avant de toucher au routing).
2. Si un `since` est fourni et que la bougie la plus ancienne reçue via `/market/candles` est encore postérieure à `since` (il manque de l'historique), paginer vers le passé via `/market/history-candles` (mêmes query params `instId`/`bar`/`limit`, curseur `after` = timestamp de la bougie la plus ancienne déjà obtenue), en répétant jusqu'à couvrir `since` ou jusqu'à ce qu'un appel `history-candles` renvoie une liste vide (fin réelle de l'historique disponible pour ce symbole — cas légitime, pas une erreur).
3. Fusionner les résultats des deux endpoints (tri chronologique + dédoublonnage, même pattern que le reste de la classe), puis appliquer `limit` comme aujourd'hui (tronquer en gardant les plus récentes si dépassement).
4. Si `since` est `null` (pas de borne basse demandée), ne pas paginer vers `history-candles` — comportement actuel inchangé (fenêtre récente uniquement, c'est le cas d'usage "prix courant"/"dernières bougies" qui n'a pas besoin de profondeur).
5. `history-candles` doit être soumis au même mapping d'erreur que `/market/candles` (2a) — factoriser `mapCandlesResponse` si le format de réponse est identique entre les deux endpoints (à vérifier lors du test empirique du point précédent), sinon dupliquer proprement avec un commentaire expliquant la différence.

**Tests attendus** : test du scénario "gap comblé via history-candles" — simuler (mock du `WebClient`, ou extraction de la logique de fusion dans une méthode statique testable indépendamment des appels réseau, à l'image de `mapCandlesResponse`) un premier appel `/market/candles` ne couvrant pas tout `since→until`, vérifier qu'un second appel est déclenché vers `/market/history-candles` et que le résultat fusionné couvre bien la période demandée. Test du cas "history-candles renvoie vide" (fin d'historique atteinte) : pas d'exception, résultat partiel retourné tel quel. Test du cas `since == null` : un seul appel à `/market/candles`, jamais de second appel.

---

## Étape 3 — Test de contrat abstrait

**Objectif** : garantir que tout `MarketDataApiClient` (existant ou futur) respecte le contrat documenté à l'étape 0 — symbole invalide → `SymbolNotFoundException`, erreur transitoire → `ProviderUnavailableException`, réponse vide légitime → liste vide sans exception.

**Fichier** : `src/test/java/fr/ses10doigts/tradeIO5/service/connector/apiclient/marketdata/AbstractMarketDataApiClientContractTest.java`.

Étant donné l'asymétrie constatée aux étapes 1/2 (Binance mappe depuis une exception SDK via `mapError(...)`, Kraken/OKX mappent depuis un payload JSON via leurs `mapXxxResponse(...)` respectifs, et OKX a en plus la logique de routing), ne pas chercher à forcer un unique point d'entrée générique par réflexion — définir plutôt une classe abstraite avec des méthodes de test `@Test` concrètes qui appellent des **méthodes hook abstraites** que chaque sous-classe de test implémente avec son propre mécanisme (payload JSON, exception SDK construite à la main, etc.) :

```java
abstract class AbstractMarketDataApiClientContractTest {

    protected abstract MarketDataSource expectedSource();

    /** Doit retourner l'exception levée pour un cas "symbole invalide" représentatif du provider. */
    protected abstract MarketDataProviderException triggerSymbolNotFound();

    /** Doit retourner l'exception levée pour un cas "erreur transitoire" représentatif du provider. */
    protected abstract MarketDataProviderException triggerProviderUnavailable();

    @Test
    void symbolNotFound_isTypedCorrectly() {
        MarketDataProviderException ex = triggerSymbolNotFound();
        assertInstanceOf(SymbolNotFoundException.class, ex);
        assertEquals(expectedSource(), ex.getSource());
    }

    @Test
    void providerUnavailable_isTypedCorrectly() {
        MarketDataProviderException ex = triggerProviderUnavailable();
        assertInstanceOf(ProviderUnavailableException.class, ex);
        assertEquals(expectedSource(), ex.getSource());
    }
}
```

(Squelette indicatif — adapter la forme exacte selon ce qui compile proprement avec les méthodes statiques réellement écrites aux étapes 1/2.)

**Chaque test existant devient une sous-classe** : `BinanceMarketDataApiClientTest`, `KrakenMarketDataApiClientTest`, `OkxMarketDataApiClientTest` étendent `AbstractMarketDataApiClientContractTest` et implémentent les deux méthodes hook en réutilisant les payloads/fixtures déjà présents dans chacun (`ERROR_RESPONSE` pour Kraken/OKX, l'exception SDK construite à la main pour Binance). Les tests spécifiques déjà présents dans chaque classe (mapping nominal, `nativeInterval`/`nativeBar`, etc.) restent en plus, inchangés.

---

## À la fin : lancer les tests via la Gateway

Une fois l'implémentation et les tests écrits, compiler et exécuter la suite de tests complète du projet via l'opération CI/CD `test:tradeio-5` du gateway SSH (`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation` ou équivalent — lister les opérations disponibles avec `listOperations` si besoin de confirmer le nom exact). Ne pas lancer `mvn` directement dans un sandbox local : le projet ne compile/teste que via cette gateway (pas de Maven/réseau disponible en sandbox).

Rapporter : le résultat global (succès/échec), le nombre de tests exécutés, et le détail de tout test en échec (classe + message). Si un test préexistant échoue à cause du changement de comportement (liste vide → exception), le corriger dans le cadre de ce lot — sauf s'il s'agit d'un test de `DcaCalculatorService`/`TreeAnalysisFacade` qui dépendait de l'ancien comportement silencieux, auquel cas le signaler sans le corriger (hors scope, cf. "Effet de bord attendu" en tête de ce prompt) et le documenter clairement dans le rapport final.
