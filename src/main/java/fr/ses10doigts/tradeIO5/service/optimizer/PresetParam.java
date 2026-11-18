package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Paramètres numériques d'un jeu Rainbow (Bull ou Bear) que l'optimiseur L1 peut laisser libres. Les bornes du domaine valide sont
 * DURES (jamais franchies, quel que soit δ). Tout le reste du preset (période SMA / ATR, modes de ré-entrée, booléens, ATH, Moon,
 * multiplicateurs, montant de base, Trend) est gelé par construction : {@link #FROZEN_BY_DESIGN}.
 */
public enum PresetParam {
    ATR_MULT_DOWN2("atrMultDown2", false, 0, 20, RainbowAtrTuning::atrMultDown2),
    ATR_MULT_DOWN1("atrMultDown1", false, 0, 20, RainbowAtrTuning::atrMultDown1),
    ATR_MULT_UP1("atrMultUp1", false, 0, 20, RainbowAtrTuning::atrMultUp1),
    ATR_MULT_UP2("atrMultUp2", false, 0, 20, RainbowAtrTuning::atrMultUp2),
    ATR_MULT_UP3("atrMultUp3", false, 0, 20, RainbowAtrTuning::atrMultUp3),
    TRAILING_STOP_BUY("trailingStopBuyPct", false, 0.1, 50, RainbowAtrTuning::trailingStopBuyPct),
    TRAILING_STOP_SELL("trailingStopSellPct", false, 0.1, 50, RainbowAtrTuning::trailingStopSellPct),
    COOLDOWN_DAYS("cooldownDays", true, 0, 365, RainbowAtrTuning::cooldownDays),
    FIXED_DELAY_DAYS("fixedDelayDays", true, 1, 365, RainbowAtrTuning::fixedDelayDays),
    SELL_FRACTION("sellFraction", false, 0.01, 1.0, RainbowAtrTuning::sellFraction);

    /** Ce que l'optimiseur L1 ne touche jamais (documenté dans le rapport). */
    public static final List<String> FROZEN_BY_DESIGN = List.of("smaPeriod", "atrPeriod", "buyReentryMode", "sellReentryMode",
            "allowSellDuringCooldown", "cooldownAfterSellOn", "blockBuyAfterSellUntilDown2", "ATH", "Moon", "multiplicateurs de zone",
            "baseAmount", "Trend");

    private final String key;
    private final boolean integer;
    private final double domainMin;
    private final double domainMax;
    private final ToDoubleFunction<RainbowAtrTuning> getter;

    PresetParam(String key, boolean integer, double domainMin, double domainMax, ToDoubleFunction<RainbowAtrTuning> getter) {
        this.key = key;
        this.integer = integer;
        this.domainMin = domainMin;
        this.domainMax = domainMax;
        this.getter = getter;
    }

    /** Nom du paramètre dans {@code RainbowAtrTuning.Builder#set}. */
    public String key() {
        return key;
    }

    public boolean integer() {
        return integer;
    }

    public double domainMin() {
        return domainMin;
    }

    public double domainMax() {
        return domainMax;
    }

    public double of(RainbowAtrTuning tuning) {
        return getter.applyAsDouble(tuning);
    }

    public static Optional<PresetParam> byKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
