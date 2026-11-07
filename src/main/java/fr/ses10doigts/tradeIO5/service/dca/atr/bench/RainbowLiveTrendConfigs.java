package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Réglages Trend + jeux Rainbow : défauts du code, (dé)sérialisation JSON, normalisation (parties
 * absentes ⇒ défauts) et validation. Fonctions pures, sans état.
 */
public final class RainbowLiveTrendConfigs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RainbowLiveTrendConfigs() {
    }

    /** Réglages résolus, prêts pour le moteur. */
    public record Resolved(TrendMixCalculator.Params trend, RangeMapping range, RainbowAtrParamSet bear,
                           RainbowAtrParamSet bull) {
        public RainbowAtrParamSet[] sets() {
            return new RainbowAtrParamSet[]{bear, bull};
        }
    }

    public static Resolved defaults(String asset) {
        List<RainbowAtrParamSet> bb = RainbowAtrPresets.bearBull(asset);
        return new Resolved(TrendMixCalculator.Params.defaults(), RangeMapping.KEEP_PREVIOUS,
                bb.get(RainbowAtrPresets.BEAR), bb.get(RainbowAtrPresets.BULL));
    }

    public static String toJson(TrendConfigDto dto) {
        try {
            return JSON.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TrendConfigDto fromJson(String json) {
        try {
            return JSON.readValue(json, TrendConfigDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Réglages Trend Mix illisibles : " + e.getMessage(), e);
        }
    }

    public static TrendConfigDto toDto(Resolved r) {
        return new TrendConfigDto(r.trend(), r.range().name(),
                new ConfigDto(r.bear().tuning(), r.bear().globals()),
                new ConfigDto(r.bull().tuning(), r.bull().globals()));
    }

    /** Parties absentes ⇒ défauts de l'actif, puis validation (IllegalArgumentException). */
    public static Resolved normalize(String asset, TrendConfigDto dto) {
        Resolved d = defaults(asset);
        if (dto == null) {
            return d;
        }
        RangeMapping range = dto.rangeMapping() == null ? d.range() : parseRange(dto.rangeMapping());
        Resolved r = new Resolved(dto.trend() == null ? d.trend() : dto.trend(), range,
                set(d.bear().name(), dto.bear(), d.bear()), set(d.bull().name(), dto.bull(), d.bull()));
        validate(r);
        return r;
    }

    public static void validate(Resolved r) {
        Map<String, String> trendErrors = trendErrors(r.trend());
        if (!trendErrors.isEmpty()) {
            throw new IllegalArgumentException("Trend : " + String.join(", ", trendErrors.values()));
        }
        for (RainbowAtrParamSet s : r.sets()) {
            if (s.tuning() == null || !s.tuning().isValid()) {
                throw new IllegalArgumentException("Jeu " + s.name() + " : tuning invalide");
            }
            if (s.globals() == null || s.globals().baseAmount() <= 0) {
                throw new IllegalArgumentException("Jeu " + s.name() + " : baseAmount doit être > 0");
            }
        }
    }

    /** Règles de validité du Trend Mix par paramètre : nom du paramètre -> message ; vide ⇒ valide. */
    public static Map<String, String> trendErrors(TrendMixCalculator.Params p) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!(p.shortWindow() >= 2)) {
            errors.put("shortWindow", "fenêtre courte ≥ 2");
        }
        if (!(p.mediumWindow() >= 2)) {
            errors.put("mediumWindow", "fenêtre moyenne ≥ 2");
        }
        if (!(p.longWindow() >= 2)) {
            errors.put("longWindow", "fenêtre longue ≥ 2");
        }
        if (!(p.slopeScale() > 0)) {
            errors.put("slopeScale", "slopeScale > 0");
        }
        if (!(p.enter() > 0)) {
            errors.put("enter", "seuil d'entrée > 0");
        }
        if (!(p.exit() >= 0 && p.exit() <= p.enter())) {
            errors.put("exit", "0 ≤ seuil de sortie ≤ seuil d'entrée");
        }
        if (!(p.confirm() >= 1)) {
            errors.put("confirm", "confirmation ≥ 1");
        }
        if (!(p.smaPeriod() >= 2)) {
            errors.put("smaPeriod", "période SMA ≥ 2");
        }
        if (!(p.atrPeriod() >= 1)) {
            errors.put("atrPeriod", "période ATR ≥ 1");
        }
        if (!(p.atrMultiplier() >= 0)) {
            errors.put("atrMultiplier", "multiplicateur ATR ≥ 0");
        }
        return errors;
    }

    private static RangeMapping parseRange(String s) {
        try {
            return RangeMapping.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rangeMapping invalide : " + s);
        }
    }

    private static RainbowAtrParamSet set(String name, ConfigDto c, RainbowAtrParamSet fallback) {
        return c == null ? fallback : new RainbowAtrParamSet(name,
                c.tuning() == null ? fallback.tuning() : c.tuning(),
                c.globals() == null ? fallback.globals() : c.globals());
    }
}
