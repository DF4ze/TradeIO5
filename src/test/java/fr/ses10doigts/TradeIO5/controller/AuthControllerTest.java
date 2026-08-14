package fr.ses10doigts.tradeIO5.controller;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fr.ses10doigts.tradeIO5.security.jwt.JwtUtils;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.impl.UserDetailsImpl;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pas de patron {@code @WebMvcTest} préexistant pour {@link AuthController} (vérifié avant d'écrire ce
 * test) : MockMvc en mode standalone, même patron que {@code UserTradingSettingsControllerTest}. Les
 * dépendances {@code @Autowired} par champ (package-private, pas d'injection par constructeur dans ce
 * controller) sont fixées directement depuis le test, même package.
 *
 * <p>Périmètre : uniquement le hook 1 de la détection de connexion (Palier 3, étape 5) — mise à jour
 * de {@code lastLogin} après un login réussi via {@code /api/auth/signinForm}.</p>
 */
@DisplayName("AuthController#authenticateUserForm")
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder encoder;

    @Mock
    private JwtUtils jwtUtils;

    private MockMvc mockMvc;

    private final User user = User.builder().id(1L).username("alice").build();

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController();
        controller.authenticationManager = authenticationManager;
        controller.userRepository = userRepository;
        controller.roleRepository = roleRepository;
        controller.encoder = encoder;
        controller.jwtUtils = jwtUtils;
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("login réussi met à jour lastLogin via userRepository.save")
    void authenticateUserForm_updatesLastLoginOnSuccess() throws Exception {
        UserDetailsImpl userDetails =
                new UserDetailsImpl(1L, "alice", "alice@test.com", "hash", Collections.emptyList());
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtUtils.generateJwtCookie(userDetails)).thenReturn(new Cookie("test-cookie", "value"));

        mockMvc.perform(post("/api/auth/signinForm")
                        .param("userName", "alice")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getLastLogin()).isNotNull();
        assertThat(savedUser.getValue().getLastLogin()).isAfter(Instant.now().minusSeconds(10));
    }

    @Test
    @DisplayName("échec d'authentification ne touche pas userRepository")
    void authenticateUserForm_doesNotUpdateLastLoginOnFailure() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad creds"));

        mockMvc.perform(post("/api/auth/signinForm")
                        .param("userName", "alice")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection());

        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }
}
