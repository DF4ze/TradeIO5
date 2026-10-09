# Jobs planifiés

Vérifié le : 2026-10-04 (`service/scheduler/**`).

**Point le plus important de cette page : la moitié des jobs sont désactivés par défaut.** Ne jamais supposer qu'un comportement "devrait" se produire automatiquement sans vérifier la propriété de cron correspondante.

| Job | Propriété | Défaut | Actif par défaut ? | Déclenchement manuel |
|---|---|---|---|---|
| `HistoricalDataBackupJob` | `tradeio.backup.historical-cron` | `0 0 4 * * SUN` | **Oui** | — |
| `MediaWatchIngestionJob` | `tradeio.media-watch.poll-cron` | `0 30 9,15,21,3 * * *` | **Oui** | `POST /api/admin/media-watch/ingest` |
| `MediaWatchExtractionJob` | `tradeio.media-watch.extraction-cron` | `0 45 9,15,21,3 * * *` | **Oui** | `POST /api/admin/media-watch/extract` |
| `EtfFlowHistorizationJob` | `tradeio.etf-flow.historization-cron` | `0 0 7 * * *` | **Oui** | — (pas d'endpoint dédié ; `POST /api/admin/etf-flow/backfill` couvre le backfill historique, pas ce rafraîchissement quotidien) |
| `DecisionScenarioSnapshotJob` | `tradeio.decision.snapshot-cron` | `-` (`CRON_DISABLED`) | **Non** | `POST /api/admin/decision/snapshot` |
| `UserArchivalJob` | `tradeio.decision.archival-cron` | `-` (`CRON_DISABLED`) | **Non** | `POST /api/admin/decision/archive` |
| `DecisionOrchestratorJob` | `tradeio.decision.orchestrator-cron` | `-` (`CRON_DISABLED`) | **Non** | `POST /api/admin/decision/orchestrate` |
| `RainbowLivePass2355Job` | `tradeio.rainbow-live.pass-2355-cron` | `-` (`CRON_DISABLED`) | **Non** (cible `0 55 23 * * *`, **UTC**) | `POST /api/admin/rainbow-live/run?pass=T2355` |
| `RainbowLivePass0005Job` | `tradeio.rainbow-live.pass-0005-cron` | `-` (`CRON_DISABLED`) | **Non** (cible `0 5 0 * * *`, **UTC**) | `POST /api/admin/rainbow-live/run?pass=T0005` |

**Profils non versionnés** (`application-dev/prod.properties`) : d'après Clem et la mémoire projet (2026-10-04), l'orchestrateur est activé depuis le 2026-09-03 (`0 0 6 * * *`) et les 2 passes `RainbowLive*` tournent quotidiennement sur le VPS. Toujours vérifier dans le profil actif.

`-` est la valeur spéciale Spring (`Scheduled.CRON_DISABLED`) qui désactive l'enregistrement de la tâche planifiée — vérifié empiriquement dans le projet (test dédié sur `DecisionScenarioSnapshotJob`), pas seulement supposé.

**Conséquence directe pour un agent qui répond à "l'app génère-t-elle des décisions en continu ?"** : non, par défaut. Le pipeline complet (orchestration → photo → archivage) ne tourne qu'à la demande via les endpoints admin, tant que ces 3 crons restent désactivés. Seuls la veille média et l'historisation ETF flow tournent en continu par défaut.

Pour activer les jobs désactivés (test en conditions réelles), décommenter dans le profil actif :

```properties
tradeio.decision.snapshot-cron=0 0 6 * * *
tradeio.decision.archival-cron=0 0 6 * * *
tradeio.decision.orchestrator-cron=0 0 * * * *
tradeio.rainbow-live.pass-2355-cron=0 55 23 * * *
tradeio.rainbow-live.pass-0005-cron=0 5 0 * * *
```

Les 2 jobs `RainbowLive*` déclarent `zone = "UTC"` explicitement (bench Rainbow, cf. [`architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md)) ; ils ne suivent pas le fuseau JVM. Toutes les autres heures de cron sans `zone` explicite sur `@Scheduled` s'exécutent dans le fuseau horaire de la JVM (confirmé `Europe/Paris` en environnement de déploiement observé pour la veille média).

Isolation par élément (asset, source) répétée dans plusieurs jobs (`EtfFlowHistorizationJob` par asset, `MediaWatchIngestionJob` par source) : un échec sur un élément n'empêche jamais la tentative sur les autres au sein du même cycle.
