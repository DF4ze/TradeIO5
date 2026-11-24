package fr.ses10doigts.tradeIO5.flyway;

import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V6 : 9 colonnes (8 taux de base + marge k), défauts alignés sur {@link ModulationDefaults}, chaque DDL conditionné (idempotent). */
@DisplayName("Migration V6 : taux de base par profil")
class BaseRatesMigrationTest {

    private static final Map<String, String> RATE_COLUMNS = Map.of(
            "base_rate_btc_prudent", ModulationDefaults.BASE_RATE_BTC_PRUDENT,
            "base_rate_btc_balanced", ModulationDefaults.BASE_RATE_BTC_BALANCED,
            "base_rate_btc_dynamic", ModulationDefaults.BASE_RATE_BTC_DYNAMIC,
            "base_rate_btc_full", ModulationDefaults.BASE_RATE_BTC_FULL,
            "base_rate_eth_prudent", ModulationDefaults.BASE_RATE_ETH_PRUDENT,
            "base_rate_eth_balanced", ModulationDefaults.BASE_RATE_ETH_BALANCED,
            "base_rate_eth_dynamic", ModulationDefaults.BASE_RATE_ETH_DYNAMIC,
            "base_rate_eth_full", ModulationDefaults.BASE_RATE_ETH_FULL);

    @Test
    @DisplayName("Colonnes ajoutées, défauts = ModulationDefaults, chaque ALTER gardé par information_schema")
    void columnsDefaultsAndIdempotence() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V6__base_rates_par_profil.sql"));
        RATE_COLUMNS.forEach((column, key) -> {
            BigDecimal def = ModulationDefaults.limit(key).defaultValue().setScale(8);
            assertTrue(sql.contains("ADD COLUMN " + column + " decimal(12,8) NOT NULL DEFAULT " + def.toPlainString()), column);
            assertTrue(sql.contains("column_name = '" + column + "'"), "garde information_schema " + column);
        });
        String k = ModulationDefaults.limit(ModulationDefaults.BASE_RATE_MARGIN_K).defaultValue().setScale(4).toPlainString();
        assertTrue(sql.contains("ADD COLUMN base_rate_margin_k decimal(10,4) NOT NULL DEFAULT " + k));
        assertTrue(sql.contains("column_name = 'base_rate_margin_k'"));
        assertEquals(9, sql.split("ALTER TABLE", -1).length - 1);
        assertEquals(9, sql.split("information_schema.columns", -1).length - 1);
    }
}
