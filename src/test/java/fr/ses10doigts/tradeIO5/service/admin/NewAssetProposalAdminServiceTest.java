package fr.ses10doigts.tradeIO5.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.model.entity.doctor.Alarm;
import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.repository.doctor.AlarmRepository;
import fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService;
import fr.ses10doigts.tradeIO5.service.doctor.AlarmService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** D1b lot 2 : alerte (une fois, acquittable), Valider (ligne de taux avant le repli BTC, preset dédié jamais écrasé), Refuser, journal. */
@DataJpaTest
@DisplayName("D1b lot 2 : alerte et validation admin des propositions")
class NewAssetProposalAdminServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private NewAssetProposalRepository proposals;
    @Autowired private AssetBaseRateRepository rates;
    @Autowired private TrendPresetRepository trendPresets;
    @Autowired private RainbowPresetRepository rainbowPresets;
    @Autowired private AssetAffectationRepository affectations;
    @Autowired private AlarmRepository alarmRepository;
    @Autowired private PlatformTransactionManager tm;

    private FixedDomainClock clock;
    private AlarmService alarms;
    private AdminActionLogService journal;
    private ExpertBasePresetService basePresets;
    private NewAssetProposalAdminService service;
    private AssetBaseRateService resolver;

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(T0);
        alarms = new AlarmService(alarmRepository, clock);
        journal = mock(AdminActionLogService.class);
        basePresets = new ExpertBasePresetService(trendPresets, rainbowPresets, clock);
        basePresets.ensureBasePresets();
        resolver = new AssetBaseRateService(rates, mock(fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository.class), clock);
        service = new NewAssetProposalAdminService(proposals, resolver, trendPresets, rainbowPresets, affectations, basePresets, alarms, journal,
                new TransactionTemplate(tm), clock);
    }

    private static String levelsJson(double... r) throws Exception {
        String[] names = {"PRUDENT", "BALANCED", "DYNAMIC", "FULL"};
        java.util.ArrayList<Level> l = new java.util.ArrayList<>();
        for (int i = 0; i < r.length; i++) {
            l.add(new Level(names[i], -15.0 * (i + 1), 1.0 + i, "OK", r[i], "2022-01..2023-12"));
        }
        return MAPPER.writeValueAsString(l);
    }

    private NewAssetProposal proposal(String asset, String levels) {
        return proposals.save(NewAssetProposal.builder().assetSymbol(asset).status(NewAssetProposalStatus.PROPOSE).presetSource("BTC")
                .historySource("BINANCE/USDT").candleChecksum("abc123").historyMonths(30).flags("NON_TRADABLE").levelsJson(levels)
                .warning("Actif non tradable").createdAt(T0).build());
    }

    private NewAssetProposal ok(String asset) throws Exception {
        return proposal(asset, levelsJson(3.0e-4, 5.0e-4, 8.0e-4, 1.5e-3));
    }

    private List<Alarm> alarmsOf(String asset) {
        return alarmRepository.findAllByOrderByLastSeenDesc().stream().filter(a -> a.getAlarmKey().equals("NEW_ASSET_PROPOSAL:" + asset)).toList();
    }

    private static Map<String, Object> payload(AdminActionLogService journal, Long id, AdminActionOutcome outcome, String actor) {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(journal).record(eq(actor), eq(AdminAction.MODULATION), eq("new-asset-proposal:" + id), cap.capture(), eq(outcome));
        return cap.getValue();
    }

    // ------------------------------------------------------------------ alerte

    @Test
    @DisplayName("Alarme « actif à valider » : créée une seule fois, avec le lien de l'écran, acquittable, jamais rouverte par un second appel")
    void alarmRaisedOnceAndAcknowledgeable() throws Exception {
        NewAssetProposal p = ok("NEWX");
        service.alert(p);
        service.alert(p);
        List<Alarm> list = alarmsOf("NEWX");
        assertEquals(1, list.size());
        Alarm a = list.getFirst();
        assertEquals(AlarmState.ACTIVE, a.getState());
        assertEquals(1, a.getOccurrences());
        assertTrue(a.getMessage().contains("/admin#modulation") && a.getMessage().contains("NEWX"));
        alarms.acknowledge(a.getId(), "root", "vu");
        service.alert(p);
        assertEquals(1, alarmsOf("NEWX").size());
        assertEquals(AlarmState.ACKED, alarmsOf("NEWX").getFirst().getState());
    }

    @Test
    @DisplayName("Seule une proposition PROPOSE lève l'alarme")
    void noAlarmForDecidedProposal() throws Exception {
        NewAssetProposal p = ok("NEWX");
        p.setStatus(NewAssetProposalStatus.REFUSE);
        service.alert(p);
        assertTrue(alarmsOf("NEWX").isEmpty());
    }

    // ------------------------------------------------------------------ Valider

    @Test
    @DisplayName("Valider : ligne de taux dédiée écrite, résolue AVANT le repli BTC ; alarme close ; statut VALIDE ; journal (qui, quoi)")
    void validateWritesRateLineResolvedBeforeFallback() throws Exception {
        NewAssetProposal p = ok("NEWX");
        service.alert(p);
        var before = resolver.resolve(StartingProfile.BALANCED, "NEWX");
        assertFalse(before.calibrated());
        assertEquals(0, ModulationDefaults.CALIBRATED_RATES.get("BTC").balanced().compareTo(before.value()));

        var view = service.validate(p.getId(), "root");

        assertEquals(NewAssetProposalStatus.VALIDE, view.status());
        assertEquals("root", proposals.findById(p.getId()).orElseThrow().getDecidedBy());
        assertNotNull(proposals.findById(p.getId()).orElseThrow().getDecidedAt());
        var row = rates.findByAssetSymbol("NEWX").orElseThrow();
        assertEquals(p.getId(), row.getProposalId());
        assertEquals("root", row.getValidatedBy());
        var after = resolver.resolve(StartingProfile.BALANCED, "NEWX");
        assertTrue(after.calibrated());
        assertEquals(0, new java.math.BigDecimal("0.00050000").compareTo(after.value()));
        assertEquals(0, new java.math.BigDecimal("0.00150000").compareTo(resolver.resolve(StartingProfile.FULL, "NEWX").value()));
        // BTC / ETH : jamais détournés par une ligne dédiée
        assertEquals(0, ModulationDefaults.CALIBRATED_RATES.get("BTC").balanced().compareTo(resolver.resolve(StartingProfile.BALANCED, "BTC").value()));
        assertEquals(AlarmState.RESOLVED, alarmsOf("NEWX").getFirst().getState());
        Map<String, Object> payload = payload(journal, p.getId(), AdminActionOutcome.OK, "root");
        assertEquals("validate", payload.get("action"));
        assertEquals("NEWX", payload.get("asset"));
        assertEquals("abc123", payload.get("candleChecksum"));
        assertTrue(payload.get("rates").toString().contains("PRUDENT=0.00030000"));
    }

    @Test
    @DisplayName("Actif sans aucun preset dédié : les presets de base BTC (Trend, Bull, Bear) lui sont copiés à l'identique")
    void noDedicatedPresetGetsBtcPresets() throws Exception {
        NewAssetProposal p = ok("NEWX");
        assertEquals(NewAssetProposalAdminService.PresetPlan.APPLIQUE_BTC, service.list(false).getFirst().presetPlan());
        service.validate(p.getId(), "root");
        TrendPreset btcTrend = trendPresets.findByAssetSymbolAndBaseRole("BTC", BasePresetRole.TREND).orElseThrow();
        TrendPreset trend = trendPresets.findByAssetSymbolAndBaseRole("NEWX", BasePresetRole.TREND).orElseThrow();
        assertEquals(btcTrend.toParams(), trend.toParams());
        RainbowPreset btcBull = rainbowPresets.findByAssetSymbolAndBaseRole("BTC", BasePresetRole.RAINBOW_BULL).orElseThrow();
        RainbowPreset bull = rainbowPresets.findByAssetSymbolAndBaseRole("NEWX", BasePresetRole.RAINBOW_BULL).orElseThrow();
        assertEquals(btcBull.getConfig().hash(), bull.getConfig().hash());
        assertTrue(rainbowPresets.findByAssetSymbolAndBaseRole("NEWX", BasePresetRole.RAINBOW_BEAR).isPresent());
        assertEquals(3, (Integer) (int) payload(journal, p.getId(), AdminActionOutcome.OK, "root").get("presetsCreated"));
    }

    @Test
    @DisplayName("PAXG (preset réglé à la main) : jamais écrasé, annoncé « dédié conservé » avant validation ; seule la ligne de taux est écrite")
    void dedicatedPaxgPresetIsNeverOverwritten() throws Exception {
        TrendPreset paxgTrend = trendPresets.findByAssetSymbolAndBaseRole("PAXG", BasePresetRole.TREND).orElseThrow();
        paxgTrend.setAtrMultiplier(7.77);
        trendPresets.saveAndFlush(paxgTrend);
        RainbowPreset paxgBull = rainbowPresets.findByAssetSymbolAndBaseRole("PAXG", BasePresetRole.RAINBOW_BULL).orElseThrow();
        String bullHash = paxgBull.getConfig().hash();
        long trendCount = trendPresets.count();
        long rainbowCount = rainbowPresets.count();
        NewAssetProposal p = ok("PAXG");
        var preview = service.list(false).getFirst();
        assertEquals(NewAssetProposalAdminService.PresetPlan.DEDIE_CONSERVE, preview.presetPlan());
        assertTrue(preview.presetPlanText().contains("conservé"));

        service.validate(p.getId(), "root");

        assertEquals(trendCount, trendPresets.count());
        assertEquals(rainbowCount, rainbowPresets.count());
        assertEquals(7.77, trendPresets.findByAssetSymbolAndBaseRole("PAXG", BasePresetRole.TREND).orElseThrow().getAtrMultiplier());
        assertEquals(bullHash, rainbowPresets.findByAssetSymbolAndBaseRole("PAXG", BasePresetRole.RAINBOW_BULL).orElseThrow().getConfig().hash());
        assertTrue(rates.findByAssetSymbol("PAXG").isPresent());
        assertEquals("DEDIE_CONSERVE", payload(journal, p.getId(), AdminActionOutcome.OK, "root").get("presetPlan"));
    }

    @Test
    @DisplayName("Proposition sans taux, ou taux invalides : validation refusée, rien écrit, statut inchangé, refus journalisé")
    void invalidProposalCannotBeValidated() throws Exception {
        NewAssetProposal none = proposal("NORATE", null);
        assertFalse(service.list(false).getFirst().canValidate());
        assertThrows(IllegalArgumentException.class, () -> service.validate(none.getId(), "root"));
        NewAssetProposal inverted = proposal("INV", levelsJson(9.0e-4, 5.0e-4, 8.0e-4, 1.5e-3));
        assertThrows(IllegalArgumentException.class, () -> service.validate(inverted.getId(), "root"));
        NewAssetProposal tiny = proposal("TINY", levelsJson(1.0e-7, 5.0e-4, 8.0e-4, 1.5e-3));
        assertThrows(IllegalArgumentException.class, () -> service.validate(tiny.getId(), "root"));
        assertTrue(rates.findAll().isEmpty());
        assertEquals(NewAssetProposalStatus.PROPOSE, proposals.findById(none.getId()).orElseThrow().getStatus());
        assertEquals(NewAssetProposalStatus.PROPOSE, proposals.findById(inverted.getId()).orElseThrow().getStatus());
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("new-asset-proposal:" + none.getId()), any(), eq(AdminActionOutcome.REFUSED));
    }

    // ------------------------------------------------------------------ Refuser

    @Test
    @DisplayName("Refuser : aucune ligne de taux, repli BTC « non calibré » conservé, alarme close, statut REFUSE, journal ; décision unique")
    void refuseKeepsBtcFallback() throws Exception {
        NewAssetProposal p = ok("NEWX");
        service.alert(p);
        long trendCount = trendPresets.count();

        var view = service.refuse(p.getId(), "root");

        assertEquals(NewAssetProposalStatus.REFUSE, view.status());
        assertTrue(rates.findByAssetSymbol("NEWX").isEmpty());
        assertEquals(trendCount, trendPresets.count());
        var r = resolver.resolve(StartingProfile.DYNAMIC, "NEWX");
        assertFalse(r.calibrated());
        assertEquals(0, ModulationDefaults.CALIBRATED_RATES.get("BTC").dynamic().compareTo(r.value()));
        assertEquals(AlarmState.RESOLVED, alarmsOf("NEWX").getFirst().getState());
        assertEquals("refuse", payload(journal, p.getId(), AdminActionOutcome.OK, "root").get("action"));
        assertThrows(IllegalArgumentException.class, () -> service.refuse(p.getId(), "root"));
        assertThrows(IllegalArgumentException.class, () -> service.validate(p.getId(), "root"));
        assertTrue(rates.findAll().isEmpty());
    }

    @Test
    @DisplayName("Aucune application automatique : une proposition en attente ne change ni taux ni preset ; profil sans table = null")
    void nothingChangesWithoutAdminAction() throws Exception {
        ok("NEWX");
        long trendCount = trendPresets.count();
        assertTrue(rates.findAll().isEmpty());
        assertEquals(trendCount, trendPresets.count());
        assertEquals(NewAssetProposalStatus.PROPOSE, service.list(false).getFirst().status());
        assertEquals(null, resolver.resolve(StartingProfile.CUSTOM, "NEWX"));
    }
}
