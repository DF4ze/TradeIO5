package fr.ses10doigts.tradeIO5.configuration.initializer;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.security.model.ERole;
import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(10)
public class RoleInitializer implements CommandLineRunner {

	private static final Logger logger = LoggerFactory.getLogger(RoleInitializer.class);

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        if (roleRepository.count() == 0) {
            Arrays.stream(ERole.values()).forEach(roleEnum ->
                roleRepository.save(new Role(null, roleEnum))
            );
			logger.info("✅ Rôles initialisés.");
        }
    }
}