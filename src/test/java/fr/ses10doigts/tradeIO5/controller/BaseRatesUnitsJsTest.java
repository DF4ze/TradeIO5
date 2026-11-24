package fr.ses10doigts.tradeIO5.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Conversion $ ↔ taux de l'onglet « Taux de base » : exécutée avec Node si disponible (ignorée sinon). */
@DisplayName("Onglet Taux de base : conversion $ pour 1 000 $ de cash ↔ taux interne")
class BaseRatesUnitsJsTest {

    private static final Path JS = Path.of("src/main/resources/static/assets/js/admin/baseRates.js");

    private static String node(String script) throws Exception {
        Process p = new ProcessBuilder("node", "-e", script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assumeTrue(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0, "node indisponible ou en erreur : " + out);
        return out;
    }

    @Test
    @DisplayName("Aller-retour sans dérive pour la table de calage ; % du cash par jour et autonomie cohérents")
    void roundTrip() throws Exception {
        String script = "const u = require('" + JS.toAbsolutePath().toString().replace("\\", "/") + "');"
                + "const out = [];"
                + "for (const r of [0.0002774, 0.0004736, 0.0007712, 0.001423, 0.0003804, 0.0006349, 0.001019, 0.001533, 0.00001, 0.01]) {"
                + "  const d = u.toDollars(r); out.push(u.toRate(d) === r ? 'ok' : 'KO ' + r + ' ' + d + ' ' + u.toRate(d)); }"
                + "out.push(u.toDollars(0.0004736), u.toRate(0.4736), u.pctPerDay(0.4736).toFixed(5), u.autonomyDays(1), u.autonomyDays(0.4736), u.autonomyDays(0), u.autonomyDays(-1));"
                + "console.log(out.map(String).join(' '));";
        assertEquals("ok ok ok ok ok ok ok ok ok ok 0.4736 0.0004736 0.04736 1000 2111 null null", node(script));
    }
}
