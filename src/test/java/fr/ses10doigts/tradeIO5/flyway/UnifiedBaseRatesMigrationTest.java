package fr.ses10doigts.tradeIO5.flyway;

import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V9 : les 8 taux de {@code rainbow_modulation_control} sont copiés (valeurs réelles) vers {@code asset_base_rate} puis leurs colonnes supprimées.
 * Contrôle statique du SQL (le comportement sur MariaDB réel relève de la recette, cf. docs/operations/flyway.md).
 */
@DisplayName("Migration V9 : taux de base unifiés dans asset_base_rate")
class UnifiedBaseRatesMigrationTest {

    private static final List<String> PROFILES = List.of("prudent", "balanced", "dynamic", "full");

    private static String sql() throws IOException {
        return Files.readString(Path.of("src/main/resources/db/migration/V9__taux_de_base_unifies.sql"));
    }

    @Test
    @DisplayName("Copie des valeurs RÉELLES (colonnes lues, pas des littéraux), 1 ligne BTC et 1 ligne ETH, validated_by 'migration V9', proposal_id NULL")
    void copiesRealValues() throws IOException {
        String sql = sql();
        for (String asset : List.of("btc", "eth")) {
            String cols = String.join(", ", PROFILES.stream().map(p -> "base_rate_" + asset + "_" + p).toList());
            String select = "SELECT '" + asset.toUpperCase() + "', " + cols + ", NULL, 'migration V9', NOW(6) FROM rainbow_modulation_control WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM asset_base_rate WHERE asset_symbol = '" + asset.toUpperCase() + "')";
            assertTrue(sql.contains(select), "copie " + asset);
        }
        assertEquals(2, count(sql, "FROM rainbow_modulation_control WHERE id = 1"));
    }

    @Test
    @DisplayName("Copie conditionnée à la présence des colonnes (information_schema) et à l'absence de la ligne (NOT EXISTS) : 2e passage = aucun effet")
    void idempotent() throws IOException {
        String sql = sql();
        for (String asset : List.of("btc", "eth")) {
            assertTrue(sql.contains("column_name = 'base_rate_" + asset + "_full');\nSET @s := IF(@c = 1, \"INSERT INTO asset_base_rate"), "garde de copie " + asset);
        }
        // chaque INSERT est gardé par NOT EXISTS (copie et repli de calage)
        assertEquals(4, count(sql, "INSERT INTO asset_base_rate"));
        assertEquals(4, count(sql, "NOT EXISTS (SELECT 1 FROM asset_base_rate WHERE asset_symbol ="));
        // chaque DROP est gardé par information_schema (@c = 1)
        for (String asset : List.of("btc", "eth")) {
            for (String profile : PROFILES) {
                String column = "base_rate_" + asset + "_" + profile;
                assertTrue(sql.contains("column_name = '" + column + "');\nSET @s := IF(@c = 1, \"ALTER TABLE rainbow_modulation_control DROP COLUMN " + column + "\""), column);
            }
        }
    }

    @Test
    @DisplayName("8 colonnes supprimées, la marge k est conservée, aucun autre DDL")
    void dropsRatesKeepsK() throws IOException {
        String sql = sql();
        assertEquals(8, count(sql, "DROP COLUMN"));
        assertEquals(8, count(sql, "ALTER TABLE"));
        assertFalse(sql.lines().filter(l -> !l.startsWith("--")).anyMatch(l -> l.contains("base_rate_margin_k")), "k ne doit être ni lue ni supprimée");
        assertFalse(sql.contains("CREATE TABLE"), "la table asset_base_rate vient de V8");
    }

    @Test
    @DisplayName("Ordre : copie, puis repli de calage, puis suppression des colonnes")
    void order() throws IOException {
        String sql = sql();
        int lastCopy = sql.lastIndexOf("FROM rainbow_modulation_control WHERE id = 1");
        int firstDefault = sql.indexOf("FROM DUAL");
        int firstDrop = sql.indexOf("DROP COLUMN");
        assertTrue(lastCopy < firstDrop && firstDefault < firstDrop && lastCopy < firstDefault);
    }

    @Test
    @DisplayName("Repli (base neuve sans ligne de modulation) : valeurs de la table de calage de ModulationDefaults, BTC et ETH")
    void fallbackIsCalibrationTable() throws IOException {
        String sql = sql();
        for (String asset : List.of("BTC", "ETH")) {
            ModulationDefaults.RateTable table = ModulationDefaults.CALIBRATED_RATES.get(asset);
            String values = String.join(", ", List.of(table.prudent(), table.balanced(), table.dynamic(), table.full()).stream()
                    .map(v -> v.setScale(8).toPlainString()).toList());
            Matcher m = Pattern.compile("SELECT '" + asset + "', ([0-9., ]+), NULL, 'migration V9', NOW\\(6\\) FROM DUAL").matcher(sql);
            assertTrue(m.find(), "repli " + asset);
            assertEquals(values, m.group(1));
            for (String v : m.group(1).split(", ")) {
                assertTrue(ModulationDefaults.baseRateInBounds(new BigDecimal(v)));
            }
        }
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }
}
