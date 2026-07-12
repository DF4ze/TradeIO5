package fr.ses10doigts.tradeIO5.service.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage.Content;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseUsage;
import fr.ses10doigts.tradeIO5.configuration.properties.OpenAIProperties;
import fr.ses10doigts.tradeIO5.exceptions.LlmResponseParsingException;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.entity.llm.LlmCallLogEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.model.enumerate.OpenAIModel;
import fr.ses10doigts.tradeIO5.repository.LlmCallLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OpenAIService {
    private final Logger logger = LoggerFactory.getLogger(OpenAIService.class);

    private final OpenAIClient client;
    private final OpenAIProperties props;
    private final LlmCallLogRepository llmCallLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Envoie une requête au modèle pour le niveau donné et retourne directement un LlmAdvice.
     * Le niveau ({@link LlmTier}) est obligatoire : il n'y a volontairement pas de modèle par
     * défaut implicite, chaque appelant doit choisir explicitement son niveau de coût/capacité.
     * <p>
     * {@code callSite} identifie le site d'appel (ex. {@code "opinion:openai-advisor"},
     * {@code "media-watch:classification"}) pour permettre de suivre le coût LLM par cas d'usage.
     * Volontairement une {@code String} libre plutôt qu'un enum fermé : un enum obligerait à
     * modifier ce fichier à chaque nouveau site d'appel (ex. futur pipeline de veille média),
     * ce qui couple des modules qui n'ont pas à se connaître.
     */
    public LlmAdvice ask(String userInput, LlmTier tier, String callSite){
        try {
            LlmAdvice advice = ask(userInput, tier, callSite, LlmAdvice.class);
            advice.setValid(true);
            return advice;
        } catch (LlmResponseParsingException e) {
            logger.error("Error mapping OpenAI response: {}\nPrompt: \n{}", e.getMessage(), userInput);
            return LlmAdvice.invalid();
        }
    }

    /**
     * Variante générique de {@link #ask(String, LlmTier, String)} : même appel/logging d'usage,
     * mais désérialise vers {@code responseType} au lieu de {@link LlmAdvice} — nécessaire pour
     * les sites d'appel dont le schéma JSON de réponse n'a rien à voir avec BUY/SELL/HOLD (ex.
     * classification/extraction de la veille média, cf.
     * docs/prompt-implementation-veille-media-full.md, Lot 2 item 0). Contrairement à
     * {@link #ask(String, LlmTier, String)}, ne retourne jamais de valeur "invalide" en silence :
     * lève {@link LlmResponseParsingException} sur réponse vide ou JSON non désérialisable, à
     * l'appelant de décider quoi en faire (pas de notion de "invalid()" générique hors contexte
     * trading).
     */
    public <T> T ask(String userInput, LlmTier tier, String callSite, Class<T> responseType){

        OpenAIModel model = resolveModel(tier);

        Response response = client.responses().create(
                ResponseCreateParams.builder()
                        .model(model.toChatModel())
                        .input(userInput)
                        .build()
        );

        logger.debug("Input : {}", userInput);
        logger.debug("Response : {}", response);

        logUsage(response, tier, model, callSite);

        List<String> texts = extractText(response);

        if (texts.isEmpty()) {
            throw new LlmResponseParsingException("Réponse LLM vide (callSite=" + callSite + ", tier=" + tier + ")");
        }

        try {
            return objectMapper.readValue(cleanString(texts.getFirst().replaceAll("```\\w*", "")), responseType);
        } catch (JsonProcessingException e) {
            throw new LlmResponseParsingException(
                    "Échec de désérialisation de la réponse LLM vers " + responseType.getSimpleName()
                            + " (callSite=" + callSite + ", tier=" + tier + ") : " + e.getMessage(), e);
        }
    }

    /**
     * Capte l'usage tokens réel remonté par l'API (uniquement disponible en mode non-streamé,
     * cf. en-tête de classe) et le persiste. Si l'API ne remonte rien (cas non censé arriver
     * en non-streamé, mais pas d'exception à lever pour autant), on se contente d'un warning :
     * pas de log LlmCallLogEntity créé pour cet appel.
     */
    private void logUsage(Response response, LlmTier tier, OpenAIModel model, String callSite) {
        Optional<ResponseUsage> usage = response.usage();

        if (usage.isEmpty()) {
            logger.warn("Pas d'usage token remonté par l'API OpenAI (callSite={}, tier={}, model={}) — rien persisté",
                    callSite, tier, model);
            return;
        }

        ResponseUsage responseUsage = usage.get();

        LlmCallLogEntity log = new LlmCallLogEntity();
        log.setCallSite(callSite);
        log.setTier(tier);
        log.setModel(model.name());
        log.setInputTokens(responseUsage.inputTokens());
        log.setOutputTokens(responseUsage.outputTokens());
        log.setTotalTokens(responseUsage.totalTokens());
        log.setOccurredAt(Instant.now());

        llmCallLogRepository.save(log);
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
