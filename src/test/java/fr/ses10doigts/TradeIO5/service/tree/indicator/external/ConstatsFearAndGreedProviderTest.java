package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.external.FearAndGreedResponse;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.feargreed.CoinstatsFearAndGreedClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConstatsFearAndGreedProviderTest {

    @Test
    void compute() {
        CoinstatsFearAndGreedClient client = new CoinstatsFearAndGreedClient();

        String key = "v8ZVIcChU67x9vyMi1Ts0fzyNgwbetIHiNodpD4UonI=";
        String baseUrl = "https://openapiv1.coinstats.app";

        ApiCredentialDTO cred = new ApiCredentialDTO(WebProviderCode.COINSTATS, key, null, baseUrl);

        FearAndGreedResponse fetch = client.fetch(cred);

        assertNotNull(fetch.getNow());
        assertNotNull(fetch.getNow().getValue());
        assertNotNull(fetch.getYesterday());
        assertNotNull(fetch.getYesterday().getValue());
        assertNotNull(fetch.getLastWeek());
        assertNotNull(fetch.getLastWeek().getValue());
    }
}