package fr.ses10doigts.tradeIO5.configuration.properties;

import fr.ses10doigts.tradeIO5.model.enumerate.OpenAIModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tradeio.openai")
public record OpenAIProperties (

    @NotBlank
    String apiKey,
    @NotNull
    ModelTiers model,
    @NotBlank
    String baseUrl

){
    /**
     * Mapping niveau logique ({@link fr.ses10doigts.tradeIO5.model.enumerate.LlmTier}) → modèle concret.
     * Chaque niveau est individuellement optionnel : {@link fr.ses10doigts.tradeIO5.service.connector.OpenAIService}
     * bascule sur le niveau immédiatement inférieur si celui demandé n'est pas configuré, et lève une erreur
     * uniquement si aucun niveau (y compris LOW) n'est configuré.
     */
    public record ModelTiers(
        OpenAIModel low,
        OpenAIModel medium,
        OpenAIModel high
    ){}
}