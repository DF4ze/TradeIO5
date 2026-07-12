# Prompt d'implémentation — Veille média YouTube multi-chaînes (feature complète)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le contexte des conversations de conception précédentes. Il couvre la **totalité** de ce qui reste à construire pour la feature de veille média (`docs/etude-veille-media-youtube.md`), dans l'ordre de dépendance défini par `docs/prompt-implementation-veille-media-roadmap.md`. Les niveaux LLM et la traçabilité coût (étapes #0 et #1 de la roadmap) sont **déjà implémentés** — vérifiés dans le code (`LlmTier`, `OpenAIProperties.ModelTiers`, `OpenAIService.ask(userInput, tier, callSite)`, `LlmCallLogEntity`) — ne pas les reconstruire, seulement les consommer.

Traiter les lots **dans l'ordre ci-dessous**, chacun compilé et testé avant de passer au suivant — un lot suppose que les classes du précédent existent déjà.

## Avant de commencer : lire dans l'ordre

1. `docs/etude-veille-media-youtube.md` — intégralement. Contient le verdict d'architecture, le modèle de données, la décroissance des claims, tous les paramètres déjà tranchés (2 min de troncature passe 1, demi-vies 3j/3sem/3mois, etc.). Ce prompt-ci ne répète pas les "pourquoi", seulement les "quoi/comment".
2. `service/connector/OpenAIService.java` — `ask(String userInput, LlmTier tier, String callSite)`. **Point d'attention important** : cette méthode désérialise toujours la réponse en `LlmAdvice` (DTO spécifique BUY/SELL/HOLD, cf. `model/dto/tree/opinion/LlmAdvice.java`) — inutilisable tel quel pour la classification et l'extraction de claims, qui ont des formats JSON différents. Voir Lot 2, item 0, qui traite ce point en premier.
3. `model/enumerate/LlmTier.java`, `configuration/properties/OpenAIProperties.java` — système de niveaux déjà en place.
4. `service/tree/opinion/advisor/OpenAIAdvisor.java` et `AbstractAdvisor.java` — noter que le prompt "réponds strictement en JSON avec ce schéma" est construit **par l'appelant** (`expectedOutputBlock()`), pas injecté par `OpenAIService`. Chaque nouveau site d'appel (passe 1, passe 2) doit faire pareil : embarquer son propre schéma JSON attendu dans le texte du prompt. Noter aussi que `AbstractAdvisor` (cache/timeout/fallback) est bâti autour d'`OpinionContext` — ne pas essayer de le réutiliser pour la veille média, qui n'a pas ce contexte ; gérer timeout/erreur directement dans le service dédié (Lot 2).
5. `service/tree/opinion/impl/ExternalMarketOpinion.java`, `service/tree/opinion/MarketOpinion.java`, `service/tree/opinion/MarketOpinionRegistry.java` — patron de l'opinion `EXTERNAL` à suivre pour `MediaMarketOpinion` (Lot 3). Noter que `MarketOpinionRegistry` reçoit `List<MarketOpinion>` par injection Spring : un `@Component implements MarketOpinion` est automatiquement enregistré, aucun câblage manuel requis.
6. `model/dto/tree/opinion/OpinionSignal.java`, `model/enumerate/tree/opinion/OpinionScope.java`, `model/enumerate/tree/SignalType.java` — réutiliser `SignalType` (`BULLISH`/`BEARISH`/`NEUTRAL`) pour le sentiment des claims plutôt que d'inventer un nouvel enum.
7. `service/tree/macro/forexfactory/ForexFactoryCalendarClient.java` — patron de client HTTP public sans authentification (`extends AbstractExternalIndicator`, cache mémoire, dégradation gracieuse sur erreur). C'est le patron le plus proche pour les clients RSS/transcript YouTube (eux non plus n'ont pas besoin de clé API), pas `CoinalyzeClient`/`TwelveDataQuoteClient` qui ont une vraie clé.
8. `model/enumerate/WebProviderCode.java`, `configuration/initializer/WebProviderInitializer.java`, `configuration/initializer/ApiCredentialInitializer.java` — chercher spécifiquement le traitement de `DEFILLAMA`/`FOREXFACTORY`/`FARSIDE` : ce sont les 3 providers déjà enregistrés sans vraie clé API (`apiKey` vide, seul `baseUrl` compte). YouTube suit exactement ce même principe.
9. `service/tree/indicator/external/AbstractExternalIndicator.java` — `getWebClient(credential)`. Point technique : un `WebClient` construit avec un `baseUrl` peut quand même appeler une URI absolue différente via `.uri(URI.create(urlAbsolue))` — nécessaire pour le client transcript (voir Lot 1a, item C).
10. `model/entity/tree/scenario/ScenarioEventEntity.java` et `model/entity/llm/LlmCallLogEntity.java` — patron d'entité JPA simple à suivre pour les nouvelles entités du Lot 1a.
11. `tools/media_watch/probe_transcript.py` — **référence de comportement attendu**, pas du code à traduire littéralement. Donne le mécanisme validé (RSS + `youtube-transcript-api`) et un échantillon réel (FR, Cryptolyze) auquel comparer la sortie du client Java une fois écrit.

