package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.execution.ExchangeLegMarketSource;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Devis à la demande du chemin d'achat d'un binding (lecture seule, aucun ordre, aucun job, aucune écriture hors cache du
 * catalogue) : sources = soldes des membres du groupe USD du wallet, catalogue d'instruments en base, frais réels du compte
 * ({@code trade-fee}) et carnet public pour le spread/slippage au montant, puis {@link PathFinder} et Fee Test.
 * Binding d'un autre utilisateur => {@link RainbowLiveBindingNotFoundException} (404).
 */
@Slf4j
@Service
public class BindingPathQuoteService {

    /** Devis + avertissement textuel pour l'utilisateur (nul si GREEN et chemin complet). */
    public record Result(PathQuote quote, String warning) {
    }

    private final RainbowLiveBindingService bindingService;
    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers;
    private final Map<WebProviderCode, TradingFeeProvider> feeProviders;
    private final Map<WebProviderCode, OrderBookClient> bookClients;
    private final InstrumentCatalog catalog;
    private final AssetGroupService groupService;
    private final PathFinder pathFinder;
    private final FeeTest feeTest;
    private final Set<String> forbiddenNodes;

    public BindingPathQuoteService(RainbowLiveBindingService bindingService, List<ReadOnlyBalanceReader> readers,
                                   List<TradingFeeProvider> feeProviders, List<OrderBookClient> bookClients,
                                   InstrumentCatalog catalog, AssetGroupService groupService, PathFinder pathFinder,
                                   FeeTest feeTest,
                                   @Value("${" + ExecutionDefaults.FORBIDDEN_NODES_PROPERTY + ":}") String forbiddenNodes) {
        this.bindingService = bindingService;
        this.readers = readers.stream().collect(Collectors.toMap(ReadOnlyBalanceReader::getProviderCode, Function.identity()));
        this.feeProviders = feeProviders.stream().collect(Collectors.toMap(TradingFeeProvider::getProviderCode, Function.identity()));
        this.bookClients = bookClients.stream().collect(Collectors.toMap(OrderBookClient::getProviderCode, Function.identity()));
        this.catalog = catalog;
        this.groupService = groupService;
        this.pathFinder = pathFinder;
        this.feeTest = feeTest;
        this.forbiddenNodes = ExecutionDefaults.forbiddenNodes(forbiddenNodes);
    }

    /**
     * @param amount montant nominal à acquérir, en USD (strictement positif)
     * @param preferredQuote cotation à préférer pour la dernière jambe (ex. USDT), ou null
     * @throws IllegalArgumentException montant invalide
     * @throws PathQuoteUnavailableException wallet/credential inutilisable, frais ou carnet non pris en charge
     * @throws fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException clé rejetée
     * @throws fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException soldes ou frais illisibles
     * @throws fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException catalogue ou carnet indisponible
     */
    public Result quote(User user, Long bindingId, BigDecimal amount, String preferredQuote) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif");
        }
        RainbowLiveBinding binding = bindingService.get(user, bindingId);
        Wallet wallet = binding.getWallet();
        ApiCredential credential = wallet.getCredential();
        WebProviderCode provider = wallet.getWebProviderCode();
        if (!wallet.isEnabled() || credential == null || !credential.isEnabled()) {
            throw new PathQuoteUnavailableException("Wallet désactivé ou sans credential active");
        }
        ReadOnlyBalanceReader reader = provider == null ? null : readers.get(provider);
        if (reader == null || !catalog.supports(provider)) {
            throw new PathQuoteUnavailableException("Exchange non pris en charge : " + provider);
        }

        List<String> stables = groupService.members(AssetGroup.USD);
        Map<String, BigDecimal> balances = reader.getAvailableBalances(credential);
        Map<String, BigDecimal> sources = new HashMap<>();
        stables.forEach(s -> sources.put(s, balances.getOrDefault(s, BigDecimal.ZERO)));
        List<InstrumentInfo> instruments = catalog.liveInstruments(credential.getWebProvider());

        PathFinder.Request request = new PathFinder.Request(instruments, forbiddenNodes, sources, binding.getAssetSymbol(),
                amount, ExecutionDefaults.MAX_PATH_DEPTH, preferredQuote);
        PathQuote quote = pathFinder.find(request, new ExchangeLegMarketSource(provider, credential, new HashSet<>(stables),
                feeProviders.get(provider), bookClients.get(provider)));
        String warning = warning(quote, provider, sources, instruments);
        log.info("Devis chemin binding={} actif={} montant={} : {} coût={}% Fee Test={}", bindingId, binding.getAssetSymbol(),
                amount, quote.status(), quote.totalCostPct(), quote.feeTestLevel());
        return new Result(quote, warning);
    }

    private String warning(PathQuote quote, WebProviderCode provider, Map<String, BigDecimal> sources,
                           List<InstrumentInfo> instruments) {
        List<String> messages = new ArrayList<>();
        if (quote.status() == PathStatus.NO_PATH) {
            boolean funded = sources.values().stream().anyMatch(b -> b.signum() > 0);
            messages.add(funded
                    ? quote.target() + " non tradable sans monnaie fiat sur " + provider + " : exécution bloquée"
                    : "Aucun solde disponible dans le groupe " + AssetGroup.USD + " sur " + provider
                    + " : aucun chemin d'achat chiffrable");
            return String.join(" ; ", messages);
        }
        if (quote.status() == PathStatus.BELOW_MIN) {
            quote.legs().stream().filter(PathLeg::belowMin).forEach(l -> messages.add("Montant inférieur au minimum de l'exchange sur "
                    + l.instId() + " (minimum " + instruments.stream().filter(i -> i.instId().equals(l.instId()))
                    .findFirst().map(i -> i.minSz().stripTrailingZeros().toPlainString()).orElse("?") + ")"));
        }
        if (quote.feeTestLevel() == FeeTestLevel.WARNING) {
            messages.add("Coût du chemin " + pct(quote.totalCostPct()) + " % : au-dessus du seuil d'alerte (" + pct(feeTest.warnPct()) + " %)");
        } else if (quote.feeTestLevel() == FeeTestLevel.RED) {
            messages.add("Coût du chemin " + pct(quote.totalCostPct()) + " % : au-dessus du seuil rouge (" + pct(feeTest.redPct()) + " %), achat bloqué");
        }
        if (quote.estimation() == PathEstimation.TICKER) {
            messages.add("Estimation sur le meilleur bid/ask (carnet indisponible) : slippage non chiffré");
        }
        if (quote.underfunded()) {
            messages.add("Solde insuffisant sur " + quote.source() + " pour ce montant");
        }
        return messages.isEmpty() ? null : String.join(" ; ", messages);
    }

    private static String pct(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
