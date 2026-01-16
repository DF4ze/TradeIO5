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
    OpenAIModel defaultModel,
    @NotBlank
    String baseUrl

){}