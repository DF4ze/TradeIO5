package fr.ses10doigts.tradeIO5.configuration;

import fr.ses10doigts.tradeIO5.security.model.ERole;
import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Order(20)
public class UserInitializer implements CommandLineRunner {

	private static final Logger logger = LoggerFactory.getLogger(UserInitializer.class);

	private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
	private final Environment environment;

    @Override
    public void run(String... args) {
        Optional<User> existing = userRepository.findByUsername("OKlm");

        if (existing.isEmpty()) {
            Set<Role> roles = new HashSet<>();
            for (ERole roleName : ERole.values()) {
                roleRepository.findByName(roleName).ifPresent(roles::add);
            }

            User user = User.builder()
                .username("OKlm")
                .email("oklm@example.com")
                .password(passwordEncoder.encode("V!veLaV!e31!"))
                .roles(roles)
                .enabled(true)
                .build();

            userRepository.save(user);

			logger.info("✅ Utilisateur OKlm créé avec tous les rôles.");
        }

        existing = userRepository.findByUsername("System");

        if (existing.isEmpty()) {
            Set<Role> roles = new HashSet<>();
            roleRepository.findByName(ERole.ROLE_SYS).ifPresent(roles::add);

            User user = User.builder()
                    .username("System")
                    .password(passwordEncoder.encode("jkmq'è_çsjkd'(-nfqmng154é'(-(è'))"))
                    .roles(roles)
                    .enabled(true)
                    .build();

            userRepository.save(user);

            logger.info("✅ Utilisateur System créé.");
        }

		List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
		if (activeProfiles.contains("dev")) {
			logger.debug("🔐 Compte de test :");
			logger.debug("URL       : http://localhost:8080/login");
			logger.debug("Utilisateur : OKlm");
			logger.debug("Mot de passe : V!veLaV!e31!");
		}
    }
}