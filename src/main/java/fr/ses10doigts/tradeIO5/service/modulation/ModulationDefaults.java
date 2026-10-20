package fr.ses10doigts.tradeIO5.service.modulation;

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

    public static Limit limit(String name) {
        return LIMITS.get(name);
    }

    private ModulationDefaults() {
    }
}
