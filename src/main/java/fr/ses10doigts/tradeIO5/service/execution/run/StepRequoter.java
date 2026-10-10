package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.execution.ExchangeLegMarketSource;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Re-devis d'une jambe juste avant son envoi : frais réels du compte + carnet public lu <b>à neuf</b> (une instance de
 * {@link ExchangeLegMarketSource} par appel), via la clé Read du wallet. Lecture seule, aucun ordre.
 */
@Component
public class StepRequoter {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** Coût de la jambe (%) et mid du carnet au moment du devis. */
    public record Requote(BigDecimal costPct, BigDecimal mid) {
    }

    private final Map<WebProviderCode, TradingFeeProvider> feeProviders;
    private final Map<WebProviderCode, OrderBookClient> bookClients;
    private final AssetGroupService groups;

    public StepRequoter(List<TradingFeeProvider> feeProviders, List<OrderBookClient> bookClients, AssetGroupService groups) {
        this.feeProviders = feeProviders.stream().collect(Collectors.toMap(TradingFeeProvider::getProviderCode, Function.identity()));
        this.bookClients = bookClients.stream().collect(Collectors.toMap(OrderBookClient::getProviderCode, Function.identity()));
        this.groups = groups;
    }

    /**
     * @param sz taille envoyée (base)
     * @param notional montant de la jambe en cotation (profondeur de carnet demandée)
     * @throws IllegalStateException carnet trop peu profond
     * @throws RuntimeException frais ou carnet indisponibles
     */
    public Requote requote(WebProviderCode provider, ApiCredential readCredential, InstrumentInfo instrument, StepSide side,
                           BigDecimal sz, BigDecimal notional) {
        ExchangeLegMarketSource source = new ExchangeLegMarketSource(provider, readCredential,
                new HashSet<>(groups.members(AssetGroup.USD)), feeProviders.get(provider), bookClients.get(provider));
        LegSide legSide = side == StepSide.BUY ? LegSide.BUY : LegSide.SELL;
        LegMarket market = source.market(instrument, legSide, notional)
                .orElseThrow(() -> new IllegalStateException("Carnet trop peu profond pour " + instrument.instId()));
        PathLeg leg = PathFinder.legOfSize(instrument, legSide, market, sz);
        BigDecimal mid = market.bid().add(market.ask()).divide(TWO, 18, RoundingMode.HALF_UP);
        return new Requote(leg.costPct(), mid);
    }
}
