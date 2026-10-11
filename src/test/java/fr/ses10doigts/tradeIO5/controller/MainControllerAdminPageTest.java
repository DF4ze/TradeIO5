package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rendu réel (Thymeleaf, templates du classpath) de {@code GET /admin} : bandeau, quatre onglets, fragments et scripts
 * attendus ; scan statique anti-injection HTML sur les scripts et fragments de la page ; accès toujours réservé ADMIN.
 */
@DisplayName("MainController : page /admin (page Admin d'exécution)")
class MainControllerAdminPageTest {

    private static final String[] FORBIDDEN = {"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "th:utext"};
    private static final String[] TABS = {"console", "credentials", "bindings", "orderTester", "journal"};

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("clem");
        IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
        when(facade.getAuthentication()).thenReturn(auth);
        MainController controller = new MainController();
        ReflectionTestUtils.setField(controller, "authenticationFacade", facade);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(engine);
        viewResolver.setCharacterEncoding("UTF-8");
        mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(viewResolver).build();
    }

    private String page() throws Exception {
        return mvc.perform(get("/admin").accept(MediaType.TEXT_HTML)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("GET /admin : bandeau, onglets Console / Credentials / Bindings / Journal, fragments, navbar et accueil conservés")
    void adminPageContainsTabs() throws Exception {
        String html = page();

        assertTrue(html.contains("Welcome in Admin's Page"), "contenu existant conservé");
        assertTrue(html.contains("data-username=\"clem\""), "nom de l'admin transmis au JS");
        assertTrue(html.contains("Se déconnecter"), "navbar existante conservée");
        for (String id : new String[] {"adm-banner", "adm-mode", "adm-live", "adm-master", "adm-ks", "adm-alert"}) {
            assertTrue(html.contains("id=\"" + id + "\""), "bandeau : " + id);
        }
        for (String tab : TABS) {
            assertTrue(html.contains("data-adm-tab=\"" + tab + "\""), "bouton d'onglet " + tab);
            assertTrue(html.contains("id=\"adm-pane-" + tab + "\""), "panneau " + tab);
            assertTrue(html.contains("assets/js/admin/" + tab + ".js"), "script " + tab);
        }
        assertTrue(html.contains("id=\"adm-steps\"") && html.contains("id=\"adm-btn-run\"") && html.contains("id=\"adm-btn-reconcile\""),
                "procédure en 7 étapes de la Console");
        assertTrue(html.contains("id=\"adm-cred-form\"") && html.contains("type=\"password\"") && html.contains("autocomplete=\"off\""),
                "formulaire de saisie des credentials (champs masqués, sans autocomplétion)");
        assertTrue(html.contains("id=\"adm-bind-form\"") && html.contains("id=\"adm-journal-body\""), "bindings et journal");
        assertTrue(html.indexOf("assets/js/admin/admin-core.js") < html.indexOf("assets/js/admin/console.js"),
                "le noyau est chargé avant les onglets");
        assertTrue(html.contains("AdminCore.start()"), "démarrage de la page");
    }

    @Test
    @DisplayName("GET /info : procédure de test d'achat Kraken, lien navbar, accès ADMIN, aucune injection HTML")
    void infoPage() throws Exception {
        String html = mvc.perform(get("/info").accept(MediaType.TEXT_HTML)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("achat BTC sur Kraken") && html.contains("Lancer la passe"), "procédure");
        assertTrue(html.contains("href=\"/info\""), "lien dans le menu");
        Method m = MainController.class.getMethod("infoAccess", org.springframework.ui.Model.class);
        assertEquals("hasRole('ADMIN')", m.getAnnotation(PreAuthorize.class).value());
        for (String forbidden : FORBIDDEN) {
            assertFalse(html.contains(forbidden), "contient " + forbidden);
        }
    }

    @Test
    @DisplayName("Contrôle d'accès de adminAccess inchangé (@PreAuthorize ADMIN)")
    void accessControlUnchanged() throws Exception {
        Method m = MainController.class.getMethod("adminAccess", org.springframework.ui.Model.class);
        assertEquals("hasRole('ADMIN')", m.getAnnotation(PreAuthorize.class).value());
    }

    private static List<Resource> adminResources() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> out = new ArrayList<>();
        for (String pattern : new String[] {"classpath:static/assets/js/admin/*.js", "classpath:templates/fragments/admin/*.html",
                "classpath:templates/adminPage.html"}) {
            out.addAll(List.of(resolver.getResources(pattern)));
        }
        return out;
    }

    @Test
    @DisplayName("Scan statique : aucun innerHTML / outerHTML / insertAdjacentHTML / document.write / th:utext dans la page Admin")
    void noHtmlInjection() throws Exception {
        List<Resource> resources = adminResources();
        assertEquals(6 + 5 + 1, resources.size(), "6 scripts + 5 fragments + la page : " + resources);
        for (Resource r : resources) {
            String content = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN) {
                assertFalse(content.contains(forbidden), r.getFilename() + " contient " + forbidden);
            }
        }
    }

    @Test
    @DisplayName("JS : table unique des motifs de blocage (tous les codes attendus, 51155 inclus), rendu en texte seul")
    void blockMessagesAreCentralised() throws Exception {
        String core = new String(getClass().getResourceAsStream("/static/assets/js/admin/admin-core.js").readAllBytes(), StandardCharsets.UTF_8);
        for (String code : new String[] {"MODE_NOT_LIVE", "PORT_UNAVAILABLE", "KILL_SWITCH", "PLAN_EXPIRED", "NO_BINDING", "BINDING_DISABLED",
                "NOT_TRADABLE", "CREDENTIAL_INVALID", "FIRST_LIVE_NOT_VALIDATED", "NO_BASE_AMOUNT", "CAP_ORDER", "CAP_DAY",
                "CAP_ABSOLUTE_ORDER", "CAP_ABSOLUTE_DAY", "'51155'", "NOT_TRADABLE_WITHOUT_FIAT"}) {
            assertTrue(core.contains(code), "motif manquant : " + code);
        }
        assertTrue(core.contains("TRADEIO_MASTER_KEY") || new String(getClass().getResourceAsStream("/static/assets/js/admin/credentials.js")
                .readAllBytes(), StandardCharsets.UTF_8).contains("TRADEIO_MASTER_KEY absente du processus"), "message clé maître");
        for (String other : new String[] {"console", "credentials", "bindings", "orderTester", "journal"}) {
            String js = new String(getClass().getResourceAsStream("/static/assets/js/admin/" + other + ".js").readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(js.contains("MODE_NOT_LIVE"), other + ".js ne duplique pas la table des motifs");
        }
    }
}
