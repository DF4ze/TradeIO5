package fr.ses10doigts.tradeIO5.model.dto.tree.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie que le mapping JSON -> record fonctionne pour les DTO de réponse LLM du pipeline de
 * veille média (Lot 2), indépendamment d'OpenAIService — le schéma exact attendu est celui
 * embarqué dans les prompts de {@code TranscriptClassificationService}/{@code TranscriptClaimExtractionService}.
 */
@DisplayName("Media - mapping JSON des DTO LLM (ClassificationResult / ExtractionResult)")
class MediaLlmDtoMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("ClassificationResult se désérialise depuis le schéma du prompt")
    void classificationResult_mapsFromPromptSchema() throws Exception {
        String json = """
                {"isMarketRelevant": true, "category": "market_analysis"}
                """;

        ClassificationResult result = objectMapper.readValue(json, ClassificationResult.class);

        assertTrue(result.isMarketRelevant());
        assertEquals("market_analysis", result.category());
    }

    @Test
    @DisplayName("ClassificationResult(false) se désérialise correctement")
    void classificationResult_mapsFalse() throws Exception {
        String json = """
                {"isMarketRelevant": false, "category": "off_topic_defi_tutorial"}
                """;

        ClassificationResult result = objectMapper.readValue(json, ClassificationResult.class);

        assertFalse(result.isMarketRelevant());
    }

    @Test
    @DisplayName("ExtractionResult se désérialise avec plusieurs claims depuis le schéma du prompt")
    void extractionResult_mapsMultipleClaims() throws Exception {
        String json = """
                {
                  "claims": [
                    {"symbol": "BTC", "sentiment": "BULLISH", "horizon": "COURT_TERME", "confidence": 0.8, "excerpt": "on va sur les 120k"},
                    {"symbol": "ETH", "sentiment": "NEUTRAL", "horizon": "LONG_TERME", "confidence": 0.4, "excerpt": "pas de vue claire"}
                  ]
                }
                """;

        ExtractionResult result = objectMapper.readValue(json, ExtractionResult.class);

        assertEquals(2, result.claims().size());
        assertEquals("BTC", result.claims().get(0).symbol());
        assertEquals("BULLISH", result.claims().get(0).sentiment());
        assertEquals("COURT_TERME", result.claims().get(0).horizon());
        assertEquals(0.8, result.claims().get(0).confidence());
    }

    @Test
    @DisplayName("ExtractionResult avec claims vide se désérialise correctement")
    void extractionResult_mapsEmptyClaims() throws Exception {
        ExtractionResult result = objectMapper.readValue("{\"claims\": []}", ExtractionResult.class);

        assertTrue(result.claims().isEmpty());
    }
}
