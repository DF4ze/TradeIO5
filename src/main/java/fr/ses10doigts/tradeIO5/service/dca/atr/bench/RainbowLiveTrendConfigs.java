package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveTrendConfig;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.util.List;

/**
 * Réglages d'un preset {@code TREND_MIX} : défauts du code, (dé)sérialisation de l'entité, normalisation (parties
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

    /** Ligne absente ⇒ défauts du code. */
    public static Resolved resolve(RainbowLiveTrendConfig row, String asset) {
        if (row == null) {
            return defaults(asset);
        }
        Resolved d = defaults(asset);
        TrendMixCalculator.Params p = new TrendMixCalculator.Params(row.getShortWindow(), row.getMediumWindow(),
                row.getLongWindow(), row.getSlopeScale(), row.getEnterThreshold(), row.getExitThreshold(),
                row.getConfirmDays(), row.getSmaPeriod(), row.getAtrPeriod(), row.getAtrMultiplier(),
                row.isWickDown(), row.isWickUp());
        return new Resolved(p, RangeMapping.valueOf(row.getRangeMapping()),
                readSet(row.getBearJson(), d.bear().name()), readSet(row.getBullJson(), d.bull().name()));
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

    public static void apply(RainbowLiveTrendConfig row, Resolved r) {
        TrendMixCalculator.Params p = r.trend();
        row.setShortWindow(p.shortWindow());
        row.setMediumWindow(p.mediumWindow());
        row.setLongWindow(p.longWindow());
        row.setSlopeScale(p.slopeScale());
        row.setEnterThreshold(p.enter());
        row.setExitThreshold(p.exit());
        row.setConfirmDays(p.confirm());
        row.setSmaPeriod(p.smaPeriod());
        row.setAtrPeriod(p.atrPeriod());
        row.setAtrMultiplier(p.atrMultiplier());
        row.setWickDown(p.wickDown());
        row.setWickUp(p.wickUp());
        row.setRangeMapping(r.range().name());
        row.setBearJson(writeSet(r.bear()));
        row.setBullJson(writeSet(r.bull()));
    }

    public static void validate(Resolved r) {
        TrendMixCalculator.Params p = r.trend();
        if (p.shortWindow() < 2 || p.mediumWindow() < 2 || p.longWindow() < 2) {
            throw new IllegalArgumentException("Trend : fenêtres de régression ≥ 2");
        }
        if (p.slopeScale() <= 0 || p.enter() <= 0 || p.exit() < 0 || p.exit() > p.enter()) {
            throw new IllegalArgumentException("Trend : slopeScale > 0, enter > 0, 0 ≤ exit ≤ enter");
        }
        if (p.confirm() < 1 || p.smaPeriod() < 2 || p.atrPeriod() < 1 || p.atrMultiplier() < 0) {
            throw new IllegalArgumentException("Trend : confirm ≥ 1, smaPeriod ≥ 2, atrPeriod ≥ 1, atrMultiplier ≥ 0");
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

    private static String writeSet(RainbowAtrParamSet s) {
        try {
            return JSON.writeValueAsString(new ConfigDto(s.tuning(), s.globals()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RainbowAtrParamSet readSet(String json, String name) {
        try {
            ConfigDto c = JSON.readValue(json, ConfigDto.class);
            return new RainbowAtrParamSet(name, c.tuning(), c.globals());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Jeu Rainbow illisible en base : " + e.getMessage(), e);
        }
    }
}
