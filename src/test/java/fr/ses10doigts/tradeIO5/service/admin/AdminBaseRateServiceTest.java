package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.AssetRates;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rates;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminBaseRateService : écritures journalisées")
class AdminBaseRateServiceTest {

    private final AssetBaseRateService rates = mock(AssetBaseRateService.class);
    private final RainbowModulationControlService control = mock(RainbowModulationControlService.class);
    private final AdminActionLogService journal = mock(AdminActionLogService.class);
    private final AdminBaseRateService service = new AdminBaseRateService(rates, control, journal);

    private static Rates r(String p, String b, String d, String f) {
        return new Rates(new BigDecimal(p), new BigDecimal(b), new BigDecimal(d), new BigDecimal(f));
    }

    private static AssetRates assetRates(Rates r) {
        return new AssetRates("NEWX", r, true, true);
    }

    @Test
    @DisplayName("save : symbole normalisé, délégué au service, journal OK avant/après")
    void saveIsJournaled() {
        Rates before = r("0.0003", "0.0005", "0.0008", "0.0015");
        Rates after = r("0.0004", "0.0006", "0.0009", "0.0016");
        when(rates.rates("NEWX")).thenReturn(assetRates(before));
        when(rates.save("NEWX", after, null, "root")).thenReturn(assetRates(after));

        service.save(" newx ", after, "root");

        verify(rates).save("NEWX", after, null, "root");
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("base-rates"), any(), eq(AdminActionOutcome.OK));
    }

    @Test
    @DisplayName("save refusé : journal REFUSED, exception propagée")
    void refusedIsJournaled() {
        Rates bad = r("0.0009", "0.0005", "0.0008", "0.0015");
        when(rates.rates("NEWX")).thenReturn(assetRates(bad));
        when(rates.save("NEWX", bad, null, "root")).thenThrow(new IllegalArgumentException("bad"));

        assertThrows(IllegalArgumentException.class, () -> service.save("NEWX", bad, "root"));

        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("base-rates"), any(), eq(AdminActionOutcome.REFUSED));
    }

    @Test
    @DisplayName("Défauts de l'actif : délégué, journal OK")
    void defaultsAreJournaled() {
        Rates table = r("0.0002774", "0.0004736", "0.0007712", "0.001423");
        when(rates.rates("BTC")).thenReturn(new AssetRates("BTC", table, true, true));
        when(rates.resetDefaults("BTC", "root")).thenReturn(new AssetRates("BTC", table, true, true));

        service.resetDefaults("btc", "root");

        verify(rates).resetDefaults("BTC", "root");
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("base-rates"), any(), eq(AdminActionOutcome.OK));
    }

    @Test
    @DisplayName("Marge k : écrite via le service de modulation, journalisée ; hors bornes => REFUSED")
    void marginKIsJournaled() {
        when(control.current()).thenReturn(RainbowModulationControl.defaults(Instant.EPOCH, null));
        service.updateMarginK(new BigDecimal("1.25"), "root");
        verify(control).updateMarginK(new BigDecimal("1.25"), "root");
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("base-rates"), any(), eq(AdminActionOutcome.OK));

        when(control.updateMarginK(new BigDecimal("9"), "root")).thenThrow(new IllegalArgumentException("bornes"));
        assertThrows(IllegalArgumentException.class, () -> service.updateMarginK(new BigDecimal("9"), "root"));
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("base-rates"), any(), eq(AdminActionOutcome.REFUSED));
    }
}
