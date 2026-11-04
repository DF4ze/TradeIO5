package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.springframework.stereotype.Component;

/**
 * One Trading n'a pas de {@code validate} natif : la validation se limite aux contrôles locaux (tick, lot, minimum, plafonds)
 * calculés par {@code OrderTestService}. <b>Aucun appel réseau</b>, aucun secret lu.
 */
@Component
public class OneTradingLocalOrderValidator implements SpotOrderValidator {

    static final String MESSAGE = "One Trading n'a pas de validation native : contrôles locaux seuls, aucun appel à l'exchange";

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.ONE_TRADING;
    }

    @Override
    public ValidationReport validate(ApiCredential credential, OrderRequest request) {
        return ValidationReport.accepted(MESSAGE, null);
    }
}
