package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractExternalIndicator {

    public static final String P_CREDENTIAL = "credential";

    // Le défaut WebClient (256 Ko) est trop bas pour certains fournisseurs : Coinalyze
    // /future-markets renvoie la liste complète des marchés futures sur ~25 exchanges en un seul
    // appel (cf. CoinalyzeSymbolResolver), largement au-delà de 256 Ko, ce qui faisait échouer la
    // désérialisation avec un DataBufferLimitException avant même d'atteindre le parsing JSON.
    // 8 Mo laisse une marge confortable pour ce endpoint et pour toute réponse future plus
    // volumineuse chez un autre fournisseur, sans risque pratique (mémoire négligeable à cette
    // échelle pour un usage par credential mis en cache).
    private static final int MAX_IN_MEMORY_SIZE_BYTES = 8 * 1024 * 1024;

    private final Map<WebProviderCode, WebClient> clients = new ConcurrentHashMap<>();

    protected WebClient getWebClient(ApiCredentialDTO credential) {
        return clients.computeIfAbsent(
                credential.provider(),
                p -> WebClient.builder()
                        .baseUrl(credential.baseUrl())
                        .exchangeStrategies(ExchangeStrategies.builder()
                                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE_BYTES))
                                .build())
                        .build()
        );
    }

}
