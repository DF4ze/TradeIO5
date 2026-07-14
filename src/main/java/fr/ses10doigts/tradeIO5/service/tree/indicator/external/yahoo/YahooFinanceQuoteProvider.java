package fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;

import java.util.List;
import java.util.Map;

/**
 * Point d'entrée Yahoo Finance pour SP500/NASDAQ, source de secours gratuite/sans clé retenue
 * après confirmation (2026-07-15) que les tickers Twelve Data {@code SPX}/{@code IXIC} sont
 * verrouillés au palier payant (voir javadoc {@code Sp500Indicator}/{@code NasdaqIndicator}).
 * {@code DXY} reste sur {@code TwelveDataQuoteProvider} (déjà fonctionnel, formule à 6 paires
 * forex) — ce provider ne le couvre pas.
 */
public interface YahooFinanceQuoteProvider {

    /**
     * {@code GET /v8/finance/chart/{symbol}} — un appel par symbole (contrairement à Twelve Data
     * {@code /quote}, cet endpoint Yahoo ne supporte pas le multi-symbole en un seul appel).
     *
     * @return map symbole -&gt; quote, potentiellement partielle si certains symboles échouent
     * (entrée absente plutôt que valeur par défaut) ; map vide (jamais {@code null}) si tous les
     * appels échouent — jamais d'exception.
     */
    Map<String, YahooFinanceQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols);
}
