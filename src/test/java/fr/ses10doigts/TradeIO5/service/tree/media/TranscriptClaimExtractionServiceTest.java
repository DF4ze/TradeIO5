package fr.ses10doigts.tradeIO5.service.tree.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractedClaim;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractionResult;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ClaimHorizon;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.service.connector.OpenAIService;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Media - TranscriptClaimExtractionService")
class TranscriptClaimExtractionServiceTest {

    @Test
    @DisplayName("extractClaims() appelle OpenAIService avec le tier MEDIUM et le callSite media-watch:extraction")
    void extractClaims_callsOpenAIServiceWithMediumTier() throws Exception {
        OpenAIService openAIService = mock(OpenAIService.class);
        TranscriptClaimExtractionService service = new TranscriptClaimExtractionService(openAIService);

        // Transcript > 120s : la passe 2 ne tronque pas, contrairement à la passe 1.
        String transcriptJson = new ObjectMapper().writeValueAsString(List.of(
                new TranscriptSegment("Début de vidéo", 0.0, 5.0),
                new TranscriptSegment("Segment tardif au-delà de 2 minutes", 400.0, 5.0)
        ));

        VideoContentEntity video = VideoContentEntity.builder()
                .videoId("V1")
                .title("Analyse marché")
                .transcript(transcriptJson)
                .build();

        ExtractionResult expected = new ExtractionResult(List.of(
                new ExtractedClaim("BTC", "BULLISH", "COURT_TERME", 0.7, "extrait")
        ));

        when(openAIService.ask(any(), eq(LlmTier.MEDIUM), eq("media-watch:extraction"), eq(ExtractionResult.class)))
                .thenReturn(expected);

        ExtractionResult result = service.extractClaims(video);

        assertEquals(expected, result);
        verify(openAIService).ask(any(), eq(LlmTier.MEDIUM), eq("media-watch:extraction"), eq(ExtractionResult.class));
    }

    @Test
    @DisplayName("parseSentiment()/parseHorizon() mappent les valeurs valides et lèvent sur une valeur inconnue")
    void parseSentimentAndHorizon_validateValues() {
        assertEquals(SignalType.BULLISH, TranscriptClaimExtractionService.parseSentiment("BULLISH"));
        assertEquals(ClaimHorizon.LONG_TERME, TranscriptClaimExtractionService.parseHorizon("LONG_TERME"));

        assertThrows(IllegalArgumentException.class, () -> TranscriptClaimExtractionService.parseSentiment("haussier"));
        assertThrows(IllegalArgumentException.class, () -> TranscriptClaimExtractionService.parseHorizon("bientot"));
    }
}
