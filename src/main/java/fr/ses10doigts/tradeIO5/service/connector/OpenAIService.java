package fr.ses10doigts.tradeIO5.service.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage.Content;
import com.openai.models.responses.ResponseOutputText;
import fr.ses10doigts.tradeIO5.configuration.properties.OpenAIProperties;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.model.enumerate.OpenAIModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OpenAIService {
    private final Logger logger = LoggerFactory.getLogger(OpenAIService.class);

    private final OpenAIClient client;
    private final OpenAIProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Envoie une requête au modèle pour le niveau donné et retourne directement un LlmAdvice.
     * Le niveau ({@link LlmTier}) est obligatoire : il n'y a volontairement pas de modèle par
     * défaut implicite, chaque appelant doit choisir explicitement son niveau de coût/capacité.
     */
    public LlmAdvice ask(String userInput, LlmTier tier ){

        OpenAIModel model = resolveModel(tier);

        Response response = client.responses().create(
                ResponseCreateParams.builder()
                        .model(model.toChatModel())
                        .input(userInput)
                        .build()
        );

        logger.debug("Input : {}", userInput);
        logger.debug("Response : {}", response);

        List<String> texts = extractText(response);

        if( !texts.isEmpty() ){
            // Conversion JSON → DTO
            try {
                LlmAdvice advice = objectMapper.readValue(cleanString(texts.getFirst().replaceAll("```\\w*", "")), LlmAdvice.class);
                advice.setValid(true);
                return advice;
            } catch (JsonProcessingException e) {
                logger.error("Error mapping OpenAI response: {}\nPrompt: \n{}\nResponse: {}",
                        e.getMessage(), userInput, response);
            }
        }

        return LlmAdvice.invalid();
    }

    /**
     * Résout le niveau logique demandé vers un modèle concret configuré. Si le niveau demandé
     * n'a pas de modèle configuré, bascule sur le niveau immédiatement inférieur (HIGH → MEDIUM → LOW).
     * Si aucun niveau, y compris LOW, n'est configuré, lève une exception (pas de fallback silencieux
     * en dessous de LOW).
     */
    OpenAIModel resolveModel(LlmTier tier) {
        OpenAIProperties.ModelTiers tiers = props.model();

        OpenAIModel model = switch (tier) {
            case HIGH -> tiers.high();
            case MEDIUM -> tiers.medium();
            case LOW -> tiers.low();
        };

        if (model != null) {
            return model;
        }

        return switch (tier) {
            case HIGH -> {
                logger.warn("Aucun modèle OpenAI configuré pour le niveau HIGH (tradeio.openai.model.high), fallback vers MEDIUM");
                yield resolveModel(LlmTier.MEDIUM);
            }
            case MEDIUM -> {
                logger.warn("Aucun modèle OpenAI configuré pour le niveau MEDIUM (tradeio.openai.model.medium), fallback vers LOW");
                yield resolveModel(LlmTier.LOW);
            }
            case LOW -> throw new IllegalStateException(
                    "Aucun modèle OpenAI configuré : tradeio.openai.model.low doit définir au minimum un modèle de repli (medium/high sont optionnels)");
        };
    }

    private static String cleanString( String result ){
        result = result.replace("```json", "");
        result = result.replace("```", "");
        result = result.trim();
        return result;
    }

    private static List<String> extractText( Response response ){
        return response.output().stream()
                .map(ResponseOutputItem::message)                                   // Optional<ResponseOutputMessage>
                .flatMap(Optional::stream)                                          // transforme Optional en Stream
                .flatMap(msg -> msg.content().stream())       // Stream<Content>
                .map(Content::outputText)                                           // Optional<ResponseOutputText>
                .flatMap(Optional::stream)                                          // transforme Optional en Stream
                .map(ResponseOutputText::text)                                      // récupérer le texte
                .toList();
    }
}
