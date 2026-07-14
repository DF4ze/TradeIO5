package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Résout la {@link ApiCredentialDTO} nécessaire aux indicateurs externes (ex: FEAR_GREED via
 * CoinStats), qui ne sont rattachés à aucun wallet/utilisateur appelant (contrairement à
 * Binance/Kraken). Va chercher la credential du user technique "System" pour le provider
 * concerné ; retourne {@code null} si l'indicateur n'en a pas besoin ou si rien n'est configuré
 * (l'indicateur retombera alors en invalid proprement).
 * <p>
 * Extrait de {@code TreeAnalysisFacade} (qui en avait le seul besoin jusqu'ici) pour être
 * réutilisable par tout composant lisant un indicateur externe sans passer par la façade MCP —
 * notamment {@code GlobalMarketOpinion}, qui lit FEAR_GREED directement via {@link IndicatorEngine}
 * plutôt que via une {@code Strategy} intermédiaire.
 */
@Service
public class IndicatorCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(IndicatorCredentialResolver.class);

    /** Utilisateur technique portant les credentials des indicateurs externes, qui ne sont
     *  rattachés à aucun wallet utilisateur. Voir {@code UserInitializer}/{@code ApiCredentialInitializer}. */
    private static final String SYSTEM_USERNAME = "System";

    private final UserRepository userRepository;
    private final ApiCredentialRepository apiCredentialRepository;

    public IndicatorCredentialResolver(UserRepository userRepository, ApiCredentialRepository apiCredentialRepository) {
        this.userRepository = userRepository;
        this.apiCredentialRepository = apiCredentialRepository;
    }

    public ApiCredentialDTO resolve(IndicatorType type) {
        WebProviderCode provider = switch (type) {
            case FEAR_GREED -> WebProviderCode.COINSTATS;
            case STABLECOIN_MARKET_CAP -> WebProviderCode.DEFILLAMA;
            case OPEN_INTEREST, FUNDING_RATE, LIQUIDATIONS -> WebProviderCode.COINALYZE;
            case DXY -> WebProviderCode.TWELVE_DATA;
            // SP500/NASDAQ : Twelve Data (tickers SPX/IXIC) verrouillé au palier payant, confirmé
            // par test réel le 2026-07-15 — bascule sur Yahoo Finance (gratuit, sans clé), cf.
            // YahooFinanceQuoteProvider.
            case SP500, NASDAQ -> WebProviderCode.YAHOO_FINANCE;
            case ETF_FLOW -> WebProviderCode.FARSIDE;
            default -> null;
        };
        if (provider == null) {
            return null;
        }

        return userRepository.findByUsername(SYSTEM_USERNAME)
                .flatMap(sysUser -> apiCredentialRepository
                        .findByUserAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(sysUser, provider))
                .map(cred -> new ApiCredentialDTO(
                        provider,
                        cred.getApiKey(),
                        cred.getSecretKey(),
                        cred.getWebProvider().getApiBaseUrl()
                ))
                .orElseGet(() -> {
                    log.warn("Aucune credential '{}' trouvée pour l'utilisateur '{}' : indicateur {} sera invalid.",
                            provider, SYSTEM_USERNAME, type);
                    return null;
                });
    }
}
