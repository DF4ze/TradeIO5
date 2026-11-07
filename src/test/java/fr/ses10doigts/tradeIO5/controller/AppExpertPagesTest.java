package fr.ses10doigts.tradeIO5.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
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

/** Espace Expert côté pages : accès réservé au mode EXPERT (sinon retour à {@code /app}), menu conditionnel, ancienne page {@code /user} disparue. */
@DisplayName("Pages /app/expert : garde EXPERT et menu")
class AppExpertPagesTest {

    private static MockMvc mvc(DisplayMode mode) {
        IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
        when(facade.getAuthentication()).thenReturn(new UsernamePasswordAuthenticationToken("clem", "x", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(facade.getConnectedUser()).thenReturn(mock(User.class));
        UserPreferencesService prefs = mock(UserPreferencesService.class);
        when(prefs.get(any())).thenReturn(new Preferences(UiTheme.DARK, mode, null, Set.of(), new Caps(null, null, 30, 100), List.of()));
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
        return MockMvcBuilders.standaloneSetup(new AppPageController(facade, prefs)).setViewResolvers(viewResolver).build();
    }

    private static String render(MockMvc mvc, String url) throws Exception {
        return mvc.perform(get(url).accept(MediaType.TEXT_HTML)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("EXPERT : /app/expert redirige vers Trend ; les trois écrans rendent 200 avec leur script")
    void expertAccess() throws Exception {
        MockMvc mvc = mvc(DisplayMode.EXPERT);
        mvc.perform(get("/app/expert")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app/expert/trend"));
        for (String tab : List.of("trend", "rainbow", "affectation")) {
            String html = render(mvc, "/app/expert/" + tab);
            assertTrue(html.contains("data-page=\"expert-" + tab + "\"") && html.contains("data-mode=\"expert\""), tab);
            assertTrue(html.contains("assets/app/js/page-expert-" + tab + ".js") && html.contains("lightweight-charts.standalone.production.js"), tab);
            assertTrue(html.contains("href=\"/app/expert\"") && html.contains("Espace Expert"), tab + " : entrée de menu");
        }
    }

    @Test
    @DisplayName("SIMPLE / ADVANCED : redirection vers /app, jamais de page Expert, pas d'entrée de menu")
    void otherModesRedirected() throws Exception {
        for (DisplayMode mode : List.of(DisplayMode.SIMPLE, DisplayMode.ADVANCED)) {
            MockMvc mvc = mvc(mode);
            for (String url : List.of("/app/expert", "/app/expert/trend", "/app/expert/rainbow", "/app/expert/affectation")) {
                mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app"));
            }
            assertFalse(render(mvc, "/app").contains("Espace Expert"), mode + " : pas d'entrée Espace Expert");
        }
    }

    @Test
    @DisplayName("L'ancienne page /user n'existe plus")
    void userPageGone() {
        boolean mapped = Arrays.stream(MainController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(GetMapping.class)).filter(a -> a != null)
                .anyMatch(a -> Arrays.asList(a.value()).contains("/user"));
        assertFalse(mapped);
        assertEquals(0, Arrays.stream(MainController.class.getDeclaredMethods()).filter(m -> m.getName().equals("userAccess")).count());
    }
}
