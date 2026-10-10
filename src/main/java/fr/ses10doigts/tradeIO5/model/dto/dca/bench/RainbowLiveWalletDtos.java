package fr.ses10doigts.tradeIO5.model.dto.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;

import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO de {@code GET /api/rainbow-live/live-wallet} : wallet réel utilisé par le preset live de chaque actif, lu dans le
 * dernier snapshot en base (jamais l'exchange). Ni credential, ni secret, ni solde hors périmètre (cash USDC et position
 * de l'actif seulement).
 */
public final class RainbowLiveWalletDtos {

    private RainbowLiveWalletDtos() {
    }

    /** Un actif ayant un binding ; {@code snapshot} nul tant qu'aucune passe live n'a été jouée. */
    public record LiveAssetDto(String assetSymbol, Long bindingId, Long presetId, String presetName, Long walletId,
                               String walletName, String exchange, double bagPercent, int priority,
                               LiveSnapshotDto snapshot) {
    }

    /**
     * Snapshot du dernier bloc de passe live ; {@code liveAction*} = action réelle plafonnée, recommandée et
     * <b>non exécutée</b>.
     */
    public record LiveSnapshotDto(LocalDate day, RainbowLivePass pass, PortfolioStatus status, Instant fetchedAt,
                                  Double cashUsdc, Double positionQty, Double tradableQty, Double cashReserved,
                                  LiveBlockReason blockReason, RainbowLiveAction liveActionType,
                                  Double liveActionAmountUsdc, Double liveActionQuantity) {
    }
}
