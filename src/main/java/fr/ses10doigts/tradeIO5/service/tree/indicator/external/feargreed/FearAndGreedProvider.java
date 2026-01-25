package fr.ses10doigts.tradeIO5.service.tree.indicator.external.feargreed;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.FearAndGreedResponse;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;

public interface FearAndGreedProvider {
    FearAndGreedResponse fetch(ApiCredentialDTO credential);
}
