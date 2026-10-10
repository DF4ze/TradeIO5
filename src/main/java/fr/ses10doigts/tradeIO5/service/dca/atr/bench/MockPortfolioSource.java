package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Portefeuille fictif du preset (toujours disponible). */
@Component
@RequiredArgsConstructor
public class MockPortfolioSource implements RainbowPortfolioSource {

    private final RainbowLiveMockWalletRepository walletRepository;
    private final DomainClock clock;

    @Override
    public PortfolioReading read(RainbowLivePreset preset) {
        RainbowLiveMockWallet wallet = walletRepository.findByPreset(preset)
                .orElseThrow(() -> new IllegalStateException("Wallet mock introuvable pour le preset " + preset.getId()));
        return new PortfolioReading(wallet.cash(), Map.of(preset.getAssetSymbol(), wallet.quantity(preset.getAssetSymbol())),
                clock.now(), PortfolioStatus.OK, null);
    }
}
