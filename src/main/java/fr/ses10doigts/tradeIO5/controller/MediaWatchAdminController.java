package fr.ses10doigts.tradeIO5.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.ses10doigts.tradeIO5.service.scheduler.MediaWatchExtractionJob;
import fr.ses10doigts.tradeIO5.service.scheduler.MediaWatchIngestionJob;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Déclenchement manuel des jobs de veille média ({@link MediaWatchIngestionJob},
 * {@link MediaWatchExtractionJob}), pour ne pas avoir à attendre le prochain créneau cron
 * (9h30/9h45, 15h30/15h45, 21h30/21h45, 3h30/3h45) en test ou en cas d'incident corrigé en cours
 * de journée. Réservé ROLE_ADMIN (même pattern que {@link MainController#adminAccess}) : ces
 * appels déclenchent des requêtes réseau (RSS, transcript, LLM) et des écritures en base, pas un
 * simple GET de lecture.
 */
@RestController
@RequestMapping("/api/admin/media-watch")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MediaWatchAdminController {

    private static final Logger logger = LoggerFactory.getLogger(MediaWatchAdminController.class);

    private final MediaWatchIngestionJob mediaWatchIngestionJob;
    private final MediaWatchExtractionJob mediaWatchExtractionJob;

    /**
     * Relance immédiate de {@link MediaWatchIngestionJob#pollActiveSources()} (découverte RSS +
     * récupération transcript). Synchrone : la requête HTTP répond une fois le poll terminé, les
     * détails (nouvelles vidéos, déjà connues, sources en erreur) restent dans les logs applicatifs
     * comme pour l'exécution planifiée.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> triggerIngestion() {
        logger.info("MediaWatchAdminController: déclenchement manuel de l'ingestion.");
        mediaWatchIngestionJob.pollActiveSources();
        return ResponseEntity.ok(Map.of("status", "done", "job", "ingestion"));
    }

    /**
     * Relance immédiate de {@link MediaWatchExtractionJob#processPendingVideos()} (classification +
     * extraction LLM des vidéos en statut PENDING). À lancer après {@code /ingest} si on veut
     * enchaîner les deux étapes sans attendre le décalage de 15 min habituel.
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, String>> triggerExtraction() {
        logger.info("MediaWatchAdminController: déclenchement manuel de l'extraction.");
        mediaWatchExtractionJob.processPendingVideos();
        return ResponseEntity.ok(Map.of("status", "done", "job", "extraction"));
    }
}
