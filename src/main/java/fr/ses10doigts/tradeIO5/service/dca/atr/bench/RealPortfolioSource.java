package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
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
 * Portefeuille réel en LECTURE SEULE d'un preset live : {@link BindingCheck} (clé valide, soldes lisibles, paire
 * {@code <actif>/USDC}) puis soldes disponibles du wallet lié. Seul bean du bench à toucher l'exchange. Périmètre : BTC,
 * ETH, PAXG et USDC ; tout autre solde est ignoré. Panne, clé rejetée, binding KO ou lecture sans aucun solde du périmètre
 * ⇒ {@code UNAVAILABLE} (jamais un 0 silencieux).
 */
@Slf4j
@Component
public class RealPortfolioSource implements RainbowPortfolioSource {

    private final RainbowLiveBindingRepository bindingRepository;
    private final BindingCheck bindingCheck;
    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers;
    private final DomainClock clock;
    private final Duration staleAfter;

    public RealPortfolioSource(RainbowLiveBindingRepository bindingRepository, BindingCheck bindingCheck,
                               List<ReadOnlyBalanceReader> readers, DomainClock clock,
                               @Value("${" + RainbowLiveDefaultPresets.READING_STALE_AFTER_PROPERTY + ":"
                                       + RainbowLiveDefaultPresets.DEFAULT_READING_STALE_AFTER + "}") Duration staleAfter) {
        this.bindingRepository = bindingRepository;
        this.bindingCheck = bindingCheck;
        this.readers = readers.stream().collect(Collectors.toMap(ReadOnlyBalanceReader::getProviderCode, Function.identity()));
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @Override
    public List<LiveSlot> liveSlots(User user) {
        return bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream()
                .map(b -> new LiveSlot(b.getPreset().getId(), b.getAssetSymbol(), b.getWallet().getId(), b.getPriority(),
                        b.getBagPercent()))
                .toList();
    }

    @Override
    public PortfolioReading checkFreshness(PortfolioReading reading) {
        return reading.checkedAt(clock.now(), staleAfter);
    }

    @Override
    public PortfolioReading read(RainbowLivePreset preset) {
        RainbowLiveBinding binding = bindingRepository.findByUserAndAssetSymbol(preset.getUser(), preset.getAssetSymbol())
                .filter(b -> b.getPreset().getId().equals(preset.getId()))
                .orElseThrow(() -> new IllegalStateException("Aucun binding live pour le preset " + preset.getId()));
        Wallet wallet = binding.getWallet();
        Instant now = clock.now();

        BindingCheckResult check = bindingCheck.check(binding);
        if (!check.isOk()) {
            log.warn("Lecture live indisponible preset={} actif={} wallet={} : {} ({})", preset.getId(),
                    preset.getAssetSymbol(), wallet.getId(), check.status(), check.message());
            return PortfolioReading.unavailable(wallet.getId(), now);
        }
        Map<String, BigDecimal> balances;
        try {
            balances = readers.get(wallet.getWebProviderCode()).getAvailableBalances(wallet.getCredential());
        } catch (BalanceUnavailableException e) {
            log.warn("Lecture live indisponible preset={} actif={} wallet={} : {}", preset.getId(), preset.getAssetSymbol(),
                    wallet.getId(), e.getMessage());
            return PortfolioReading.unavailable(wallet.getId(), now);
        }

        Map<String, Double> positions = new HashMap<>();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            BigDecimal qty = balances.get(asset);
            if (qty != null) {
                positions.put(asset, qty.doubleValue());
            }
        }
        BigDecimal usdc = balances.get(RainbowLiveDefaultPresets.STABLECOIN);
        if (positions.isEmpty() && usdc == null) {
            log.warn("Lecture live vide suspecte preset={} actif={} wallet={} : aucun solde BTC/ETH/PAXG/USDC, ignorée",
                    preset.getId(), preset.getAssetSymbol(), wallet.getId());
            return PortfolioReading.unavailable(wallet.getId(), now);
        }
        PortfolioReading reading = new PortfolioReading(usdc == null ? 0.0 : usdc.doubleValue(), positions, now,
                PortfolioStatus.OK, wallet.getId());
        log.debug("Lecture live preset={} actif={} wallet={} cash={} position={}", preset.getId(), preset.getAssetSymbol(),
                wallet.getId(), reading.cash(), reading.quantity(preset.getAssetSymbol()));
        return reading;
    }
}
