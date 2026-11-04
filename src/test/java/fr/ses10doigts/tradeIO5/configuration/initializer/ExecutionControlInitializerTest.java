package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.repository.execution.ExecutionControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@DisplayName("ExecutionControlInitializer : base d'abord, puis propriété, puis constante de l'initializer")
class ExecutionControlInitializerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Autowired
    private ExecutionControlRepository repository;

    private ExecutionControlInitializer initializer(MockEnvironment env) {
        return new ExecutionControlInitializer(repository, () -> NOW, env);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "attendu " + expected + " obtenu " + actual);
    }

    @Test
    @DisplayName("Ligne absente => créée, kill switch engagé, plafonds et seuils aux constantes")
    void createsDefaults() {
        initializer(new MockEnvironment()).run();

        ExecutionControl row = repository.findById(ExecutionControl.SINGLETON_ID).orElseThrow();
        assertTrue(row.isKillSwitch());
        assertDecimal("30", row.getAbsoluteMaxOrderUsd());
        assertDecimal("100", row.getAbsoluteMaxDayUsd());
        assertDecimal("0.2", row.getFeeWarnPct());
        assertDecimal("0.8", row.getFeeRedPct());
    }

    @Test
    @DisplayName("Propriétés Fee Test : prioritaires sur la constante quand la base est vide")
    void propertiesBeatConstants() {
        initializer(new MockEnvironment().withProperty("tradeio.execution.fee-test.warn-pct", "0.1")
                .withProperty("tradeio.execution.fee-test.red-pct", "0.5")).run();

        ExecutionControl row = repository.findById(ExecutionControl.SINGLETON_ID).orElseThrow();
        assertDecimal("0.1", row.getFeeWarnPct());
        assertDecimal("0.5", row.getFeeRedPct());
    }

    @Test
    @DisplayName("Seuils de propriété incohérents => constantes")
    void incoherentPropertiesFallBack() {
        initializer(new MockEnvironment().withProperty("tradeio.execution.fee-test.warn-pct", "2")
                .withProperty("tradeio.execution.fee-test.red-pct", "1")).run();

        ExecutionControl row = repository.findById(ExecutionControl.SINGLETON_ID).orElseThrow();
        assertDecimal("0.2", row.getFeeWarnPct());
        assertDecimal("0.8", row.getFeeRedPct());
    }

    @Test
    @DisplayName("Ligne existante : seuls les champs nuls sont complétés, les valeurs en base ne sont jamais écrasées")
    void completesOnlyNulls() {
        ExecutionControl legacy = ExecutionControl.initial(NOW);
        legacy.setAbsoluteMaxOrderUsd(new BigDecimal("50"));
        legacy.setKillSwitch(false);
        legacy.setUpdatedBy("admin");
        repository.saveAndFlush(legacy);

        initializer(new MockEnvironment()).run();

        ExecutionControl row = repository.findById(ExecutionControl.SINGLETON_ID).orElseThrow();
        assertDecimal("50", row.getAbsoluteMaxOrderUsd());
        assertDecimal("100", row.getAbsoluteMaxDayUsd());
        assertDecimal("0.2", row.getFeeWarnPct());
        assertEquals("admin", row.getUpdatedBy());
        assertEquals(false, row.isKillSwitch());
        assertEquals(1, repository.count());
    }
}
