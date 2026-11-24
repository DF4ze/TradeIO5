package fr.ses10doigts.tradeIO5.model.dto.dca.bench;

import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.Checked;

/** DTO de l'API {@code /api/rainbow-live/bindings} (jamais d'entité, aucun secret ni solde). */
public final class RainbowLiveBindingDtos {

    private RainbowLiveBindingDtos() {
    }

    public record WalletOptionDto(Long id, String name, String exchange) {
        public static WalletOptionDto of(fr.ses10doigts.tradeIO5.model.entity.currency.Wallet w) {
            return new WalletOptionDto(w.getId(), w.getName(), w.getWebProviderCode() == null ? null : w.getWebProviderCode().name());
        }
    }

    public record BindingDto(Long id, String assetSymbol, Long affectationId, String affectationName, Long walletId,
                             String walletName, String exchange, double bagPercent, int priority, Double baseAmount,
                             String tradability, String quoteMember, String routeKey, String preferredRoute,
                             String feeTestRoute, java.math.BigDecimal feeTestPct, String feeTestLevel,
                             java.math.BigDecimal feeTestAmount, java.time.Instant feeTestAt,
                             boolean executionEnabled, boolean firstLiveArmed, boolean firstLiveConfirmed,
                             String executionMode, Double minBuyUsd,
                             boolean suspended, String suspensionReason, String suspensionMessage, boolean pendingValidation,
                             String pendingValidationMessage) {

        /** Sans état dérivé (ni suspendu ni en attente de validation). */
        public static BindingDto of(RainbowLiveBinding b) {
            return of(b, LinkStateService.LinkState.NONE);
        }

        /** Avec l'état dérivé du lien : « Suspendu : <raison> » et/ou « en attente de validation » (simulation seule). */
        public static BindingDto of(RainbowLiveBinding b, LinkStateService.LinkState state) {
            return new BindingDto(b.getId(), b.getAssetSymbol(), b.getAffectation().getId(), b.getAffectation().label(),
                    b.getWallet().getId(), b.getWallet().getName(),
                    b.getWallet().getWebProviderCode() == null ? null : b.getWallet().getWebProviderCode().name(),
                    b.getBagPercent(), b.getPriority(), b.getBaseAmount(),
                    b.getTradability() == null ? null : b.getTradability().name(), b.getQuoteMember(), b.getRouteKey(), b.getPreferredRoute(),
                    b.getFeeTestRoute(), b.getFeeTestPct(), b.getFeeTestLevel() == null ? null : b.getFeeTestLevel().name(),
                    b.getFeeTestAmount(), b.getFeeTestAt(),
                    b.isExecutionEnabled(), b.getFirstLiveApprovedAt() != null, b.getFirstLiveConfirmedAt() != null,
                    b.executionMode().name(), b.getMinBuyUsd(),
                    state.suspended(), state.suspension() == null ? null : state.suspension().name(), state.suspensionMessage(),
                    state.pendingValidation(), state.pendingValidation() ? LinkStateService.PENDING_VALIDATION_MESSAGE : null);
        }
    }

    public record CheckDto(String status, String message, boolean ok, boolean executable, boolean blocked,
                           String quoteMember, String routeKey) {
        public static CheckDto of(BindingCheckResult r) {
            return new CheckDto(r.status().name(), r.message(), r.isOk(), r.isExecutable(), r.isBlocked(),
                    r.quoteMember(), r.routeKey());
        }
    }

    public record CheckedBindingDto(BindingDto binding, CheckDto check) {
        public static CheckedBindingDto of(Checked c) {
            return of(c, LinkStateService.LinkState.NONE);
        }

        public static CheckedBindingDto of(Checked c, LinkStateService.LinkState state) {
            return new CheckedBindingDto(BindingDto.of(c.binding(), state), CheckDto.of(c.check()));
        }
    }
}
