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
		Optional<User> userOpt = userRepository.findByUsername("OKlm");
		Optional<User> sysOpt = userRepository.findByUsername("System");
		Optional<WebProvider> wpBinanceTestNetOpt = providerRepository.findByCode(WebProviderCode.BINANCE_TESTNET);
		Optional<WebProvider> wpBinanceOpt = providerRepository.findByCode(WebProviderCode.BINANCE);
		Optional<WebProvider> wpKrakenOpt = providerRepository.findByCode(WebProviderCode.KRAKEN);
		Optional<WebProvider> wpOkxOpt = providerRepository.findByCode(WebProviderCode.OKX);
		Optional<WebProvider> wpCoinStatsOpt = providerRepository.findByCode(WebProviderCode.COINSTATS);
		Optional<WebProvider> wpDefiLlamaOpt = providerRepository.findByCode(WebProviderCode.DEFILLAMA);
		Optional<WebProvider> wpCoinalyzeOpt = providerRepository.findByCode(WebProviderCode.COINALYZE);
		Optional<WebProvider> wpTwelveDataOpt = providerRepository.findByCode(WebProviderCode.TWELVE_DATA);
		Optional<WebProvider> wpFinnhubOpt = providerRepository.findByCode(WebProviderCode.FINNHUB);
		Optional<WebProvider> wpForexFactoryOpt = providerRepository.findByCode(WebProviderCode.FOREXFACTORY);
		Optional<WebProvider> wpFarsideOpt = providerRepository.findByCode(WebProviderCode.FARSIDE);
		Optional<WebProvider> wpYoutubeOpt = providerRepository.findByCode(WebProviderCode.YOUTUBE);
		Optional<WebProvider> wpYahooFinanceOpt = providerRepository.findByCode(WebProviderCode.YAHOO_FINANCE);
		Optional<WebProvider> wpSosoValueOpt = providerRepository.findByCode(WebProviderCode.SOSOVALUE);

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

		if (wpOkxOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider OKX manquant.");
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

		if (wpYoutubeOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider YouTube manquant.");
			return;
		}

		if (wpYahooFinanceOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider Yahoo Finance manquant.");
			return;
		}

		if (wpSosoValueOpt.isEmpty()) {
			logger.warn("❗ Impossible d’ajouter la clé API : provider SoSoValue manquant.");
			return;
		}

        User user = userOpt.get();
		User sys = sysOpt.get();
		WebProvider webProviderBinanceTestnet = wpBinanceTestNetOpt.get();
		WebProvider webProviderBinance = wpBinanceOpt.get();
		WebProvider webProviderKraken = wpKrakenOpt.get();
		WebProvider webProviderOkx = wpOkxOpt.get();
		WebProvider webProviderCoinstats = wpCoinStatsOpt.get();
		WebProvider webProviderDefiLlama = wpDefiLlamaOpt.get();
		WebProvider webProviderCoinalyze = wpCoinalyzeOpt.get();
		WebProvider webProviderTwelveData = wpTwelveDataOpt.get();
		WebProvider webProviderFinnhub = wpFinnhubOpt.get();
		WebProvider webProviderForexFactory = wpForexFactoryOpt.get();
		WebProvider webProviderFarside = wpFarsideOpt.get();
		WebProvider webProviderYoutube = wpYoutubeOpt.get();
		WebProvider webProviderYahooFinance = wpYahooFinanceOpt.get();
		WebProvider webProviderSosoValue = wpSosoValueOpt.get();

		boolean alreadyExistsBTN = credentialRepository.findByUserAndWebProvider(user, webProviderBinanceTestnet).isPresent();
		boolean alreadyExistsBin = credentialRepository.findByUserAndWebProvider(user, webProviderBinance).isPresent();
		boolean alreadyExistsKraken = credentialRepository.findByUserAndWebProvider(user, webProviderKraken).isPresent();

		boolean alreadyExistsOkx = credentialRepository.findByUserAndWebProvider(user, webProviderOkx).isPresent();

		boolean alreadyExistsCoinstats = credentialRepository.findByUserAndWebProvider(sys, webProviderCoinstats).isPresent();
		boolean alreadyExistsDefiLlama = credentialRepository.findByUserAndWebProvider(sys, webProviderDefiLlama).isPresent();
		boolean alreadyExistsCoinalyze = credentialRepository.findByUserAndWebProvider(sys, webProviderCoinalyze).isPresent();
		boolean alreadyExistsTwelveData = credentialRepository.findByUserAndWebProvider(sys, webProviderTwelveData).isPresent();
		boolean alreadyExistsFinnhub = credentialRepository.findByUserAndWebProvider(sys, webProviderFinnhub).isPresent();
		boolean alreadyExistsForexFactory = credentialRepository.findByUserAndWebProvider(sys, webProviderForexFactory).isPresent();
		boolean alreadyExistsFarside = credentialRepository.findByUserAndWebProvider(sys, webProviderFarside).isPresent();
		boolean alreadyExistsYoutube = credentialRepository.findByUserAndWebProvider(sys, webProviderYoutube).isPresent();
		boolean alreadyExistsYahooFinance = credentialRepository.findByUserAndWebProvider(sys, webProviderYahooFinance).isPresent();
		boolean alreadyExistsSosoValue = credentialRepository.findByUserAndWebProvider(sys, webProviderSosoValue).isPresent();

		if (alreadyExistsBTN) {
			logger.debug("🔑 Clé API " + webProviderBinanceTestnet.getName() + " déjà présente pour l'utilisateur OKlm.");

		} else {
			ApiCredential credential = ApiCredential.builder()
					.user(user)
					.webProvider(webProviderBinanceTestnet)
					.apiKey("xzXEX3KAL07YwrMny63DU4pOIrnqDNObNvfhHlJJ0vUSW1O8w58Kt4gR1HYjVXqi")
					.secretKey("LQ91SkNO6GBjpRC7PglZDutRQEDuf55aKTqa5kjQiGmqoKuEMZ0oFPBhQMDkB7dt")
					.enabled(false)
					.createdAt(LocalDateTime.now())
					.build();

			credentialRepository.save(credential);

			logger.debug("- Clé API ajoutée pour OKlm sur BINANCE_TESTNET");
		}

		if (alreadyExistsBin) {
            logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderBinance.getName());

		} else {
			// Clé API-utilisateur (compte trading réel d'OKlm), lue dans les propriétés (gitignorées)
			// `tradeio.binance.apiKey` / `tradeio.binance.secretKey` ; absentes ⇒ aucune credential, à saisir en DB.
			seedUserExchangeCredential(user, webProviderBinance, "tradeio.binance", true, false);
        }

		if (alreadyExistsKraken) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderKraken.getName());

		} else {
			// Même principe que BINANCE ci-dessus : propriétés `tradeio.kraken.apiKey` / `tradeio.kraken.secretKey`.
			seedUserExchangeCredential(user, webProviderKraken, "tradeio.kraken", true, false);
		}

		if (alreadyExistsOkx) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur OKlm.", webProviderOkx.getName());

		} else {
			// Clé OKX « Read » uniquement (IP du VPS allowlistée) : propriétés `tradeio.okx.apiKey` /
			// `tradeio.okx.secretKey` / `tradeio.okx.passphrase` (la passphrase est exigée à chaque requête OKX).
			seedUserExchangeCredential(user, webProviderOkx, "tradeio.okx", true, true);
		}

		if (alreadyExistsCoinstats) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderCoinstats.getName());

		} else {
			// Clé System partagée (Fear & Greed, aucun wallet/utilisateur rattaché — cf.
			// IndicatorCredentialResolver), même principe que COINALYZE/TWELVE_DATA/FINNHUB ci-dessous :
			// migrée hors du code en dur le 2026-07-09 (backlog 5.1) vers application-*.properties
			// (gitignoré) sous `tradeio.coinstats.apiKey`. Si absente, on n'insère pas de credential :
			// FEAR_GREED retombera proprement en invalid() plutôt que d'utiliser une clé factice.
			String coinstatsApiKey = environment.getProperty("tradeio.coinstats.apiKey");

			if (coinstatsApiKey == null || coinstatsApiKey.isBlank()) {
				logger.warn("❗ `tradeio.coinstats.apiKey` absente d'application-*.properties : "
						+ "aucune credential COINSTATS créée pour System (FEAR_GREED restera invalid "
						+ "tant qu'elle n'est pas renseignée).");
			} else {
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderCoinstats)
						.apiKey(coinstatsApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur COINSTATS");
			}
		}

		if (alreadyExistsDefiLlama) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderDefiLlama.getName());

		} else {
			// DefiLlama /stablecoins ne demande aucune clé API (endpoint public) : apiKey/secretKey
			// restent vides, seul le baseUrl porté par la credential résolue importe (cf.
			// AbstractExternalIndicator#getWebClient et étude "indicateurs-macro-externes" §7).

			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderDefiLlama)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur DEFILLAMA");
		}

		if (alreadyExistsCoinalyze) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderCoinalyze.getName());

		} else {
			// Contrairement aux autres providers de cette méthode, la clé Coinalyze n'est PAS
			// committée en clair ici : elle est générée manuellement sur coinalyze.net/account/api-key/
			// (compte gratuit) et doit être fournie via application-*.properties (gitignoré,
			// cf. mémoire projet "TradeIO5 secrets are gitignored") sous la clé
			// `tradeio.coinalyze.apiKey`. Si absente, on n'insère pas de credential : l'indicateur
			// retombera proprement en invalid() (cf. IndicatorCredentialResolver) plutôt que
			// d'utiliser une clé factice.
			String coinalyzeApiKey = environment.getProperty("tradeio.coinalyze.apiKey");

			if (coinalyzeApiKey == null || coinalyzeApiKey.isBlank()) {
				logger.warn("❗ `tradeio.coinalyze.apiKey` absente d'application-*.properties : "
						+ "aucune credential COINALYZE créée pour System (OPEN_INTEREST/FUNDING_RATE/"
						+ "LIQUIDATIONS resteront invalid tant qu'elle n'est pas renseignée).");
			} else {
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderCoinalyze)
						.apiKey(coinalyzeApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur COINALYZE");
			}
		}

		if (alreadyExistsTwelveData) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderTwelveData.getName());

		} else {
			// Même principe que COINALYZE ci-dessus : clé générée manuellement sur twelvedata.com
			// (compte gratuit), jamais committée en clair. À renseigner via application-*.properties
			// (gitignoré) sous `tradeio.twelvedata.apiKey`. Sans elle, DXY/SP500/NASDAQ resteront
			// invalid (cf. IndicatorCredentialResolver).
			String twelveDataApiKey = environment.getProperty("tradeio.twelvedata.apiKey");

			if (twelveDataApiKey == null || twelveDataApiKey.isBlank()) {
				logger.warn("❗ `tradeio.twelvedata.apiKey` absente d'application-*.properties : "
						+ "aucune credential TWELVE_DATA créée pour System (DXY/SP500/NASDAQ resteront "
						+ "invalid tant qu'elle n'est pas renseignée).");
			} else {
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderTwelveData)
						.apiKey(twelveDataApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur TWELVE_DATA");
			}
		}

		if (alreadyExistsFinnhub) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderFinnhub.getName());

		} else {
			// Même principe : clé générée manuellement sur finnhub.io (compte gratuit), jamais
			// committée en clair. À renseigner via application-*.properties (gitignoré) sous
			// `tradeio.finnhub.apiKey`. Sans elle, MacroEventCalendarService ne recevra aucun
			// événement Finnhub (ForexFactory reste disponible sans clé, voir ci-dessous).
			String finnhubApiKey = environment.getProperty("tradeio.finnhub.apiKey");

			if (finnhubApiKey == null || finnhubApiKey.isBlank()) {
				logger.warn("❗ `tradeio.finnhub.apiKey` absente d'application-*.properties : "
						+ "aucune credential FINNHUB créée pour System (calendrier macro limité à "
						+ "ForexFactory tant qu'elle n'est pas renseignée).");
			} else {
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderFinnhub)
						.apiKey(finnhubApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur FINNHUB");
			}
		}

		if (alreadyExistsForexFactory) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderForexFactory.getName());

		} else {
			// ff_calendar_thisweek.json ne demande aucune clé API (endpoint public), même principe
			// que DEFILLAMA ci-dessus : apiKey vide, seul le baseUrl porté par la credential résolue
			// importe.

			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderForexFactory)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

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

			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderFarside)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur FARSIDE");
		}

		if (alreadyExistsYoutube) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderYoutube.getName());

		} else {
			// Flux RSS (/feeds/videos.xml) et page /watch ne demandent aucune clé API (endpoints
			// publics), même principe que DEFILLAMA/FOREXFACTORY/FARSIDE ci-dessus : apiKey vide,
			// seul le baseUrl porté par la credential résolue importe (cf.
			// AbstractExternalIndicator#getWebClient et docs/prompts/prompt-implementation-veille-media-full.md,
			// Lot 1a).

			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderYoutube)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur YOUTUBE");
		}

		if (alreadyExistsYahooFinance) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderYahooFinance.getName());

		} else {
			// /v8/finance/chart/{symbol} ne demande aucune clé API (endpoint public non officiel),
			// même principe que DEFILLAMA/FOREXFACTORY/FARSIDE/YOUTUBE ci-dessus : apiKey vide, seul
			// le baseUrl porté par la credential résolue importe. Source de secours pour SP500/NASDAQ
			// (cf. IndicatorCredentialResolver) après confirmation le 2026-07-15 que les tickers
			// Twelve Data SPX/IXIC sont verrouillés au palier payant — DXY reste sur TWELVE_DATA.

			ApiCredential credential = ApiCredential.builder()
					.user(sys)
					.webProvider(webProviderYahooFinance)
					.apiKey("")
					.enabled(true)
					.createdAt(LocalDateTime.now())
					.build();

			credentialRepository.save(credential);
			logger.debug("- Clé API ajoutée pour System sur YAHOO_FINANCE");
		}

		if (alreadyExistsSosoValue) {
			logger.debug("🔑 Clé API {} déjà présente pour l'utilisateur System.", webProviderSosoValue.getName());

		} else {
			// ETF_FLOW (docs/etudes/etude-sourcing-etf-flow-alternative-farside.md) : remplace FARSIDE
			// (scraping HTML) le 2026-07-16. Clé obtenue via inscription gratuite sur
			// sosovalue.com/developer (palier "Demo", 20 appels/min), jamais committée en clair (ce
			// fichier est gitignoré). À renseigner via application-*.properties sous
			// `tradeio.sosovalue.apiKey`. Sans elle, ETF_FLOW reste invalid (voir
			// IndicatorCredentialResolver) — même principe que COINALYZE/TWELVE_DATA/FINNHUB/COINSTATS.
			String sosoValueApiKey = environment.getProperty("tradeio.sosovalue.apiKey");

			if (sosoValueApiKey == null || sosoValueApiKey.isBlank()) {
				logger.warn("❗ `tradeio.sosovalue.apiKey` absente d'application-*.properties : "
						+ "aucune credential SOSOVALUE créée pour System (ETF_FLOW restera invalid "
						+ "tant qu'elle n'est pas renseignée).");
			} else {
				ApiCredential credential = ApiCredential.builder()
						.user(sys)
						.webProvider(webProviderSosoValue)
						.apiKey(sosoValueApiKey)
						.enabled(true)
						.createdAt(LocalDateTime.now())
						.build();

				credentialRepository.save(credential);
				logger.debug("- Clé API ajoutée pour System sur SOSOVALUE");
			}
		}
    }

	/**
	 * Crée la credential exchange d'OKlm depuis les propriétés {@code <prefix>.apiKey} / {@code <prefix>.secretKey}
	 * (et {@code <prefix>.passphrase} si {@code requirePassphrase}), fichiers {@code application-*.properties}
	 * gitignorés. Propriétés absentes ou vides : aucune credential n'est créée (WARN, jamais d'échec au démarrage) et la
	 * clé reste à saisir directement en DB. Appelé uniquement quand la credential n'existe pas encore.
	 */
	private void seedUserExchangeCredential(User user, WebProvider provider, String prefix, boolean enabled,
			boolean requirePassphrase) {
		String apiKey = environment.getProperty(prefix + ".apiKey");
		String secretKey = environment.getProperty(prefix + ".secretKey");
		String passphrase = environment.getProperty(prefix + ".passphrase");
		if (apiKey == null || apiKey.isBlank() || secretKey == null || secretKey.isBlank()
				|| (requirePassphrase && (passphrase == null || passphrase.isBlank()))) {
			logger.warn("❗ `{}.apiKey` / `{}.secretKey`{} absentes : aucune credential {} créée pour OKlm "
					+ "(à renseigner dans application-*.properties ou directement en DB, table api_credentials).",
					prefix, prefix, requirePassphrase ? " / `" + prefix + ".passphrase`" : "", provider.getName());
			return;
		}
		credentialRepository.save(ApiCredential.builder()
				.user(user)
				.webProvider(provider)
				.apiKey(apiKey)
				.secretKey(secretKey)
				.passphrase(requirePassphrase ? passphrase : null)
				.enabled(enabled)
				.createdAt(LocalDateTime.now())
				.build());
		logger.info("🔑 Clé API {} ajoutée pour OKlm depuis les propriétés.", provider.getName());
	}
}
