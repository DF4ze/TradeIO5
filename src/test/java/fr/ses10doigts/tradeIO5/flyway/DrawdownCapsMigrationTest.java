package fr.ses10doigts.tradeIO5.flyway;

import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V4 : 4 colonnes de plafonds de drawdown, défauts alignés sur {@link ModulationDefaults}, chaque DDL conditionné (idempotent). */
@DisplayName("Migration V4 : plafonds de drawdown")
class DrawdownCapsMigrationTest {

    private static final Map<String, String> COLUMNS = Map.of(
            "drawdown_cap_prudent_pct", ModulationDefaults.DRAWDOWN_CAP_PRUDENT_PCT,
            "drawdown_cap_balanced_pct", ModulationDefaults.DRAWDOWN_CAP_BALANCED_PCT,
            "drawdown_cap_dynamic_pct", ModulationDefaults.DRAWDOWN_CAP_DYNAMIC_PCT,
            "drawdown_cap_full_pct", ModulationDefaults.DRAWDOWN_CAP_FULL_PCT);

    @Test
    @DisplayName("Colonnes ajoutées, défauts = ModulationDefaults, chaque ALTER gardé par information_schema")
    void columnsDefaultsAndIdempotence() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V4__drawdown_caps.sql"));
        COLUMNS.forEach((column, key) -> {
            String def = ModulationDefaults.limit(key).defaultValue().setScale(4).toPlainString();
            assertTrue(sql.contains("ADD COLUMN " + column + " decimal(10,4) NOT NULL DEFAULT " + def), column);
            assertTrue(sql.contains("column_name = '" + column + "'"), "garde information_schema " + column);
        });
        assertEquals(4, sql.split("ALTER TABLE", -1).length - 1);
        assertEquals(4, sql.split("information_schema.columns", -1).length - 1);
    }
}
