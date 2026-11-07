package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.PhaseDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SwitchDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.TrendMetricsDto;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Régimes de simulation : Trend brut (UP / DOWN / RANGE / indéfini) → sortie finale UP / DOWN uniquement ({@code rangeMapping}
 * appliqué en interne), {@code null} = barre sans régime résolu (inactive). Phases, bascules et métriques sur une plage.
 */
final class SimulationRegimes {

    private SimulationRegimes() {
    }

    /**
     * RANGE → selon {@code mapping} (garder le précédent / DOWN / UP) ; indéfini (warmup) → on garde le précédent. Tant
     * qu'aucun régime n'a été résolu, la barre reste {@code null} : contrairement à {@code RainbowSetSelector} il n'y a pas de
     * jeu par défaut, la barre est inactive.
     */
    static TrendRegime[] resolve(TrendRegime[] raw, RangeMapping mapping) {
        TrendRegime[] out = new TrendRegime[raw.length];
        TrendRegime current = null;
        for (int i = 0; i < raw.length; i++) {
            TrendRegime r = raw[i];
            if (r == TrendRegime.UP || r == TrendRegime.DOWN) {
                current = r;
            } else if (r == TrendRegime.RANGE) {
                switch (mapping) {
                    case TO_BEAR -> current = TrendRegime.DOWN;
                    case TO_BULL -> current = TrendRegime.UP;
                    case KEEP_PREVIOUS -> { }
                }
            }
            out[i] = current;
        }
        return out;
    }

    /** Segments contigus d'un même régime résolu sur {@code [start, end]} (les barres sans régime n'en font pas partie). */
    static List<PhaseDto> phases(TrendRegime[] regime, long[] time, int start, int end) {
        List<PhaseDto> phases = new ArrayList<>();
        int from = -1;
        for (int i = start; i <= end + 1; i++) {
            TrendRegime r = i <= end ? regime[i] : null;
            boolean continues = from >= 0 && r == regime[from];
            if (from >= 0 && !continues) {
                phases.add(new PhaseDto(regime[from].name(), day(time[from]), day(time[i - 1]), i - from));
                from = -1;
            }
            if (from < 0 && r != null) {
                from = i;
            }
        }
        return phases;
    }

    /** Bascules UP ↔ DOWN entre deux barres consécutives résolues (la 1re résolution n'est pas une bascule). */
    static List<SwitchDto> switches(TrendRegime[] regime, long[] time, int start, int end) {
        List<SwitchDto> switches = new ArrayList<>();
        for (int i = Math.max(start, 1); i <= end; i++) {
            if (regime[i] != null && regime[i - 1] != null && regime[i] != regime[i - 1]) {
                switches.add(new SwitchDto(day(time[i]), regime[i - 1].name(), regime[i].name()));
            }
        }
        return switches;
    }

    static TrendMetricsDto metrics(List<PhaseDto> phases, List<SwitchDto> switches, TrendRegime[] regime, int start, int end) {
        int up = 0;
        int down = 0;
        int upPhases = 0;
        int downPhases = 0;
        int longest = 0;
        int shortest = Integer.MAX_VALUE;
        for (PhaseDto p : phases) {
            boolean isUp = TrendRegime.UP.name().equals(p.regime());
            up += isUp ? p.bars() : 0;
            down += isUp ? 0 : p.bars();
            upPhases += isUp ? 1 : 0;
            downPhases += isUp ? 0 : 1;
            longest = Math.max(longest, p.bars());
            shortest = Math.min(shortest, p.bars());
        }
        int resolved = up + down;
        int total = end - start + 1;
        return new TrendMetricsDto(switches.size(), phases.size(), phases.isEmpty() ? 0 : (double) resolved / phases.size(),
                upPhases == 0 ? 0 : (double) up / upPhases, downPhases == 0 ? 0 : (double) down / downPhases, longest,
                phases.isEmpty() ? 0 : shortest, resolved == 0 ? 0 : (double) up / resolved, resolved, total - resolved,
                regime[end] == null ? null : regime[end].name());
    }

    static LocalDate day(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
