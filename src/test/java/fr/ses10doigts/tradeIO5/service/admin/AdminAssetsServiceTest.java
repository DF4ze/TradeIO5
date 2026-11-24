package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.repository.market.AssetEligibilitySettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService.AssetView;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService.Calibration;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService.Overview;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.LinkState;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.SuspensionReason;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityDefaults;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityService;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilitySettingsService;
import fr.ses10doigts.tradeIO5.service.market.eligibility.D1HistoryProbe;
import fr.ses10doigts.tradeIO5.service.market.eligibility.IneligibilityReason;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Onglet admin « Actifs » : liste noire (ajout, retrait, doublon, symbole invalide, effet immédiat), seuil de volume (borne, persistance), vue par actif, journal d'audit. */
@DataJpaTest
@DisplayName("Admin Actifs : liste noire, seuil de volume, vue par actif")
@SuppressWarnings("unchecked")
class AdminAssetsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired private AssetBlocklistRepository blocklist;
    @Autowired private AssetEligibilitySettingsRepository settingsRows;

    private final RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
    private final NewAssetProposalRepository proposals = mock(NewAssetProposalRepository.class);
    private final AdminActionLogService journal = mock(AdminActionLogService.class);
    private final AssetBaseRateService rates = mock(AssetBaseRateService.class);
    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final AssetEligibilityService eligibility = mock(AssetEligibilityService.class);
    private final LinkStateService linkStates = mock(LinkStateService.class);
    private final NewAssetRecomputeService recompute = mock(NewAssetRecomputeService.class);
    private AssetEligibilitySettingsService settings;
    private AdminAssetsService service;

    @BeforeEach
    void setUp() {
        settings = new AssetEligibilitySettingsService(settingsRows, new FixedDomainClock(NOW));
        service = new AdminAssetsService(bindings, blocklist, settingsRows, settings, eligibility, catalog, rates, linkStates, proposals, recompute, journal,
                new FixedDomainClock(NOW));
    }

    private ArgumentCaptor<Map<String, Object>> verifyAudit(String target, AdminActionOutcome outcome) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq(target), payload.capture(), eq(outcome));
        return payload;
    }

    // ------------------------------------------------------------------ liste noire

    @Test
    @DisplayName("Ajout : symbole normalisé, auteur et date, ligne écrite, audit OK (cible asset-blocklist:<SYM>, payload JSON)")
    void addNormalizesAndAudits() {
        AdminAssetsService.BlocklistEntry entry = service.addToBlocklist(" sol ", " illiquide ", "root");

        assertEquals("SOL", entry.asset());
        assertEquals("illiquide", entry.reason());
        assertEquals("root", entry.createdBy());
        assertEquals(NOW, entry.createdAt());
        assertTrue(blocklist.existsByAssetSymbol("SOL"));
        ArgumentCaptor<Map<String, Object>> payload = verifyAudit("asset-blocklist:SOL", AdminActionOutcome.OK);
        assertEquals("blocklist-add", payload.getValue().get("action"));
        assertEquals("illiquide", payload.getValue().get("reason"));
    }

    @Test
    @DisplayName("Doublon : refusé (IllegalArgumentException), une seule ligne, audit REFUSED")
    void duplicateRefused() {
        service.addToBlocklist("SOL", null, "root");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist("sol", "bis", "root"));
        assertTrue(e.getMessage().contains("déjà en liste noire"));
        assertEquals(1, blocklist.count());
        verifyAudit("asset-blocklist:SOL", AdminActionOutcome.REFUSED);
    }

    @Test
    @DisplayName("Symbole invalide (vide, caractères, trop long) ou motif trop long : refusé, rien écrit")
    void invalidRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist(null, null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist("  ", null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist("S", null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist("SO-L", null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist("A".repeat(17), null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.addToBlocklist("SOL", "x".repeat(AdminAssetsService.REASON_MAX + 1), "root"));
        assertEquals(0, blocklist.count());
    }

    @Test
    @DisplayName("Retrait : ligne supprimée, audit OK ; actif absent => refusé")
    void remove() {
        service.addToBlocklist("SOL", "motif", "root");
        service.removeFromBlocklist("sol", "root");

        assertFalse(blocklist.existsByAssetSymbol("SOL"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(journal, org.mockito.Mockito.times(2)).record(eq("root"), eq(AdminAction.MODULATION), eq("asset-blocklist:SOL"), payload.capture(), eq(AdminActionOutcome.OK));
        assertEquals("blocklist-remove", payload.getAllValues().getLast().get("action"));
        assertThrows(IllegalArgumentException.class, () -> service.removeFromBlocklist("SOL", "root"));
    }

    @Test
    @DisplayName("Effet immédiat sans redémarrage ni cache : l'ajout suspend le lien et refuse le nouveau lien, le retrait lève les deux")
    void blocklistEffectIsImmediate() {
        WebProvider provider = WebProvider.builder().code(WebProviderCode.OKX).name("OKX").enabled(true).build();
        ApiCredential credential = new ApiCredential();
        credential.setEnabled(true);
        RainbowLiveBinding link = RainbowLiveBinding.builder().id(1L).assetSymbol("SOL").wallet(Wallet.builder().id(9L).webProvider(provider).credential(credential).build()).build();
        when(rates.isCalibrated("SOL")).thenReturn(true);
        when(catalog.supports(WebProviderCode.OKX)).thenReturn(true);
        when(catalog.storedLiveInstruments(WebProviderCode.OKX)).thenReturn(List.of());
        LinkStateService realStates = new LinkStateService(blocklist, rates, catalog, mock(AssetEligibilityService.class));
        AssetEligibilityService realEligibility = new AssetEligibilityService(catalog, blocklist, settings, mock(PathFinder.class), mock(D1HistoryProbe.class),
                new FixedDomainClock(NOW), "");

        assertFalse(realStates.of(link).suspended());
        assertNotEquals(IneligibilityReason.BLOCKLISTED, realEligibility.check("SOL", provider).reason());

        service.addToBlocklist("SOL", "test", "root");
        assertEquals(SuspensionReason.BLOCKLISTED, realStates.of(link).suspension());
        assertFalse(realStates.of(link).allowsRealOrders());
        assertEquals(IneligibilityReason.BLOCKLISTED, realEligibility.check("SOL", provider).reason());

        service.removeFromBlocklist("SOL", "root");
        assertFalse(realStates.of(link).suspended());
        assertNotEquals(IneligibilityReason.BLOCKLISTED, realEligibility.check("SOL", provider).reason());
    }

    // ------------------------------------------------------------------ seuil de volume

    @Test
    @DisplayName("Seuil : défaut 1 M$ sans ligne, 0 accepté (borne), persisté et relu, auteur et audit avant / après")
    void minVolumeBoundsAndPersistence() {
        assertEquals(0, AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD.compareTo(service.settings().minVolumeUsd()));
        assertEquals(0, new BigDecimal("1000000").compareTo(service.settings().defaultMinVolumeUsd()));
        assertNull(service.settings().updatedBy());

        AdminAssetsService.SettingsView zero = service.updateMinVolume(BigDecimal.ZERO, "root");
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.minVolumeUsd()));

        AdminAssetsService.SettingsView saved = service.updateMinVolume(new BigDecimal("2500000"), "root");
        assertEquals(0, new BigDecimal("2500000").compareTo(saved.minVolumeUsd()));
        assertEquals("root", saved.updatedBy());
        assertEquals(NOW, saved.updatedAt());
        // relu par un service neuf sur le même stockage : persisté, pas un cache
        assertEquals(0, new BigDecimal("2500000").compareTo(new AssetEligibilitySettingsService(settingsRows, new FixedDomainClock(NOW)).minVolumeUsd()));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(journal, org.mockito.Mockito.times(2)).record(eq("root"), eq(AdminAction.MODULATION), eq("asset-settings:min-volume"), payload.capture(), eq(AdminActionOutcome.OK));
        assertEquals("0", payload.getAllValues().getFirst().get("after"));
        assertEquals("1000000", payload.getAllValues().getFirst().get("before"));
        assertEquals("2500000", payload.getAllValues().getLast().get("after"));
    }

    @Test
    @DisplayName("Seuil négatif ou absent : refusé, valeur inchangée, audit REFUSED")
    void minVolumeRefused() {
        service.updateMinVolume(new BigDecimal("5"), "root");

        assertThrows(IllegalArgumentException.class, () -> service.updateMinVolume(new BigDecimal("-1"), "root"));
        assertThrows(IllegalArgumentException.class, () -> service.updateMinVolume(null, "root"));
        assertEquals(0, new BigDecimal("5").compareTo(settings.minVolumeUsd()));
        verify(journal, org.mockito.Mockito.times(2)).record(eq("root"), eq(AdminAction.MODULATION), eq("asset-settings:min-volume"), any(), eq(AdminActionOutcome.REFUSED));
    }

    // ------------------------------------------------------------------ vue par actif

    private RainbowLiveBinding link(long id, long userId, String asset, WebProviderCode provider) {
        return RainbowLiveBinding.builder().id(id).assetSymbol(asset).user(User.builder().id(userId).build())
                .wallet(Wallet.builder().id(id).webProviderCode(provider).build()).build();
    }

    @Test
    @DisplayName("Vue : liens, utilisateurs, calibration, proposition, bloqué, suspendus ; volume vérifié ou « non vérifiable » ; actif en liste noire sans lien listé")
    void overview() {
        RainbowLiveBinding sol1 = link(1, 10, "SOL", WebProviderCode.OKX);
        RainbowLiveBinding sol2 = link(2, 11, "SOL", WebProviderCode.OKX);
        RainbowLiveBinding avax = link(3, 10, "AVAX", WebProviderCode.OKX);
        when(bindings.findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc()).thenReturn(List.of(sol1, sol2, avax));
        when(catalog.storedInstruments(WebProviderCode.OKX)).thenReturn(List.of());
        when(eligibility.usdVolumes(anyList())).thenReturn(Map.of("SOL", new BigDecimal("5000000")));
        when(rates.isCalibrated("SOL")).thenReturn(true);
        when(rates.isCalibrated("AVAX")).thenReturn(false);
        when(rates.isCalibrated("ZZZ")).thenReturn(false);
        when(linkStates.of(sol1)).thenReturn(new LinkState(SuspensionReason.BLOCKLISTED, "Suspendu : SOL est en liste noire", false));
        when(linkStates.of(sol2)).thenReturn(new LinkState(SuspensionReason.BLOCKLISTED, "Suspendu : SOL est en liste noire", false));
        when(linkStates.of(avax)).thenReturn(LinkState.NONE);
        when(proposals.findFirstByAssetSymbolOrderByIdDesc(any())).thenAnswer(i -> "AVAX".equals(i.getArgument(0))
                ? Optional.of(NewAssetProposal.builder().status(NewAssetProposalStatus.REFUSE).build()) : Optional.empty());
        when(recompute.enabled()).thenReturn(true);
        when(recompute.running("AVAX")).thenReturn(true);
        service.addToBlocklist("SOL", "illiquide", "root");
        service.addToBlocklist("ZZZ", null, "root");

        Overview overview = service.overview();

        assertEquals(List.of("AVAX", "SOL", "ZZZ"), overview.assets().stream().map(AssetView::asset).toList());
        assertTrue(overview.recomputeEnabled());
        assertEquals(0, AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD.compareTo(overview.minVolumeUsd()));
        AssetView sol = overview.assets().get(1);
        assertEquals(2, sol.linkCount());
        assertEquals(2, sol.userCount());
        assertEquals(Calibration.CALIBRE, sol.calibration());
        assertNull(sol.proposalStatus());
        assertTrue(sol.blocked());
        assertEquals("illiquide", sol.blockReason());
        assertEquals(2, sol.suspendedLinks());
        assertEquals(1, sol.volumes().size());
        assertTrue(sol.volumes().getFirst().verified());
        assertEquals(0, new BigDecimal("5000000").compareTo(sol.volumes().getFirst().volumeUsd()));
        AssetView avaxView = overview.assets().getFirst();
        assertEquals(1, avaxView.linkCount());
        assertEquals(Calibration.EN_ATTENTE_VALIDATION, avaxView.calibration());
        assertEquals(NewAssetProposalStatus.REFUSE, avaxView.proposalStatus());
        assertFalse(avaxView.blocked());
        assertEquals(0, avaxView.suspendedLinks());
        assertTrue(avaxView.recomputeRunning());
        // volume NULL : listé explicitement, non vérifié (éligible mais non vérifiable)
        assertEquals(WebProviderCode.OKX, avaxView.volumes().getFirst().provider());
        assertNull(avaxView.volumes().getFirst().volumeUsd());
        assertFalse(avaxView.volumes().getFirst().verified());
        AssetView zzz = overview.assets().getLast();
        assertEquals(0, zzz.linkCount());
        assertTrue(zzz.blocked());
        assertTrue(zzz.volumes().isEmpty());
    }
}
