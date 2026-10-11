package fr.ses10doigts.tradeIO5.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Page : bloc « Plan d'exécution (simulation) » du panneau live")
class RainbowLiveExecutionPlanJsTest {

    private static String js() throws Exception {
        return new String(RainbowLiveExecutionPlanJsTest.class.getResourceAsStream("/static/assets/js/rainbow-live.js").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Lecture du plan en base, libellé « non envoyé », états et badges Fee Test gérés")
    void rendersPlanStates() throws Exception {
        String js = js();
        assertTrue(js.contains("'/execution-plans/latest'"));
        assertTrue(js.contains("Plan d\\'exécution (simulation)") && js.contains("non envoyé"));
        for (String status : new String[] {"PLANNED", "BLOCKED", "EXPIRED", "DISABLED"}) {
            assertTrue(js.contains(status + ":"), "statut " + status);
        }
        assertTrue(js.contains("Fee Test vert") && js.contains("Fee Test orange") && js.contains("Fee Test rouge"));
        assertTrue(js.contains("'expiré'"), "mention d'expiration");
        assertTrue(js.contains("Plan bloqué :"), "raison du blocage");
    }

    @Test
    @DisplayName("Aucun interrupteur d'exécution ni écriture : le bloc ne fait que des GET")
    void readOnly() throws Exception {
        String js = js();
        assertFalse(js.contains("'/execution-plans', {") || js.contains("api('POST', '/execution") || js.contains("api('PUT', '/execution"));
        for (String forbidden : new String[] {"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write"}) {
            assertFalse(js.contains(forbidden + " =") || js.contains(forbidden + "(") || js.contains(forbidden + "="), forbidden);
        }
    }
}
