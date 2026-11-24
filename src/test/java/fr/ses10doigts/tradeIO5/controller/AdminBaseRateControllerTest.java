package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.AssetRates;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rates;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AdminBaseRateController : contenu des réponses")
class AdminBaseRateControllerTest {

    private final AssetBaseRateService rates = mock(AssetBaseRateService.class);
    private final RainbowModulationControlService control = mock(RainbowModulationControlService.class);
    private final AdminBaseRateController controller = new AdminBaseRateController(rates, control, mock(AdminBaseRateService.class), mock(IAuthenticationFacade.class));

    private static Rates r(String p, String b, String d, String f) {
        return new Rates(new BigDecimal(p), new BigDecimal(b), new BigDecimal(d), new BigDecimal(f));
    }

    @Test
    @DisplayName("GET : actifs avec leurs 4 taux et le drapeau calibre ; repli = actif BTC annoncé ; bornes d'un taux et marge k avec défaut / bornes")
    void listShape() {
        RainbowModulationControl c = RainbowModulationControl.defaults(Instant.EPOCH, null);
        c.setBaseRateMarginK(new BigDecimal("1.3"));
        when(control.current()).thenReturn(c);
        Rates btc = r("0.0002774", "0.0004736", "0.0007712", "0.001423");
        when(rates.list()).thenReturn(List.of(new AssetRates("BTC", btc, true, true), new AssetRates("PAXG", btc, false, false)));

        var dto = controller.list();

        assertEquals(2, dto.assets().size());
        var first = dto.assets().getFirst();
        assertEquals("BTC", first.asset());
        assertEquals(0, new BigDecimal("0.0004736").compareTo(first.balanced()));
        assertTrue(first.calibre() && first.dedicated());
        assertNull(first.fallbackAsset());
        var paxg = dto.assets().get(1);
        assertFalse(paxg.calibre() || paxg.dedicated());
        assertEquals("BTC", paxg.fallbackAsset());
        assertEquals(ModulationDefaults.BASE_RATE_MIN, dto.rateMin());
        assertEquals(ModulationDefaults.BASE_RATE_MAX, dto.rateMax());
        assertEquals(0, new BigDecimal("1.3").compareTo((BigDecimal) dto.marginK().value()));
        assertEquals(0, new BigDecimal("1.1").compareTo(dto.marginK().defaultValue()));
        assertEquals(0, BigDecimal.ONE.compareTo(dto.marginK().min()));
        assertEquals(0, new BigDecimal("3").compareTo(dto.marginK().max()));
    }

    @Test
    @DisplayName("Valeur invalide => 400 avec le message (rien n'est écrit)")
    void badRequestHandler() {
        var response = controller.badRequest(new IllegalArgumentException("Taux non croissants"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Taux non croissants", response.getBody().error());
    }
}
