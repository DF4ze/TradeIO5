package fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo;

/**
 * Résultat "propre" d'une consultation {@code GET /v8/finance/chart/{symbol}} Yahoo Finance,
 * source de secours gratuite/sans clé pour SP500/NASDAQ après confirmation que ces deux tickers
 * (SPX/IXIC) sont verrouillés au palier payant Twelve Data (cf. investigation Cowork du
 * 2026-07-15). Champs vérifiés contre un appel réel (contrairement à {@code TwelveDataQuote} au
 * moment de son écriture) :
 * <pre>
 * {"chart":{"result":[{"meta":{"regularMarketPrice":7543.59,"regularMarketTime":1784062596,...}}]}}
 * </pre>
 * {@code timestampEpochSeconds} (mappé depuis {@code meta.regularMarketTime}) porte la même
 * fraîcheur que {@code TwelveDataQuote#timestampEpochSeconds} (indices actions non 24/7) — exposé
 * de la même façon par les indicateurs consommateurs sous {@code Sp500Indicator.V_LAST_TRADE_TIME}.
 * Pas d'équivalent {@code marketOpen} : ni {@code Sp500Indicator} ni {@code NasdaqIndicator} ne
 * l'exploitaient déjà côté Twelve Data, inutile de le porter ici.
 */
public record YahooFinanceQuote(
        double price,
        Long timestampEpochSeconds
) {
}
