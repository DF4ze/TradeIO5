package fr.ses10doigts.tradeIO5.configuration;

import java.time.LocalDateTime;
import java.util.List;

import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(30)
public class ExchangeInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeInitializer.class);

    private final ProviderRepository providerRepository;

    @Override
    public void run(String... args) {

        if (providerRepository.count() == 0) {
            List<Provider> providers = List.of(
	Provider.builder().code(ProviderCode.BINANCE)			.name("Binance")		.apiBaseUrl("https://api.binance.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build(),
	Provider.builder().code(ProviderCode.BINANCE_TESTNET)	.name("Binance TestNet").apiBaseUrl("https://testnet.binance.vision")	.enabled(false) .createdAt(LocalDateTime.now()).build(),
    Provider.builder().code(ProviderCode.KRAKEN)			.name("Kraken")			.apiBaseUrl("https://api.kraken.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build()
    //Exchange.builder().code("CRYPTOCOM")		.name("Crypto.com")		.apiBaseUrl("https://api.crypto.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build()
            );

            providerRepository.saveAll(providers);
            logger.info("🏦 Exchanges initialisés : {}", providers.stream().map(Provider::getCode).toList());
        }else{
            logger.info("🏦 Exchanges DEJA initialisés !");
        }

    }
}
