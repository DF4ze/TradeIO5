package fr.ses10doigts.tradeIO5.configuration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import fr.ses10doigts.tradeIO5.configuration.properties.OpenAIProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OpenAIConfig {

    private final OpenAIProperties props;

    @Bean
    public OpenAIClient openAIClient() {

        return OpenAIOkHttpClient.builder()
                .apiKey(props.apiKey())
                .baseUrl(props.baseUrl())
                .build();
    }
}