# Veille média (YouTube)

Vérifié le : 2026-09-12 (`service/tree/media/{TranscriptExtractionService,TranscriptClassificationService,TranscriptClaimExtractionService}`, `service/scheduler/{MediaWatchIngestionJob,MediaWatchExtractionJob}`).

## Pipeline en 2 jobs planifiés

1. **Ingestion** (`MediaWatchIngestionJob`, cron par défaut `0 30 9,15,21,3 * * *`, **actif**) : pour chaque `ContentSourceEntity` actif, découverte des nouvelles vidéos par flux RSS (`YoutubeRssClient`), récupération du transcript (`YoutubeTranscriptClient`), écriture en base en statut `PENDING` (`VideoContentEntity`). Idempotent (`existsBySourceAndVideoId`). Isolation par source : une source en échec (RSS injoignable, pas de transcript disponible) n'empêche pas le traitement des autres. Sans credential `YOUTUBE` résolue, le job se termine sans effet (warning).

   Créneaux revus le 2026-07-15 (avant : `0 0 */6 * * *`) suite à l'observation des horaires réels de publication d'une chaîne suivie (~09h00 et ~18h17 heure de Paris) — l'ancien calage ratait la vidéo du soir de près de 6h.

2. **Extraction** (`MediaWatchExtractionJob`, cron par défaut `0 45 9,15,21,3 * * *`, **actif**, décalé de 15 min après l'ingestion) : pipeline LLM à 2 passes sur les vidéos `PENDING` — classification (`TranscriptClassificationService`) puis extraction de claims (`TranscriptClaimExtractionService`) — écrites en `MediaClaimEntity`.

Déclenchement manuel des deux étapes, indépendamment : `POST /api/admin/media-watch/ingest` et `/extract` (`MediaWatchAdminController`).

## Consommation en aval

`MediaMarketOpinion` (`service/tree/opinion/impl/`) lit le store `MediaClaimEntity` pour produire un `OpinionSignal` de scope `EXTERNAL`. **Rappel important** : ce scope a une 2ᵉ implémentation concurrente, `ExternalMarketOpinion` (conseiller LLM en direct, sans lien avec la veille média) — les deux sont enregistrées sous le même `OpinionScope.EXTERNAL`, la façade n'en résout qu'une (la première trouvée) avec un warning de log. Détail : [`architecture/02-tree-pipeline.md`](02-tree-pipeline.md#3-opinion).
