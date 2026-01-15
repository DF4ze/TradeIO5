package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractExternalIndicator {

    public static final String P_CREDENTIAL = "credential";

    private final Map<WebProviderCode, WebClient> clients = new ConcurrentHashMap<>();

    protected WebClient getWebClient(ApiCredentialDTO credential) {
        return clients.computeIfAbsent(
                credential.provider(),
                p -> WebClient.builder()
                        .baseUrl(credential.baseUrl())
                        .build()
        );
    }

}
