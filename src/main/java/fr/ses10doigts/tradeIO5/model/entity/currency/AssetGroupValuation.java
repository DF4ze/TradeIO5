package fr.ses10doigts.tradeIO5.model.entity.currency;

/**
 * Mode de valorisation d'un {@link AssetGroup}. {@code NOMINAL} : un membre vaut exactement une unité du groupe
 * (1 USDC = 1 USDT = 1 USD), l'écart de peg est ignoré. Extension prévue : un mode « prix de marché ».
 */
public enum AssetGroupValuation {
    NOMINAL
}
