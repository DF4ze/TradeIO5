package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Order(30)
public class WebProviderInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(WebProviderInitializer.class);

    private final ProviderRepository providerRepository;

    @Override
    public void run(String... args) {

        for (WebProvider wp : List.of(
                WebProvider.builder()
                        .code(WebProviderCode.BINANCE)
                        .name("Binance")
                        .apiBaseUrl("https://api.binance.com")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.BINANCE_TESTNET)
                        .name("Binance TestNet")
                        .apiBaseUrl("https://testnet.binance.vision")
                        .enabled(false)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.KRAKEN)
                        .name("Kraken")
                        .apiBaseUrl("https://api.kraken.com")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.COINSTATS)
                        .name("CoinStats")
                        .apiBaseUrl("https://openapiv1.coinstats.app")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.DEFILLAMA)
                        .name("DefiLlama")
                        .apiBaseUrl("https://stablecoins.llama.fi")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.COINALYZE)
                        .name("Coinalyze")
                        .apiBaseUrl("https://api.coinalyze.net/v1")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.TWELVE_DATA)
                        .name("Twelve Data")
                        .apiBaseUrl("https://api.twelvedata.com")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.FINNHUB)
                        .name("Finnhub")
                        .apiBaseUrl("https://finnhub.io/api/v1")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.FOREXFACTORY)
                        .name("ForexFactory")
                        .apiBaseUrl("https://nfs.faireconomy.media")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                WebProvider.builder()
                        .code(WebProviderCode.FARSIDE)
                        .name("Farside")
                        .apiBaseUrl("https://farside.co.uk")
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build()
        )) {
            providerRepository.findByCode(wp.getCode())
                    .or(() -> {
                        providerRepository.save(wp);
                        logger.info("🏦 WebProvider initialisé : {}", wp.getCode());
                        return Optional.of(wp);
                    });
        }
        logger.info("🏦 Tous les WebProvider sont initialisés.");
    }
}
