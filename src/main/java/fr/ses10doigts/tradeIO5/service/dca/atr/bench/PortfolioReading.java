package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioView;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Lecture d'un portefeuille à un instant : cash USDC et quantités par actif (BTC, ETH, PAXG uniquement). {@code walletId}
 * est nul pour le wallet mock. Une lecture non {@link PortfolioStatus#OK} ne doit jamais servir à dimensionner une action.
 */
public record PortfolioReading(double cash, Map<String, Double> positions, Instant fetchedAt, PortfolioStatus status,
                               Long walletId) implements PortfolioView {

    public PortfolioReading {
        positions = Map.copyOf(positions);
    }

    /** Lecture impossible : aucun solde n'est porté (jamais un 0 silencieux). */
    public static PortfolioReading unavailable(Long walletId, Instant at) {
        return new PortfolioReading(0.0, Map.of(), at, PortfolioStatus.UNAVAILABLE, walletId);
    }

    /** Relit le snapshot d'une passe : cash du pool et position réelle de l'actif (les autres actifs n'y figurent pas). */
    public static PortfolioReading fromSnapshot(RainbowLivePassBlock block, String assetSymbol) {
        return new PortfolioReading(nz(block.getLiveCashUsdc()), Map.of(assetSymbol, nz(block.getLivePositionQty())),
                block.getLiveFetchedAt(), block.getLiveStatus(), block.getLiveWalletId());
    }

    public boolean isOk() {
        return status == PortfolioStatus.OK;
    }

    @Override
    public double quantity(String assetSymbol) {
        return positions.getOrDefault(assetSymbol, 0.0);
    }

    /** Même lecture, {@code STALE} si plus ancienne que {@code staleAfter} à l'instant {@code now}. */
    public PortfolioReading checkedAt(Instant now, Duration staleAfter) {
        if (status == PortfolioStatus.OK && Duration.between(fetchedAt, now).compareTo(staleAfter) > 0) {
            return new PortfolioReading(cash, positions, fetchedAt, PortfolioStatus.STALE, walletId);
        }
        return this;
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
