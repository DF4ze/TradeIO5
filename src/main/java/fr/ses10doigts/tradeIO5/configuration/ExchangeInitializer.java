package fr.ses10doigts.tradeIO5.configuration;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.exchange.Exchange;
import fr.ses10doigts.tradeIO5.repository.ExchangeRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(30)
public class ExchangeInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeInitializer.class);

    private final ExchangeRepository exchangeRepository;
	// private final Environment environment;

    @Override
    public void run(String... args) {
		// if (!List.of(environment.getActiveProfiles()).contains("dev")) return;

        if (exchangeRepository.count() == 0) {
			// @formatter:off
            List<Exchange> exchanges = List.of(
	Exchange.builder().code("BINANCE")			.name("Binance")		.apiBaseUrl("https://api.binance.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build(),
	Exchange.builder().code("BINANCE_TESTNET")	.name("Binance TestNet").apiBaseUrl("https://testnet.binance.vision")	.enabled(false) .createdAt(LocalDateTime.now()).build(),
    Exchange.builder().code("KRAKEN")			.name("Kraken")			.apiBaseUrl("https://api.kraken.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build()
    //Exchange.builder().code("CRYPTOCOM")		.name("Crypto.com")		.apiBaseUrl("https://api.crypto.com")			.enabled(true)  .createdAt(LocalDateTime.now()).build()
            );
            // @formatter:on

            exchangeRepository.saveAll(exchanges);
            logger.info("🏦 Exchanges initialisés : {}", exchanges.stream().map(Exchange::getCode).toList());
        }else{
            logger.info("🏦 Exchanges DEJA initialisés !");
        }

    }
}
