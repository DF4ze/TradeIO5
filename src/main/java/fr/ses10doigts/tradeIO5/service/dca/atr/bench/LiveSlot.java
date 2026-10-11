package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Lien (binding) vu par le moteur : preset servi, wallet réel, ordre de passage du cash, part de la position offerte. */
public record LiveSlot(Long bindingId, Long presetId, String assetSymbol, Long walletId, int priority, double bagPercent) {
}
