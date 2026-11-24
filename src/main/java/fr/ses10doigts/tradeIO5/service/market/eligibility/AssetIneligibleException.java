package fr.ses10doigts.tradeIO5.service.market.eligibility;

/** Création de lien refusée : l'actif n'est pas éligible chez le provider du lien (400, raison lisible dans le message). */
public class AssetIneligibleException extends IllegalArgumentException {

    private final transient AssetEligibilityService.Result result;

    public AssetIneligibleException(AssetEligibilityService.Result result) {
        super("Actif non éligible : " + result.asset() + " chez " + result.provider() + " (" + result.reason().label() + ")");
        this.result = result;
    }

    public AssetEligibilityService.Result result() {
        return result;
    }
}
