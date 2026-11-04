package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Frais Revolut X : <b>l'API n'expose aucun endpoint de frais</b> (seuls {@code total_fee} / {@code fee_currency} d'un
 * ordre déjà exécuté existent). Les taux (en % du montant) sont donc fournis par la configuration
 * ({@value #MAKER_PROPERTY}, {@value #TAKER_PROPERTY}), à lire sur la grille tarifaire du compte. Propriété absente ou illisible
 * => {@link BalanceUnavailableException} (le Fee Test est indisponible), jamais de frais supposé.
 */
@Component
public class RevolutXTradingFeeProvider implements TradingFeeProvider {

    public static final String MAKER_PROPERTY = "tradeio.revolutx.fee.maker-pct";
    public static final String TAKER_PROPERTY = "tradeio.revolutx.fee.taker-pct";

    private final String maker;
    private final String taker;

    public RevolutXTradingFeeProvider(@Value("${" + MAKER_PROPERTY + ":}") String maker,
                                      @Value("${" + TAKER_PROPERTY + ":}") String taker) {
        this.maker = maker;
        this.taker = taker;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        return new FeeRate(percent(maker, MAKER_PROPERTY), percent(taker, TAKER_PROPERTY));
    }

    private static BigDecimal percent(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new BalanceUnavailableException("Revolut X : frais non exposés par l'API, renseigner " + property + " (en %)");
        }
        try {
            BigDecimal rate = new BigDecimal(value.strip());
            if (rate.signum() < 0) {
                throw new NumberFormatException("négatif");
            }
            return rate;
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Revolut X : " + property + " illisible (pourcentage positif attendu)", e);
        }
    }
}
