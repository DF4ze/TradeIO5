package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** « Actifs à valider » vit dans l'onglet Actifs (plus dans Modulation) : section, boutons, pastille d'onglet et du menu, lien d'alarme. */
@DisplayName("Admin : section « Actifs à valider » dans l'onglet Actifs")
class AdminAssetsProposalsJsTest {

    private static String res(String path) throws Exception {
        return new String(AdminAssetsProposalsJsTest.class.getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Modulation ne garde que ses réglages : plus de section, de logique ni d'appel aux propositions")
    void modulationNoLongerHasProposals() throws Exception {
        String html = res("/templates/fragments/admin/modulation.html");
        String js = res("/static/assets/js/admin/modulation.js");
        for (String gone : new String[] {"adm-nap-", "Actifs à valider", "new-asset-proposals", "loadProposals", "validateBlockedReason"}) {
            assertFalse(html.contains(gone), "modulation.html : " + gone);
            assertFalse(js.contains(gone), "modulation.js : " + gone);
        }
        assertTrue(html.contains("adm-mod-form") && js.contains("/api/admin/rainbow-modulation"), "réglages globaux conservés");
    }

    @Test
    @DisplayName("Onglet Actifs : section en haut (avant la vue par actif), endpoints existants, Valider grisé avec sa raison, texte seul")
    void assetsTabHasProposals() throws Exception {
        String html = res("/templates/fragments/admin/assets.html");
        String js = res("/static/assets/js/admin/assets.js");
        assertTrue(html.contains("id=\"adm-nap-list\"") && html.contains("Actifs à valider"));
        assertTrue(html.indexOf("id=\"adm-nap-list\"") < html.indexOf("id=\"adm-as-body\""), "section avant la vue par actif");
        for (String needed : new String[] {"'adm-nap-' + p.id", "/api/admin/new-asset-proposals", "'/' + action", "disabled: !p.canValidate", "title: p.validateBlockedReason",
                "p.validateBlockedReason && pending", "askConfirm"}) {
            assertTrue(js.contains(needed), "assets.js : " + needed);
        }
        assertEquals(1, js.split(java.util.regex.Pattern.quote("api('POST', '/api/admin/new-asset-proposals/recompute"), -1).length - 1, "un seul appel Recalculer (pas de doublon avec la carte)");
        assertFalse(js.contains("text: 'Recalculer', onclick"), "pas de Recalculer sur la carte de proposition");
    }

    @Test
    @DisplayName("Pastille de l'onglet Actifs : masquée à 0, polling 60 s comme le Diagnostic, rafraîchie après Valider / Refuser / Recalculer")
    void tabBadge() throws Exception {
        String page = res("/templates/adminPage.html");
        String js = res("/static/assets/js/admin/assets.js");
        String doctor = res("/static/assets/js/admin/doctor.js");
        assertTrue(page.contains("id=\"adm-assets-badge\"") && page.indexOf("id=\"adm-assets-badge\"") < page.indexOf("id=\"adm-tab-limits\""), "pastille dans le bouton Actifs");
        assertTrue(page.contains("adm-doctor-badge"), "pastille Diagnostic inchangée");
        assertTrue(js.contains("classList.toggle('d-none', count === 0)"));
        assertTrue(js.contains("const REFRESH_MS = 60000;") && doctor.contains("const REFRESH_MS = 60000;"), "même cadence que doctor.js");
        assertTrue(js.contains("setInterval(pollBadge, REFRESH_MS)") && js.contains("pollBadge();"), "polling onglet fermé compris");
        // Valider / Refuser et Recalculer rechargent l'onglet (liste + pastille).
        assertTrue(js.contains("'/' + action); await load(); });"), "load() après Valider / Refuser");
        assertTrue(js.contains("core.showAlert(res.message);\n      await load();"), "load() après Recalculer");
        assertTrue(js.contains("Promise.all([loadProposals(), loadAssets(), loadBlocklist()])"));
    }

    @Test
    @DisplayName("Menu admin : pastille = menuCount (proposition = 1 point), repli sur CRIT + WARN")
    void menuBadge() throws Exception {
        String js = res("/static/assets/app/js/admin-alarms.js");
        assertTrue(js.contains("r.menuCount"), "lit menuCount");
        assertTrue(js.contains("(r.activeCrit || 0) + (r.activeWarn || 0)"), "repli");
    }

    @Test
    @DisplayName("Lien d'alarme NEW_ASSET_PROPOSAL : /admin#assets, id de l'onglet réel ; plus aucune occurrence de #modulation dans les sources")
    void alarmLinkPointsToAssetsTab() throws Exception {
        assertEquals("/admin#assets", NewAssetProposalAdminService.SCREEN_LINK);
        String page = res("/templates/adminPage.html");
        String core = res("/static/assets/js/admin/admin-core.js");
        assertTrue(page.contains("data-adm-tab=\"assets\""), "le hash « assets » désigne l'onglet");
        assertTrue(core.contains("[data-adm-tab=\"' + (window.location.hash || '').replace('#', '')"), "ouverture directe de l'onglet par le hash");
        for (String path : new String[] {"/static/assets/js/admin/assets.js", "/static/assets/js/admin/doctor.js", "/templates/adminPage.html"}) {
            assertFalse(res(path).contains("#modulation"), path);
        }
    }
}
