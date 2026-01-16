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
import fr.ses10doigts.tradeIO5.model.dto.MyResponseDTO;
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

    // System prompt pour forcer JSON
    private static final String SYSTEM_JSON_PROMPT = """
    Tu es un assistant qui répond **uniquement** par un JSON valide.
    **Ne mets jamais de bloc de code**, ni de ```json```.
    Le texte que tu retournes doit avoir exactement ce format et ces champs : { "indic": String, "value": Double }
    Voici le prompt :
    """;

    /**
     * Envoie une requête au modèle et retourne directement une DTO
     */
    public MyResponseDTO askForSomething1(String userInput) throws JsonProcessingException {

        Response response = client.responses().create(
            ResponseCreateParams.builder()
                .model(props.defaultModel().toChatModel())
                .input(SYSTEM_JSON_PROMPT+userInput)
                .build()
        );

        logger.debug("Response : {}", response);

        List<String> texts = extractText(response);

        if( !texts.isEmpty() ){
            return objectMapper.readValue(texts.get(0), MyResponseDTO.class);
        }

        // Conversion JSON → DTO
        return null;
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
