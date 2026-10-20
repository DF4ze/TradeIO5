package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.repository.modulation.RainbowModulationControlRepository;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService.Params;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RainbowModulationControlService")
class RainbowModulationControlServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T20:00:00Z");

    private RainbowModulationControlRepository repository;
    private RainbowModulationControlService service;

    @BeforeEach
    void setUp() {
        repository = mock(RainbowModulationControlRepository.class);
        service = new RainbowModulationControlService(repository, () -> NOW);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Params defaults() {
        return Params.of(RainbowModulationControl.defaults(NOW, "t"));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Params with(UnaryOperator<ParamsBuilder> change) {
        return change.apply(new ParamsBuilder(defaults())).build();
    }

    /** Variation d'un seul champ à la fois. */
    private record ParamsBuilder(Params p) {
        ParamsBuilder margin(String v) { return new ParamsBuilder(new Params(bd(v), p.sellBoostMax(), p.biasMax(), p.powerMin(), p.powerMax(), p.minBandWidthPts(), p.modulationProductMax(), p.autonomyDays())); }
        ParamsBuilder boost(String v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), bd(v), p.biasMax(), p.powerMin(), p.powerMax(), p.minBandWidthPts(), p.modulationProductMax(), p.autonomyDays())); }
        ParamsBuilder bias(String v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), p.sellBoostMax(), bd(v), p.powerMin(), p.powerMax(), p.minBandWidthPts(), p.modulationProductMax(), p.autonomyDays())); }
        ParamsBuilder powerMin(String v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), p.sellBoostMax(), p.biasMax(), bd(v), p.powerMax(), p.minBandWidthPts(), p.modulationProductMax(), p.autonomyDays())); }
        ParamsBuilder powerMax(String v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), p.sellBoostMax(), p.biasMax(), p.powerMin(), bd(v), p.minBandWidthPts(), p.modulationProductMax(), p.autonomyDays())); }
        ParamsBuilder width(String v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), p.sellBoostMax(), p.biasMax(), p.powerMin(), p.powerMax(), bd(v), p.modulationProductMax(), p.autonomyDays())); }
        ParamsBuilder product(String v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), p.sellBoostMax(), p.biasMax(), p.powerMin(), p.powerMax(), p.minBandWidthPts(), bd(v), p.autonomyDays())); }
        ParamsBuilder days(Integer v) { return new ParamsBuilder(new Params(p.regulationMarginPct(), p.sellBoostMax(), p.biasMax(), p.powerMin(), p.powerMax(), p.minBandWidthPts(), p.modulationProductMax(), v)); }
        Params build() { return p; }
    }

    private void rejected(Params params) {
        org.mockito.Mockito.clearInvocations(repository);
        assertThrows(IllegalArgumentException.class, () -> service.update(params, "admin"));
        verify(repository, never()).save(any());
    }

    private void accepted(Params params) {
        service.update(params, "admin");
    }

    @Test
    @DisplayName("Ligne absente => défauts en constantes")
    void absentRowGivesDefaults() {
        when(repository.findById(RainbowModulationControl.SINGLETON_ID)).thenReturn(Optional.empty());
        assertEquals(defaults(), Params.of(service.current()));
    }

    @Test
    @DisplayName("current() relit la base à chaque appel, sans cache")
    void noCache() {
        RainbowModulationControl row = RainbowModulationControl.defaults(NOW, "t");
        when(repository.findById(RainbowModulationControl.SINGLETON_ID)).thenReturn(Optional.of(row));
        service.current();
        row.setAutonomyDays(30);
        assertEquals(30, service.current().getAutonomyDays());
        verify(repository, org.mockito.Mockito.times(2)).findById(RainbowModulationControl.SINGLETON_ID);
    }

    @Test
    @DisplayName("Défauts acceptés ; écriture : valeurs, horodatage et auteur")
    void defaultsAccepted() {
        when(repository.findById(RainbowModulationControl.SINGLETON_ID)).thenReturn(Optional.empty());
        RainbowModulationControl saved = service.update(with(b -> b.margin("30")), "admin");
        assertEquals(0, bd("30").compareTo(saved.getRegulationMarginPct()));
        assertEquals(NOW, saved.getUpdatedAt());
        assertEquals("admin", saved.getUpdatedBy());
    }

    @Test
    @DisplayName("regulationMarginPct : 5 et 50 inclus, 4,99 et 50,01 refusés")
    void margin() {
        accepted(with(b -> b.margin("5")));
        accepted(with(b -> b.margin("50")));
        rejected(with(b -> b.margin("4.99")));
        rejected(with(b -> b.margin("50.01")));
    }

    @Test
    @DisplayName("sellBoostMax : 1 et 4 inclus, hors bornes refusés")
    void sellBoost() {
        accepted(with(b -> b.boost("1")));
        accepted(with(b -> b.boost("4")));
        rejected(with(b -> b.boost("0.99")));
        rejected(with(b -> b.boost("4.01")));
    }

    @Test
    @DisplayName("biasMax : 0 et 1 inclus, hors bornes refusés")
    void biasMax() {
        accepted(with(b -> b.bias("0")));
        accepted(with(b -> b.bias("1")));
        rejected(with(b -> b.bias("-0.01")));
        rejected(with(b -> b.bias("1.01")));
    }

    @Test
    @DisplayName("powerMin / powerMax : 0–3, et powerMin ≤ 1 ≤ powerMax")
    void power() {
        accepted(with(b -> b.powerMin("0")));
        accepted(with(b -> b.powerMin("1")));
        accepted(with(b -> b.powerMax("1")));
        accepted(with(b -> b.powerMax("3")));
        rejected(with(b -> b.powerMin("1.01")));
        rejected(with(b -> b.powerMin("-0.1")));
        rejected(with(b -> b.powerMax("0.99")));
        rejected(with(b -> b.powerMax("3.01")));
    }

    @Test
    @DisplayName("minBandWidthPts : 5 et 50 inclus")
    void width() {
        accepted(with(b -> b.width("5")));
        accepted(with(b -> b.width("50")));
        rejected(with(b -> b.width("4.9")));
        rejected(with(b -> b.width("50.1")));
    }

    @Test
    @DisplayName("modulationProductMax : 1 et 5 inclus")
    void product() {
        accepted(with(b -> b.product("1")));
        accepted(with(b -> b.product("5")));
        rejected(with(b -> b.product("0.9")));
        rejected(with(b -> b.product("5.1")));
    }

    @Test
    @DisplayName("autonomyDays : 7 et 365 inclus, absent refusé")
    void autonomy() {
        accepted(with(b -> b.days(7)));
        accepted(with(b -> b.days(365)));
        rejected(with(b -> b.days(6)));
        rejected(with(b -> b.days(366)));
        rejected(with(b -> b.days(null)));
    }

    @Test
    @DisplayName("Valeur absente ou jeu absent refusé, rien écrit")
    void nullsRejected() {
        rejected(null);
        Params d = defaults();
        rejected(new Params(null, d.sellBoostMax(), d.biasMax(), d.powerMin(), d.powerMax(), d.minBandWidthPts(),
                d.modulationProductMax(), d.autonomyDays()));
    }
}
