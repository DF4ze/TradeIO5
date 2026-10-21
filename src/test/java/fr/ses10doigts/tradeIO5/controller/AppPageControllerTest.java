package fr.ses10doigts.tradeIO5.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import fr.ses10doigts.tradeIO5.model.entity.user.DisplayMode;
import fr.ses10doigts.tradeIO5.model.entity.user.UiTheme;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Caps;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Preferences;

/** Rendu réel (Thymeleaf) des pages {@code /app/**} : coquille, thème et mode posés par le serveur, script de page, route d'actif. */
@DisplayName("AppPageController : pages /app")
class AppPageControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
        when(facade.getAuthentication()).thenReturn(new UsernamePasswordAuthenticationToken("clem", "x", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(facade.getConnectedUser()).thenReturn(mock(User.class));
        UserPreferencesService prefs = mock(UserPreferencesService.class);
        when(prefs.get(any())).thenReturn(new Preferences(UiTheme.LIGHT, DisplayMode.ADVANCED, null, Set.of(), new Caps(null, null, 30, 100), List.of()));

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
        mvc = MockMvcBuilders.standaloneSetup(new AppPageController(facade, prefs)).setViewResolvers(viewResolver).build();
    }

    private String render(String url) throws Exception {
        return mvc.perform(get(url).accept(MediaType.TEXT_HTML)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Chaque page : 200, thème et mode des préférences, navigation, script de la page")
    void everyPageRenders() throws Exception {
        for (String page : List.of("home:/app", "assets:/app/assets", "journal:/app/journal", "settings:/app/settings", "onboarding:/app/onboarding", "components:/app/components")) {
            String name = page.substring(0, page.indexOf(':'));
            String html = render(page.substring(page.indexOf(':') + 1));
            assertTrue(html.contains("data-theme=\"light\""), name + " : thème");
            assertTrue(html.contains("data-page=\"" + name + "\"") && html.contains("data-mode=\"advanced\"") && html.contains("data-user=\"clem\""), name + " : attributs");
            assertTrue(html.contains("assets/app/css/tradeio.css") && html.contains("assets/app/js/core.js"), name + " : feuille et noyau");
            assertTrue(html.contains("assets/app/js/page-" + name + ".js"), name + " : script de page");
            assertTrue(html.contains("Mes actifs") && html.contains("Journal") && html.contains("Réglages"), name + " : navigation");
            assertTrue(html.contains("/admin"), name + " : lien admin visible pour un administrateur");
            assertFalse(html.contains("innerHTML"), name + " : jamais d'innerHTML");
        }
    }

    @Test
    @DisplayName("Fiche actif : symbole en majuscules, bibliothèque de graphique chargée ; symbole invalide => 404")
    void assetPage() throws Exception {
        String html = render("/app/assets/btc");
        assertTrue(html.contains("data-symbol=\"BTC\"") && html.contains("data-page=\"asset\""));
        assertTrue(html.contains("lightweight-charts.standalone.production.js") && html.contains("assets/app/js/page-asset.js"));
        mvc.perform(get("/app/assets/<script>").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Aucun script du front n'utilise innerHTML / outerHTML / insertAdjacentHTML / document.write")
    void noDangerousDomApi() throws IOException {
        List<String> banned = List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(");
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/static/assets/app/js"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".js")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                for (String b : banned) {
                    assertFalse(text.contains(b), p.getFileName() + " contient " + b);
                }
            }
        }
    }
}
