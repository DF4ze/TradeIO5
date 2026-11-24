package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.LinkState;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.SuspensionReason;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityService;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LinkStateService : suspension (liste noire, provider déconnecté, route retirée) et attente de validation")
class LinkStateServiceTest {

    private final AssetBlocklistRepository blocklist = mock(AssetBlocklistRepository.class);
    private final AssetBaseRateService rates = mock(AssetBaseRateService.class);
    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final AssetEligibilityService eligibility = mock(AssetEligibilityService.class);
    private final LinkStateService service = new LinkStateService(blocklist, rates, catalog, eligibility);
    private WebProvider provider;
    private ApiCredential credential;
    private RainbowLiveBinding binding;

    @BeforeEach
    void setUp() {
        provider = WebProvider.builder().code(WebProviderCode.OKX).name("OKX").enabled(true).build();
        credential = new ApiCredential();
        credential.setEnabled(true);
        Wallet wallet = Wallet.builder().id(9L).webProvider(provider).credential(credential).build();
        binding = RainbowLiveBinding.builder().id(1L).assetSymbol("SOL").wallet(wallet).build();
        when(rates.isCalibrated("SOL")).thenReturn(true);
        when(catalog.supports(WebProviderCode.OKX)).thenReturn(true);
        when(catalog.storedLiveInstruments(WebProviderCode.OKX)).thenReturn(List.of());
    }

    @Test
    @DisplayName("Lien sain et calibré : aucun état, ordres réels permis")
    void healthy() {
        LinkState state = service.of(binding);
        assertFalse(state.suspended());
        assertFalse(state.pendingValidation());
        assertTrue(state.allowsRealOrders());
        assertNull(state.blockMessage());
    }

    @Test
    @DisplayName("Actif en liste noire => suspendu BLOCKLISTED")
    void blocklisted() {
        when(blocklist.existsByAssetSymbol("SOL")).thenReturn(true);
        LinkState state = service.of(binding);
        assertEquals(SuspensionReason.BLOCKLISTED, state.suspension());
        assertTrue(state.suspensionMessage().startsWith("Suspendu : "));
        assertFalse(state.allowsRealOrders());
    }

    @Test
    @DisplayName("Clé désactivée ou provider désactivé => suspendu PROVIDER_DISCONNECTED")
    void providerDisconnected() {
        credential.setEnabled(false);
        assertEquals(SuspensionReason.PROVIDER_DISCONNECTED, service.of(binding).suspension());
        credential.setEnabled(true);
        provider.setEnabled(false);
        assertEquals(SuspensionReason.PROVIDER_DISCONNECTED, service.of(binding).suspension());
    }

    @Test
    @DisplayName("Catalogue chargé sans route d'achat => suspendu ROUTE_REMOVED ; catalogue vide => rien")
    void routeRemoved() {
        assertFalse(service.of(binding).suspended());
        when(catalog.storedLiveInstruments(WebProviderCode.OKX)).thenReturn(List.of(mock(fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo.class)));
        when(eligibility.hasBuyRoute(anyList(), eq("SOL"))).thenReturn(false);
        assertEquals(SuspensionReason.ROUTE_REMOVED, service.of(binding).suspension());
        when(eligibility.hasBuyRoute(anyList(), eq("SOL"))).thenReturn(true);
        assertFalse(service.of(binding).suspended());
    }

    @Test
    @DisplayName("Sans ligne asset_base_rate valide => en attente de validation, ordres réels refusés avec message")
    void pendingValidation() {
        when(rates.isCalibrated("SOL")).thenReturn(false);
        LinkState state = service.of(binding);
        assertTrue(state.pendingValidation());
        assertFalse(state.suspended());
        assertEquals(LinkStateService.PENDING_VALIDATION_MESSAGE, state.blockMessage());
    }

    @Test
    @DisplayName("Lien archivé : aucun état")
    void archived() {
        binding.setArchivedAt(java.time.Instant.now());
        when(blocklist.existsByAssetSymbol(any())).thenReturn(true);
        assertEquals(LinkState.NONE, service.of(binding));
    }
}
