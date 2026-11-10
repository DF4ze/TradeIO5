package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.connector.keypair.KeyPairDefaults;
import fr.ses10doigts.tradeIO5.service.connector.keypair.ProviderKeyPairService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purge périodique des paires de clés en attente expirées (toutes les heures par défaut). */
@Component
@RequiredArgsConstructor
public class ProviderPendingKeyPurgeJob {

    private final ProviderKeyPairService keyPairs;

    @Scheduled(initialDelayString = KeyPairDefaults.PURGE_INITIAL_DELAY_MS, fixedDelayString = KeyPairDefaults.PURGE_INTERVAL_MS)
    public void run() {
        keyPairs.purgeExpired();
    }
}
