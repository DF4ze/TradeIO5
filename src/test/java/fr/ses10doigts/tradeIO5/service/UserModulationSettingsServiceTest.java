package fr.ses10doigts.tradeIO5.service;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserTradingSettingsService : réglages de modulation")
class UserModulationSettingsServiceTest {

    private final User user = User.builder().id(1L).username("alice").build();
    private UserTradingSettingsRepository repository;
    private RainbowModulationControlService control;
    private RainbowModulationControl limits;
    private UserTradingSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserTradingSettingsRepository.class);
        control = mock(RainbowModulationControlService.class);
        limits = RainbowModulationControl.defaults(Instant.EPOCH, null);
        when(control.current()).thenAnswer(i -> limits);
        service = new UserTradingSettingsService(repository, control);
    }

    private void stored(Double min, Double max, Double power, Double bias) {
        when(repository.findByUser(user)).thenReturn(Optional.of(UserTradingSettings.builder().user(user).riskCursor(7)
                .exposureMin(min).exposureMax(max).power(power).bias(bias).build()));
    }

    private void rejected(double min, double max, double power, double bias) {
        org.mockito.Mockito.clearInvocations(repository);
        assertThrows(IllegalArgumentException.class, () -> service.setModulationSettings(user, min, max, power, bias));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Sans ligne : valeurs neutres 0 / 100 / 1 / 0")
    void neutralWithoutRow() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        assertEquals(new ModulationSettings(0, 100, 1, 0), service.getModulationSettings(user));
    }

    @Test
    @DisplayName("Colonnes nulles (base existante) : valeurs neutres, riskCursor non concerné")
    void neutralWithNullColumns() {
        stored(null, null, null, null);
        assertEquals(new ModulationSettings(0, 100, 1, 0), service.getModulationSettings(user));
    }

    @Test
    @DisplayName("Valeurs stockées renvoyées, une colonne nulle reste neutre")
    void storedValues() {
        stored(20d, 80d, 1.5, null);
        assertEquals(new ModulationSettings(20, 80, 1.5, 0), service.getModulationSettings(user));
    }

    @Test
    @DisplayName("Création de ligne : riskCursor à DEFAULT_RISK_CURSOR")
    void createsRowWithDefaultRiskCursor() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());

        service.setModulationSettings(user, 20, 80, 1.5, 0.2);

        ArgumentCaptor<UserTradingSettings> saved = ArgumentCaptor.forClass(UserTradingSettings.class);
        verify(repository).save(saved.capture());
        assertEquals(UserTradingSettingsService.DEFAULT_RISK_CURSOR, saved.getValue().getRiskCursor());
        assertEquals(user, saved.getValue().getUser());
        assertEquals(20d, saved.getValue().getExposureMin());
        assertEquals(80d, saved.getValue().getExposureMax());
        assertEquals(1.5, saved.getValue().getPower());
        assertEquals(0.2, saved.getValue().getBias());
    }

    @Test
    @DisplayName("Ligne existante : riskCursor intact")
    void riskCursorUntouched() {
        stored(null, null, null, null);
        UserTradingSettings row = repository.findByUser(user).orElseThrow();

        service.setModulationSettings(user, 10, 90, 1, 0);

        assertEquals(7, row.getRiskCursor());
        assertEquals(10d, row.getExposureMin());
        verify(repository).save(row);
    }

    @Test
    @DisplayName("Bande : 0 ≤ min < max ≤ 100 et largeur ≥ minBandWidthPts (10), bornes incluses")
    void bandValidation() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        service.setModulationSettings(user, 0, 10, 1, 0);
        service.setModulationSettings(user, 90, 100, 1, 0);
        rejected(-0.1, 50, 1, 0);
        rejected(50, 100.1, 1, 0);
        rejected(60, 50, 1, 0);
        rejected(50, 50, 1, 0);
        rejected(40, 49.9, 1, 0);
        rejected(Double.NaN, 50, 1, 0);
        rejected(0, Double.POSITIVE_INFINITY, 1, 0);
    }

    @Test
    @DisplayName("Puissance dans [powerMin, powerMax] (0,25 – 2), bornes incluses")
    void powerValidation() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        service.setModulationSettings(user, 0, 100, 0.25, 0);
        service.setModulationSettings(user, 0, 100, 2, 0);
        rejected(0, 100, 0.24, 0);
        rejected(0, 100, 2.01, 0);
    }

    @Test
    @DisplayName("|biais| ≤ biasMax (0,5), bornes incluses, signe libre")
    void biasValidation() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        service.setModulationSettings(user, 0, 100, 1, 0.5);
        service.setModulationSettings(user, 0, 100, 1, -0.5);
        rejected(0, 100, 1, 0.51);
        rejected(0, 100, 1, -0.51);
    }

    @Test
    @DisplayName("Validation contre les limites admin COURANTES (relues à chaque appel)")
    void usesCurrentAdminLimits() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        limits.setBiasMax(new BigDecimal("0.1"));
        rejected(0, 100, 1, 0.2);
    }

    @Test
    @DisplayName("effectiveSettings : valeurs dans les limites => inchangées")
    void effectiveUnchangedWhenWithinLimits() {
        stored(20d, 80d, 1.5, 0.3);
        assertEquals(new ModulationSettings(20, 80, 1.5, 0.3), service.effectiveSettings(user));
    }

    @Test
    @DisplayName("effectiveSettings : l'admin resserre puissance, biais et largeur => valeurs clampées")
    void effectiveClampedAfterAdminTightens() {
        stored(45d, 55d, 1.8, -0.4);
        limits.setPowerMax(new BigDecimal("1.2"));
        limits.setBiasMax(new BigDecimal("0.1"));
        limits.setMinBandWidthPts(new BigDecimal("30"));

        ModulationSettings effective = service.effectiveSettings(user);

        assertEquals(1.2, effective.power(), 1e-9);
        assertEquals(-0.1, effective.bias(), 1e-9);
        assertEquals(35, effective.exposureMin(), 1e-9);
        assertEquals(65, effective.exposureMax(), 1e-9);
    }

    @Test
    @DisplayName("effectiveSettings : bande élargie décalée pour rester dans [0, 100]")
    void effectiveBandShiftedInsideRange() {
        stored(0d, 10d, 1d, 0d);
        limits.setMinBandWidthPts(new BigDecimal("30"));
        ModulationSettings low = service.effectiveSettings(user);
        assertEquals(0, low.exposureMin(), 1e-9);
        assertEquals(30, low.exposureMax(), 1e-9);

        stored(92d, 100d, 1d, 0d);
        ModulationSettings high = service.effectiveSettings(user);
        assertEquals(70, high.exposureMin(), 1e-9);
        assertEquals(100, high.exposureMax(), 1e-9);
    }

    @Test
    @DisplayName("effectiveSettings sans ligne : neutre (0 / 100 / 1 / 0)")
    void effectiveNeutralWithoutRow() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        assertEquals(new ModulationSettings(0, 100, 1, 0), service.effectiveSettings(user));
    }

    @Test
    @DisplayName("Marge de régulation : enregistrée si dans [5, 50] %, absente = inchangée, hors bornes = refus")
    void regulationMargin() {
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        assertEquals(30.0, service.setModulationSettings(user, 5, 95, 1, 0, 30.0).regulationMarginPct());
        org.mockito.Mockito.clearInvocations(repository);
        assertThrows(IllegalArgumentException.class, () -> service.setModulationSettings(user, 5, 95, 1, 0, 60.0));
        assertThrows(IllegalArgumentException.class, () -> service.setModulationSettings(user, 5, 95, 1, 0, 2.0));
        verify(repository, never()).save(any());
        stored(5d, 95d, 1d, 0d);
        assertEquals(null, service.setModulationSettings(user, 10, 90, 1, 0).regulationMarginPct());   // jamais saisie : défaut admin
    }
}