---

## Lot 1a — Entités + clients RSS/transcript

### Entités (patron `ScenarioEventEntity`/`LlmCallLogEntity`, package `model/entity/media/`)

- **`ContentSourceEntity`** : `id`, `platform` (`@Enumerated(STRING)`, enum `ContentPlatform { YOUTUBE }` — un seul membre aujourd'hui mais un enum, pas une String, pour that le jour où un 2ᵉ type de plateforme arrive ce soit un `switch` à compléter et non une chaîne libre à comparer), `channelId` (String), `displayName` (String), `credibilityWeight` (double), `active` (boolean). Contrainte unique sur `channelId`.
- **`VideoContentEntity`** : `id`, `source` (`@ManyToOne` vers `ContentSourceEntity`), `videoId` (String), `title` (String), `publishedAt` (`Instant`), `transcript` (`@Column(columnDefinition="TEXT")`), `status` (`@Enumerated(STRING)`, enum `VideoContentStatus { PENDING, PROCESSED, IRRELEVANT, ERROR }`), `errorReason` (String, nullable — utile pour le Lot 4, autant l'ajouter maintenant plutôt que migrer le schéma plus tard). Contrainte unique sur `(source_id, videoId)`.
- **`MediaClaimEntity`** : `id`, `videoContent` (`@ManyToOne` vers `VideoContentEntity`), `symbol` (String), `sentiment` (`@Enumerated(STRING)`, type `SignalType` — réutiliser l'enum existant, pas un nouveau), `horizon` (`@Enumerated(STRING)`, nouvel enum `ClaimHorizon { COURT_TERME, MOYEN_TERME, LONG_TERME }`), `confidence` (double), `excerpt` (`@Column(columnDefinition="TEXT")`).

Repositories (`JpaRepository`) :
- `ContentSourceRepository` : `findByActiveTrue()`.
- `VideoContentRepository` : `existsBySourceAndVideoId(ContentSourceEntity source, String videoId)` (idempotence du job d'ingestion, Lot 1b), `findByStatus(VideoContentStatus status)` (consommation Lot 2).
- `MediaClaimRepository` : une méthode pour récupérer, pour un symbole donné, les claims dont `videoContent.publishedAt` est postérieur à une borne de cutoff (ex. `findBySymbolAndVideoContent_PublishedAtAfter(String symbol, Instant cutoff)`) — le cutoff exact est une borne large de requête (cf. `etude-veille-media-youtube.md` §5, "borne de requête" ≠ mécanisme de fraîcheur), la décroissance elle-même se calcule en mémoire côté `MediaMarketOpinion` (Lot 3), pas en SQL.

### Enregistrement provider (suivre le patron DEFILLAMA/FOREXFACTORY/FARSIDE)

1. `WebProviderCode.YOUTUBE` — nouvelle valeur.
2. `WebProviderInitializer` : nouvelle entrée `WebProvider.builder().code(WebProviderCode.YOUTUBE).name("YouTube").apiBaseUrl("https://www.youtube.com").enabled(true)...`.
3. `ApiCredentialInitializer` : nouvelle entrée pour le user `"System"`, `apiKey`/`secretKey` vides — même commentaire que DEFILLAMA/FOREXFACTORY/FARSIDE (endpoint public, seul le `baseUrl` de la credential résolue compte).

### Client A — `YoutubeRssClient` (`extends AbstractExternalIndicator`, package `service/tree/media/youtube/`)

`fetchLatestVideos(ApiCredentialDTO credential, String channelId) -> List<YoutubeVideoRef>` (record `YoutubeVideoRef(String videoId, String title, Instant publishedAt)`).

- `GET /feeds/videos.xml?channel_id={channelId}` via `getWebClient(credential)`.
- Réponse = flux Atom XML (pas JSON) : parser avec `javax.xml.parsers.DocumentBuilder` (déjà dans le JDK, pas de nouvelle dépendance à ajouter) plutôt que d'introduire `jackson-dataformat-xml` ou une lib RSS pour un besoin aussi simple — extraire pour chaque `<entry>` : `yt:videoId`, `title`, `published`.
- Mêmes garde-fous que `ForexFactoryCalendarClient` (`onStatus` 4xx/5xx, `timeout(20s)`, retour `List.of()` propre sur erreur, jamais d'exception qui remonte).

### Client B — `YoutubeTranscriptClient` (`extends AbstractExternalIndicator`, même package)

`fetchTranscript(ApiCredentialDTO credential, String videoId) -> Optional<List<TranscriptSegment>>` (record `TranscriptSegment(String text, double startSeconds, double durationSeconds)`, `Optional.empty()` si aucun transcript disponible).

**Mécanisme (celui que `youtube-transcript-api` utilise en interne, à répliquer — pas de lib Java équivalente mature disponible)** :
1. `GET /watch?v={videoId}` via `getWebClient(credential)` → corps HTML brut (`String`).
2. Dans ce HTML, chercher le bloc `var ytInitialPlayerResponse = {...};` (ou `ytInitialPlayerResponse =` selon les variations observées) et en extraire le JSON qui suit jusqu'à la fin de l'objet (attention à ne pas couper sur un `;` interne à une chaîne — utiliser un compteur d'accolades, pas juste `indexOf(";")`).
3. Parser ce JSON (Jackson) jusqu'à `captions.playerCaptionsTracklistRenderer.captionTracks[]` — chaque élément a `languageCode` et `baseUrl`. Choisir la piste `languageCode == "fr"` en priorité, sinon la première disponible (cf. `etude-veille-media-youtube.md` §5 sur la langue). Si `captions` absent → `Optional.empty()` (vidéo sans sous-titres).
4. `baseUrl` est une **URL absolue déjà complète** (domaine différent de `youtube.com`, généralement `googlevideo.com`) : l'appeler avec `getWebClient(credential).get().uri(URI.create(baseUrl))...` — un `WebClient` construit avec un `baseUrl` accepte une URI absolue en argument de `.uri(...)` et l'utilise telle quelle (ne pas la traiter comme un chemin relatif).
5. La réponse de cette 2ᵉ requête est un XML `<transcript><text start="…" dur="…">…</text>…</transcript>` : parser (même `DocumentBuilder`), dé-échapper les entités HTML dans le texte (`&amp;`, `&#39;`, etc. — `org.apache.commons.text.StringEscapeUtils` si déjà une dépendance du projet, sinon un remplacement manuel des entités les plus courantes suffit).

**Fragilité assumée** : ce mécanisme dépend de la structure interne (non documentée) des pages YouTube, qui peut changer sans préavis — même limite que la lib Python utilisée en prototype. Écrire le parsing de façon défensive (tout champ absent → `Optional.empty()`/`ERROR`, jamais d'exception qui remonte jusqu'au job planifié). **Valider avec un vrai `videoId` Cryptolyze en FR et comparer au résultat de `tools/media_watch/probe_transcript.py` sur la même vidéo** avant de considérer ce client terminé — tester depuis une machine avec accès réseau normal (pas un environnement de build sans accès sortant, même limite que documentée dans `tools/calibration/fetch_real_klines.py`).

**Tests attendus (Lot 1a)** : parsing Atom (RSS) sur un échantillon capturé une fois en local (fixture, pas d'appel réseau dans les tests) ; extraction du JSON `ytInitialPlayerResponse` depuis un extrait de HTML fixture ; parsing du XML transcript depuis une fixture ; comportement `Optional.empty()` sur chaîne sans captions.

---

## Lot 1b — Job planifié d'ingestion

Package `service/scheduler/` (vide à ce jour — premier `@Scheduled` du projet). Vérifier/ajouter `@EnableScheduling` sur la classe de configuration principale si absent.

`MediaWatchIngestionJob` (`@Component`) :
- Une méthode `@Scheduled(cron = "${tradeio.media-watch.poll-cron:0 0 */6 * * *}")` (toutes les 6h par défaut, configurable — cadence ~4 vidéos/semaine chez Cryptolyze ne justifie pas un polling agressif).
- Pour chaque `ContentSourceRepository.findByActiveTrue()` :
  1. `YoutubeRssClient.fetchLatestVideos(credential, source.getChannelId())`.
  2. Pour chaque vidéo dont `!videoContentRepository.existsBySourceAndVideoId(source, videoId)` (idempotence — ne jamais retraiter une vidéo déjà connue) :
     - `YoutubeTranscriptClient.fetchTranscript(...)`. Si présent → `VideoContentEntity(status=PENDING, transcript=...)`. Si absent → `status=ERROR, errorReason="no_transcript_available"`.
  3. **Isoler les erreurs par source** (try/catch autour du traitement de chaque `ContentSource` individuellement) : une chaîne en échec (RSS injoignable, etc.) ne doit jamais empêcher le traitement des autres — même principe que la dégradation gracieuse de `ForexFactoryCalendarClient`.
- Logger un résumé par exécution (nb vidéos découvertes/nb déjà connues/nb erreurs) au niveau `info`, utile pour le Lot 4.

**Tests attendus** : idempotence (2ᵉ exécution sur les mêmes données ne recrée aucun `VideoContentEntity`) ; isolation d'erreur (une source qui lève une exception n'empêche pas le traitement des autres, testable avec un mock de `YoutubeRssClient` qui lève sur une source et retourne normalement sur une autre).

---

## Lot 2 — Classification + extraction LLM (2 passes)

### Item 0 — Prérequis technique : généraliser `OpenAIService` au-delà de `LlmAdvice`

`OpenAIService.ask(userInput, tier, callSite)` désérialise toujours en `LlmAdvice` — inutilisable pour la classification/l'extraction. Ajouter une surcharge générique :

```java
public <T> T ask(String userInput, LlmTier tier, String callSite, Class<T> responseType)
```

qui fait exactement ce que fait `ask` aujourd'hui (appel, `logUsage(...)`, extraction du texte) mais désérialise vers `responseType` au lieu de `LlmAdvice.class`. Faire de `ask(userInput, tier, callSite)` un simple appel à cette surcharge avec `LlmAdvice.class`, pour ne rien casser côté `OpenAIAdvisor`. Gérer l'échec de désérialisation en levant une exception dédiée (ou en retournant `null`/`Optional` selon convention à choisir) plutôt que de réutiliser `LlmAdvice.invalid()` qui n'a pas de sens hors contexte trading.

### Item A — Passe 1 : classification (`LlmTier.LOW`)

DTO `record ClassificationResult(boolean isMarketRelevant, String category)`.

Prompt (construit par un nouveau service, ex. `TranscriptClassificationService`, suivant le style `expectedOutputBlock()` d'`AbstractAdvisor` — schéma JSON embarqué explicitement dans le texte) : titre de la vidéo + extrait tronqué du transcript (segments dont `startSeconds < 120`, cf. `etude-veille-media-youtube.md` §2/§6 — réutiliser exactement la même logique de troncature que `tools/media_watch/probe_transcript.py --excerpt-seconds`, la valeur 120 doit être la même des deux côtés). Instruction : déterminer si le contenu est une analyse de marché crypto/macro ou un contenu à thème sans rapport (ex. tutoriel DeFi, psychologie de trading générale) — répondre strictement en JSON `{"isMarketRelevant": true|false, "category": "market_analysis|off_topic_defi_tutorial|off_topic_psychology|other"}` (catégories indicatives, à ajuster librement, l'important est la distinction `isMarketRelevant`).

Appel : `openAIService.ask(prompt, LlmTier.LOW, "media-watch:classification", ClassificationResult.class)`.

Si `!isMarketRelevant` → `VideoContentEntity.status = IRRELEVANT`, ne jamais déclencher l'item B pour cette vidéo.

### Item B — Passe 2 : extraction de claims (`LlmTier.MEDIUM`)

DTOs :
```java
record ExtractedClaim(String symbol, String sentiment, String horizon, double confidence, String excerpt)
record ExtractionResult(List<ExtractedClaim> claims)
```
`sentiment` doit correspondre exactement aux noms de `SignalType` (`BULLISH`/`BEARISH`/`NEUTRAL`), `horizon` à ceux de `ClaimHorizon` (`COURT_TERME`/`MOYEN_TERME`/`LONG_TERME`) — le préciser explicitement dans le prompt pour éviter tout écart de casse/orthographe qui casserait le `valueOf(...)` à la persistance.

Prompt : transcript complet cette fois (pas de troncature). Instruction : extraire une liste de 0 à N affirmations de marché, chacune avec le symbole concerné, le sentiment directionnel, l'horizon temporel, un niveau de confiance (0-1), et un court extrait du texte source (audit/traçabilité, cf. Lot 4). Schéma JSON attendu explicite dans le prompt, sur le modèle d'`ExtractionResult`.

Appel : `openAIService.ask(prompt, LlmTier.MEDIUM, "media-watch:extraction", ExtractionResult.class)`. Persister un `MediaClaimEntity` par élément de `claims`, puis `VideoContentEntity.status = PROCESSED`.

### `TranscriptExtractionService` (orchestrateur)

- Consomme `videoContentRepository.findByStatus(PENDING)`.
- Pour chaque `VideoContentEntity` : passe 1 → si pertinent, passe 2 → persistance des claims. Toute exception (timeout LLM, JSON invalide) → `status = ERROR`, `errorReason` renseigné, ne doit jamais faire échouer le traitement des autres vidéos de la même exécution (même principe d'isolation qu'au Lot 1b).
- Peut être appelé depuis un second job planifié (même package `service/scheduler/`), ou depuis le même job que l'ingestion après l'écriture des `VideoContent` — trancher à l'implémentation selon ce qui est le plus simple à tester (probablement deux jobs séparés, l'un pour l'ingestion RSS/transcript, l'autre pour la classification/extraction, pour pouvoir les tester et les faire échouer indépendamment).

**Tests attendus** : mapping JSON → `ClassificationResult`/`ExtractionResult` ; `isMarketRelevant=false` n'appelle jamais la passe 2 (vérifiable avec un mock d'`OpenAIService` qui échouerait si appelé une 2ᵉ fois) ; `sentiment`/`horizon` invalides dans la réponse LLM ne cassent pas le traitement des autres claims de la même vidéo (isoler le `valueOf` par claim, pas un seul `valueOf` global qui ferait échouer toute la liste).

---

## Lot 3 — Intégration Opinion / décision

`MediaMarketOpinion` (`@Component implements MarketOpinion` directement, **pas** `extends AbstractMarketOpinion`, même raisonnement qu'`ExternalMarketOpinion` : pas de `Strategy` à agréger via `StrategyAggregator`, rien à faire dans `getRequiredCandles` — retourner `Map.of()`).

`decide(OpinionContext context, MarketOpinionParameters parameters)` :
1. Récupérer `symbol` depuis `context.marketContext().symbol()` (même accès qu'`ExternalMarketOpinion`).
2. `mediaClaimRepository.findBySymbolAndVideoContent_PublishedAtAfter(symbol, cutoff)` — `cutoff` = borne large (ex. `Instant.now().minus(Duration.ofDays(400))`, couvre largement la demi-vie long-terme de 3 mois sans laisser la requête illimitée).
3. Pour chaque claim : `poids = claim.getConfidence() * claim.getVideoContent().getSource().getCredibilityWeight() * Math.pow(0.5, ageEnJours / demiVie(claim.getHorizon()))` avec `demiVie(COURT_TERME)=3j, demiVie(MOYEN_TERME)=21j, demiVie(LONG_TERME)=90j` (constantes, cf. `etude-veille-media-youtube.md` §5 — marquées comme valeurs de départ à calibrer, pas figées dans le marbre : les extraire en constantes nommées faciles à ajuster, pas en littéraux dispersés).
4. Signe directionnel par claim : `BULLISH=+1, BEARISH=-1, NEUTRAL=0` (`claim.getSentiment()`, le `SignalType` réutilisé).
5. `score = Σ(signe_i × poids_i) / Σ(poids_i)`, bornée `[-1, 1]` (si `Σ(poids_i) == 0`, aucun claim pertinent → ne pas publier d'`OpinionEvent`, même comportement qu'`ExternalMarketOpinion` quand `LlmAdvice` est invalide).
6. `confidence` : fonction saturante de `Σ(poids_i)`, ex. `Math.min(1.0, Σ(poids_i) / K)` avec `K` une constante de normalisation à calibrer (proposer une valeur de départ raisonnable, ex. `K = 2.0`, documentée comme telle).
7. Construire un `OpinionSignal` sur le même modèle qu'`ExternalMarketOpinion` : `majoritySignal`/`weightedSignal` dérivés du score, `scope = OpinionScope.EXTERNAL`, `sources` = ensemble des `videoId`/chaînes ayant contribué (traçabilité, Lot 4), `reason` = résumé lisible (ex. nombre de claims retenus, chaîne(s) source(s)). Publier via `eventBus.publish(new OpinionEvent(...))`.

**Câblage** : aucun (Spring détecte automatiquement `@Component implements MarketOpinion` via `MarketOpinionRegistry`). Vérifier simplement que `MediaMarketOpinion` est bien pris en compte dans les tests d'intégration existants du registry, s'ils énumèrent les opinions attendues explicitement.

**Tests attendus** : décroissance correcte par horizon (un claim `COURT_TERME` vieux de 6 jours pèse ~1/4 d'un claim identique publié aujourd'hui) ; agrégation multi-claims (plusieurs claims concordants → confidence plus élevée qu'un seul) ; aucun `OpinionEvent` publié quand `Σ(poids_i) == 0`.

---

## Lot 4 — Observabilité / ops

- Vérifier que `sources`/`reason` d'`OpinionSignal` (Lot 3) permettent bien de remonter jusqu'au(x) `videoId` source(s) — c'est la traçabilité bout-en-bout demandée, pas de nouveau mécanisme à construire si Lot 3 est fait correctement.
- `errorReason` sur `VideoContentEntity` (déjà ajouté au Lot 1a) : vérifier qu'il est renseigné de façon utile à chaque cas d'erreur identifié (pas de transcript, RSS indisponible, JSON LLM invalide, timeout) plutôt que des messages génériques.
- Documenter (README ou commentaire sur `ContentSourceEntity`) que l'ajout d'une nouvelle chaîne = une nouvelle ligne en base (via un script/endpoint d'admin minimal si aucun n'existe déjà pour ce type d'entité — vérifier s'il existe déjà un mécanisme d'admin pour insérer ce genre de config, sinon un simple insert manuel documenté suffit pour V1, pas besoin d'interface).

---

## Hors scope explicite

Ne pas construire de dashboard, ne pas ajouter de nouvelle plateforme que YouTube (le design doit rester généralisable mais l'implémentation ne couvre que `ContentPlatform.YOUTUBE`), ne pas recalibrer les demi-vies/`K`/cadence de poll au-delà des valeurs de départ documentées (calibration explicitement laissée pour plus tard, cf. `etude-veille-media-youtube.md`).
