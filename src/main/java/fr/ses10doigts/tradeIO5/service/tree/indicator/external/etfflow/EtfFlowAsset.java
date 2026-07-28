package fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow;

import lombok.Getter;

/**
 * Actif couvert par le scraping Farside (étude "indicateurs-macro-externes", §14, item I) : le
 * document source demandait explicitement BTC *et* ETH, deux pages distinctes de structure
 * identique sur farside.co.uk.
 */
@Getter
public enum EtfFlowAsset {

    BTC("/btc/", "/bitcoin-etf-flow-all-data/"),
    ETH("/eth/", "/ethereum-etf-flow-all-data/");

    private final String path;

    /** Page "all data" de Farside (docs/etude-cache-etf-flow-historisation.md, addendum backfill
     *  Farside) : même structure de tableau que {@link #path}, mais remonte à janvier 2024
     *  (lancement des ETF BTC) au lieu de ne montrer que les ~2 dernières semaines. Utilisée
     *  uniquement par {@code FarsideEtfFlowClient#fetchHistory}, jamais par le {@code fetch()} live. */
    private final String historyPath;

    EtfFlowAsset(String path, String historyPath) {
        this.path = path;
        this.historyPath = historyPath;
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
