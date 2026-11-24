package fr.ses10doigts.tradeIO5.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Onboarding /app : choix de l'actif (recherche, top N, badges)")
class OnboardingAssetPickJsTest {

    private static String js() throws Exception {
        return new String(OnboardingAssetPickJsTest.class.getResourceAsStream("/static/assets/app/js/page-onboarding.js").readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Top N : constante unique à 12 ; données de l'endpoint des actifs éligibles ; recherche avec debounce ; coin + symbole ; états vides")
    void structure() throws Exception {
        String js = js();
        assertEquals(1, js.split("const ONBOARDING_TOP_N = 12;", -1).length - 1, "constante unique");
        assertEquals(0, js.replace("const ONBOARDING_TOP_N = 12;", "").split("\\b12\\b", -1).length - 1, "pas de 12 en dur ailleurs");
        for (String needed : new String[] {"/api/rainbow-live/assets/eligible", "DEBOUNCE_MS", "setTimeout", "ui.coin(", "Aucun résultat", "Catalogue vide",
                "Aucun exchange connecté", "/app/exchanges", "Calibré", "En attente de validation", "Volume non vérifié", "Déjà géré", "ui.bubble(", "ui.error("}) {
            assertTrue(js.contains(needed), "manquant : " + needed);
        }
    }

    @Test
    @DisplayName("Aucune API DOM d'injection HTML : symboles et noms du provider rendus en texte")
    void noHtmlInjection() throws Exception {
        String js = js();
        for (String forbidden : new String[] {"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval("}) {
            assertFalse(js.contains(forbidden), forbidden);
        }
    }
}
