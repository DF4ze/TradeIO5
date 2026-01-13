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
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

@Component
@RequiredArgsConstructor
@Order(40)
public class ApiCredentialInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ApiCredentialInitializer.class);

    private final UserRepository userRepository;
    private final ProviderRepository providerRepository;
    private final ApiCredentialRepository credentialRepository;
    private final Environment environment;

    @Override
    public void run(String... args) {
        if (!List.of(environment.getActiveProfiles()).contains("dev")) return;

        Optional<User> userOpt = userRepository.findByUsername("OKlm");
		Optional<WebProvider> exchangeBinanceTestNetOpt = providerRepository.findByCode(WebProviderCode.BINANCE_TESTNET);
		Optional<WebProvider> exchangeBinanceOpt = providerRepository.findByCode(WebProviderCode.BINANCE);
		Optional<WebProvider> exchangeKrakenOpt = providerRepository.findByCode(WebProviderCode.KRAKEN);

		if (userOpt.isEmpty() || exchangeBinanceTestNetOpt.isEmpty() || exchangeBinanceOpt.isEmpty() || exchangeKrakenOpt.isEmpty()) {
            logger.warn("❗ Impossible d’ajouter la clé API : utilisateur ou exchange manquant.");
            return;
        }

        User user = userOpt.get();
		WebProvider webProviderBinanceTestnet = exchangeBinanceTestNetOpt.get();
		WebProvider webProviderBinance = exchangeBinanceOpt.get();
		WebProvider webProviderKraken = exchangeKrakenOpt.get();

		boolean alreadyExistsBTN = credentialRepository.findByUserAndWebProvider(user, webProviderBinanceTestnet).isPresent();
		boolean alreadyExistsBin = credentialRepository.findByUserAndWebProvider(user, webProviderBinance).isPresent();
		boolean alreadyExistsKraken = credentialRepository.findByUserAndWebProvider(user, webProviderKraken).isPresent();

		if (alreadyExistsBTN) {
			logger.debug("🔑 Clé API " + webProviderBinanceTestnet.getName() + " déjà présente pour l'utilisateur OKlm.");
			// return;

		} else {

			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(user)
					.webProvider(webProviderBinanceTestnet)
					.apiKey("xzXEX3KAL07YwrMny63DU4pOIrnqDNObNvfhHlJJ0vUSW1O8w58Kt4gR1HYjVXqi")
					.secretKey("LQ91SkNO6GBjpRC7PglZDutRQEDuf55aKTqa5kjQiGmqoKuEMZ0oFPBhQMDkB7dt")
					.enabled(false)
					.createdAt(LocalDateTime.now())
					.build();

			//@formatter:on

			credentialRepository.save(credential);

			logger.debug("- Clé API ajoutée pour OKlm sur BINANCE_TESTNET");
		}

		if (alreadyExistsBin) {
            logger.debug("\uD83D\uDD11 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderBinance.getName());
			// return;

		} else {
			ApiCredential credential = ApiCredential.builder()
					.user(user)
					.webProvider(webProviderBinance)
					.apiKey("ookiVIjsqami8rrYSHsGWZiRxjBxjGsoVwtb9hPTz01FiB2Q2oKeUvQeNGmaP5A3")
					.secretKey("tyPupQa10QibqIXddoMscZVtsEPeITReslLDJDio7ghtKFkJl7x6b2zZ8p5DSU0O")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour OKlm sur BINANCE");
        }

		if (alreadyExistsKraken) {
            logger.debug("\uD83D\uDD11 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderKraken.getName());
			// return;

		} else {
			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(user)
					.webProvider(webProviderKraken)
					.apiKey("rG/lccLLc0K7sbVorYcDexoSLWC9z140YyVzU2tncG/GkpHJjwF2d0pn")
					.secretKey("jX+uMxkb9/tTypQiqgD+m4lXtE+lXZF0X5SkAsqPJGkyalIr71nWURPJ/aaNU0ev1ZyHu3HPk2jd6Td5yKQw7g==")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();
			//@formatter:on

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour OKlm sur KRAKEN");
		}


		if (!alreadyExistsBin || !alreadyExistsBTN)
			logger.info("🔐 Clé.s API ajoutée.s pour utilisateur OKlm sur BINANCE");
    }

	// Kraken :
	// api : rG/lccLLc0K7sbVorYcDexoSLWC9z140YyVzU2tncG/GkpHJjwF2d0pn
	// secret : jX+uMxkb9/tTypQiqgD+m4lXtE+lXZF0X5SkAsqPJGkyalIr71nWURPJ/aaNU0ev1ZyHu3HPk2jd6Td5yKQw7g==
}
