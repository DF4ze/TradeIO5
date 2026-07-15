package fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo;

/**
 * Résultat "propre" d'une consultation {@code GET /v8/finance/chart/{symbol}} Yahoo Finance,
 * source de secours gratuite/sans clé pour SP500/NASDAQ après confirmation que ces deux tickers
 * (SPX/IXIC) sont verrouillés au palier payant Twelve Data (cf. investigation Cowork du
 * 2026-07-15). Champs vérifiés contre un appel réel (contrairement à {@code TwelveDataQuote} au
 * moment de son écriture) :
 * <pre>
 * {"chart":{"result":[{"meta":{"regularMarketPrice":7543.59,"regularMarketTime":1784062596,
 *                                "previousClose":7538.20,"chartPreviousClose":7538.20,...}}]}}
 * </pre>
 * {@code timestampEpochSeconds} (mappé depuis {@code meta.regularMarketTime}) porte la même
 * fraîcheur que {@code TwelveDataQuote#timestampEpochSeconds} (indices actions non 24/7) — exposé
 * de la même façon par les indicateurs consommateurs sous {@code Sp500Indicator.V_LAST_TRADE_TIME}.
 * Pas d'équivalent {@code marketOpen} : ni {@code Sp500Indicator} ni {@code NasdaqIndicator} ne
 * l'exploitaient déjà côté Twelve Data, inutile de le porter ici.
 * <p>
 * {@code previousClose} (étude "nouvelles-opinions-indicateurs-non-branches" §2.1, ajouté pour
 * {@code MacroMarketOpinion}) : mappé depuis {@code meta.previousClose}, avec repli sur
 * {@code meta.chartPreviousClose} si absent (les deux champs coexistent selon les symboles dans les
 * réponses Yahoo observées, {@code chartPreviousClose} étant le plus systématiquement présent) —
 * voir {@link YahooFinanceQuoteClient#parseQuote}. Peut être {@code null} si aucun des deux n'est
 * présent (dégradation propre, jamais une exception).
 */
public record YahooFinanceQuote(
        double price,
        Long timestampEpochSeconds,
        Double previousClose
) {
}
