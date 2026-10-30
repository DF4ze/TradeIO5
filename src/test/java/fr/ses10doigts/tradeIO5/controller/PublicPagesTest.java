package fr.ses10doigts.tradeIO5.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;

/** Rendu réel (Thymeleaf) des pages publiques : présentation {@code /}, {@code /login}, {@code /register}. */
@DisplayName("MainController : pages publiques")
class PublicPagesTest {

    private IAuthenticationFacade facade;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        facade = mock(IAuthenticationFacade.class);
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

    private String render(String url) throws Exception {
        return mvc.perform(get(url).accept(MediaType.TEXT_HTML)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String renderWithParam(String url, String name, String value) throws Exception {
        return mvc.perform(get(url).param(name, value).accept(MediaType.TEXT_HTML)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("visiteur : / affiche la présentation avec connexion et inscription, sans redirection")
    void homeIsLandingForVisitors() throws Exception {
        when(facade.getConnectedUser()).thenReturn(null);
        String html = render("/");
        assertThat(html).contains("href=\"/login\"").contains("href=\"/register\"").contains("Comment ça marche").contains("risque de perte en capital");
    }

    @Test
    @DisplayName("visiteur dont la lecture de l'utilisateur échoue : même présentation")
    void homeIsLandingWhenFacadeThrows() throws Exception {
        when(facade.getConnectedUser()).thenThrow(new IllegalStateException("anonyme"));
        assertThat(render("/")).contains("Comment ça marche");
    }

    @Test
    @DisplayName("utilisateur connecté : /, /login et /register redirigent vers /app")
    void connectedUserIsSentToApp() throws Exception {
        when(facade.getConnectedUser()).thenReturn(mock(User.class));
        for (String url : new String[] { "/", "/login", "/register" }) {
            mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app"));
        }
    }

    @Test
    @DisplayName("/login : champs du formulaire, erreur affichée sur demande, retour à l'accueil")
    void loginPage() throws Exception {
        when(facade.getConnectedUser()).thenReturn(null);
        String ok = render("/login");
        assertThat(ok).contains("name=\"userName\"").contains("name=\"password\"").contains("/api/auth/signinForm").contains("Retour à l'accueil").doesNotContain("Identifiant ou mot de passe invalide");
        assertThat(render("/login?error=true")).contains("Identifiant ou mot de passe invalide");
    }

    @Test
    @DisplayName("/register : pas de choix de rôle, messages de retour affichés")
    void registerPage() throws Exception {
        when(facade.getConnectedUser()).thenReturn(null);
        String html = render("/register");
        assertThat(html).contains("name=\"username\"").contains("name=\"email\"").contains("name=\"verifPassword\"").contains("/api/auth/signupForm").doesNotContain("name=\"roles\"");
        assertThat(render("/register?ok=true")).contains("Compte créé").doesNotContain("signupForm");
        assertThat(renderWithParam("/register", "exception", "Username is already taken!")).contains("Username is already taken!");
        assertThat(renderWithParam("/register", "signUpRequest1", "Passwords don't match")).contains("Passwords don&#39;t match");
    }
}
