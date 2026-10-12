package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.execution.TransitStock;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.execution.TransitStockRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Portefeuille réel en LECTURE SEULE d'un lien (binding) : {@link BindingCheck} (clé valide, soldes lisibles, paire
 * {@code <actif>/<membre USD>}) puis soldes disponibles du wallet lié. Seul bean du bench à toucher l'exchange. Périmètre : BTC,
 * ETH, PAXG et les membres du groupe USD (USDC, cash sommé) ; tout autre solde est ignoré. La position d'un actif =
 * solde réel du wallet - stock en transit actif de la même devise. Panne, clé rejetée, binding KO ou lecture sans aucun solde du périmètre
 * ⇒ {@code UNAVAILABLE} (jamais un 0 silencieux).
 */
@Slf4j
@Component
public class RealPortfolioSource implements RainbowPortfolioSource {

    private final RainbowLiveBindingRepository bindingRepository;
    private final BindingCheck bindingCheck;
    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers;
    private final AssetGroupService groupService;
    private final TransitStockRepository transitRepository;
    private final DomainClock clock;

    public RealPortfolioSource(RainbowLiveBindingRepository bindingRepository, BindingCheck bindingCheck,
                               List<ReadOnlyBalanceReader> readers, AssetGroupService groupService,
                               TransitStockRepository transitRepository, DomainClock clock) {
        this.bindingRepository = bindingRepository;
        this.bindingCheck = bindingCheck;
        this.readers = readers.stream().collect(Collectors.toMap(ReadOnlyBalanceReader::getProviderCode, Function.identity()));
        this.groupService = groupService;
        this.transitRepository = transitRepository;
        this.clock = clock;
    }

    @Override
    public List<LiveSlot> liveSlots(User user) {
        return bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream()
                .map(b -> new LiveSlot(b.getId(), b.getPreset().getId(), b.getAssetSymbol(), b.getWallet().getId(),
                        b.getPriority(), b.getBagPercent(), LiveCandleSource.of(b.getWallet())))
                .toList();
    }

    /** Lecture du portefeuille réel du lien. Ne lève pas pour une panne exchange : statut {@code UNAVAILABLE}. */
    public PortfolioReading read(RainbowLiveBinding binding) {
        String asset = binding.getAssetSymbol();
        Wallet wallet = binding.getWallet();
        Instant now = clock.now();

        BindingCheckResult check = bindingCheck.check(binding);
        binding.recordCheck(check, now);
        bindingRepository.save(binding);
        if (check.isBlocked()) {
            log.warn("Lecture live bloquée binding={} actif={} wallet={} : {}", binding.getId(), asset,
                    wallet.getId(), check.message());
            return PortfolioReading.notTradable(wallet.getId(), now);
        }
        if (!check.isOk()) {
            log.warn("Lecture live indisponible binding={} actif={} wallet={} : {} ({})", binding.getId(),
                    asset, wallet.getId(), check.status(), check.message());
            return PortfolioReading.unavailable(wallet.getId(), now);
        }
        Map<String, BigDecimal> balances;
        try {
            balances = readers.get(wallet.getWebProviderCode()).getAvailableBalances(wallet.getCredential());
        } catch (BalanceUnavailableException e) {
            log.warn("Lecture live indisponible binding={} actif={} wallet={} : {}", binding.getId(), asset,
                    wallet.getId(), e.getMessage());
            return PortfolioReading.unavailable(wallet.getId(), now);
        }

        // position d'un lien = solde réel du wallet - stock en transit de la même devise (intermédiaire d'un achat en cours)
        Map<String, BigDecimal> transit = transitRepository.findByWalletIdAndResolvedAtIsNull(wallet.getId()).stream()
                .collect(Collectors.toMap(TransitStock::getCurrency, TransitStock::getQuantity, BigDecimal::add));
        Map<String, Double> positions = new HashMap<>();
        for (String symbol : RainbowLiveDefaultPresets.ASSETS) {
            BigDecimal qty = balances.get(symbol);
            if (qty != null) {
                positions.put(symbol, qty.subtract(transit.getOrDefault(symbol, BigDecimal.ZERO)).max(BigDecimal.ZERO).doubleValue());
            }
        }
        Map<String, Double> cashByMember = groupService.byMember(AssetGroup.USD, balances);
        boolean cashPresent = groupService.members(AssetGroup.USD).stream().anyMatch(balances::containsKey);
        if (positions.isEmpty() && !cashPresent) {
            log.warn("Lecture live vide suspecte binding={} actif={} wallet={} : aucun solde BTC/ETH/PAXG/USD, ignorée",
                    binding.getId(), asset, wallet.getId());
            return PortfolioReading.unavailable(wallet.getId(), now);
        }
        double cash = cashByMember.values().stream().mapToDouble(Double::doubleValue).sum();
        PortfolioReading reading = new PortfolioReading(cash, cashByMember, positions, now, PortfolioStatus.OK,
                wallet.getId());
        log.debug("Lecture live binding={} actif={} wallet={} cash={} position={}", binding.getId(), asset,
                wallet.getId(), reading.cash(), reading.quantity(asset));
        return reading;
    }
}
