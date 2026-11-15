package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.DrawdownDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullSeriesDto;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Bloc drawdown du résultat du simulateur complet")
class FullSimulationDrawdownTest {

    private static FullSeriesDto series(List<Number> wallet, List<Number> fixed) {
        List<String> d = new java.util.ArrayList<>();
        for (int i = 0; i < wallet.size(); i++) {
            d.add(java.time.LocalDate.parse("2025-03-01").plusDays(i).toString());
        }
        return new FullSeriesDto(d, wallet, fixed, List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("Verdict par profil : -30 % dépasse Prudent (-15) et Équilibré (-25), respecte Dynamique (-40) et Full (-60)")
    void verdictPerProfile() {
        FullSeriesDto s = series(List.of(1000, 1200, 840, 1300), List.of(1000, 1100, 1000, 1200));
        DrawdownDto dd = FullSimulationService.drawdown(s, StartingProfile.BALANCED, RainbowModulationControl.defaults(Instant.EPOCH, null));
        assertEquals(-30.0, dd.strategyMeasure().maxPct(), 1e-9);
        assertEquals(-9.0909, dd.fixedMeasure().maxPct(), 1e-3);
        assertEquals(4, dd.verdicts().size());
        assertEquals(List.of(false, false, true, true), dd.verdicts().stream().map(v -> v.respected()).toList());
        assertEquals("2025-03-03", dd.verdicts().getFirst().firstBreachDay().toString());
        assertEquals(15.0, dd.verdicts().getFirst().maxOverrunPts(), 1e-9);
        assertEquals("BALANCED", dd.defaultProfile());
        assertEquals(4, dd.strategy().size());
    }

    @Test
    @DisplayName("CUSTOM ou aucun profil : pas de profil présélectionné")
    void customHasNoDefault() {
        FullSeriesDto s = series(List.of(100, 90), List.of(100, 95));
        RainbowModulationControl c = RainbowModulationControl.defaults(Instant.EPOCH, null);
        DrawdownDto custom = FullSimulationService.drawdown(s, StartingProfile.CUSTOM, c);
        assertEquals("CUSTOM", custom.userProfile());
        assertNull(custom.defaultProfile());
        assertNull(FullSimulationService.drawdown(s, null, c).userProfile());
    }

    @Test
    @DisplayName("Changer un plafond change le verdict, pas la mesure")
    void capChangesVerdictOnly() {
        FullSeriesDto s = series(List.of(1000, 800, 1000), List.of(1000, 1000, 1000));
        RainbowModulationControl c = RainbowModulationControl.defaults(Instant.EPOCH, null);
        DrawdownDto before = FullSimulationService.drawdown(s, StartingProfile.PRUDENT, c);
        assertFalse(before.verdicts().getFirst().respected());
        c.setDrawdownCapPrudentPct(new BigDecimal("-20"));
        DrawdownDto after = FullSimulationService.drawdown(s, StartingProfile.PRUDENT, c);
        assertTrue(after.verdicts().getFirst().respected());
        assertEquals(before.strategyMeasure(), after.strategyMeasure());
    }
}
