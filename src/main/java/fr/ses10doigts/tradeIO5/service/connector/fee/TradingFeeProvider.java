package fr.ses10doigts.tradeIO5.service.connector.fee;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

/**
 * Frais réels du compte pour un instrument spot (jamais supposés), clé API en lecture seule. Aucune opération d'ordre.
 */
public interface TradingFeeProvider {

    WebProviderCode getProviderCode();

    /**
     * @return frais maker/taker en % du montant, signés comme un coût (positif = payé, négatif = rebate)
     * @throws fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException clé rejetée par l'exchange
     * @throws fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException panne, réponse illisible,
     *         credential incomplète
     */
    FeeRate getFeeRate(ApiCredential credential, String instId);
}
