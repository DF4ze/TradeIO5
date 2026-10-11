package fr.ses10doigts.tradeIO5.model.dto.admin;

/** Binding vu par l'admin (tous utilisateurs) : aucun solde, aucun secret. */
public record AdminBindingDto(Long id, Long userId, String username, String assetSymbol, String exchange, Long walletId,
                              String walletName, Long presetId, String presetName, double bagPercent, int priority,
                              boolean executionEnabled, String tradability, boolean firstLiveArmed,
                              boolean firstLiveConfirmed) {
}
