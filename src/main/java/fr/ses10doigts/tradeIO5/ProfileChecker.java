package fr.ses10doigts.tradeIO5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProfileChecker implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(ProfileChecker.class);

    @Autowired
    private Environment env;

    @Override
    public void run(ApplicationArguments args) {
        String[] activeProfiles = env.getActiveProfiles();
        if (activeProfiles.length == 0) {
			logger.error("❌ Aucun profil Spring actif. Veuillez définir -Dspring.profiles.active=dev, prod, test...");
            System.exit(1);
        } else {
			logger.debug("✅ Profil actif : " + String.join(", ", activeProfiles));
        }
    }
}