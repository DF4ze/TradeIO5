package fr.ses10doigts.tradeIO5.model.dto.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.Checked;

/** DTO de l'API {@code /api/rainbow-live/bindings} (jamais d'entité, aucun secret ni solde). */
public final class RainbowLiveBindingDtos {

    private RainbowLiveBindingDtos() {
    }

    public record BindingDto(Long id, String assetSymbol, Long presetId, String presetName, Long walletId,
                             String walletName, String exchange, double bagPercent, int priority,
                             String tradability, String quoteMember, String bridgePair,
                             boolean executionEnabled, boolean firstLiveArmed, boolean firstLiveConfirmed) {
        public static BindingDto of(RainbowLiveBinding b) {
            return new BindingDto(b.getId(), b.getAssetSymbol(), b.getPreset().getId(), b.getPreset().getName(),
                    b.getWallet().getId(), b.getWallet().getName(),
                    b.getWallet().getWebProviderCode() == null ? null : b.getWallet().getWebProviderCode().name(),
                    b.getBagPercent(), b.getPriority(),
                    b.getTradability() == null ? null : b.getTradability().name(), b.getQuoteMember(), b.getBridgePair(),
                    b.isExecutionEnabled(), b.getFirstLiveApprovedAt() != null, b.getFirstLiveConfirmedAt() != null);
        }
    }

    public record CheckDto(String status, String message, boolean ok, boolean executable, boolean blocked,
                           String quoteMember, String bridgePair) {
        public static CheckDto of(BindingCheckResult r) {
            return new CheckDto(r.status().name(), r.message(), r.isOk(), r.isExecutable(), r.isBlocked(),
                    r.quoteMember(), r.bridgePair());
        }
    }

    public record CheckedBindingDto(BindingDto binding, CheckDto check) {
        public static CheckedBindingDto of(Checked c) {
            return new CheckedBindingDto(BindingDto.of(c.binding()), CheckDto.of(c.check()));
        }
    }
}
