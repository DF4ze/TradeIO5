package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation des réglages saisis dans l'Espace Expert, un message par champ. Trend et bornes Rainbow réutilisent les règles
 * existantes ({@link RainbowLiveTrendConfigs#trendErrors}, {@link RainbowAtrTuning#violations()}) ; les paramètres globaux
 * n'avaient pas de validation : bornes saines ajoutées ici. Aucune logique moteur.
 */
@Component
public class ExpertPresetValidator {

    public static final int NAME_MAX = 64;
    private static final int DAYS_PER_MONTH = 30;
    /** Fenêtre d'analyse maximale : ce que la fenêtre D1 chargée par la passe permet de rejouer. */
    public static final int MAX_ANALYSIS_WINDOW_MONTHS = RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES / DAYS_PER_MONTH;
    private static final double PCT_MAX = 100.0;

    /** Nom nettoyé (non vide, ≤ {@value #NAME_MAX} caractères). */
    public String name(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX) {
            throw new ExpertValidationException("name", "obligatoire, " + NAME_MAX + " caractères maximum");
        }
        return trimmed;
    }

    public void trend(TrendMixCalculator.Params params, String rangeMapping) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (params == null) {
            errors.put("trend", "obligatoire");
        } else {
            errors.putAll(RainbowLiveTrendConfigs.trendErrors(params));
        }
        try {
            RangeMapping.valueOf(rangeMapping);
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.put("rangeMapping", "valeur attendue : " + java.util.Arrays.toString(RangeMapping.values()));
        }
        throwIfAny(errors);
    }

    public void rainbow(Integer analysisWindowMonths, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (analysisWindowMonths == null || analysisWindowMonths < 1 || analysisWindowMonths > MAX_ANALYSIS_WINDOW_MONTHS) {
            errors.put("analysisWindowMonths", "entre 1 et " + MAX_ANALYSIS_WINDOW_MONTHS + " mois");
        }
        if (tuning == null) {
            errors.put("tuning", "obligatoire");
        } else {
            errors.putAll(tuning.violations());
            tuningModes(tuning, errors);
        }
        if (globals == null) {
            errors.put("globals", "obligatoire");
        } else {
            globals(globals, errors);
        }
        throwIfAny(errors);
    }

    private static void tuningModes(RainbowAtrTuning t, Map<String, String> errors) {
        require(errors, "buyReentryMode", t.buyReentryMode() != null, "obligatoire");
        require(errors, "sellReentryMode", t.sellReentryMode() != null, "obligatoire");
        require(errors, "trailingStopBuyPct", pct(t.trailingStopBuyPct()), "entre 0 et 100");
        require(errors, "trailingStopSellPct", pct(t.trailingStopSellPct()), "entre 0 et 100");
    }

    private static void globals(RainbowAtrGlobals g, Map<String, String> errors) {
        require(errors, "globals.athRefDdBuyPct", g.athRefDdBuyPct() > 0 && g.athRefDdBuyPct() <= PCT_MAX, "dans ]0, 100]");
        require(errors, "globals.athRefDdSellPct", g.athRefDdSellPct() > 0 && g.athRefDdSellPct() <= PCT_MAX, "dans ]0, 100]");
        require(errors, "globals.athBuyMin", g.athBuyMin() >= 0, "≥ 0");
        require(errors, "globals.athBuyMax", g.athBuyMax() >= g.athBuyMin(), "≥ athBuyMin");
        require(errors, "globals.athSellMax", g.athSellMax() >= 0, "≥ 0");
        require(errors, "globals.athSellMin", g.athSellMin() >= 0, "≥ 0");
        require(errors, "globals.moonReservePct", pct(g.moonReservePct()), "entre 0 et 100");
        require(errors, "globals.moonTrailingStopPct", pct(g.moonTrailingStopPct()), "entre 0 et 100");
        require(errors, "globals.moonStopSellPct", pct(g.moonStopSellPct()), "entre 0 et 100");
        require(errors, "globals.multX2", g.multX2() > 0, "> 0");
        require(errors, "globals.multX1", g.multX1() > 0, "> 0");
        require(errors, "globals.multX0_5", g.multX0_5() > 0, "> 0");
        require(errors, "globals.multTriggered", g.multTriggered() > 0, "> 0");
        require(errors, "globals.baseAmount", g.baseAmount() > 0 && Double.isFinite(g.baseAmount()), "> 0");
    }

    private static boolean pct(double v) {
        return v >= 0 && v <= PCT_MAX;
    }

    private static void require(Map<String, String> errors, String field, boolean ok, String message) {
        if (!ok) {
            errors.put(field, message);
        }
    }

    private static void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ExpertValidationException(errors);
        }
    }
}
