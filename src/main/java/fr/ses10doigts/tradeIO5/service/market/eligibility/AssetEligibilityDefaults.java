package fr.ses10doigts.tradeIO5.service.market.eligibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

/**
 * Constantes métier de l'éligibilité d'un actif (source unique : ne pas les recopier). Le seuil de volume est le DÉFAUT du
 * réglage admin ({@code asset_eligibility_settings}) ; l'historique minimal est aussi celui du pipeline D1b
 * ({@code NewAssetConfig.defaults().minCandles()}).
 */
public final class AssetEligibilityDefaults {

    /** Historique D1 minimal (jours de bougies closes) chez le provider du lien. */
    public static final int MIN_D1_DAYS = 120;

    /** Défaut du volume spot 24 h minimum, en USD (réglable en admin). */
    public static final BigDecimal DEFAULT_MIN_VOLUME_USD = new BigDecimal("1000000");

    /** Un volume lu depuis plus longtemps que cela est considéré inconnu (catalogue figé, endpoint de volume en panne). */
    public static final Duration VOLUME_MAX_AGE = Duration.ofDays(7);

    /**
     * Volume inconnu (exchange sans volume global, lecture en échec ou périmée, aucune paire cotée en USD) : éligible, mais non vérifié
     * ({@link AssetEligibilityService.Result#volumeVerified()} = false). Passer à {@code false} pour refuser ces cas.
     */
    public static final boolean UNKNOWN_VOLUME_ELIGIBLE = true;

    /** Cotations assimilées au dollar (volume additionnable sans conversion de change). */
    public static final Set<String> USD_QUOTES = Set.of("USD", "USDC", "USDT");

    private AssetEligibilityDefaults() {
    }
}
