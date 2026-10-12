package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Lien (binding) vu par le moteur : preset servi, wallet réel, ordre de passage du cash, part de la position offerte et
 * exchange des candles du signal ({@code null} = wallet sans exchange de marché : défauts {@code asset_provider}).
 */
public record LiveSlot(Long bindingId, Long presetId, String assetSymbol, Long walletId, int priority, double bagPercent,
                       MarketDataSource candleSource) {
}
