package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Modification ou suppression refusée sur un preset qui suit une stratégie Actif (seule l'activation est modifiable). */
public class RainbowLivePresetFollowsStrategyException extends RuntimeException {
    public RainbowLivePresetFollowsStrategyException(Long presetId) {
        super("Le preset " + presetId + " suit une stratégie Actif : ni modifiable ni supprimable (dupliquer pour s'en détacher ; seule son activation peut changer)");
    }
}
