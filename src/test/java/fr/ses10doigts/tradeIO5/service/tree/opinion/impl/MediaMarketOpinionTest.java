package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.MediaClaimEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ClaimHorizon;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ContentPlatform;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.repository.MediaClaimRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Media - MediaMarketOpinion")
class MediaMarketOpinionTest {

    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");

    private MediaClaimRepository mediaClaimRepository;
    private EventBus eventBus;
    private MediaMarketOpinion opinion;

    @BeforeEach
    void setUp() {
        mediaClaimRepository = mock(MediaClaimRepository.class);
        eventBus = mock(EventBus.class);
        opinion = new MediaMarketOpinion(mediaClaimRepository, eventBus);
    }

    @Test
    @DisplayName("getRequiredCandles() ne demande aucune bougie")
    void getRequiredCandles_returnsEmptyMap() {
        assertTrue(opinion.getRequiredCandles(MarketOpinionParameters.builder().build()).isEmpty());
    }

    @Test
    @DisplayName("Décroissance : un claim COURT_TERME vieux de 6 jours pèse ~1/4 d'un claim identique publié aujourd'hui")
    void computeWeight_decaysCorrectly_forShortTermHorizon() {
        ContentSourceEntity source = source(1.0);

        MediaClaimEntity fresh = claim(source, ClaimHorizon.COURT_TERME, 0.8, NOW);
        MediaClaimEntity old = claim(source, ClaimHorizon.COURT_TERME, 0.8, NOW.minusSeconds(6 * 86400L));

        double freshWeight = MediaMarketOpinion.computeWeight(fresh, NOW);
        double oldWeight = MediaMarketOpinion.computeWeight(old, NOW);

        // demi-vie 3j, âge 6j -> 2 demi-vies -> facteur 0.25
        assertEquals(freshWeight * 0.25, oldWeight, 0.0001);
    }

    @Test
    @DisplayName("Décroissance : demi-vies distinctes par horizon (court < moyen < long, à âge égal)")
    void computeWeight_halfLifeDiffersByHorizon() {
        ContentSourceEntity source = source(1.0);
        Instant tenDaysAgo = NOW.minusSeconds(10 * 86400L);

        double shortWeight = MediaMarketOpinion.computeWeight(claim(source, ClaimHorizon.COURT_TERME, 1.0, tenDaysAgo), NOW);
        double mediumWeight = MediaMarketOpinion.computeWeight(claim(source, ClaimHorizon.MOYEN_TERME, 1.0, tenDaysAgo), NOW);
        double longWeight = MediaMarketOpinion.computeWeight(claim(source, ClaimHorizon.LONG_TERME, 1.0, tenDaysAgo), NOW);

        assertTrue(shortWeight < mediumWeight);
        assertTrue(mediumWeight < longWeight);
    }

    @Test
    @DisplayName("Agrégation : plusieurs claims concordants et récents -> confidence plus élevée qu'un seul")
    void decide_higherConfidence_withMoreConcordantClaims() {
        ContentSourceEntity source = source(1.0);
        VideoContentEntity video1 = video(source, "V1", NOW);
        VideoContentEntity video2 = video(source, "V2", NOW);

        MediaClaimEntity single = MediaClaimEntity.builder()
                .videoContent(video1).symbol("BTC").sentiment(SignalType.BULLISH)
                .horizon(ClaimHorizon.COURT_TERME).confidence(0.8).excerpt("e1").build();

        MediaClaimEntity concordant1 = MediaClaimEntity.builder()
                .videoContent(video1).symbol("BTC").sentiment(SignalType.BULLISH)
                .horizon(ClaimHorizon.COURT_TERME).confidence(0.8).excerpt("e1").build();
        MediaClaimEntity concordant2 = MediaClaimEntity.builder()
                .videoContent(video2).symbol("BTC").sentiment(SignalType.BULLISH)
                .horizon(ClaimHorizon.COURT_TERME).confidence(0.8).excerpt("e2").build();

        double singleConfidence = runDecideAndCaptureConfidence(List.of(single));
        double multiConfidence = runDecideAndCaptureConfidence(List.of(concordant1, concordant2));

        assertTrue(multiConfidence > singleConfidence);
    }

