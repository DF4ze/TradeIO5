package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.portfolio.StrategyStateDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;

/**
 * Traduit l'état de fin de jour de la machine Rainbow en un état lisible pour le Journal. Même priorité que
 * {@code RainbowAtrStrategy} : moon, vente armée, cooldown, verrou d'achat, achat armé, sinon simple surveillance.
 * Pure : ne lit que ce qui a déjà été figé par la passe du jour (signal, état moteur, config effective).
 */
public final class StrategyStateDescriber {

    private StrategyStateDescriber() {
    }

    /** @return l'état, ou {@code null} si la passe du jour n'a pas été jouée ({@code signal} nul) */
    public static StrategyStateDto describe(RainbowLivePassBlock signal, RainbowLiveEngineState engine, RainbowAtrConfig config) {
        if (signal == null) {
            return null;
        }
        boolean locked = Boolean.TRUE.equals(signal.getBuyLocked());
        int cooldown = signal.getCooldownRemaining() == null ? 0 : signal.getCooldownRemaining();
        Double close = signal.getClose();
        if (Boolean.TRUE.equals(signal.getMoonMode())) {
            Double peak = engine == null ? null : engine.getMoonPeak();
            return build(signal, "MOON_ACTIVE", peak, below(peak, config == null ? null : config.getMoonTrailingStopPct()),
                    null, 0, locked);
        }
        if (Boolean.TRUE.equals(signal.getSellArmed())) {
            Double high = engine == null ? null : engine.getHighestSinceArmed();
            return build(signal, "SELL_TRAILING", high, below(high, config == null ? null : config.getTrailingStopSellPct()),
                    engine == null ? null : engine.getSellArmedDays(), 0, locked);
        }
        if (cooldown > 0) {
            return build(signal, "COOLDOWN", null, null, null, cooldown, locked);
        }
        if (locked) {
            return build(signal, "LOCKED_UNTIL_DOWN2", null, null, null, 0, true);
        }
        if (Boolean.TRUE.equals(signal.getBuyArmed())) {
            Double low = engine == null ? null : engine.getLowestSinceArmed();
            return build(signal, "BUY_TRAILING", low, above(low, config == null ? null : config.getTrailingStopBuyPct()),
                    engine == null ? null : engine.getBuyArmedDays(), 0, false);
        }
        return build(signal, "WATCHING", null, null, null, 0, false);
    }

    private static StrategyStateDto build(RainbowLivePassBlock s, String kind, Double ref, Double trigger, Integer armedDays,
                                          int cooldown, boolean locked) {
        return new StrategyStateDto(kind, s.getClose(), ref, trigger, s.getBoundDown2(), cooldown > 0 ? cooldown : null,
                armedDays, locked, s.getZone(), s.getActiveSet(), s.getTrendRegime(),
                s.getAthDistance(), s.getBuyFactor(), s.getSellFactor());
    }

    private static Double below(Double ref, Double pct) {
        return ref == null || pct == null ? null : ref * (1 - pct / 100.0);
    }

    private static Double above(Double ref, Double pct) {
        return ref == null || pct == null ? null : ref * (1 + pct / 100.0);
    }
}
