package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Affectation : écart entre provider de référence des presets et providers des liens (information)")
class ExpertAffectationProviderGapTest {

    private static RainbowLiveBinding binding(String asset, WebProviderCode code) {
        Wallet wallet = mock(Wallet.class);
        when(wallet.getWebProviderCode()).thenReturn(code);
        RainbowLiveBinding b = mock(RainbowLiveBinding.class);
        when(b.getAssetSymbol()).thenReturn(asset);
        when(b.getWallet()).thenReturn(wallet);
        return b;
    }

    private static AssetAffectation affectation(String trendProvider, String upProvider) {
        TrendPreset trend = TrendPreset.builder().id(1L).name("T").origin(PresetOrigin.SYSTEM).referenceProvider(trendProvider).build();
        RainbowPreset up = RainbowPreset.builder().id(2L).name("U").origin(PresetOrigin.SYSTEM).referenceProvider(upProvider).build();
        RainbowPreset down = RainbowPreset.builder().id(3L).name("D").origin(PresetOrigin.SYSTEM).build();
        return AssetAffectation.builder().id(9L).assetSymbol("BTC").trendPreset(trend).rainbowUp(up).rainbowDown(down).build();
    }

    @Test
    @DisplayName("Écart si un lien de l'actif lit un autre provider ; sans lien, sans provider de référence ou lien d'un autre actif : pas d'écart")
    void gap() {
        AffectationDto dto = ExpertAffectationService.dto(affectation("KRAKEN", "OKX"),
                List.of(binding("BTC", WebProviderCode.OKX), binding("ETH", WebProviderCode.BINANCE)));
        assertEquals(List.of("OKX"), dto.providerGaps().getFirst().bindingProviders());
        assertTrue(dto.providerGaps().get(0).gap(), "Trend KRAKEN vs lien OKX");
        assertFalse(dto.providerGaps().get(1).gap(), "Rainbow UP OKX = lien OKX");
        assertFalse(dto.providerGaps().get(2).gap(), "Rainbow DOWN sans provider de référence");

        assertTrue(ExpertAffectationService.dto(affectation("KRAKEN", "OKX"), List.of()).providerGaps().stream().noneMatch(g -> g.gap()));
    }
}
