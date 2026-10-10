package fr.ses10doigts.tradeIO5.service.connector.balance;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Lecture seule des soldes d'un exchange. Aucune opération d'ordre dans ce contrat (cf. test d'architecture
 * {@code ConnectorNoOrderMethodTest}).
 * <p>
 * Contrat : soldes <b>disponibles</b> par symbole standard (BTC, ETH, USDC...), soldes nuls absents. Toute panne lève
 * {@link BalanceUnavailableException} ; une map vide signifie uniquement « aucun solde ».
 */
public interface ReadOnlyBalanceReader {

    WebProviderCode getProviderCode();

    /** Lecture avec cache court ({@link BalanceCacheManager}) ; un échec n'est jamais mis en cache. */
    Map<String, BigDecimal> getAvailableBalances(ApiCredential credential);

    /** Lecture réseau directe, sans cache. */
    Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential);
}
