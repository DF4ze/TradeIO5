package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.Instant;
import java.util.List;

/**
 * Soldes <b>live</b> (lecture seule des exchanges, cache court) valorisés au dernier cours D1, pour un wallet ou tous. Les actifs
 * identiques de plusieurs wallets sont regroupés ({@code parts} garde le détail). {@code stable} = stablecoins (valeur nominale),
 * {@code assetsValue} = hors stable. {@code partial} = au moins un actif détenu sans cours (exclu des totaux).
 */
public record HoldingsDto(Long walletId, Instant fetchedAt, double total, double stable, double assetsValue, Double exposurePct,
                          boolean partial, List<Line> assets, List<Unavailable> unavailable) {

    public record Line(String assetSymbol, double quantity, Double price, Double value, Double sharePct, boolean stable,
                       List<Part> parts) {
    }

    public record Part(Long walletId, String walletName, String exchange, double quantity, Double value) {
    }

    public record Unavailable(Long walletId, String walletName, String exchange, String reason) {
    }
}
