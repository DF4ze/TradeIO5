package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
		Optional<User> sysOpt = userRepository.findByUsername("System");
		Optional<WebProvider> wpBinanceTestNetOpt = providerRepository.findByCode(WebProviderCode.BINANCE_TESTNET);
		Optional<WebProvider> wpBinanceOpt = providerRepository.findByCode(WebProviderCode.BINANCE);
		Optional<WebProvider> wpKrakenOpt = providerRepository.findByCode(WebProviderCode.KRAKEN);
		Optional<WebProvider> wpCoinStatsOpt = providerRepository.findByCode(WebProviderCode.COINSTATS);
		Optional<WebProvider> wpDefiLlamaOpt = providerRepository.findByCode(WebProviderCode.DEFILLAMA);
		Optional<WebProvider> wpCoinalyzeOpt = providerRepository.findByCode(WebProviderCode.COINALYZE);
		Optional<WebProvider> wpTwelveDataOpt = providerRepository.findByCode(WebProviderCode.TWELVE_DATA);
		Optional<WebProvider> wpFinnhubOpt = providerRepository.findByCode(WebProviderCode.FINNHUB);
		Optional<WebProvider> wpForexFactoryOpt = providerRepository.findByCode(WebProviderCode.FOREXFACTORY);
		Optional<WebProvider> wpFarsideOpt = providerRepository.findByCode(WebProviderCode.FARSIDE);

		if (userOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : utilisateur manquant.");
			return;
		}

		if (sysOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : système manquant.");
			return;
		}

		if (wpBinanceTestNetOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Binance TestNet manquant.");
			return;
		}

		if (wpBinanceOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Binance manquant.");
			return;
		}

		if (wpKrakenOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Kraken manquant.");
			return;
		}

		if (wpCoinStatsOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider CoinStats manquant.");
			return;
		}

		if (wpDefiLlamaOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider DefiLlama manquant.");
			return;
		}

		if (wpCoinalyzeOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Coinalyze manquant.");
			return;
		}

		if (wpTwelveDataOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Twelve Data manquant.");
			return;
		}

		if (wpFinnhubOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Finnhub manquant.");
			return;
		}

		if (wpForexFactoryOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider ForexFactory manquant.");
			return;
		}

		if (wpFarsideOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Farside manquant.");
			return;
		}

        User user = userOpt.get();
		User sys = sysOpt.get();
		WebProvider webProviderBinanceTestnet = wpBinanceTestNetOpt.get();
		WebProvider webProviderBinance = wpBinanceOpt.get();
		WebProvider webProviderKraken = wpKrakenOpt.get();
		WebProvider webProviderCoinstats = wpCoinStatsOpt.get();
		WebProvider webProviderDefiLlama = wpDefiLlamaOpt.get();
		WebProvider webProviderCoinalyze = wpCoinalyzeOpt.get();
		WebProvider webProviderTwelveData = wpTwelveDataOpt.get();
		WebProvider webProviderFinnhub = wpFinnhubOpt.get();
		WebProvider webProviderForexFactory = wpForexFactoryOpt.get();
		WebProvider webProviderFarside = wpFarsideOpt.get();

		boolean alreadyExistsBTN = credentialRepository.findByUserAndWebProvider(user, webProviderBinanceTestnet).isPresent();
		boolean alreadyExistsBin = credentialRepository.findByUserAndWebProvider(user, webProviderBinance).isPresent();
		boolean alreadyExistsKraken = credentialRepository.findByUserAndWebProvider(user, webProviderKraken).isPresent();

		boolean alreadyExistsCoinstats = credentialRepository.findByUserAndWebProvider(sys, webProviderCoinstats).isPresent();
		boolean alreadyExistsDefiLlama = credentialRepository.findByUserAndWebProvider(sys, webProviderDefiLlama).isPresent();
		boolean alreadyExistsCoinalyze = credentialRepository.findByUserAndWebProvider(sys, webProviderCoinalyze).isPresent();
		boolean alreadyExistsTwelveData = credentialRepository.findByUserAndWebProvider(sys, webProviderTwelveData).isPresent();
		boolean alreadyExistsFinnhub = credentialRepository.findByUserAndWebProvider(sys, webProviderFinnhub).isPresent();
		boolean alreadyExistsForexFactory = credentialRepository.findByUserAndWebProvider(sys, webProviderForexFactory).isPresent();
		boolean alreadyExistsFarside = credentialRepository.findByUserAndWebProvider(sys, webProviderFarside).isPresent();

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
            logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderBinance.getName());
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
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderKraken.getName());
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

		if (alreadyExistsCoinstats) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderCoinstats.getName());
			// return;

		} else {
			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderCoinstats)
					.apiKey("v8ZVIcChU67x9vyMi1Ts0fzyNgwbetIHiNodpD4UonI=")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();
			//@formatter:on

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur COINSTATS");
		}

		if (alreadyExistsDefiLlama) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderDefiLlama.getName());
			// return;

		} else {
			// DefiLlama /stablecoins ne demande aucune clé API (endpoint public) : apiKey/secretKey
			// restent vides, seul le baseUrl porté par la credential résolue importe (cf.
			// AbstractExternalIndicator#getWebClient et étude "indicateurs-macro-externes" §7).
			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderDefiLlama)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();
			//@formatter:on

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur DEFILLAMA");
		}

		if (alreadyExistsCoinalyze) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderCoinalyze.getName());
			// return;

		} else {
			// Contrairement aux autres providers de cette méthode, la clé Coinalyze n'est PAS
			// committée en clair ici : elle est générée manuellement sur coinalyze.net/account/api-key/
			// (compte gratuit) et doit être fournie via application-dev.properties (gitignoré,
			// cf. mémoire projet "TradeIO5 secrets are gitignored") sous la clé
			// `tradeio.coinalyze.apiKey`. Si absente, on n'insère pas de credential : l'indicateur
			// retombera proprement en invalid() (cf. IndicatorCredentialResolver) plutôt que
			// d'utiliser une clé factice.
			String coinalyzeApiKey = environment.getProperty("tradeio.coinalyze.apiKey");

			if (coinalyzeApiKey == null || coinalyzeApiKey.isBlank()) {
				logger.warn("❗ `tradeio.coinalyze.apiKey` absente d'application-dev.properties : "
						+ "aucune credential COINALYZE créée pour System (OPEN_INTEREST/FUNDING_RATE/"
						+ "LIQUIDATIONS resteront invalid tant qu'elle n'est pas renseignée).");
			} else {
				//@formatter:off
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderCoinalyze)
						.apiKey(coinalyzeApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();
				//@formatter:on

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur COINALYZE");
			}
		}

		if (alreadyExistsTwelveData) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderTwelveData.getName());
			// return;

		} else {
			// Même principe que COINALYZE ci-dessus : clé générée manuellement sur twelvedata.com
			// (compte gratuit), jamais committée en clair. À renseigner via application-dev.properties
			// (gitignoré) sous `tradeio.twelvedata.apiKey`. Sans elle, DXY/SP500/NASDAQ resteront
			// invalid (cf. IndicatorCredentialResolver).
			String twelveDataApiKey = environment.getProperty("tradeio.twelvedata.apiKey");

			if (twelveDataApiKey == null || twelveDataApiKey.isBlank()) {
				logger.warn("❗ `tradeio.twelvedata.apiKey` absente d'application-dev.properties : "
						+ "aucune credential TWELVE_DATA créée pour System (DXY/SP500/NASDAQ resteront "
						+ "invalid tant qu'elle n'est pas renseignée).");
			} else {
				//@formatter:off
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderTwelveData)
						.apiKey(twelveDataApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();
				//@formatter:on

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur TWELVE_DATA");
			}
		}

		if (alreadyExistsFinnhub) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderFinnhub.getName());
			// return;

		} else {
			// Même principe : clé générée manuellement sur finnhub.io (compte gratuit), jamais
			// committée en clair. À renseigner via application-dev.properties (gitignoré) sous
			// `tradeio.finnhub.apiKey`. Sans elle, MacroEventCalendarService ne recevra aucun
			// événement Finnhub (ForexFactory reste disponible sans clé, voir ci-dessous).
			String finnhubApiKey = environment.getProperty("tradeio.finnhub.apiKey");

			if (finnhubApiKey == null || finnhubApiKey.isBlank()) {
				logger.warn("❗ `tradeio.finnhub.apiKey` absente d'application-dev.properties : "
						+ "aucune credential FINNHUB créée pour System (calendrier macro limité à "
						+ "ForexFactory tant qu'elle n'est pas renseignée).");
			} else {
				//@formatter:off
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderFinnhub)
						.apiKey(finnhubApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();
				//@formatter:on

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur FINNHUB");
			}
		}

		if (alreadyExistsForexFactory) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderForexFactory.getName());
			// return;

		} else {
			// ff_calendar_thisweek.json ne demande aucune clé API (endpoint public), même principe
			// que DEFILLAMA ci-dessus : apiKey vide, seul le baseUrl porté par la credential résolue
			// importe.
			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderForexFactory)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();
			//@formatter:on

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur FOREXFACTORY");
		}

		if (alreadyExistsFarside) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderFarside.getName());
			// return;

		} else {
			// farside.co.uk/btc(eth)/ ne demande aucune clé API (page publique scrapée), même
			// principe que DEFILLAMA/FOREXFACTORY ci-dessus : apiKey vide, seul le baseUrl porté
			// par la credential résolue importe (cf. AbstractExternalIndicator#getWebClient et
			// prompt d'implémentation Lot 3, item I).
			//@formatter:off
			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderFarside)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();
			//@formatter:on

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur FARSIDE");
		}

    }

}
