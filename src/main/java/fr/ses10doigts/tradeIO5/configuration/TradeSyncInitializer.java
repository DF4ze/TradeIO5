package fr.ses10doigts.tradeIO5.configuration;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.connector.ApiCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.sync.TradeSynchronizerService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(50)
public class TradeSyncInitializer implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(RoleInitializer.class);

	private final TradeSynchronizerService tradeSyncService;
	private final ApiCredentialService apiCredentialService;


    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Log de démarrage
		logger.debug("Démarrage de la synchronisation incrementicomplète des trades...");

		List<ApiCredential> allCredentials = apiCredentialService.getAllCredentials();

		for (ApiCredential apiCredential : allCredentials) {
			tradeSyncService.incrementalSync(apiCredential);
		}

		logger.info("Synchronisation complète terminée.");
    }
}