    @Test
    @DisplayName("Aucun OpinionEvent publié quand la somme des poids est nulle (aucun claim)")
    void decide_publishesNothing_whenNoClaims() {
        when(mediaClaimRepository.findBySymbolAndVideoContent_PublishedAtAfter(any(), any())).thenReturn(List.of());

        opinion.decide(contextFor("BTCUSDT"), MarketOpinionParameters.builder().build());

        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("Aucun OpinionEvent publié quand le contexte n'a pas de symbole")
    void decide_publishesNothing_whenNoSymbolInContext() {
        MarketContext mc = new MarketContext(null, BigDecimal.ZERO, new FixedDomainClock(NOW), Map.of(), Map.of());
        OpinionContext ctx = new OpinionContext(null, null, mc, Map.of(), new FixedDomainClock(NOW));

        opinion.decide(ctx, MarketOpinionParameters.builder().build());

        verify(eventBus, never()).publish(any());
        verify(mediaClaimRepository, never()).findBySymbolAndVideoContent_PublishedAtAfter(any(), any());
    }

    @Test
    @DisplayName("Le score directionnel reflète le sens majoritaire pondéré des claims")
    void decide_scoreReflectsWeightedDirection() {
        ContentSourceEntity source = source(1.0);
        VideoContentEntity video = video(source, "V1", NOW);

        MediaClaimEntity bullish = MediaClaimEntity.builder()
                .videoContent(video).symbol("BTC").sentiment(SignalType.BULLISH)
                .horizon(ClaimHorizon.COURT_TERME).confidence(1.0).excerpt("e").build();

        when(mediaClaimRepository.findBySymbolAndVideoContent_PublishedAtAfter(any(), any())).thenReturn(List.of(bullish));

        opinion.decide(contextFor("BTCUSDT"), MarketOpinionParameters.builder().build());

        ArgumentCaptor<OpinionEvent> captor = ArgumentCaptor.forClass(OpinionEvent.class);
        verify(eventBus).publish(captor.capture());

        assertEquals(SignalType.BULLISH, captor.getValue().getWeightedSignal());
        assertTrue(captor.getValue().getScore() > 0);
    }

    private double runDecideAndCaptureConfidence(List<MediaClaimEntity> claims) {
        MediaClaimRepository repo = mock(MediaClaimRepository.class);
        EventBus bus = mock(EventBus.class);
        MediaMarketOpinion op = new MediaMarketOpinion(repo, bus);

        when(repo.findBySymbolAndVideoContent_PublishedAtAfter(any(), any())).thenReturn(claims);

        op.decide(contextFor("BTCUSDT"), MarketOpinionParameters.builder().build());

        ArgumentCaptor<OpinionEvent> captor = ArgumentCaptor.forClass(OpinionEvent.class);
        verify(bus).publish(captor.capture());
        return captor.getValue().getConfidence();
    }

    private OpinionContext contextFor(String symbol) {
        MarketContext mc = new MarketContext(symbol, BigDecimal.ONE, new FixedDomainClock(NOW), Map.of(), Map.of());
        return new OpinionContext(null, null, mc, Map.of(), new FixedDomainClock(NOW));
    }

    private ContentSourceEntity source(double credibilityWeight) {
        return ContentSourceEntity.builder()
                .platform(ContentPlatform.YOUTUBE)
                .channelId("CH1")
                .displayName("Cryptolyze")
                .credibilityWeight(credibilityWeight)
                .active(true)
                .build();
    }

    private VideoContentEntity video(ContentSourceEntity source, String videoId, Instant publishedAt) {
        return VideoContentEntity.builder()
                .source(source)
                .videoId(videoId)
                .title("Titre " + videoId)
                .publishedAt(publishedAt)
                .build();
    }

    private MediaClaimEntity claim(ContentSourceEntity source, ClaimHorizon horizon, double confidence, Instant publishedAt) {
        VideoContentEntity video = video(source, "V-" + publishedAt, publishedAt);
        return MediaClaimEntity.builder()
                .videoContent(video)
                .symbol("BTC")
                .sentiment(SignalType.BULLISH)
                .horizon(horizon)
                .confidence(confidence)
                .excerpt("excerpt")
                .build();
    }
}
