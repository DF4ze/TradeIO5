package fr.ses10doigts.tradeIO5.configuration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.Exchange;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ExchangeRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(40)
public class ApiCredentialInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ApiCredentialInitializer.class);

    private final UserRepository userRepository;
    private final ExchangeRepository exchangeRepository;
    private final ApiCredentialRepository credentialRepository;
    private final Environment environment;

    @Override
    public void run(String... args) {
        if (!List.of(environment.getActiveProfiles()).contains("dev")) return;

        Optional<User> userOpt = userRepository.findByUsername("OKlm");
		Optional<Exchange> exchangeBinanceTestNetOpt = exchangeRepository.findByCodeIgnoreCase("BINANCE_TESTNET");
		Optional<Exchange> exchangeBinanceOpt = exchangeRepository.findByCodeIgnoreCase("BINANCE");

		if (userOpt.isEmpty() || exchangeBinanceTestNetOpt.isEmpty() || exchangeBinanceOpt.isEmpty()) {
            logger.warn("❗ Impossible d’ajouter la clé API : utilisateur ou exchange manquant.");
            return;
        }

        User user = userOpt.get();
		Exchange exchangeBinanceTestnet = exchangeBinanceTestNetOpt.get();
		Exchange exchangeBinance = exchangeBinanceOpt.get();

		boolean alreadyExistsBTN = credentialRepository.findByUserAndExchange(user, exchangeBinanceTestnet).isPresent();
		boolean alreadyExistsBin = credentialRepository.findByUserAndExchange(user, exchangeBinance).isPresent();

		if (alreadyExistsBTN) {
			logger.debug("🔑 Clé API " + exchangeBinanceTestnet.getName() + " déjà présente pour l'utilisateur OKlm.");
			// return;

		} else {

			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(user)
					.exchange(exchangeBinanceTestnet)
					.apiKey("xzXEX3KAL07YwrMny63DU4pOIrnqDNObNvfhHlJJ0vUSW1O8w58Kt4gR1HYjVXqi")
					.secretKey("LQ91SkNO6GBjpRC7PglZDutRQEDuf55aKTqa5kjQiGmqoKuEMZ0oFPBhQMDkB7dt")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

			//@formatter:on

			credentialRepository.save(credential);

			logger.debug("- Clé API ajoutée pour OKlm sur BINANCE_TESTNET");
		}

		if (alreadyExistsBin) {
			logger.debug("🔑 Clé API " + exchangeBinance.getName() + " déjà présente pour l'utilisateur OKlm.");
			// return;

		} else {
			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(user)
					.exchange(exchangeBinance)
					.apiKey("ookiVIjsqami8rrYSHsGWZiRxjBxjGsoVwtb9hPTz01FiB2Q2oKeUvQeNGmaP5A3")
					.secretKey("tyPupQa10QibqIXddoMscZVtsEPeITReslLDJDio7ghtKFkJl7x6b2zZ8p5DSU0O")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();
			//@formatter:on

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour OKlm sur BINANCE");
        }

		if (!alreadyExistsBin || !alreadyExistsBTN)
			logger.info("🔐 Clé.s API ajoutée.s pour utilisateur OKlm sur BINANCE");
    }
}
