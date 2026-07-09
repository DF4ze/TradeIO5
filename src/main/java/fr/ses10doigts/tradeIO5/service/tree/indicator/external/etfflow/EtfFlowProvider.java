package fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;

public interface EtfFlowProvider {
    EtfFlowResponse fetch(ApiCredentialDTO credential, EtfFlowAsset asset);
}
