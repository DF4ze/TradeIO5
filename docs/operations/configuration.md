# Configuration

Vérifié le : 2026-09-12 (`configuration/**`).

## Beans de configuration (`configuration/`)

| Classe | Rôle |
|---|---|
| `McpServerConfig` | Enregistre les 3 groupes de tools MCP (voir [`api/mcp-tools.md`](../api/mcp-tools.md)) |
| `OpenAIConfig` + `properties/OpenAIProperties` | Client OpenAI-compatible (`baseUrl` configurable, donc pas nécessairement l'API OpenAI officielle). Tarification par modèle optionnelle (`pricing`), utilisée uniquement en lecture pour calculer un coût a posteriori à partir de tokens déjà loggés — volontairement pas stockée en base (les tarifs changent sans devoir invalider l'historique) |
| `MarketDataCachingConfig` | Enveloppe Binance/Kraken/OKX dans un cache DB (voir [`architecture/04-market-data.md`](../architecture/04-market-data.md)) |
| `EtfFlowCachingConfig` | Enveloppe SoSoValue dans un cache DB, `@Primary` nécessaire (2 candidats `EtfFlowProvider` : SoSoValue + Farside pour le backfill) — voir [`architecture/05-external-providers.md`](../architecture/05-external-providers.md) |

## Propriétés `tradeio.*` recensées dans ce lot

| Propriété | Défaut | Effet |
|---|---|---|
| `tradeio.openai.api-key` | — (requis, `@NotBlank`) | Clé du client OpenAI-compatible |
| `tradeio.openai.base-url` | — (requis) | Endpoint du client |
| `tradeio.openai.model.{low,medium,high}` | — (chacun optionnel) | Mapping `LlmTier` → modèle concret ; fallback vers le niveau inférieur si celui demandé n'est pas configuré (`OpenAIService`) |
| `tradeio.openai.pricing.<MODEL_NAME>.{input,output}` | — (optionnel) | Tarifs par million de tokens, pour `LlmCostCalculator` |
| `tradeio.media-watch.poll-cron` | `0 30 9,15,21,3 * * *` | Cron ingestion veille média |
| `tradeio.media-watch.extraction-cron` | `0 45 9,15,21,3 * * *` | Cron extraction veille média |
| `tradeio.etf-flow.historization-cron` | `0 0 7 * * *` | Cron historisation ETF flow |
| `tradeio.decision.snapshot-cron` | `-` (désactivé) | Cron photo quotidienne |
| `tradeio.decision.archival-cron` | `-` (désactivé) | Cron archivage inactivité |
| `tradeio.decision.orchestrator-cron` | `-` (désactivé) | Cron orchestrateur de décision |
| `tradeio.security.agent-api-key` | vide (filtre inactif) | Active l'authentification par clé API (`ROLE_API_AGENT`) |

Cette liste n'est pas nécessairement exhaustive — construite à partir des classes lues dans ce lot (jobs, sécurité, OpenAI, ETF flow), pas d'une recherche exhaustive de tous les `@Value`/`@ConfigurationProperties` du projet.

## Initializers (`configuration/initializer/`)

Composants de démarrage (non détaillés fichier par fichier dans ce lot) : `ApiCredentialInitializer` (19,7 Ko — le plus volumineux, probable seeding des credentials providers), `AssetInitializer`, `ContentSourceInitializer` (sources de veille média), `RoleInitializer`, `TransactionSyncInitializer`, `UserInitializer`, `WalletInitializer`, `WebProviderInitializer`. À lire directement si un comportement précis de seeding au démarrage doit être documenté.
