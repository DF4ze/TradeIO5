package fr.ses10doigts.tradeIO5.service.execution;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
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
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Marché réel des jambes d'un chemin (lecture seule) : frais du compte (taker) + carnet public. Sont valorisées les paires
 * cotées en stable ou en intermédiaire autorisé ({@link ExecutionDefaults#INTERMEDIARIES}). Le notionnel reçu est en unités de
 * la cotation. Une instance vaut pour un calcul (un devis, un plan) : le carnet de chaque instrument n'est lu qu'une fois par
 * instance (le slippage est recalculé localement pour chaque montant). Prix USD d'une devise non stable = milieu du carnet de sa
 * paire stable (catalogue {@code instruments}).
 */
@Slf4j
public final class ExchangeLegMarketSource implements PathFinder.LegMarketSource {

    private final WebProviderCode provider;
    private final ApiCredential credential;
    private final Set<String> stables;
    private final List<InstrumentInfo> instruments;
    private final TradingFeeProvider feeProvider;
    private final OrderBookClient bookClient;
    private final Map<String, BookResult> books = new HashMap<>();
    private final Map<String, Optional<BigDecimal>> usdPrices = new HashMap<>();

    /** {@code feeProvider} ou {@code bookClient} nul : exchange non pris en charge (échec au premier appel de marché). */
    public ExchangeLegMarketSource(WebProviderCode provider, ApiCredential credential, Set<String> stables,
                                   List<InstrumentInfo> instruments, TradingFeeProvider feeProvider, OrderBookClient bookClient) {
        this.provider = provider;
        this.credential = credential;
        this.stables = stables;
        this.instruments = instruments;
        this.feeProvider = feeProvider;
        this.bookClient = bookClient;
    }

    /** Sans catalogue : seuls les prix des stables sont connus (re-devis d'une jambe). */
    public ExchangeLegMarketSource(WebProviderCode provider, ApiCredential credential, Set<String> stables,
                                   TradingFeeProvider feeProvider, OrderBookClient bookClient) {
        this(provider, credential, stables, List.of(), feeProvider, bookClient);
    }

    @Override
    public boolean accepts(InstrumentInfo instrument, LegSide side) {
        return stables.contains(instrument.quote()) || ExecutionDefaults.INTERMEDIARIES.contains(instrument.quote());
    }

    @Override
    public Optional<BigDecimal> usdPrice(String currency) {
        if (stables.contains(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        return usdPrices.computeIfAbsent(currency, c -> instruments.stream()
                .filter(i -> i.base().equals(c) && stables.contains(i.quote()))
                .min(Comparator.comparing(InstrumentInfo::instId))
                .map(i -> {
                    OrderBookSnapshot book = book(i.instId()).book();
                    return book.bids().getFirst().price().add(book.asks().getFirst().price())
                            .divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
                }));
    }

    @Override
    public Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notional) {
        if (feeProvider == null || bookClient == null) {
            throw new PathQuoteUnavailableException("Frais ou carnet non pris en charge pour " + provider);
        }
        FeeRate fee = feeProvider.getFeeRate(credential, instrument.instId());
        BookResult result = book(instrument.instId());
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

    private BookResult book(String instId) {
        if (bookClient == null) {
            throw new PathQuoteUnavailableException("Carnet non pris en charge pour " + provider);
        }
        return books.computeIfAbsent(instId, id -> bookClient.fetchBook(credential.getWebProvider(), id, ExecutionDefaults.BOOK_DEPTH));
    }
}
