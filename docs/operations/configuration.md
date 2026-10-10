# Configuration

Vérifié le : 2026-10-03 (`configuration/**`, `RainbowLiveDefaultPresets`).

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
| `tradeio.backup.dir` | `backup/historical` | Dossier des backups `candle` / `etf_flow_snapshot` (gitignoré) |
| `tradeio.backup.historical-cron` | `0 0 4 * * SUN` | Cron du backup hebdomadaire des données historiques (`-` désactive) |
| `tradeio.rainbow-live.pass-2355-cron` | `-` (désactivé) | Cron passe 23:55 UTC du bench Rainbow (action fictive) |
| `tradeio.rainbow-live.pass-0005-cron` | `-` (désactivé) | Cron passe 00:05 UTC du bench Rainbow (vraie clôture, action non appliquée) |
| `tradeio.execution.mode` | `DRY_RUN` | Mode global d'exécution `OFF\|DRY_RUN\|LIVE` ; `OFF` coupe le plan dry-run, `LIVE` fait échouer le démarrage (exécution réelle inexistante) |
| `tradeio.execution.plan-cron` | `-` (désactivé) | Cron (UTC) du plan d'ordres dry-run, cible `0 58 23 * * *` |
| `tradeio.execution.plan-ttl` | `PT5M` | Durée de vie d'un plan avant `EXPIRED` |
| `tradeio.execution.slippage-tolerance-pct` | `0.1` | Tolérance (%) appliquée au prix de référence pour le prix plafond des ordres limit IOC |
| `tradeio.execution.fee-test.warn-pct` / `red-pct` | `0.2` / `0.8` | Seuils Fee Test (coût total en %) |
| `tradeio.execution.forbidden-nodes` | devises fiat | Nœuds interdits dans un chemin (liste CSV) |
| `tradeio.execution.instrument-catalog.refresh-after` | `PT24H` | Âge maximal du catalogue d'instruments avant rafraîchissement |
| `tradeio.security.agent-api-key` | vide (filtre inactif) | Active l'authentification par clé API (`ROLE_API_AGENT`) |

Cette liste n'est pas nécessairement exhaustive — construite à partir des classes lues dans ce lot (jobs, sécurité, OpenAI, ETF flow), pas d'une recherche exhaustive de tous les `@Value`/`@ConfigurationProperties` du projet.

## Initializers (`configuration/initializer/`)

Composants de démarrage (non détaillés fichier par fichier dans ce lot) : `ApiCredentialInitializer` (19,7 Ko — le plus volumineux, probable seeding des credentials providers), `AssetInitializer`, `ContentSourceInitializer` (sources de veille média), `RoleInitializer`, `TransactionSyncInitializer`, `UserInitializer`, `WalletInitializer`, `WebProviderInitializer`. À lire directement si un comportement précis de seeding au démarrage doit être documenté.
