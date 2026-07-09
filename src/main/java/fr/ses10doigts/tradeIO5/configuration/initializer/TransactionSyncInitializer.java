package fr.ses10doigts.tradeIO5.configuration.initializer;

import java.util.List;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.service.TransactionService;
import fr.ses10doigts.tradeIO5.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Synchronisation complète des transactions de tous les wallets actifs de type {@code EXCHANGE}.
 * <p>
 * <b>Ne s'exécute plus automatiquement au démarrage de l'application</b> (retiré le 2026-07-09,
 * décision explicite de Clem) : resynchroniser l'intégralité des portefeuilles à chaque boot
 * représentait une charge jugée inadéquate dès le démarrage, sans rapport avec un besoin réel à cet
 * instant précis. Le déclenchement doit à terme se faire sur un événement métier plus pertinent
 * (ex: connexion de l'utilisateur côté web) plutôt qu'au boot de l'application — ce câblage reste à
 * faire séparément. Cette classe ne garde que la logique de synchronisation elle-même, prête à être
 * appelée par ce futur déclencheur via {@link #syncAllWallets()}.
 */
@Component
@RequiredArgsConstructor
public class TransactionSyncInitializer {

	private static final Logger logger = LoggerFactory.getLogger(TransactionSyncInitializer.class);

	private final WalletService walletService;
	private final TransactionService transactionService;

	/**
	 * Synchronise l'intégralité des wallets actifs de type {@code EXCHANGE}. Anciennement appelé
	 * automatiquement au démarrage via {@code ApplicationRunner} — à appeler explicitement depuis
	 * le futur déclencheur métier (ex: connexion utilisateur côté web) une fois celui-ci écrit.
	 */
	public void syncAllWallets() {
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