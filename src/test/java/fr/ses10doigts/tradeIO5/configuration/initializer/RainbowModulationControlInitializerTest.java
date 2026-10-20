package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.repository.modulation.RainbowModulationControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@DisplayName("RainbowModulationControlInitializer")
class RainbowModulationControlInitializerTest {

    private static final Instant NOW = Instant.parse("2026-10-13T20:00:00Z");

    @Autowired
    private RainbowModulationControlRepository repository;

    private RainbowModulationControlInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new RainbowModulationControlInitializer(repository, () -> NOW);
    }

    @Test
    @DisplayName("Ligne absente => créée aux défauts")
    void createsDefaults() {
        initializer.run();

        RainbowModulationControl row = repository.findById(RainbowModulationControl.SINGLETON_ID).orElseThrow();
        assertEquals(0, new BigDecimal("25").compareTo(row.getRegulationMarginPct()));
        assertEquals(90, row.getAutonomyDays());
        assertEquals(NOW, row.getUpdatedAt());
        assertEquals("initializer", row.getUpdatedBy());
    }

    @Test
    @DisplayName("Idempotent et sans écrasement d'une ligne modifiée")
    void neverOverwrites() {
        initializer.run();
        RainbowModulationControl row = repository.findById(RainbowModulationControl.SINGLETON_ID).orElseThrow();
        row.setAutonomyDays(30);
        row.setUpdatedBy("admin");
        repository.saveAndFlush(row);

        initializer.run();

        assertEquals(1, repository.count());
        RainbowModulationControl after = repository.findById(RainbowModulationControl.SINGLETON_ID).orElseThrow();
        assertEquals(30, after.getAutonomyDays());
        assertEquals("admin", after.getUpdatedBy());
    }
}
