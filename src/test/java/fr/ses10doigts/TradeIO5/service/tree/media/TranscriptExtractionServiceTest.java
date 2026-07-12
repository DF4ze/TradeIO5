package fr.ses10doigts.tradeIO5.service.tree.media;

import fr.ses10doigts.tradeIO5.model.dto.tree.media.ClassificationResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractedClaim;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractionResult;
import fr.ses10doigts.tradeIO5.model.entity.media.MediaClaimEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import fr.ses10doigts.tradeIO5.repository.MediaClaimRepository;
import fr.ses10doigts.tradeIO5.repository.VideoContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Media - TranscriptExtractionService (orchestrateur)")
class TranscriptExtractionServiceTest {

    private VideoContentRepository videoContentRepository;
    private MediaClaimRepository mediaClaimRepository;
    private TranscriptClassificationService classificationService;
    private TranscriptClaimExtractionService claimExtractionService;
    private TranscriptExtractionService service;

    @BeforeEach
    void setUp() {
        videoContentRepository = mock(VideoContentRepository.class);
        mediaClaimRepository = mock(MediaClaimRepository.class);
        classificationService = mock(TranscriptClassificationService.class);
        claimExtractionService = mock(TranscriptClaimExtractionService.class);

        service = new TranscriptExtractionService(videoContentRepository, mediaClaimRepository, classificationService, claimExtractionService);
    }

    @Test
    @DisplayName("isMarketRelevant=false -> statut IRRELEVANT, la passe 2 n'est jamais appelée")
    void processPendingVideos_neverCallsPass2_whenIrrelevant() {
        VideoContentEntity video = videoPending("V1");

        when(videoContentRepository.findByStatus(VideoContentStatus.PENDING)).thenReturn(List.of(video));
        when(classificationService.classify(video)).thenReturn(new ClassificationResult(false, "off_topic_defi_tutorial"));
        // Si la passe 2 est appelée alors qu'elle ne devrait pas l'être, on le fait échouer bruyamment.
        when(claimExtractionService.extractClaims(any())).thenThrow(new AssertionError("La passe 2 n'aurait jamais dû être appelée"));

        service.processPendingVideos();

        verify(claimExtractionService, never()).extractClaims(any());
        assertEquals(VideoContentStatus.IRRELEVANT, video.getStatus());
        verify(videoContentRepository).save(video);
    }

    @Test
    @DisplayName("isMarketRelevant=true -> passe 2 appelée, claims valides persistés, statut PROCESSED")
    void processPendingVideos_persistsClaims_whenRelevant() {
        VideoContentEntity video = videoPending("V2");

        when(videoContentRepository.findByStatus(VideoContentStatus.PENDING)).thenReturn(List.of(video));
        when(classificationService.classify(video)).thenReturn(new ClassificationResult(true, "market_analysis"));
        when(claimExtractionService.extractClaims(video)).thenReturn(new ExtractionResult(List.of(
                new ExtractedClaim("BTC", "BULLISH", "COURT_TERME", 0.8, "extrait 1")
        )));

        service.processPendingVideos();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MediaClaimEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaClaimRepository).saveAll(captor.capture());

        assertEquals(1, captor.getValue().size());
        assertEquals("BTC", captor.getValue().get(0).getSymbol());
        assertEquals(VideoContentStatus.PROCESSED, video.getStatus());
    }

    @Test
    @DisplayName("Un claim avec sentiment/horizon invalide est ignoré sans casser les autres claims de la vidéo")
    void processPendingVideos_isolatesInvalidClaim_amongValidOnes() {
        VideoContentEntity video = videoPending("V3");

        when(videoContentRepository.findByStatus(VideoContentStatus.PENDING)).thenReturn(List.of(video));
        when(classificationService.classify(video)).thenReturn(new ClassificationResult(true, "market_analysis"));
        when(claimExtractionService.extractClaims(video)).thenReturn(new ExtractionResult(List.of(
                new ExtractedClaim("BTC", "BULLISH", "COURT_TERME", 0.8, "valide"),
                new ExtractedClaim("ETH", "haussier", "COURT_TERME", 0.5, "sentiment invalide"),
                new ExtractedClaim("SOL", "BEARISH", "jamais", 0.3, "horizon invalide")
        )));

        assertDoesNotThrow(() -> service.processPendingVideos());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MediaClaimEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaClaimRepository).saveAll(captor.capture());

        assertEquals(1, captor.getValue().size());
        assertEquals("BTC", captor.getValue().get(0).getSymbol());
        assertEquals(VideoContentStatus.PROCESSED, video.getStatus());
    }

    @Test
    @DisplayName("Isolation entre vidéos : une vidéo en échec (classification) n'empêche pas le traitement des autres")
    void processPendingVideos_isolatesErrors_betweenVideos() {
        VideoContentEntity failing = videoPending("FAIL");
        VideoContentEntity ok = videoPending("OK");

        when(videoContentRepository.findByStatus(VideoContentStatus.PENDING)).thenReturn(List.of(failing, ok));
        when(classificationService.classify(failing)).thenThrow(new RuntimeException("timeout LLM"));
        when(classificationService.classify(ok)).thenReturn(new ClassificationResult(true, "market_analysis"));
        when(claimExtractionService.extractClaims(ok)).thenReturn(new ExtractionResult(List.of()));

        assertDoesNotThrow(() -> service.processPendingVideos());

        assertEquals(VideoContentStatus.ERROR, failing.getStatus());
        assertTrue(failing.getErrorReason() != null && failing.getErrorReason().contains("timeout LLM"));
        assertEquals(VideoContentStatus.PROCESSED, ok.getStatus());

        verify(videoContentRepository, times(1)).save(failing);
        verify(videoContentRepository, times(1)).save(ok);
    }

    private VideoContentEntity videoPending(String videoId) {
        return VideoContentEntity.builder()
                .videoId(videoId)
                .title("Titre " + videoId)
                .transcript("[]")
                .status(VideoContentStatus.PENDING)
                .build();
    }
}
