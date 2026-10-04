package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Synthèse du delta passe 23:55 vs passe 00:05 (pur, sans Spring). Seuls les jours ayant les deux blocs sont
 * comparés. Écarts en absolu et en % de la valeur 23:55 (valeur 23:55 nulle ⇒ jour exclu du % seulement).
 */
public final class RainbowLiveDeltaCalculator {

    private RainbowLiveDeltaCalculator() {
    }

    public record Action(RainbowLiveAction type, double amountUsdc, double quantity) {
    }

    public record ActionDifference(LocalDate day, Action pass2355, Action pass0005) {
    }

    public record Stat(double meanAbs, double maxAbs, double meanPct, double maxPct) {
    }

    public record ZoneDivergence(LocalDate day, Integer zone2355, Integer zone0005) {
    }

    public record StateDivergence(LocalDate day, List<String> fields) {
    }

    public record Delta(int daysCompared, int daysIgnored, int daysActionDiffers,
                        List<ActionDifference> actionDifferences, Map<String, Stat> indicators,
                        List<ZoneDivergence> zoneDivergences, List<StateDivergence> stateDivergences) {
    }

    private static final Map<String, Function<RainbowLivePassBlock, Double>> INDICATORS = new LinkedHashMap<>();

    static {
        INDICATORS.put("close", RainbowLivePassBlock::getClose);
        INDICATORS.put("sma", RainbowLivePassBlock::getSma);
        INDICATORS.put("atr", RainbowLivePassBlock::getAtr);
        INDICATORS.put("boundDown2", RainbowLivePassBlock::getBoundDown2);
        INDICATORS.put("boundDown1", RainbowLivePassBlock::getBoundDown1);
        INDICATORS.put("boundUp1", RainbowLivePassBlock::getBoundUp1);
        INDICATORS.put("boundUp2", RainbowLivePassBlock::getBoundUp2);
        INDICATORS.put("boundUp3", RainbowLivePassBlock::getBoundUp3);
    }

    public static Delta compute(List<RainbowLiveRun> runs) {
        int compared = 0;
        List<ActionDifference> differences = new ArrayList<>();
        List<ZoneDivergence> zones = new ArrayList<>();
        List<StateDivergence> states = new ArrayList<>();
        Map<String, double[]> acc = new LinkedHashMap<>();
        INDICATORS.keySet().forEach(k -> acc.put(k, new double[5])); // sumAbs, maxAbs, sumPct, maxPct, nPct

        for (RainbowLiveRun run : runs) {
            RainbowLivePassBlock a = run.getPass2355();
            RainbowLivePassBlock b = run.getPass0005();
            if (a == null || b == null) {
                continue;
            }
            compared++;
            if (run.deltaActionDiffers()) {
                differences.add(new ActionDifference(run.getDay(), action(a), action(b)));
            }
            if (!Objects.equals(a.getZone(), b.getZone())) {
                zones.add(new ZoneDivergence(run.getDay(), a.getZone(), b.getZone()));
            }
            List<String> fields = new ArrayList<>();
            addIfDiffers(fields, "moonMode", a.getMoonMode(), b.getMoonMode());
            addIfDiffers(fields, "buyArmed", a.getBuyArmed(), b.getBuyArmed());
            addIfDiffers(fields, "sellArmed", a.getSellArmed(), b.getSellArmed());
            addIfDiffers(fields, "buyLocked", a.getBuyLocked(), b.getBuyLocked());
            addIfDiffers(fields, "cooldownRemaining", a.getCooldownRemaining(), b.getCooldownRemaining());
            if (!fields.isEmpty()) {
                states.add(new StateDivergence(run.getDay(), fields));
            }
            INDICATORS.forEach((name, getter) -> accumulate(acc.get(name), getter.apply(a), getter.apply(b)));
        }

        Map<String, Stat> stats = new LinkedHashMap<>();
        final int daysCompared = compared;
        acc.forEach((name, s) -> stats.put(name, new Stat(
                daysCompared > 0 ? s[0] / daysCompared : 0, s[1], s[4] > 0 ? s[2] / s[4] : 0, s[3])));
        return new Delta(compared, runs.size() - compared, differences.size(), differences, stats, zones, states);
    }

    private static Action action(RainbowLivePassBlock b) {
        return new Action(b.getActionType() == null ? RainbowLiveAction.NONE : b.getActionType(),
                b.getActionAmountUsdc() == null ? 0 : b.getActionAmountUsdc(),
                b.getActionQuantity() == null ? 0 : b.getActionQuantity());
    }

    private static void addIfDiffers(List<String> out, String name, Object x, Object y) {
        if (!Objects.equals(x, y)) {
            out.add(name);
        }
    }

    private static void accumulate(double[] s, Double x, Double y) {
        if (x == null || y == null) {
            return;
        }
        double abs = Math.abs(y - x);
        s[0] += abs;
        s[1] = Math.max(s[1], abs);
        if (x != 0) {
            double pct = abs / Math.abs(x) * 100;
            s[2] += pct;
            s[3] = Math.max(s[3], pct);
            s[4]++;
        }
    }
}
