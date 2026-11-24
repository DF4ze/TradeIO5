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

/** V6 (historique, déjà appliquée) : 9 colonnes (8 taux de base + marge k), chaque DDL conditionné (idempotent). Les 8 taux sont repris par V9. */
@DisplayName("Migration V6 : taux de base par profil")
class BaseRatesMigrationTest {

    /** Défauts écrits par V6 (migration historique, jamais modifiée) : table de calage BTC / ETH. */
    private static final Map<String, String> RATE_COLUMNS = Map.of(
            "base_rate_btc_prudent", "0.00027740", "base_rate_btc_balanced", "0.00047360",
            "base_rate_btc_dynamic", "0.00077120", "base_rate_btc_full", "0.00142300",
            "base_rate_eth_prudent", "0.00038040", "base_rate_eth_balanced", "0.00063490",
            "base_rate_eth_dynamic", "0.00101900", "base_rate_eth_full", "0.00153300");

    @Test
    @DisplayName("Colonnes ajoutées, défauts de V6, chaque ALTER gardé par information_schema")
    void columnsDefaultsAndIdempotence() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V6__base_rates_par_profil.sql"));
        RATE_COLUMNS.forEach((column, def) -> {
            assertTrue(sql.contains("ADD COLUMN " + column + " decimal(12,8) NOT NULL DEFAULT " + def), column);
            assertTrue(sql.contains("column_name = '" + column + "'"), "garde information_schema " + column);
        });
        String k = ModulationDefaults.limit(ModulationDefaults.BASE_RATE_MARGIN_K).defaultValue().setScale(4).toPlainString();
        assertTrue(sql.contains("ADD COLUMN base_rate_margin_k decimal(10,4) NOT NULL DEFAULT " + k));
        assertTrue(sql.contains("column_name = 'base_rate_margin_k'"));
        assertEquals(9, sql.split("ALTER TABLE", -1).length - 1);
        assertEquals(9, sql.split("information_schema.columns", -1).length - 1);
    }
}
