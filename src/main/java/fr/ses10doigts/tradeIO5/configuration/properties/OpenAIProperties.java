package fr.ses10doigts.tradeIO5.configuration.properties;

import fr.ses10doigts.tradeIO5.model.enumerate.OpenAIModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "tradeio.openai")
public record OpenAIProperties (

    @NotBlank
    String apiKey,
    @NotNull
    ModelTiers model,
    @NotBlank
    String baseUrl,
    /**
     * Tarifs par modèle ({@link OpenAIModel#name()} en clé, ex. {@code GPT_4_1_MINI}), utilisés
     * uniquement à la lecture par {@link fr.ses10doigts.tradeIO5.service.connector.LlmCostCalculator}
     * pour calculer un coût à partir des tokens déjà loggés. Volontairement pas stocké en base : les
     * tarifs changent dans le temps et ne doivent pas invalider l'historique déjà écrit. Optionnel —
     * un modèle absent de cette map donne simplement un coût non calculable (warning, pas d'exception).
     */
    Map<String, ModelPricing> pricing

){
    public OpenAIProperties {
        pricing = pricing != null ? pricing : Map.of();
    }

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

    /** Prix par million de tokens (input/output), dans la devise choisie à la configuration. */
    public record ModelPricing(
        BigDecimal input,
        BigDecimal output
    ){}
}