package fr.ses10doigts.tradeIO5.service.tree.media;

import fr.ses10doigts.tradeIO5.model.dto.tree.media.ClassificationResult;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.service.connector.OpenAIService;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Media - TranscriptClassificationService")
class TranscriptClassificationServiceTest {

    @Test
    @DisplayName("buildExcerpt() ne garde que les segments dont startSeconds < 120 (2 minutes)")
    void buildExcerpt_truncatesAt120Seconds() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment("Bonjour à tous", 0.0, 3.0),
                new TranscriptSegment("aujourd'hui on regarde BTC", 3.0, 4.0),
                new TranscriptSegment("juste avant la limite", 119.9, 2.0),
                new TranscriptSegment("pile à la limite exclue", 120.0, 2.0),
                new TranscriptSegment("bien après la limite", 300.0, 2.0)
        );

        String excerpt = TranscriptClassificationService.buildExcerpt(segments);

        assertTrue(excerpt.contains("Bonjour à tous"));
        assertTrue(excerpt.contains("aujourd'hui on regarde BTC"));
        assertTrue(excerpt.contains("juste avant la limite"));
        assertFalse(excerpt.contains("pile à la limite exclue"));
        assertFalse(excerpt.contains("bien après la limite"));
    }

    @Test
    @DisplayName("buildExcerpt() retourne une chaîne vide sur une liste de segments vide")
    void buildExcerpt_empty_onNoSegments() {
        assertEquals("", TranscriptClassificationService.buildExcerpt(List.of()));
    }

    @Test
    @DisplayName("classify() appelle OpenAIService avec le tier LOW et le callSite media-watch:classification")
    void classify_callsOpenAIServiceWithLowTier() throws Exception {
        OpenAIService openAIService = mock(OpenAIService.class);
        TranscriptClassificationService service = new TranscriptClassificationService(openAIService);

        String transcriptJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                List.of(new TranscriptSegment("Analyse BTC du jour", 0.0, 5.0))
        );

        VideoContentEntity video = VideoContentEntity.builder()
                .videoId("V1")
                .title("Analyse marché")
                .transcript(transcriptJson)
                .build();

        ClassificationResult expected = new ClassificationResult(true, "market_analysis");
        when(openAIService.ask(any(), eq(LlmTier.LOW), eq("media-watch:classification"), eq(ClassificationResult.class)))
                .thenReturn(expected);

        ClassificationResult result = service.classify(video);

        assertEquals(expected, result);
        verify(openAIService).ask(any(), eq(LlmTier.LOW), eq("media-watch:classification"), eq(ClassificationResult.class));
    }
}
