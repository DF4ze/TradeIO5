package fr.ses10doigts.tradeIO5.configuration;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.ProfileChecker;
import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(1)
public class AssetInitializer implements CommandLineRunner {

	private static final Logger logger = LoggerFactory.getLogger(ProfileChecker.class);

    private final AssetRepository assetRepository;

    @Override
    public void run(String... args) {
        if (assetRepository.count() == 0) {
            assetRepository.saveAll(List.of(
					Asset.builder().symbol("BTC").name("Bitcoin").decimals(8).build(),
					Asset.builder().symbol("ETH").name("Ethereum").decimals(18).build(),
					Asset.builder().symbol("SOL").name("Solana").decimals(9).build(),
					Asset.builder().symbol("BNB").name("Binance").decimals(18).build(),
					Asset.builder().symbol("XRP").name("Ripple").decimals(6).build(),
					Asset.builder().symbol("USDT").name("USD Tether").decimals(6).build(),
					Asset.builder().symbol("USDC").name("USD Circle").decimals(6).build()
                // Ajoute ceux que tu veux
            ));
			logger.info("✅ Actifs initiaux insérés en BDD.");
        }
    }
}