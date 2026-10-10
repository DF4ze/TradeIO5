package fr.ses10doigts.tradeIO5.service.execution;

/**
 * Mode global d'exécution. {@code OFF} : ni plan ni ordre ; {@code DRY_RUN} (défaut) : plans enregistrés, jamais envoyés ;
 * {@code LIVE} : envoi réel possible, refusé au démarrage tant que le verrou {@code tradeio.execution.live-unlocked} n'est
 * pas levé et qu'aucune clé maître n'est configurée.
 */
public enum ExecutionMode {
    OFF,
    DRY_RUN,
    LIVE
}
