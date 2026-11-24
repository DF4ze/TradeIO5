package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;

import java.util.List;
import java.util.Optional;

/**
 * Récupération de bougies D1 pour l'ÉTUDE seulement (D1b) : jamais l'exécution, jamais le live. Ne lève jamais : source injoignable ou actif
 * inconnu ⇒ {@link Optional#empty()}.
 */
public interface BenchHistoryFetcher {

    /** Bougies D1 closes de {@code asset}, de la plus ancienne à la plus récente, avec le symbole et la quote de la source. */
    Optional<Fetched> fetch(String asset);

    record Fetched(String symbol, String quote, List<MarketData> candles) {
    }
}
