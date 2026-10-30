package fr.ses10doigts.tradeIO5.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import fr.ses10doigts.tradeIO5.security.model.ERole;
import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.model.payload.request.SignupRequest;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;

/** Inscription publique : le compte créé est toujours ROLE_USER, quels que soient les rôles postés. */
@DisplayName("AuthController#registerUserForm : rôle imposé")
class AuthControllerSignupTest {

    @Test
    @DisplayName("rôles postés ignorés : ROLE_USER uniquement")
    void signupAlwaysGetsUserRole() {
        AuthController controller = new AuthController();
        controller.userRepository = mock(UserRepository.class);
        controller.roleRepository = mock(RoleRepository.class);
        controller.encoder = mock(PasswordEncoder.class);
        Role userRole = new Role();
        userRole.setName(ERole.ROLE_USER);
        Role adminRole = new Role();
        adminRole.setName(ERole.ROLE_ADMIN);
        when(controller.roleRepository.findByName(ERole.ROLE_USER)).thenReturn(Optional.of(userRole));
        when(controller.encoder.encode(any())).thenReturn("hash");

        SignupRequest req = new SignupRequest();
        req.setUsername("ami");
        req.setEmail("ami@test.fr");
        req.setPassword("secret1");
        req.setVerifPassword("secret1");
        req.setRoles(Set.of(adminRole));
        controller.registerUserForm(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(controller.userRepository).save(saved.capture());
        assertThat(saved.getValue().getRoles()).containsExactly(userRole);
    }
}
