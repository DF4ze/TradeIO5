package fr.ses10doigts.tradeIO5.configuration;

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

@Component
@RequiredArgsConstructor
@Order(30)
public class WebProviderInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(WebProviderInitializer.class);

    private final ProviderRepository providerRepository;

    @Override
    public void run(String... args) {

        if (providerRepository.count() == 0) {
            List<WebProvider> webProviders = List.of(
	WebProvider.builder().code(WebProviderCode.BINANCE)			.name("Binance")		.apiBaseUrl("https://api.binance.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build(),
	WebProvider.builder().code(WebProviderCode.BINANCE_TESTNET)	.name("Binance TestNet").apiBaseUrl("https://testnet.binance.vision")	.enabled(false) .createdAt(LocalDateTime.now()).build(),
    WebProvider.builder().code(WebProviderCode.KRAKEN)			.name("Kraken")			.apiBaseUrl("https://api.kraken.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build(),
    WebProvider.builder().code(WebProviderCode.COINSTATS)		.name("CoinStats")		.apiBaseUrl("https://openapiv1.coinstats.app")	.enabled(true)  .createdAt(LocalDateTime.now()).build()
    //Exchange.builder().code("CRYPTOCOM")		.name("Crypto.com")		.apiBaseUrl("https://api.crypto.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build()
            );

            providerRepository.saveAll(webProviders);
            logger.info("🏦 WebProviders initialisés : {}", webProviders.stream().map(WebProvider::getCode).toList());
        }else{
            logger.info("🏦 WebProviders DEJA initialisés !");
        }

    }
}
