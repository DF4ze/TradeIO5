package fr.ses10doigts.tradeIO5.service.expert.simulation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Garde-fous de la simulation (propriétés {@code tradeio.expert.simulation.*}, défauts sûrs) : exécuteur borné, limite de
 * débit par utilisateur, durée de vie et plafond des résultats conservés en mémoire.
 *
 * @param poolSize         simulations calculées en parallèle (chargements de données compris)
 * @param queueCapacity    simulations en attente avant refus (429)
 * @param rateLimitMax     demandes acceptées par utilisateur et par fenêtre
 * @param rateLimitWindow  fenêtre de la limite de débit
 * @param resultTtl        durée de conservation d'un résultat terminé
 * @param maxResultsPerUser résultats conservés par utilisateur (le plus ancien est libéré au-delà)
 */
@Component
public record ExpertSimulationSettings(int poolSize, int queueCapacity, int rateLimitMax, Duration rateLimitWindow,
                                       Duration resultTtl, int maxResultsPerUser) {

    /** Plage rejouée quand l'utilisateur n'en fixe pas (comme le pine : la plage visible). */
    public static final int DEFAULT_RANGE_MONTHS = 24;

    @Autowired
    public ExpertSimulationSettings(
            @Value("${tradeio.expert.simulation.pool-size:2}") int poolSize,
            @Value("${tradeio.expert.simulation.queue-capacity:10}") int queueCapacity,
            @Value("${tradeio.expert.simulation.rate-limit-max:10}") int rateLimitMax,
            @Value("${tradeio.expert.simulation.rate-limit-window-seconds:60}") long rateLimitWindowSeconds,
            @Value("${tradeio.expert.simulation.result-ttl-minutes:30}") long resultTtlMinutes,
            @Value("${tradeio.expert.simulation.max-results-per-user:5}") int maxResultsPerUser) {
        this(poolSize, queueCapacity, rateLimitMax, Duration.ofSeconds(rateLimitWindowSeconds),
                Duration.ofMinutes(resultTtlMinutes), maxResultsPerUser);
    }
}
