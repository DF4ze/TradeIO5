package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.AssetBaseRate;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.AssetRates;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rate;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AssetBaseRateService : source unique des taux de base")
class AssetBaseRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private final Map<String, AssetBaseRate> db = new LinkedHashMap<>();
    private AssetBaseRateRepository repository;
    private RainbowLiveBindingRepository bindings;
    private AssetBaseRateService service;

    @BeforeEach
    void setUp() {
        repository = mock(AssetBaseRateRepository.class);
        bindings = mock(RainbowLiveBindingRepository.class);
        when(repository.findByAssetSymbol(any())).thenAnswer(i -> Optional.ofNullable(db.get(i.<String>getArgument(0))));
        when(repository.findAll()).thenAnswer(i -> new ArrayList<>(db.values()));
        when(repository.save(any())).thenAnswer(i -> {
            AssetBaseRate row = i.getArgument(0);
            db.put(row.getAssetSymbol(), row);
            return row;
        });
        org.mockito.Mockito.doAnswer(i -> {
            db.remove(i.<AssetBaseRate>getArgument(0).getAssetSymbol());
            return null;
        }).when(repository).delete(any());
        when(bindings.findActiveAssetSymbols()).thenReturn(List.of());
        service = new AssetBaseRateService(repository, bindings, () -> NOW);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Rates rates(String p, String b, String d, String f) {
        return new Rates(bd(p), bd(b), bd(d), bd(f));
    }

    private void row(String asset, Rates r) {
        db.put(asset, AssetBaseRate.builder().assetSymbol(asset).ratePrudent(r.prudent()).rateBalanced(r.balanced()).rateDynamic(r.dynamic())
                .rateFull(r.full()).validatedBy("t").validatedAt(NOW).build());
    }

    // ------------------------------------------------------------------ lecture

    @Test
    @DisplayName("Ligne de l'actif d'abord : calibré, ligne dédiée")
    void dedicatedRowFirst() {
        row("NEWX", rates("0.0003", "0.0005", "0.0008", "0.0015"));
        Rate r = service.resolve(StartingProfile.BALANCED, "NEWX");
        assertEquals(0, bd("0.0005").compareTo(r.value()));
        assertTrue(r.calibrated());
        AssetRates all = service.rates("NEWX");
        assertTrue(all.dedicated() && all.calibrated());
    }

    @Test
    @DisplayName("Actif sans ligne : repli sur la ligne BTC, signalé non calibré (jamais bloqué)")
    void fallsBackToBtcRow() {
        row("BTC", rates("0.0009", "0.001", "0.002", "0.003"));
        for (StartingProfile p : List.of(StartingProfile.PRUDENT, StartingProfile.BALANCED, StartingProfile.DYNAMIC, StartingProfile.FULL)) {
            Rate paxg = service.resolve(p, "PAXG");
            assertFalse(paxg.calibrated());
            assertEquals(0, service.resolve(p, "BTC").value().compareTo(paxg.value()));
        }
        assertFalse(service.rates("PAXG").dedicated());
    }

    @Test
    @DisplayName("Aucune ligne du tout : BTC / ETH = table de calage (calibrés), autre actif = table BTC non calibrée")
    void noRowFallsBackToCalibrationTable() {
        assertEquals(0, bd("0.0002774").compareTo(service.resolve(StartingProfile.PRUDENT, "BTC").value()));
        assertEquals(0, bd("0.001533").compareTo(service.resolve(StartingProfile.FULL, "ETH").value()));
        assertTrue(service.resolve(StartingProfile.FULL, "ETH").calibrated());
        assertFalse(service.rates("ETH").dedicated());
        Rate paxg = service.resolve(StartingProfile.DYNAMIC, "PAXG");
        assertFalse(paxg.calibrated());
        assertEquals(0, bd("0.0007712").compareTo(paxg.value()));
    }

    @Test
    @DisplayName("Profil sans table (CUSTOM, nul) : null")
    void profileWithoutTable() {
        assertNull(service.resolve(StartingProfile.CUSTOM, "BTC"));
        assertNull(service.resolve(null, "BTC"));
    }

    @Test
    @DisplayName("Table de calage : croissante Prudent -> Full, dans les bornes absolues, ordre des profils conservé")
    void calibrationTableIsConsistent() {
        ModulationDefaults.CALIBRATED_RATES.forEach((asset, t) -> {
            new Rates(t.prudent(), t.balanced(), t.dynamic(), t.full()).validate();
            assertTrue(t.prudent().compareTo(t.balanced()) < 0 && t.balanced().compareTo(t.dynamic()) < 0 && t.dynamic().compareTo(t.full()) < 0, asset);
        });
        assertEquals(java.util.Set.of("BTC", "ETH"), ModulationDefaults.CALIBRATED_RATE_ASSETS);
    }

    @Test
    @DisplayName("list : BTC, ETH, actifs des liens et actifs avec ligne, sans doublon, ordre alphabétique, drapeau calibré")
    void listMergesSupervisedAndRows() {
        when(bindings.findActiveAssetSymbols()).thenReturn(List.of("PAXG", "BTC", "SOL"));
        row("NEWX", rates("0.0003", "0.0005", "0.0008", "0.0015"));
        List<AssetRates> out = service.list();
        assertEquals(List.of("BTC", "ETH", "NEWX", "PAXG", "SOL"), out.stream().map(AssetRates::asset).toList());
        assertTrue(out.stream().filter(a -> a.asset().equals("NEWX") || a.asset().equals("BTC")).allMatch(AssetRates::calibrated));
        assertFalse(out.stream().filter(a -> a.asset().equals("PAXG") || a.asset().equals("SOL")).anyMatch(AssetRates::calibrated));
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("Validation : ≥ 0 (négatif et nul refusés), bornes absolues, 4 taux obligatoires")
    void validationBounds() {
        assertThrows(IllegalArgumentException.class, () -> rates("-0.0003", "0.0005", "0.0008", "0.0015").validate());
        assertThrows(IllegalArgumentException.class, () -> rates("0", "0.0005", "0.0008", "0.0015").validate());
        assertThrows(IllegalArgumentException.class, () -> rates("0.000001", "0.0005", "0.0008", "0.0015").validate());
        assertThrows(IllegalArgumentException.class, () -> rates("0.0003", "0.0005", "0.0008", "0.5").validate());
        assertThrows(IllegalArgumentException.class, () -> new Rates(bd("0.0003"), null, bd("0.0008"), bd("0.0015")).validate());
        rates("0.00001", "0.00001", "0.01", "0.01").validate();
    }

    @Test
    @DisplayName("Validation : Prudent ≤ Équilibré ≤ Dynamique ≤ Full (égalité permise, inversion refusée)")
    void validationOrder() {
        rates("0.0005", "0.0005", "0.0005", "0.0005").validate();
        assertThrows(IllegalArgumentException.class, () -> rates("0.0006", "0.0005", "0.0008", "0.0015").validate());
        assertThrows(IllegalArgumentException.class, () -> rates("0.0003", "0.0009", "0.0008", "0.0015").validate());
        assertThrows(IllegalArgumentException.class, () -> rates("0.0003", "0.0005", "0.002", "0.0015").validate());
    }

    // ------------------------------------------------------------------ écriture

    @Test
    @DisplayName("save : crée la ligne dédiée (auteur, date, proposition), puis la remplace ; l'actif devient calibré")
    void saveCreatesThenReplaces() {
        AssetRates created = service.save("NEWX", rates("0.0003", "0.0005", "0.0008", "0.0015"), 7L, "root");
        assertTrue(created.dedicated() && created.calibrated());
        AssetBaseRate row = db.get("NEWX");
        assertEquals("root", row.getValidatedBy());
        assertEquals(NOW, row.getValidatedAt());
        assertEquals(7L, row.getProposalId());

        service.save("NEWX", rates("0.0004", "0.0006", "0.0009", "0.0016"), null, "admin2");
        assertEquals(1, db.size());
        assertEquals(0, bd("0.0006").compareTo(db.get("NEWX").getRateBalanced()));
        assertEquals("admin2", db.get("NEWX").getValidatedBy());
        assertEquals(7L, db.get("NEWX").getProposalId(), "la proposition d'origine est conservée");
    }

    @Test
    @DisplayName("save invalide : rien n'est écrit")
    void invalidSaveWritesNothing() {
        assertThrows(IllegalArgumentException.class, () -> service.save("NEWX", rates("0.0009", "0.0005", "0.0008", "0.0015"), null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.save("  ", rates("0.0003", "0.0005", "0.0008", "0.0015"), null, "root"));
        assertThrows(IllegalArgumentException.class, () -> service.save("NEWX", null, null, "root"));
        assertTrue(db.isEmpty());
    }

    @Test
    @DisplayName("Défauts : BTC / ETH = table de calage réécrite ; autre actif = ligne dédiée supprimée (retour au repli BTC non calibré)")
    void resetDefaults() {
        row("BTC", rates("0.0009", "0.001", "0.002", "0.003"));
        AssetRates btc = service.resetDefaults("BTC", "root");
        assertEquals(0, bd("0.0002774").compareTo(btc.rates().prudent()));
        assertEquals(0, bd("0.001423").compareTo(db.get("BTC").getRateFull()));

        service.resetDefaults("ETH", "root");
        assertEquals(0, bd("0.0003804").compareTo(db.get("ETH").getRatePrudent()));

        row("NEWX", rates("0.0003", "0.0005", "0.0008", "0.0015"));
        AssetRates back = service.resetDefaults("NEWX", "root");
        assertFalse(db.containsKey("NEWX"));
        assertFalse(back.calibrated() || back.dedicated());
        assertEquals(0, db.get("BTC").getRateBalanced().compareTo(back.rates().balanced()));
    }
}
