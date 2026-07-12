package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.entity.media.MediaClaimEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ClaimHorizon;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.repository.MediaClaimRepository;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opinion {@code EXTERNAL} qui lit un store (au lieu d'appeler un LLM en live) : consomme les
 * {@link MediaClaimEntity} déjà extraits par le pipeline de veille média (Lot 2) et les agrège en
 * un {@link OpinionSignal} pondéré par décroissance temporelle
 * (docs/etude-veille-media-youtube.md §1/§5, docs/prompt-implementation-veille-media-full.md Lot 3).
 * <p>
 * Implémente {@link MarketOpinion} directement plutôt que d'étendre {@code AbstractMarketOpinion}
 * — même raisonnement qu'{@link ExternalMarketOpinion} : pas de {@code Strategy} à agréger via
 * {@code StrategyAggregator}, rien à faire dans {@link #getRequiredCandles}.
 */
@Component
public class MediaMarketOpinion implements MarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(MediaMarketOpinion.class);

    /**
     * Demi-vies de décroissance par horizon (docs/etude-veille-media-youtube.md §5) — valeurs de
     * départ explicitement marquées "à calibrer" dans l'étude, extraites en constantes nommées
     * pour être faciles à ajuster plutôt que dispersées en littéraux.
     */
    static final double COURT_TERME_HALF_LIFE_DAYS = 3.0;
    static final double MOYEN_TERME_HALF_LIFE_DAYS = 21.0;
    static final double LONG_TERME_HALF_LIFE_DAYS = 90.0;

    /**
     * Constante de normalisation pour la confidence saturante ({@code min(1, Σpoids / K)}) —
     * valeur de départ documentée comme telle (docs/etude-veille-media-youtube.md §5), à calibrer.
     */
    static final double CONFIDENCE_NORMALIZATION_K = 2.0;

    /**
     * Borne large de requête (ne pas remonter au-delà, même à poids quasi nul) — pas un mécanisme
     * de fraîcheur en tant que tel, la décroissance se calcule en mémoire ci-dessous. Couvre
     * largement la demi-vie long-terme (90j) avec une marge confortable.
     */
    static final Duration QUERY_CUTOFF_WINDOW = Duration.ofDays(400);

    private final MediaClaimRepository mediaClaimRepository;
    private final EventBus eventBus;

    public MediaMarketOpinion(MediaClaimRepository mediaClaimRepository, EventBus eventBus) {
        this.mediaClaimRepository = mediaClaimRepository;
        this.eventBus = eventBus;
    }

    @Override
    public OpinionScope getScope() {
        return OpinionScope.EXTERNAL;
    }

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters) {
        // Pas de Strategy à évaluer, pas de bougies à pré-charger : cette opinion lit un store de
        // claims déjà extraits par le pipeline de veille média, pas le marché en direct.
        return Map.of();
    }

    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        String symbol = context.marketContext() != null ? context.marketContext().symbol() : null;

        if (symbol == null) {
            logger.debug("{} : pas de symbole dans le contexte, aucun OpinionEvent publié", getName());
            return;
        }

        Instant now = context.clock().now();
        Instant cutoff = now.minus(QUERY_CUTOFF_WINDOW);
        List<MediaClaimEntity> claims = mediaClaimRepository.findBySymbolAndVideoContent_PublishedAtAfter(symbol, cutoff);

        double weightedSum = 0.0;
        double totalWeight = 0.0;
        int retainedCount = 0;
        Set<String> sources = new HashSet<>();

        for (MediaClaimEntity claim : claims) {
            double weight = computeWeight(claim, now);
            if (weight <= 0.0) {
                continue;
            }

            weightedSum += directionalSign(claim.getSentiment()) * weight;
            totalWeight += weight;
            retainedCount++;

            if (claim.getVideoContent() != null) {
                sources.add(claim.getVideoContent().getVideoId());
            }
        }

        if (totalWeight <= 0.0) {
            logger.debug("{} : aucun claim pertinent pour {} (poids total nul), aucun OpinionEvent publié", getName(), symbol);
            return;
        }

        double score = clamp(weightedSum / totalWeight, -1.0, 1.0);
        double confidence = Math.min(1.0, totalWeight / CONFIDENCE_NORMALIZATION_K);
        SignalType signal = toSignalType(score);

        String reason = "%d claim(s) retenu(s) sur %s, source(s) vidéo: %s"
                .formatted(retainedCount, symbol, String.join(", ", sources));

        OpinionEvent event = new OpinionEvent(new OpinionSignal(
                getId(),
                java.util.Optional.of(symbol),
                signal,
                signal,
                confidence,
                score,
                getScope(),
                sources,
                reason,
                now
        ));

        eventBus.publish(event);
    }

    /**
     * {@code poids = confidence × credibilityWeight(source) × 0.5^(âge / demi_vie(horizon))}
     * (docs/etude-veille-media-youtube.md §5). Package-private + statique-friendly pour être
     * testable isolément.
     */
    static double computeWeight(MediaClaimEntity claim, Instant now) {
        if (claim.getVideoContent() == null || claim.getVideoContent().getPublishedAt() == null
                || claim.getVideoContent().getSource() == null) {
            return 0.0;
        }

        Instant publishedAt = claim.getVideoContent().getPublishedAt();
        double ageInDays = Duration.between(publishedAt, now).toSeconds() / 86400.0;
        if (ageInDays < 0) {
            // Claim "du futur" (horloge désynchronisée) : pas de pénalité de décroissance,
            // mais pas de bonus non plus.
            ageInDays = 0.0;
        }

        double halfLifeDays = halfLifeDays(claim.getHorizon());
        double decay = Math.pow(0.5, ageInDays / halfLifeDays);
        double credibility = claim.getVideoContent().getSource().getCredibilityWeight();

        return claim.getConfidence() * credibility * decay;
    }

    static double halfLifeDays(ClaimHorizon horizon) {
        return switch (horizon) {
            case COURT_TERME -> COURT_TERME_HALF_LIFE_DAYS;
            case MOYEN_TERME -> MOYEN_TERME_HALF_LIFE_DAYS;
            case LONG_TERME -> LONG_TERME_HALF_LIFE_DAYS;
        };
    }

    static double directionalSign(SignalType sentiment) {
        return switch (sentiment) {
            case BULLISH -> 1.0;
            case BEARISH -> -1.0;
            case NEUTRAL -> 0.0;
        };
    }

    static SignalType toSignalType(double score) {
        if (score > 0.0) return SignalType.BULLISH;
        if (score < 0.0) return SignalType.BEARISH;
        return SignalType.NEUTRAL;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
