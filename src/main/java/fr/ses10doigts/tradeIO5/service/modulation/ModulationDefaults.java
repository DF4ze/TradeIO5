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
    /** Marge k du calage des taux (plafond de calage = plafond / k) : information seulement, non utilisée par le calcul. */
    public static final String BASE_RATE_MARGIN_K = "baseRateMarginK";

    /**
     * Taux de base d'un actif : montant de base par jour / cash alloué au lien (bande neutre, marge k), un par profil. Source
     * {@code docs/decisions/full-autonome-v1.md}. Proposition INDICATIVE d'onboarding ; aucun effet sur les ordres.
     */
    public record RateTable(BigDecimal prudent, BigDecimal balanced, BigDecimal dynamic, BigDecimal full) {

        static RateTable of(String prudent, String balanced, String dynamic, String full) {
            return new RateTable(new BigDecimal(prudent), new BigDecimal(balanced), new BigDecimal(dynamic), new BigDecimal(full));
        }

        /** Taux du profil ; {@code null} pour un profil sans table ({@code CUSTOM}, nul). */
        public BigDecimal rate(StartingProfile profile) {
            if (profile == null || !profile.selectable()) {
                return null;
            }
            return switch (profile) {
                case PRUDENT -> prudent;
                case BALANCED -> balanced;
                case DYNAMIC -> dynamic;
                default -> full;
            };
        }
    }

    /**
     * Table de calage (valeurs initiales de la migration V9 et « défauts de l'actif » de l'onglet Taux de base). Seuls BTC et ETH en ont une ;
     * tout autre actif retombe sur la ligne {@value #FALLBACK_RATE_ASSET} (niveau non calibré) tant qu'il n'a pas de ligne dédiée.
     */
    public static final Map<String, RateTable> CALIBRATED_RATES = Map.of(
            "BTC", RateTable.of("0.0002774", "0.0004736", "0.0007712", "0.001423"),
            "ETH", RateTable.of("0.0003804", "0.0006349", "0.001019", "0.001533"));
    public static final String FALLBACK_RATE_ASSET = "BTC";
    public static final java.util.Set<String> CALIBRATED_RATE_ASSETS = CALIBRATED_RATES.keySet();

    /** Bornes absolues d'un taux de base par jour / cash : strictement positif, au plus 1 %/jour (la table de calage va de 0,028 % à 0,15 %). */
    public static final BigDecimal BASE_RATE_MIN = new BigDecimal("0.00001");
    public static final BigDecimal BASE_RATE_MAX = new BigDecimal("0.01");

    public static boolean baseRateInBounds(BigDecimal rate) {
        return rate.compareTo(BASE_RATE_MIN) >= 0 && rate.compareTo(BASE_RATE_MAX) <= 0;
    }

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
        limits.put(BASE_RATE_MARGIN_K, Limit.of("1.1", "1", "3"));
        LIMITS = Collections.unmodifiableMap(limits);
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
