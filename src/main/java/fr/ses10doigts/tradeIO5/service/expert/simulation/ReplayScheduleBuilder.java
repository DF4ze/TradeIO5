package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeService;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.instrument.BookSlippage;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Construit le barème figé ({@link OrderReplay.Schedule}) d'un wallet avant la boucle de rejeu (état {@code LOADING_DATA}) : seule
 * partie du simulateur qui touche le réseau. Frais = cache global 24 h ({@link TradingFeeService}, estimation : valeur périmée
 * acceptée et signalée) ; spread et slippage = <b>un carnet public actuel par instrument</b>, lu une fois au lancement (estimation
 * signalée). La boucle de rejeu n'appelle jamais le réseau.
 */
@Slf4j
@Component
public class ReplayScheduleBuilder {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InstrumentCatalog catalog;
    private final TradingFeeService feeService;
    private final Map<WebProviderCode, OrderBookClient> bookClients;
    private final AssetGroupService groups;
    private final Set<String> forbiddenNodes;

    public ReplayScheduleBuilder(InstrumentCatalog catalog, TradingFeeService feeService, List<OrderBookClient> bookClients,
                                 AssetGroupService groups,
                                 @Value("${" + ExecutionDefaults.FORBIDDEN_NODES_PROPERTY + ":}") String forbiddenNodes) {
        this.catalog = catalog;
        this.feeService = feeService;
        this.bookClients = bookClients.stream().collect(Collectors.toMap(OrderBookClient::getProviderCode, Function.identity()));
        this.groups = groups;
        this.forbiddenNodes = ExecutionDefaults.forbiddenNodes(forbiddenNodes);
    }

    /**
     * @param assets    actifs des liens du wallet
     * @param refAmount notionnel de référence (USD) pour estimer le slippage (ex. plus grande base de lien)
     * @throws RuntimeException frais ou carnet indisponibles (rien à rejouer sans barème : jamais de coût supposé)
     */
    public OrderReplay.Schedule build(WebProviderCode provider, ApiCredential credential, Collection<String> assets,
                                      BigDecimal refAmount) {
        OrderBookClient books = bookClients.get(provider);
        if (books == null || !catalog.supports(provider)) {
            throw new IllegalStateException("Frais / carnet non pris en charge pour " + provider);
        }
        Set<String> stables = new HashSet<>(groups.members(AssetGroup.USD));
        List<InstrumentInfo> instruments = catalog.liveInstruments(credential.getWebProvider());
        Set<String> wanted = new HashSet<>(assets);
        wanted.addAll(ExecutionDefaults.CHOSEN_INTERMEDIARIES);
        List<InstrumentInfo> relevant = instruments.stream()
                .filter(i -> wanted.contains(i.base()) && !forbiddenNodes.contains(i.base()) && !forbiddenNodes.contains(i.quote())
                        && (stables.contains(i.quote()) || ExecutionDefaults.INTERMEDIARIES.contains(i.quote())))
                .toList();
        feeService.warm(provider, credential, relevant.stream().map(InstrumentInfo::instId).toList());

        List<String> warnings = new ArrayList<>();
        warnings.add("Frais actuels du compte appliqués à toute la période (barème figé).");
        warnings.add("Spread et slippage estimés sur un carnet public actuel (une lecture par instrument au lancement).");
        Set<String> stale = new java.util.TreeSet<>();
        TradingFeeService.Lookup probe;
        Map<String, OrderReplay.Quote> quotes = new HashMap<>();
        Map<String, BigDecimal> currentUsd = new HashMap<>();
        for (InstrumentInfo i : relevant) {
            probe = feeService.getFeeRateLenient(provider, credential, i.instId());
            if (probe.stale()) {
                stale.add(i.instId());
            }
            FeeRate fee = probe.rate();
            BookResult result = books.fetchBook(credential.getWebProvider(), i.instId(), ExecutionDefaults.BOOK_DEPTH);
            OrderBookSnapshot book = result.book();
            BigDecimal bid = book.bids().getFirst().price();
            BigDecimal ask = book.asks().getFirst().price();
            BigDecimal mid = bid.add(ask).divide(TWO, 18, RoundingMode.HALF_UP);
            BigDecimal halfSpread = ask.subtract(bid).multiply(HUNDRED).divide(ask.add(bid), 8, RoundingMode.HALF_UP);
            BigDecimal slippage = BigDecimal.ZERO;
            if (result.estimation() == PathEstimation.BOOK) {
                slippage = BookSlippage.slippagePct(book, LegSide.BUY, refAmount).orElseThrow(
                        () -> new IllegalStateException("Carnet " + i.instId() + " trop peu profond pour " + refAmount + " USD"));
            }
            quotes.put(i.instId(), new OrderReplay.Quote(fee.takerPct(), halfSpread, slippage));
            if (stables.contains(i.quote())) {
                currentUsd.putIfAbsent(i.base(), mid);
            }
        }
        if (!stale.isEmpty()) {
            warnings.add("Frais de plus de 24 h (exchange injoignable) sur " + String.join(", ", stale) + ".");
        }
        log.info("Barème de rejeu {} : {} instrument(s), {} avertissement(s)", provider, quotes.size(), warnings.size());
        return new OrderReplay.Schedule(relevant, stables, forbiddenNodes, quotes, currentUsd, List.copyOf(warnings));
    }
}
