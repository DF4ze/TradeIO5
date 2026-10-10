package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Lien live d'un preset vu par le moteur : wallet réel, ordre de passage du cash, part de la position offerte. */
public record LiveSlot(Long presetId, String assetSymbol, Long walletId, int priority, double bagPercent) {
}
