package fr.ses10doigts.tradeIO5.service.optimizer;

/**
 * Configuration du pipeline d'accueil d'un nouvel actif (D1b). Tout seuil est ici ; aucun n'est bloquant (un historique court donne un drapeau).
 *
 * @param provisionalMonths        sous cet historique (mois), la proposition est « provisoire » (24)
 * @param providerSufficientMonths historique du provider du lien jugé suffisant, sans complément Binance (48 : une fenêtre de 24 mois + de quoi en
 *                                 faire glisser plusieurs)
 * @param minCandles               sous ce nombre de bougies D1, aucun rejeu n'est tenté : « historique insuffisant » (120)
 * @param blockMonths              durée d'un bloc compté dans le détail (24)
 * @param blockCount               nombre maximal de blocs comptés (4)
 * @param budgetMinutes            coût estimé (série) au-delà duquel le calage n'est pas lancé : drapeau {@code CALAGE_COUT_ELEVE} (10)
 * @param timeoutMinutes           durée maximale d'un calcul avant abandon (30)
 * @param binancePauseMs           pause entre deux pages Binance (1 000)
 */
public record NewAssetConfig(int provisionalMonths, int providerSufficientMonths, int minCandles, int blockMonths, int blockCount, double budgetMinutes,
                             int timeoutMinutes, long binancePauseMs) {

    public static NewAssetConfig defaults() {
        return new NewAssetConfig(24, 48, 120, 24, 4, 10.0, 30, 1_000L);
    }
}
