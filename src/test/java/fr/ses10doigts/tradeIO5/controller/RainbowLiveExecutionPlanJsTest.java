package fr.ses10doigts.tradeIO5.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Page d'accueil : lecture seule du plan d'exécution")
class RainbowLiveExecutionPlanJsTest {

    private static String js() throws Exception {
        return new String(RainbowLiveExecutionPlanJsTest.class.getResourceAsStream("/static/assets/app/js/page-home.js").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Aucun interrupteur d'exécution ni écriture : la page ne fait que des GET")
    void readOnly() throws Exception {
        String js = js();
        assertFalse(js.contains("'/execution-plans', {") || js.contains("api('POST', '/execution") || js.contains("api('PUT', '/execution"));
        for (String forbidden : new String[] {"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write"}) {
            assertFalse(js.contains(forbidden + " =") || js.contains(forbidden + "(") || js.contains(forbidden + "="), forbidden);
        }
    }
}
