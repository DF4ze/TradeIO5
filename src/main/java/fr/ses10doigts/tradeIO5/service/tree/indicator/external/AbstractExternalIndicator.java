package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

public abstract class AbstractExternalIndicator implements Indicator {

    public static final String P_CREDENTIAL = "credential";

    private static WebClient webClient = null;

    protected WebClient getWebClient(ApiCredential credential) {
        if( webClient == null ){
            webClient = WebClient.builder()
                    .baseUrl(credential.getWebProvider().getApiBaseUrl())
                    .defaultHeader("X-API-KEY", credential.getApiKey())
                    .build();
        }
        return webClient;
    }

    protected WebClient getWebClient(String url) {
        if( webClient == null ){
            WebClient.builder()
                    .baseUrl(url)
                    .build();
        }
        return webClient;
    }


    @Override
    public List<String> getParametersNames() {
        return List.of(P_CREDENTIAL);
    }
}
