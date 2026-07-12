package fr.ses10doigts.tradeIO5.service.connector;

import com.openai.client.OpenAIClient;
import fr.ses10doigts.tradeIO5.configuration.properties.OpenAIProperties;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.model.enumerate.OpenAIModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("OpenAIService - résolution niveau -> modèle")
class OpenAIServiceTest {

    private OpenAIService serviceWith(OpenAIModel low, OpenAIModel medium, OpenAIModel high) {
        OpenAIProperties props = new OpenAIProperties(
                "fake-key",
                new OpenAIProperties.ModelTiers(low, medium, high),
                "https://api.openai.com/v1",
                Map.of()
        );
        // Le client et le repository ne sont pas sollicités par resolveModel(), null suffit pour ces tests.
        return new OpenAIService((OpenAIClient) null, props, null);
    }

    @Test
    @DisplayName("Chaque niveau configuré résout vers son propre modèle")
    void resolveModel_returnsConfiguredModelForEachTier() {
        OpenAIService service = serviceWith(OpenAIModel.GPT_4_1_MINI, OpenAIModel.GPT_4_1, OpenAIModel.GPT_5_5);

        assertEquals(OpenAIModel.GPT_4_1_MINI, service.resolveModel(LlmTier.LOW));
        assertEquals(OpenAIModel.GPT_4_1, service.resolveModel(LlmTier.MEDIUM));
        assertEquals(OpenAIModel.GPT_5_5, service.resolveModel(LlmTier.HIGH));
    }

    @Test
    @DisplayName("HIGH non configuré -> repli sur MEDIUM")
    void resolveModel_fallsBackFromHighToMedium() {
        OpenAIService service = serviceWith(OpenAIModel.GPT_4_1_MINI, OpenAIModel.GPT_4_1, null);

        assertEquals(OpenAIModel.GPT_4_1, service.resolveModel(LlmTier.HIGH));
    }

    @Test
    @DisplayName("HIGH et MEDIUM non configurés -> repli en cascade jusqu'à LOW")
    void resolveModel_fallsBackFromHighToLowThroughMedium() {
        OpenAIService service = serviceWith(OpenAIModel.GPT_4_1_MINI, null, null);

        assertEquals(OpenAIModel.GPT_4_1_MINI, service.resolveModel(LlmTier.HIGH));
        assertEquals(OpenAIModel.GPT_4_1_MINI, service.resolveModel(LlmTier.MEDIUM));
    }

    @Test
    @DisplayName("MEDIUM non configuré -> repli sur LOW")
    void resolveModel_fallsBackFromMediumToLow() {
        OpenAIService service = serviceWith(OpenAIModel.GPT_4_1_MINI, null, OpenAIModel.GPT_5_5);

        assertEquals(OpenAIModel.GPT_4_1_MINI, service.resolveModel(LlmTier.MEDIUM));
        assertEquals(OpenAIModel.GPT_5_5, service.resolveModel(LlmTier.HIGH));
    }

    @Test
    @DisplayName("Aucun niveau configuré -> erreur, pas de fallback silencieux")
    void resolveModel_throwsWhenNothingConfigured() {
        OpenAIService service = serviceWith(null, null, null);

        assertThrows(IllegalStateException.class, () -> service.resolveModel(LlmTier.LOW));
        assertThrows(IllegalStateException.class, () -> service.resolveModel(LlmTier.MEDIUM));
        assertThrows(IllegalStateException.class, () -> service.resolveModel(LlmTier.HIGH));
    }
}
