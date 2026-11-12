---
statut: courant
vérifié_le: 2026-10-03
modifié_le: 2026-11-11
empreinte: 49aa581978
---
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
| `tradeio.signup.enabled` | `false` | Inscription publique (`/register`, `POST /api/auth/signupForm`) ; fermée : les comptes sont créés par l'admin |
| `tradeio.ssh.enabled` | `false` | `true` ⇒ provisioning réel des comptes tunnel via le helper root (VPS) ; sinon provisioner factice en mémoire. Détail : [`acces-amis-ssh.md`](acces-amis-ssh.md) |
| `tradeio.ssh.helper-path` / `sudo` / `timeout-seconds` | `/usr/local/sbin/tradeio-user` / `true` / `10` | Appel `sudo -n <helper> <cmd>` (règle sudoers limitée à ce script) |
| `tradeio.ssh.host` / `port` / `hostkey` | — / `22` / — | Valeurs du `tradeio.conf` fourni aux amis (`hostkey` = clé publique d'hôte `ssh-ed25519 AAAA…`) |
| `tradeio.ssh.local-port` / `remote` / `url` | `18080` / `127.0.0.1:9003` / `http://127.0.0.1:18080/` | Idem, côté tunnel |
| `tradeio.ssh.client-dir` | — | Dossier du VPS avec les scripts client zippés avec le `tradeio.conf` ; vide ⇒ pas de package |
| `tradeio.media-watch.poll-cron` | `0 30 9,15,21,3 * * *` | Cron ingestion veille média |
| `tradeio.media-watch.extraction-cron` | `0 45 9,15,21,3 * * *` | Cron extraction veille média |
| `tradeio.etf-flow.historization-cron` | `0 0 7 * * *` | Cron historisation ETF flow |
| `tradeio.decision.snapshot-cron` | `-` (désactivé) | Cron photo quotidienne |
| `tradeio.decision.archival-cron` | `-` (désactivé) | Cron archivage inactivité |
| `tradeio.decision.orchestrator-cron` | `-` (désactivé) | Cron orchestrateur de décision |
| `tradeio.backup.dir` | `backup/historical` | Dossier des backups `candle` / `etf_flow_snapshot` (gitignoré) |
| `tradeio.backup.historical-cron` | `0 0 4 * * SUN` | Cron du backup hebdomadaire des données historiques (`-` désactive) |
| `tradeio.rainbow-live.pass-2355-cron` | `-` (désactivé) | Cron du run Rainbow quotidien 23:55 UTC (bougie D1 ouverte ; signal + dimensionnement sur wallet réel lecture seule) |
| `tradeio.rainbow-live.resync-on-startup` | absent (désactivé) ; `true` dans `application.properties` | Au démarrage, resynchronise l'état moteur des presets Trend Mix actifs jusqu'à la veille s'il est en retard (`RainbowLiveResyncRunner`, sans run ni ordre) |
| `tradeio.execution.mode` | `DRY_RUN` | Mode global d'exécution `OFF\|DRY_RUN\|LIVE` ; `OFF` coupe le plan dry-run, `LIVE` exige `live-unlocked=true` et `TRADEIO_MASTER_KEY` (sinon échec au démarrage) |
| `tradeio.execution.live-unlocked` | `false` | Déverrouillage de `LIVE` (avec le mode) ; à laisser `false` hors exécution réelle |
| `TRADEIO_MASTER_KEY` (variable d'environnement) | – | Clé maître AES-256 (base64, 32 octets) chiffrant les clés API (TRADE `enc:v1:`, READ `encr:v1:`) ; jamais en base ni dans un fichier versionné |
| `tradeio.security.secrets-strict` | `true` | Sans `TRADEIO_MASTER_KEY`, refuse toute écriture de clé API (jamais en clair) et logue une erreur au démarrage ; `false` (tests) autorise l'écriture en clair |
| `tradeio.okx.read.*` / `tradeio.okx.trade.*` | – | `apiKey`, `secretKey`, `passphrase` OKX semés par `ApiCredentialInitializer` (TRADE chiffré, créé si absent) ; fichier de propriétés gitignoré |
| `tradeio.execution.plan-cron` | `-` (désactivé) | Cron (UTC) du plan d'ordres dry-run, cible `0 58 23 * * *` |
| `tradeio.execution.plan-ttl` | `PT5M` | Durée de vie d'un plan avant `EXPIRED` |
| `tradeio.execution.bulk.max-plans` | `10` | Taille maximale d'un lot d'actions groupées de la Console admin (lot synchrone ; à relever selon les durées mesurées) |
| `tradeio.execution.slippage-tolerance-pct` | `0.1` | Tolérance (%) appliquée au prix de référence pour le prix plafond des ordres limit IOC |
| `tradeio.execution.fee-test.warn-pct` / `red-pct` | `0.2` / `0.8` | Seuils Fee Test (coût total en %) |
| `tradeio.execution.forbidden-nodes` | devises fiat | Nœuds interdits dans un chemin (liste CSV) |
| `tradeio.execution.instrument-catalog.refresh-after` | `PT24H` | Âge maximal du catalogue d'instruments avant rafraîchissement |
| `spring.flyway.enabled` / `baseline-on-migrate` / `baseline-version` | `true` / `true` / `1` | Flyway actif (désactivé dans les tests) ; `spring.jpa.hibernate.ddl-auto=validate` en dev/prod — cf. [`flyway.md`](flyway.md) |
| Profil `recette` | – | Répétition de déploiement sur copie de prod (port 9004, exécution `OFF`, crons coupés) — cf. [`recette.md`](recette.md) |
| `tradeio.security.agent-api-key` | vide (filtre inactif) | Active l'authentification par clé API (`ROLE_API_AGENT`) |
| `tradeio.security.agent-api-key-admin` | `false` | Recette : la clé agent reçoit aussi `ROLE_ADMIN`, **uniquement** si le profil `recette` est actif (jamais en prod) ; cf. [`recette.md`](recette.md) |

Cette liste n'est pas nécessairement exhaustive — construite à partir des classes lues dans ce lot (jobs, sécurité, OpenAI, ETF flow), pas d'une recherche exhaustive de tous les `@Value`/`@ConfigurationProperties` du projet.

## Initializers (`configuration/initializer/`)

Composants de démarrage (non détaillés fichier par fichier dans ce lot) : `ApiCredentialInitializer` (19,7 Ko — le plus volumineux, probable seeding des credentials providers), `AssetInitializer`, `ContentSourceInitializer` (sources de veille média), `RoleInitializer`, `TransactionSyncInitializer`, `UserInitializer`, `WalletInitializer`, `WebProviderInitializer`. À lire directement si un comportement précis de seeding au démarrage doit être documenté.
