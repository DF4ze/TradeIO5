package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Affectation inexistante ou appartenant à un autre utilisateur (404). */
public class AssetAffectationNotFoundException extends RuntimeException {

    public AssetAffectationNotFoundException(Long id) {
        super("Affectation introuvable pour cet utilisateur : " + id);
    }
}
