package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialStoredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tout nouvel exchange (ou toute clé de lecture remplacée) d'un utilisateur remonte ses transactions tout de suite, sans attendre une
 * visite de la page d'accueil : import forcé (hors espacement) du wallet de l'exchange, après commit, en tâche de fond. Un échec
 * (exchange injoignable, clé refusée) est loggué ; l'import périodique reprend ensuite.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeImportOnCredentialListener {

    private final ExchangeTradeImportService importService;

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onReadCredentialStored(ReadCredentialStoredEvent event) {
        try {
            ExchangeTradeImportService.SyncResult result = importService.sync(event.user(), null, true);
            log.info("Import initial après ajout de {} (user={}) : {} trade(s), échecs {}", event.provider(), event.user().getId(),
                    result.imported(), result.failedWallets());
        } catch (RuntimeException e) {
            log.warn("Import initial après ajout de {} (user={}) impossible : {}", event.provider(), event.user().getId(), e.getMessage());
        }
    }
}
