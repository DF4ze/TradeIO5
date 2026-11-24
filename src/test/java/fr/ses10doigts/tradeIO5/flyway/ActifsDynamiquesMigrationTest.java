package fr.ses10doigts.tradeIO5.flyway;

import fr.ses10doigts.tradeIO5.model.entity.market.AssetBlocklist;
import fr.ses10doigts.tradeIO5.model.entity.market.AssetEligibilitySettings;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V10 : socle des actifs dynamiques, idempotente, colonnes alignées sur les entités, ligne PAXG copiée de BTC seulement si absente. */
@DisplayName("Migration V10 : actifs dynamiques (socle)")
class ActifsDynamiquesMigrationTest {

    private static String sql() throws IOException {
        return Files.readString(Path.of("src/main/resources/db/migration/V10__actifs_dynamiques_socle.sql"));
    }

    private static long count(String sql, String fragment) {
        return sql.lines().filter(l -> l.contains(fragment)).count();
    }

    @Test
    @DisplayName("Tables neuves en CREATE TABLE IF NOT EXISTS, unicité du symbole en liste noire")
    void newTablesAreIdempotent() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS asset_blocklist"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS asset_eligibility_settings"));
        assertEquals(2, sql.lines().filter(l -> l.startsWith("CREATE TABLE")).count());
        assertTrue(sql.contains("UNIQUE KEY uk_asset_blocklist_asset (asset_symbol)"));
        assertTrue(sql.contains("min_volume_usd decimal(20,2) NOT NULL"));
    }

    @Test
    @DisplayName("Chaque ALTER est gardé par information_schema + PREPARE/EXECUTE (rejouable)")
    void alterIsGuarded() throws IOException {
        String sql = sql();
        assertEquals(2, count(sql, "ALTER TABLE exchange_instrument ADD COLUMN"));
        assertEquals(2, count(sql, "FROM information_schema.columns"));
        assertEquals(2, count(sql, "PREPARE st FROM @s"));
        assertEquals(2, count(sql, "IF(@c = 0,"));
        assertTrue(sql.contains("volume_24h_quote decimal(38,8) NULL"));
        assertTrue(sql.contains("volume_read_at datetime(6) NULL"));
        assertFalse(sql.contains("DROP"), "aucune suppression dans ce lot");
    }

    @Test
    @DisplayName("PAXG : copie des 4 taux de BTC, validated_by 'migration V10', proposition NULL, seulement si absente et si BTC existe")
    void paxgRateCopiedFromBtcOnlyIfAbsent() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("INSERT INTO asset_base_rate"));
        assertTrue(sql.contains("SELECT 'PAXG', b.rate_prudent, b.rate_balanced, b.rate_dynamic, b.rate_full, NULL, 'migration V10', NOW(6)"));
        assertTrue(sql.contains("FROM asset_base_rate b"));
        assertTrue(sql.contains("WHERE b.asset_symbol = 'BTC' AND NOT EXISTS (SELECT 1 FROM asset_base_rate p WHERE p.asset_symbol = 'PAXG')"));
        assertEquals(1, count(sql, "INSERT INTO"), "une seule insertion");
    }

    @Test
    @DisplayName("Les noms de colonnes des entités (@Column(name)) sont ceux de V10")
    void columnsMatchEntities() throws IOException {
        String sql = sql();
        for (Class<?> entity : new Class<?>[]{AssetBlocklist.class, AssetEligibilitySettings.class}) {
            for (Field field : entity.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    assertTrue(sql.contains(column.name()), entity.getSimpleName() + "." + column.name());
                }
            }
        }
        Set<String> newInstrumentColumns = Set.of("volume24hQuote", "volumeReadAt");
        for (String name : newInstrumentColumns) {
            Column column = field(ExchangeInstrument.class, name).getAnnotation(Column.class);
            assertTrue(column != null && !column.name().isBlank(), name + " : @Column(name) explicite");
            assertTrue(sql.contains(column.name()), column.name());
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
