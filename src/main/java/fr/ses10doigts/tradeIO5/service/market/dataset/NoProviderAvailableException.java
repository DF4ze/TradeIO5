package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import lombok.Getter;

/**
 * Exception d'orchestration levée par {@link MarketDatasetEngine#getDatasetForAsset} quand aucun
 * provider n'a pu fournir de dataset pour un asset — par opposition à
 * {@link MarketDataProviderException} (et ses sous-classes), qui sont des exceptions "par
 * provider" levées par un {@code MarketDataApiClient} individuel. Cf.
 * docs/etude-fallback-multi-provider-marketdata.md §3 (étape 7).
 * <p>
 * Deux cas distincts, distingués dans le message (pas par un type dédié) :
 * <ul>
 *     <li>aucun provider configuré pour cet asset (liste {@code asset_provider} vide, ou aucun
 *     candidat ne couvre l'horizon demandé) — {@link #getLastError()} est {@code null} ;</li>
 *     <li>tous les providers éligibles ont échoué (chaque tentative a levé une
 *     {@link MarketDataProviderException}) — {@link #getLastError()} porte la dernière erreur
 *     rencontrée.</li>
 * </ul>
 */
@Getter
public class NoProviderAvailableException extends RuntimeException {

    private final String symbol;
    private final MarketDataProviderException lastError;

    public NoProviderAvailableException(String symbol, MarketDataProviderException lastError) {
        super(buildMessage(symbol, lastError), lastError);
        this.symbol = symbol;
        this.lastError = lastError;
    }

    private static String buildMessage(String symbol, MarketDataProviderException lastError) {
        if (lastError == null) {
            return "Aucun provider (asset_provider) configuré ou éligible (horizon demandé trop "
                    + "large) pour l'asset " + symbol + ".";
        }
        return "Tous les providers éligibles ont échoué pour l'asset " + symbol
                + ". Dernière erreur (" + lastError.getSource() + ") : " + lastError.getMessage();
    }

}
