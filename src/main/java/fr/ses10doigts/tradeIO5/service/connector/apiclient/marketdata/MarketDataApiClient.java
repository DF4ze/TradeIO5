package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;

import java.time.Instant;
import java.util.List;

/**
 * Client d'accès aux données de marché PUBLIQUES (candles/klines) d'un exchange.
 * <p>
 * Volontairement découplé de {@link fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential} :
 * contrairement au solde ou à l'historique de trades exposés par
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.ProviderApiClient}, les candles
 * sont des données publiques, identiques pour tout le monde, et ne nécessitent aucune clé API
 * ni aucun {@code User} propriétaire.
 */
public interface MarketDataApiClient {

    MarketDataSource getSource();

    /**
     * Récupère les bougies {@code symbol}/{@code timeFrame} sur la plage {@code [since, until]}
     * (au plus {@code limit} éléments).
     * <p>
     * Contrat d'erreur (vérifié par
     * {@code AbstractMarketDataApiClientContractTest}, pas par le compilateur — ces exceptions
     * sont unchecked, comme le comportement historique de cette méthode) :
     * <ul>
     *   <li>si le provider signale explicitement une erreur (symbole/paire inconnu chez lui), la
     *       méthode lève {@link SymbolNotFoundException} — cas permanent, ne pas réessayer ce
     *       provider pour cet asset ;</li>
     *   <li>si l'appel échoue pour une raison transitoire (réseau, timeout, rate limit, 5xx, ou
     *       toute autre erreur provider qui n'est pas un symbole invalide), la méthode lève
     *       {@link ProviderUnavailableException} ;</li>
     *   <li>la méthode ne renvoie une liste vide que lorsque l'appel a techniquement réussi et
     *       que le provider n'a légitimement aucune bougie à renvoyer sur la période demandée
     *       (ex: période antérieure au listing de l'actif) — jamais pour masquer une erreur.</li>
     * </ul>
     */
    List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit);
}
