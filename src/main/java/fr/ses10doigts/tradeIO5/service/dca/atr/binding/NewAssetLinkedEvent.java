package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Un lien vient d'être créé (après commit) : point d'entrée du pipeline d'accueil d'un nouvel actif (D1b). Ne porte aucun calcul. */
public record NewAssetLinkedEvent(Long userId, String assetSymbol, Long bindingId, Long walletId) {
}
