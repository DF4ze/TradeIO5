package fr.ses10doigts.tradeIO5.flyway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V8 : table {@code asset_base_rate} (ligne de taux dédiée par actif), idempotente, colonnes alignées sur l'entité. */
@DisplayName("Migration V8 : ligne de taux dédiée par actif")
class AssetBaseRateMigrationTest {

    @Test
    @DisplayName("CREATE TABLE IF NOT EXISTS, un seul DDL, unicité par actif, 4 taux decimal(12,8)")
    void idempotentTable() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V8__asset_base_rate.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS asset_base_rate"));
        assertEquals(1, sql.lines().filter(l -> l.startsWith("CREATE TABLE")).count());
        assertEquals(0, sql.lines().filter(l -> l.contains("ALTER TABLE")).count());
        for (String column : new String[]{"rate_prudent", "rate_balanced", "rate_dynamic", "rate_full"}) {
            assertTrue(sql.contains(column + " decimal(12,8) NOT NULL"), column);
        }
        assertTrue(sql.contains("UNIQUE KEY uk_asset_base_rate_asset (asset_symbol)"));
        assertTrue(sql.contains("validated_by varchar(64) NOT NULL") && sql.contains("validated_at datetime(6) NOT NULL") && sql.contains("proposal_id bigint"));
    }
}
