package fr.ses10doigts.tradeIO5.configuration.initializer;

import java.util.List;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.service.TransactionService;
import fr.ses10doigts.tradeIO5.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Order(50)
public class TransactionSyncInitializer implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(TransactionSyncInitializer.class);

	private final WalletService walletService;
	private final TransactionService transactionService;


    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Log de démarrage
		logger.debug("Démarrage de la synchronisation complète de toutes les transactions...");

		List<Wallet> wallets = walletService.getAllActiveWallets();
		for( Wallet wallet : wallets ){
			if( wallet.getSource() == WalletSource.EXCHANGE ) {
				try {
					transactionService.incrementalSync(wallet);
				} catch (Exception e) {
					logger.warn("❌ Echec de la synchronisation pour le wallet id={} ({}), poursuite avec les autres wallets.",
							wallet.getId(), wallet.getSource(), e);
				}
			}
		}

		logger.info("Synchronisation complète terminée.");
    }
}