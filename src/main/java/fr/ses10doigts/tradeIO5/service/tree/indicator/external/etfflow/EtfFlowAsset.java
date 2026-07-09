package fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow;

import lombok.Getter;

/**
 * Actif couvert par le scraping Farside (étude "indicateurs-macro-externes", §14, item I) : le
 * document source demandait explicitement BTC *et* ETH, deux pages distinctes de structure
 * identique sur farside.co.uk.
 */
@Getter
public enum EtfFlowAsset {

    BTC("/btc/"),
    ETH("/eth/");

    private final String path;

    EtfFlowAsset(String path) {
        this.path = path;
    }

    /** Résout un nom d'actif (paramètre {@code IndicatorParameters.getString("asset")}) vers
     *  l'enum, insensible à la casse. Retourne {@link #BTC} par défaut si absent/inconnu plutôt
     *  que de faire planter l'indicateur pour un paramètre mal renseigné. */
    public static EtfFlowAsset fromParameter(String raw) {
        if (raw == null || raw.isBlank()) {
            return BTC;
        }
        try {
            return EtfFlowAsset.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BTC;
        }
    }
}
