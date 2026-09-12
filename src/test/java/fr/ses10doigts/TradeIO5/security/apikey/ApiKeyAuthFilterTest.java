package fr.ses10doigts.tradeIO5.security.apikey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Même patron que {@code AuthTokenFilterTest} : filtre instancié directement (pas de contexte Spring),
 * champ {@code @Value} fixé via {@link ReflectionTestUtils}, {@code doFilterInternal(...)} appelé avec
 * des mocks Servlet. Ajouté le 2026-09-03 (Clem) avec {@link ApiKeyAuthFilter}.
 */
@DisplayName("ApiKeyAuthFilter — authentification machine-à-machine par clé API")
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    private static final String CONFIGURED_KEY = "correct-key";

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new ApiKeyAuthFilter();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void configureKey(String key) {
        ReflectionTestUtils.setField(filter, "configuredApiKey", key);
    }

    @Test
    @DisplayName("clé configurée + header correct : authentification ROLE_API_AGENT posée dans le contexte")
    void setsAuthentication_whenHeaderMatchesConfiguredKey() throws Exception {
        configureKey(CONFIGURED_KEY);
        when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn(CONFIGURED_KEY);

        filter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_" + ApiKeyAuthFilter.ROLE_API_AGENT);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("clé configurée + header absent : aucune authentification posée")
    void doesNotAuthenticate_whenHeaderMissing() throws Exception {
        configureKey(CONFIGURED_KEY);
        when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("clé configurée + header incorrect : aucune authentification posée")
    void doesNotAuthenticate_whenHeaderDoesNotMatch() throws Exception {
        configureKey(CONFIGURED_KEY);
        when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn("wrong-key");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("clé non configurée (vide) : le filtre ne fait rien, même avec un header présent")
    void doesNothing_whenNoKeyConfigured() throws Exception {
        configureKey("");
        lenient().when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn(CONFIGURED_KEY);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("authentification déjà présente dans le contexte : le filtre ne l'écrase pas, même avec une clé valide")
    void doesNotOverwrite_whenAuthenticationAlreadyPresent() throws Exception {
        configureKey(CONFIGURED_KEY);
        Authentication existing = new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);
        lenient().when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn(CONFIGURED_KEY);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
        verify(filterChain).doFilter(request, response);
    }
}
