package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.tree.media.TranscriptExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2ᵉ job planifié du pipeline de veille média : classification + extraction LLM (Lot 2), séparé
 * de {@link MediaWatchIngestionJob} pour pouvoir tester et faire échouer les deux étapes
 * indépendamment (docs/prompt-implementation-veille-media-full.md, Lot 2 "TranscriptExtractionService").
 * <p>
 * Décalé de 15 minutes par rapport à l'ingestion (cron par défaut) pour laisser le temps aux
 * transcripts fraîchement ingérés d'être disponibles avant la classification. Révisé le
 * 2026-07-15 en même temps que {@link MediaWatchIngestionJob} : 9h45/15h45/21h45/3h45 (heure de
 * Paris), toujours 15 min après chaque run d'ingestion — la vidéo publiée ~9h a donc ses claims
 * disponibles avant 10h.
 */
@Component
@RequiredArgsConstructor
public class MediaWatchExtractionJob {

    private final TranscriptExtractionService transcriptExtractionService;

    @Scheduled(cron = "${tradeio.media-watch.extraction-cron:0 45 9,15,21,3 * * *}")
    public void processPendingVideos() {
        transcriptExtractionService.processPendingVideos();
    }
}
