package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rendu réel (Thymeleaf, templates du classpath) de {@code GET /user} : la section du bench grandeur nature est
 * présente (conteneur + scripts) et le contrôle d'accès existant de {@code userAccess} est inchangé.
 */
@DisplayName("MainController : page /user (bench grandeur nature)")
class MainControllerUserPageTest {

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

    @Test
    @DisplayName("GET /user authentifié : 200, section du bench (id stable) + scripts + navbar existante")
    void userPageContainsRainbowLiveSection() throws Exception {
        String html = mvc.perform(get("/user").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"rainbow-live\""), "conteneur de la section");
        assertTrue(html.contains("Bench grandeur nature"), "titre FR de la section");
        assertTrue(html.contains("id=\"rl-asset-tabs\"") && html.contains("id=\"rl-presets-body\""), "tableau des presets");
        assertTrue(html.contains("id=\"rl-form-modal\"") && html.contains("id=\"rl-delete-modal\""), "modales");
        assertTrue(html.contains("assets/js/rainbow-live.js"), "script de la page");
        assertTrue(html.contains("assets/vendor/lightweight-charts/lightweight-charts.standalone.production.js"),
                "lib de graphique vendorée (pas de CDN)");
        assertTrue(html.contains("Welcome in User's Page"), "contenu existant conservé");
        assertTrue(html.contains("Se déconnecter"), "navbar existante conservée");
        assertTrue(html.indexOf("lightweight-charts.standalone") < html.indexOf("assets/js/rainbow-live.js"),
                "la lib est chargée avant le script de la page");
    }

    @Test
    @DisplayName("Page : conteneur du wallet réel (masqué par défaut => page inchangée sans binding) + wallet fictif étiqueté")
    void userPageContainsLiveWalletPanel() throws Exception {
        String html = mvc.perform(get("/user").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.matches("(?s).*id=\"rl-live-panel\"[^>]*class=\"[^\"]*d-none[^\"]*\".*|(?s).*class=\"[^\"]*d-none[^\"]*\"[^>]*id=\"rl-live-panel\".*"),
                "bloc wallet réel masqué tant qu'aucun binding n'existe");
        assertTrue(html.contains("Wallet fictif (mock)"), "wallet mock conservé et étiqueté");
    }

    @Test
    @DisplayName("JS : rendu live en textContent uniquement (aucun innerHTML/outerHTML/insertAdjacentHTML/document.write), états OK/BLOCKED/UNAVAILABLE/STALE gérés")
    void liveRenderingIsTextOnly() throws Exception {
        String js = new String(getClass().getResourceAsStream("/static/assets/js/rainbow-live.js").readAllBytes(),
                StandardCharsets.UTF_8);
        for (String forbidden : new String[] {"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write"}) {
            assertTrue(!js.contains(forbidden + " =") && !js.contains(forbidden + "(") && !js.contains(forbidden + "="),
                    "interdit : " + forbidden);
        }
        assertTrue(js.contains("'/live-wallet'"), "lecture du snapshot en base");
        assertTrue(js.contains("Achat bloqué") && js.contains("Données indisponibles, aucune action")
                && js.contains("recommandée, non exécutée") && js.contains("Ancien") && js.contains("LIVE"),
                "encarts et mentions du lot");
    }

    @Test
    @DisplayName("Contrôle d'accès de userAccess inchangé (@PreAuthorize USER/MODERATOR/ADMIN)")
    void accessControlUnchanged() throws Exception {
        Method m = MainController.class.getMethod("userAccess", org.springframework.ui.Model.class);
        assertEquals("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')", m.getAnnotation(PreAuthorize.class).value());
    }
}
