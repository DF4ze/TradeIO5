package fr.ses10doigts.tradeIO5.security.jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.UserDetailsServiceImpl;
import fr.ses10doigts.tradeIO5.security.service.impl.UserDetailsImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pas de test dédié préexistant pour {@link AuthTokenFilter} (vérifié avant d'écrire cette classe).
 * Le filtre est instancié directement (pas via le conteneur Spring) et ses champs {@code @Autowired}
 * privés sont fixés via {@link ReflectionTestUtils}, {@code doFilterInternal(...)} est appelé avec des
 * mocks Servlet — patron cohérent avec ce que {@code OncePerRequestFilter} attend en dehors d'un
 * contexte Spring complet.
 *
 * <p>Périmètre : hook 2 de la détection de connexion (Palier 3, étape 5) — mise à jour throttlée de
 * {@code lastLogin} à chaque requête authentifiée revalidée. Le comportement le plus important à
 * couvrir est le throttle (pas d'écriture si la dernière valeur connue a moins de 15 minutes).</p>
 */
@DisplayName("AuthTokenFilter — hook lastLogin throttlé")
@ExtendWith(MockitoExtension.class)
class AuthTokenFilterTest {

    private static final long THROTTLE_MINUTES = 15L;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private AuthTokenFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthTokenFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(filter, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ReflectionTestUtils.setField(filter, "lastLoginThrottleMinutes", THROTTLE_MINUTES);
    }

    private void mockValidJwtFor(User user) {
        UserDetailsImpl userDetails = new UserDetailsImpl(
                user.getId(), user.getUsername(), user.getEmail(), user.getPassword(), Collections.emptyList());

        when(jwtUtils.getJwtFromCookies(request)).thenReturn("valid-jwt");
        when(jwtUtils.validateJwtToken("valid-jwt")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("valid-jwt")).thenReturn(user.getUsername());
        when(userDetailsService.loadUserByUsername(user.getUsername())).thenReturn(userDetails);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("JWT valide, lastLogin null : save() appelé avec lastLogin renseigné")
    void updatesLastLogin_whenCurrentlyNull() throws Exception {
        User user = User.builder().id(1L).username("alice").email("alice@test.com").lastLogin(null).build();
        mockValidJwtFor(user);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getLastLogin()).isNotNull();
        assertThat(saved.getValue().getLastLogin()).isAfter(Instant.now().minusSeconds(10));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT valide, lastLogin récent (< 15 min) : save() n'est pas appelé (throttle)")
    void doesNotUpdateLastLogin_whenRecent() throws Exception {
        Instant recent = Instant.now().minus(5, ChronoUnit.MINUTES);
        User user = User.builder().id(1L).username("alice").email("alice@test.com").lastLogin(recent).build();
        mockValidJwtFor(user);

        filter.doFilterInternal(request, response, filterChain);

        verify(userRepository, never()).save(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT valide, lastLogin ancien (> 15 min) : save() appelé avec une nouvelle valeur proche de maintenant")
    void updatesLastLogin_whenStale() throws Exception {
        Instant stale = Instant.now().minus(20, ChronoUnit.MINUTES);
        User user = User.builder().id(1L).username("alice").email("alice@test.com").lastLogin(stale).build();
        mockValidJwtFor(user);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getLastLogin()).isAfter(Instant.now().minusSeconds(10));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT absent/invalide : aucun appel à userRepository")
    void doesNotTouchUserRepository_whenJwtMissing() throws Exception {
        when(jwtUtils.getJwtFromCookies(request)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verifyNoInteractions(userRepository);
        verify(filterChain).doFilter(request, response);
    }
}
