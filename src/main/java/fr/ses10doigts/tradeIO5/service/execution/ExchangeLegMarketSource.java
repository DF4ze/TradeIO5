package fr.ses10doigts.tradeIO5.service.execution;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.PathQuoteUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.instrument.BookSlippage;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Marché réel des jambes d'un chemin (lecture seule) : frais du compte (taker) + carnet public. Seules les paires cotées en
 * stable sont valorisées. Une instance vaut pour un calcul (un devis, un plan) : le carnet de chaque instrument n'est lu
 * qu'une fois par instance (le slippage est recalculé localement pour chaque montant).
 */
@Slf4j
public final class ExchangeLegMarketSource implements PathFinder.LegMarketSource {

    private final WebProviderCode provider;
    private final ApiCredential credential;
    private final Set<String> stables;
    private final TradingFeeProvider feeProvider;
    private final OrderBookClient bookClient;
    private final Map<String, BookResult> books = new HashMap<>();

    /** {@code feeProvider} ou {@code bookClient} nul : exchange non pris en charge (échec au premier appel de marché). */
    public ExchangeLegMarketSource(WebProviderCode provider, ApiCredential credential, Set<String> stables,
                                   TradingFeeProvider feeProvider, OrderBookClient bookClient) {
        this.provider = provider;
        this.credential = credential;
        this.stables = stables;
        this.feeProvider = feeProvider;
        this.bookClient = bookClient;
    }

    @Override
    public boolean accepts(InstrumentInfo instrument, LegSide side) {
        return stables.contains(instrument.quote());
    }

    @Override
    public Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notional) {
        if (feeProvider == null || bookClient == null) {
            throw new PathQuoteUnavailableException("Frais ou carnet non pris en charge pour " + provider);
        }
        FeeRate fee = feeProvider.getFeeRate(credential, instrument.instId());
        BookResult result = books.computeIfAbsent(instrument.instId(),
                id -> bookClient.fetchBook(credential.getWebProvider(), id, ExecutionDefaults.BOOK_DEPTH));
        BigDecimal bid = result.book().bids().getFirst().price();
        BigDecimal ask = result.book().asks().getFirst().price();
        BigDecimal slippage = BigDecimal.ZERO;
        if (result.estimation() == PathEstimation.BOOK) {
            Optional<BigDecimal> computed = BookSlippage.slippagePct(result.book(), side, notional);
            if (computed.isEmpty()) {
                log.warn("Carnet {} trop peu profond pour {} : arête écartée", instrument.instId(), notional);
                return Optional.empty();
            }
            slippage = computed.get();
        }
        return Optional.of(new LegMarket(fee.takerPct(), bid, ask, slippage, result.estimation()));
    }
}
