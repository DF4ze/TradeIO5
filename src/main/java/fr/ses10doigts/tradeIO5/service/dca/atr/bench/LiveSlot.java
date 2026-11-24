package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

import java.util.Comparator;

/**
 * Lien (binding) vu par le moteur : preset servi, wallet réel, ordre de passage du cash, part de la position offerte et
 * exchange des candles du signal ({@code null} = wallet sans exchange de marché : défauts {@code asset_provider}).
 */
public record LiveSlot(Long bindingId, Long affectationId, String assetSymbol, Long walletId, int priority, double bagPercent,
                       MarketDataSource candleSource) {

    /**
     * Ordre de service des liens d'un utilisateur, source unique live / simulation : {@code priority} croissante, puis ANCIENNETÉ DU LIEN
     * (identifiant, le plus ancien d'abord ; plus de rang d'actif BTC / ETH / PAXG). Il détermine qui voit le cash et l'exposition restants.
     */
    public static final Comparator<LiveSlot> SERVICE_ORDER = Comparator.comparingInt(LiveSlot::priority)
            .thenComparing(LiveSlot::bindingId);
}
