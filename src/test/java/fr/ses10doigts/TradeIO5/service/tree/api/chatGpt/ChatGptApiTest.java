package fr.ses10doigts.tradeIO5.service.tree.api.chatGpt;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import fr.ses10doigts.tradeIO5.configuration.properties.OpenAIProperties;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@DisplayName("Indicator External - Open AI Client")
@SpringBootTest
class ChatGptApiTest {

    @Autowired
    private OpenAIClient openAIClient;
    @Autowired
    private OpenAIProperties props;

    @Disabled("Test temporairement désactivé")
    @Test
    void HelloWorld(){
        Response response = openAIClient.responses().create(
                ResponseCreateParams.builder()
                        .model(props.model().low().toChatModel())
                        .input("Dis simplement : Hello World depuis OpenAI")
                        .build()
        );

        System.out.println("Raw response: " + response);
        System.out.println("Output text: " + response.output());

        if( response.conversation().isPresent() )
            System.out.println("Respond : "+response.conversation().get());
        else
            System.out.println("Error?");
    }
}