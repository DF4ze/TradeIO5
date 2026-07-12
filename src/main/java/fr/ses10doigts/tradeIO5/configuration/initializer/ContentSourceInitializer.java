package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ContentPlatform;
import fr.ses10doigts.tradeIO5.repository.ContentSourceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed initial des {@link ContentSourceEntity} suivies par la veille média
 * (docs/etude-veille-media-youtube.md — Cryptolyze en point de départ).
 * <p>
 * <b>Ajouter une nouvelle chaîne (Lot 4, observabilité/ops)</b> : aucun code métier à toucher
 * (le job d'ingestion et le pipeline LLM lisent {@code ContentSourceRepository.findByActiveTrue()}
 * dynamiquement). Deux façons d'ajouter une ligne, aucune interface d'admin n'existant à ce jour
 * pour ce type d'entité (même situation que {@code WebProvider}/{@code ApiCredential}, seedés de
 * la même manière) :
 * <ol>
 *   <li>Ajouter une entrée à la liste ci-dessous (même patron que {@code WebProviderInitializer})
 *       — recommandé, reste versionné avec le code.</li>
 *   <li>Insert SQL direct dans la table {@code content_sources} (colonnes : platform='YOUTUBE',
 *       channel_id, display_name, credibility_weight, active=1) — suffisant pour un ajout ponctuel
 *       en prod sans redéploiement.</li>
 * </ol>
 * {@code credibilityWeight} pèse directement dans le calcul de poids des claims
 * ({@code MediaMarketOpinion}, Lot 3) — valeur de départ 1.0 (référence), à ajuster par chaîne
 * selon la fiabilité perçue au fil du temps (pas de mécanisme de calibration automatique).
 */
@Component
@RequiredArgsConstructor
@Order(50)
public class ContentSourceInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ContentSourceInitializer.class);

    private final ContentSourceRepository contentSourceRepository;

    @Override
    public void run(String... args) {
        seedIfAbsent("UCuXgThwkFpefb41aKWKqrOw", "Cryptolyze", 1.0);

        logger.info("📺 Toutes les ContentSource de veille média sont initialisées.");
    }

    private void seedIfAbsent(String channelId, String displayName, double credibilityWeight) {
        boolean exists = contentSourceRepository.findAll().stream()
                .anyMatch(source -> channelId.equals(source.getChannelId()));

        if (exists) {
            logger.debug("📺 ContentSource déjà présente pour channelId={}", channelId);
            return;
        }

        ContentSourceEntity source = ContentSourceEntity.builder()
                .platform(ContentPlatform.YOUTUBE)
                .channelId(channelId)
                .displayName(displayName)
                .credibilityWeight(credibilityWeight)
                .active(true)
                .build();

        contentSourceRepository.save(source);
        logger.info("📺 ContentSource initialisée : {} ({})", displayName, channelId);
    }
}
