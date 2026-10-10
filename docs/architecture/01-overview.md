# Vue d'ensemble

Vérifié le : 2026-09-12 (pom.xml, controllers, security, service/tree/**, service/scheduler/**).

## Ce qu'est TradeIO5

Une application Spring Boot 3.3.4 / Java 21 (`fr.ses10doigts.tradeIO5`, groupId `fr.ses10doigts`, version `5.0.1`) qui :

- Récupère des données de marché (candles crypto multi-exchange) et des données macro/média externes.
- Les transforme via un pipeline de calcul en signaux pondérés (`Indicator → Strategy → Opinion → Scenario → Decision`), multi-utilisateur.
- Expose ce pipeline à la fois en interface web (Thymeleaf, JWT cookie) et en tools **MCP** (`spring-ai-starter-mcp-server-webmvc`) consommables par un agent IA externe.
- Persiste l'état vivant (scénarios/décisions par utilisateur) avec photo quotidienne et rejeu au redémarrage.

## Ce que TradeIO5 N'EST PAS (point critique)

**Ce n'est pas un système d'exécution d'ordres automatique.** C'est un moteur de signal de décision. La chaîne s'arrête à la production d'un objet `Decision`/`ActionStep` en base — rien dans le code n'appelle jamais un exchange pour passer un ordre réel.

Concrètement, au 2026-09-12 :

- La quantité proposée dans un `ActionStep` est un **placeholder constant** (`BigDecimal.ONE`), pas un vrai calcul de sizing.
- `WalletSnapshot` et `UserProfile` (portefeuille réel, profil de risque) sont construits **vides** (`.builder().build()`) dans `TreeAnalysisFacade` — aucune donnée réelle n'y est injectée, quel que soit l'utilisateur.
- Aucun composant du code n'émet jamais les événements `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED` : le cycle de vie `CREATED → EXECUTED/ABORTED` d'une `Decision` existe comme modèle d'état, mais rien ne le fait progresser vers `EXECUTED` via un vrai appel `BinanceApiClient`/`KrakenApiClient`.
- Le scheduler qui générerait des décisions en continu (`DecisionOrchestratorJob`) est **désactivé par défaut** (cron `-`), au même titre que la photo quotidienne et l'archivage — voir [`operations/scheduled-jobs.md`](../operations/scheduled-jobs.md).

Seule exécution quotidienne autonome liée au DCA : le **bench grandeur nature Rainbow** ([`08`](08-rainbow-bench-grandeur-nature.md)), en simulation (wallet fictif ; le preset « live » d'un actif lit en plus le solde réel de l'exchange en LECTURE SEULE pour se dimensionner, aucun ordre, test de garde).

Détail complet, historique et plan de comblement : [`known-gaps/decision-to-order-gap.md`](../known-gaps/decision-to-order-gap.md). **Tout agent amené à raisonner sur "que se passe-t-il quand l'app décide d'acheter" doit lire ce fichier avant de répondre.**

L'ancien `README.md` racine décrivait l'app comme une "plateforme d'investissement autonome" avec une "gestion de portefeuille" — formulation trompeuse sur l'état actuel, corrigée dans le nouveau `README.md` et documentée précisément ici.

## Architecture en couches (vérifiée)

Contrairement à la description "3 couches" de l'ancien README (Données/Décision/Utilisateur, trop vague pour être actionnable), l'organisation réelle du code est :

| Couche | Package(s) | Rôle |
|---|---|---|
| Ingestion de données | `service/connector/apiclient/**`, `service/market/**` | Récupération candles (Binance/Kraken/OKX) + cache DB, résolution de provider par asset |
| Indicateurs externes | `service/tree/indicator/external/**` | Wrappers par provider (Coinalyze, Twelve Data, Yahoo Finance, CoinStats, DefiLlama, SoSoValue, Finnhub, ForexFactory) |
| Veille média | `service/tree/media/**` | Pipeline YouTube RSS → transcript → classification/extraction LLM |
| Moteur `tree` (métier) | `service/tree/{indicator,strategy,opinion,scenario,decision,event}/**` | Pipeline Indicator→Strategy→Opinion→Scenario→Decision, `EventBus`/`EventStore` |
| Exposition | `controller/**` (web + REST admin), `service/tree/api/mcp/**`, `service/dca/DcaMcpTools`, `service/tree/macro/MacroCalendarMcpTools` | Endpoints REST/Thymeleaf + tools MCP |
| Orchestration temporelle | `service/scheduler/**` | Jobs `@Scheduled` (tous désactivables individuellement par propriété) |
| Sécurité | `security/**` | JWT cookie (utilisateur interactif) + clé API statique (agent machine-à-machine), voir [`architecture/07-security.md`](07-security.md) |
| Configuration | `configuration/**` | Beans de cache (candles, ETF flow), MCP server, OpenAI client, initializers de démarrage |

Voir [`architecture/02-tree-pipeline.md`](02-tree-pipeline.md) pour le détail du moteur `tree`, qui est le cœur du projet.

## Multi-utilisateur

L'app gère plusieurs utilisateurs (`User` + `Role` Spring Security classique) avec un état de scénarios/décisions isolé par utilisateur (`ScenarioOwner`), plus un owner technique `SYSTEM` (`SystemOwner`) utilisé pour la résolution de credentials communes. Détail : [`architecture/03-ownership-and-lifecycle.md`](03-ownership-and-lifecycle.md).

## Stack technique (pom.xml, vérifié)

- Spring Boot 3.3.4, Java 21, Spring AI 1.0.9 (bornes : la branche 1.1.x exigerait Spring Boot 3.5+).
- Persistance : Spring Data JPA + MySQL (`mysql-connector-j` runtime) ; H2 en test.
- Sécurité : Spring Security + JWT (`io.jsonwebtoken:jjwt:0.9.1`) + filtre clé API custom.
- Web : Thymeleaf (UI serveur) + `spring-boot-starter-web` + `webflux` (clients HTTP réactifs vers les providers externes) + MCP server HTTP/SSE (`spring-ai-starter-mcp-server-webmvc`).
- LLM : `com.openai:openai-java-spring-boot-starter` (client OpenAI-compatible, `baseUrl` configurable — pas nécessairement OpenAI en dur).
- Client exchange : `io.github.binance:binance-connector-java`.
- Scraping HTML : `jsoup` (ETF flow Farside — chemin conservé pour le backfill historique, plus pour le flux courant, voir [`architecture/05-external-providers.md`](05-external-providers.md)).
- ID : `ulid-creator`.
