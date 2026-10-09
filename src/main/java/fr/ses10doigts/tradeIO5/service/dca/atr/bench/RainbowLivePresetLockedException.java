package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Modification ou suppression refusée sur un preset système (seul l'activation est modifiable). */
public class RainbowLivePresetLockedException extends RuntimeException {
    public RainbowLivePresetLockedException(Long presetId) {
        super("Le preset système " + presetId + " n'est ni modifiable ni supprimable (seule son activation peut changer)");
    }
}
