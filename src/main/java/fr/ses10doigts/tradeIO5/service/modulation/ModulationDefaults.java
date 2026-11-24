package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constantes métier mutualisées de la modulation Rainbow (bande d'exposition, puissance, biais) : défauts et <b>bornes
 * absolues</b> des paramètres admin ({@code RainbowModulationControl}), valeurs neutres des réglages utilisateur. Les bornes
 * absolues sont en code : la base ne peut pas les relever (même principe qu'{@code ExecutionDefaults}).
 */
public final class ModulationDefaults {

    /** Défaut et bornes absolues (incluses) d'un paramètre admin. */
    public record Limit(BigDecimal defaultValue, BigDecimal min, BigDecimal max) {

        static Limit of(String defaultValue, String min, String max) {
            return new Limit(new BigDecimal(defaultValue), new BigDecimal(min), new BigDecimal(max));
        }

        public boolean contains(BigDecimal value) {
            return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        }
    }

    public static final String REGULATION_MARGIN_PCT = "regulationMarginPct";
    public static final String SELL_BOOST_MAX = "sellBoostMax";
    public static final String BIAS_MAX = "biasMax";
    public static final String POWER_MIN = "powerMin";
    public static final String POWER_MAX = "powerMax";
    public static final String MIN_BAND_WIDTH_PTS = "minBandWidthPts";
    public static final String MODULATION_PRODUCT_MAX = "modulationProductMax";
    public static final String AUTONOMY_DAYS = "autonomyDays";
    /** Plafonds de drawdown du wallet par profil de départ, en % (négatifs) ; signalement du simulateur, aucun effet sur les ordres. */
    public static final String DRAWDOWN_CAP_PRUDENT_PCT = "drawdownCapPrudentPct";
    public static final String DRAWDOWN_CAP_BALANCED_PCT = "drawdownCapBalancedPct";
    public static final String DRAWDOWN_CAP_DYNAMIC_PCT = "drawdownCapDynamicPct";
    public static final String DRAWDOWN_CAP_FULL_PCT = "drawdownCapFullPct";
    /**
     * Taux de base par profil et par actif calibré (BTC, ETH) : montant de base par jour / cash alloué au lien (bande neutre, marge k),
     * source {@code docs/decisions/full-autonome-v1.md}. Servent la proposition INDICATIVE d'onboarding ; aucun effet sur les ordres.
     */
    public static final String BASE_RATE_BTC_PRUDENT = "baseRateBtcPrudent";
    public static final String BASE_RATE_BTC_BALANCED = "baseRateBtcBalanced";
    public static final String BASE_RATE_BTC_DYNAMIC = "baseRateBtcDynamic";
    public static final String BASE_RATE_BTC_FULL = "baseRateBtcFull";
    public static final String BASE_RATE_ETH_PRUDENT = "baseRateEthPrudent";
    public static final String BASE_RATE_ETH_BALANCED = "baseRateEthBalanced";
    public static final String BASE_RATE_ETH_DYNAMIC = "baseRateEthDynamic";
    public static final String BASE_RATE_ETH_FULL = "baseRateEthFull";
    /** Marge k du calage des taux (plafond de calage = plafond / k) : information seulement, non utilisée par le calcul. */
    public static final String BASE_RATE_MARGIN_K = "baseRateMarginK";

    /** Actifs ayant une ligne propre de taux ; tout autre actif retombe sur la ligne {@value #FALLBACK_RATE_ASSET} (niveau non calibré). */
    public static final String FALLBACK_RATE_ASSET = "BTC";
    public static final java.util.Set<String> CALIBRATED_RATE_ASSETS = java.util.Set.of("BTC", "ETH");

    /** Paramètres admin dans l'ordre d'affichage : nom => défaut + bornes absolues. */
    public static final Map<String, Limit> LIMITS;

    static {
        Map<String, Limit> limits = new LinkedHashMap<>();
        limits.put(REGULATION_MARGIN_PCT, Limit.of("25", "5", "50"));
        limits.put(SELL_BOOST_MAX, Limit.of("2", "1", "4"));
        limits.put(BIAS_MAX, Limit.of("0.5", "0", "1"));
        limits.put(POWER_MIN, Limit.of("0.25", "0", "3"));
        limits.put(POWER_MAX, Limit.of("2", "0", "3"));
        limits.put(MIN_BAND_WIDTH_PTS, Limit.of("10", "5", "50"));
        limits.put(MODULATION_PRODUCT_MAX, Limit.of("2.5", "1", "5"));
        limits.put(AUTONOMY_DAYS, Limit.of("90", "7", "365"));
        limits.put(DRAWDOWN_CAP_PRUDENT_PCT, Limit.of("-15", "-100", "0"));
        limits.put(DRAWDOWN_CAP_BALANCED_PCT, Limit.of("-25", "-100", "0"));
        limits.put(DRAWDOWN_CAP_DYNAMIC_PCT, Limit.of("-40", "-100", "0"));
        limits.put(DRAWDOWN_CAP_FULL_PCT, Limit.of("-60", "-100", "0"));
        limits.put(BASE_RATE_BTC_PRUDENT, rate("0.0002774"));
        limits.put(BASE_RATE_BTC_BALANCED, rate("0.0004736"));
        limits.put(BASE_RATE_BTC_DYNAMIC, rate("0.0007712"));
        limits.put(BASE_RATE_BTC_FULL, rate("0.001423"));
        limits.put(BASE_RATE_ETH_PRUDENT, rate("0.0003804"));
        limits.put(BASE_RATE_ETH_BALANCED, rate("0.0006349"));
        limits.put(BASE_RATE_ETH_DYNAMIC, rate("0.001019"));
        limits.put(BASE_RATE_ETH_FULL, rate("0.001533"));
        limits.put(BASE_RATE_MARGIN_K, Limit.of("1.1", "1", "3"));
        LIMITS = Collections.unmodifiableMap(limits);
    }

    /** Taux de base par jour / cash : strictement positif, au plus 1 %/jour (la table de calage va de 0,028 % à 0,15 %). */
    private static Limit rate(String defaultValue) {
        return Limit.of(defaultValue, "0.00001", "0.01");
    }

    /** Clé du paramètre admin du taux de base de {@code profile} sur {@code asset} (BTC ou ETH) ; {@code null} si pas de ligne propre. */
    public static String baseRateKey(StartingProfile profile, String asset) {
        if (profile == null || !profile.selectable() || !CALIBRATED_RATE_ASSETS.contains(asset)) {
            return null;
        }
        return "baseRate" + (asset.equals("BTC") ? "Btc" : "Eth") + switch (profile) {
            case PRUDENT -> "Prudent";
            case BALANCED -> "Balanced";
            case DYNAMIC -> "Dynamic";
            default -> "Full";
        };
    }

    /** Valeurs neutres d'un réglage utilisateur absent ou nul : bande 0–100 %, puissance ×1, biais 0 (aucun effet). */
    public static final double NEUTRAL_EXPOSURE_MIN = 0d;
    public static final double NEUTRAL_EXPOSURE_MAX = 100d;
    public static final double NEUTRAL_POWER = 1d;
    public static final double NEUTRAL_BIAS = 0d;

    /** Bornes de l'exposition (en % de la valeur du portefeuille). */
    public static final double EXPOSURE_FLOOR_PCT = 0d;
    public static final double EXPOSURE_CEILING_PCT = 100d;

    /**
     * Plafonds de mise ATH par profil (achat / vente, en multiples de la base / facteur ATH de vente), valeurs initiales à valider :
     * Prudent 3 / 1, Équilibré 6 / 2, Dynamique 10 / 3, Full 15 / 5. {@link StartingProfile#CUSTOM} : aucun défaut. Constantes de code
     * pour l'instant (persistance et réglage admin : étape 7), utilisées par le rejeu Expert seulement.
     */
    private static final Map<StartingProfile, AthCap> ATH_CAPS = Map.of(
            StartingProfile.PRUDENT, new AthCap(3d, 1d),
            StartingProfile.BALANCED, new AthCap(6d, 2d),
            StartingProfile.DYNAMIC, new AthCap(10d, 3d),
            StartingProfile.FULL, new AthCap(15d, 5d));

    /** Plafond de mise ATH par défaut du profil ({@code null} pour {@link StartingProfile#CUSTOM} : pas de défaut). */
    public static AthCap athCapOf(StartingProfile profile) {
        return ATH_CAPS.get(profile);
    }

    public static Limit limit(String name) {
        return LIMITS.get(name);
    }

    private ModulationDefaults() {
    }
}
