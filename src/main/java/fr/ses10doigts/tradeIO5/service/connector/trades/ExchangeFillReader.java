package fr.ses10doigts.tradeIO5.service.connector.trades;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;

import java.time.Instant;
import java.util.List;

/**
 * Lecture seule de l'historique des remplissages spot d'un exchange (clé de LECTURE suffisante). Contrat : du plus récent au plus
 * ancien non exigé ; {@code since} nul = tout l'historique accessible. Toute panne lève {@link BalanceUnavailableException}.
 */
public interface ExchangeFillReader {

    WebProviderCode getProviderCode();

    List<ImportedFill> fetchFills(ApiCredential credential, Instant since);
}
