package fr.ses10doigts.tradeIO5.service.dca;

/**
 * Mode de calcul des bornes de zone Rainbow (2026-09-27, demande Clem — cf.
 * memoire projet tradeio5_dca_rainbow_pistes_regime_exposition_risque.md) :
 * {@code PERCENT} = comportement V0 d'origine, bornes = SMA +/- un %fixe.
 * {@code ATR} = bornes adaptees a la volatilite realisee, SMA +/- (atrMult * ATR(atrPeriod)),
 * en unite de prix absolue plutot qu'en % fixe de la SMA — pour respirer avec le regime de
 * volatilite courant (grinding bull != spike bull, cf. l'analyse du 2026-09-26).
 */
public enum BoundsMode {
    PERCENT,
    ATR
}
