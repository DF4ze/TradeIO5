package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

/**
 * Validation d'un ordre <b>sans jamais l'envoyer</b> (Kraken : {@code AddOrder validate=true}, OKX : aucun appel, contrôles
 * locaux seuls). Aucune méthode d'envoi ni d'annulation ; seul {@code OrderTestService} (package {@code run}) l'appelle.
 */
public interface SpotOrderValidator {

    WebProviderCode provider();

    /**
     * @return le verdict (un refus métier est un rapport {@code accepted=false}, pas une exception)
     * @throws TradeCredentialRejectedException clé Trade refusée
     * @throws ExchangeUnavailableException exchange injoignable ou réponse illisible
     */
    ValidationReport validate(ApiCredential credential, OrderRequest request);
}
