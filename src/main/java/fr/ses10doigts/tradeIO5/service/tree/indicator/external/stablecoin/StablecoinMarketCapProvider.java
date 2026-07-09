package fr.ses10doigts.tradeIO5.service.tree.indicator.external.stablecoin;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.StablecoinMarketCapResponse;

public interface StablecoinMarketCapProvider {
    StablecoinMarketCapResponse fetch(ApiCredentialDTO credential);
}
