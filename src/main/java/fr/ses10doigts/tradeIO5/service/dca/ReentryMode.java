package fr.ses10doigts.tradeIO5.service.dca;

/**
 * Méthode de sortie d'un état ARMÉ (achat sous {@code percdown2} ou vente au-dessus de
 * {@code percup3}) dans {@link RainbowDcaBacktestService}. Ajoutée pour comparer plusieurs façons
 * de gérer le franchissement des bornes extrêmes (demande explicite de Clem, 2026-09-13) — cf.
 * docs/etudes/etude-dca-tool-mcp.md §12.
 * <p>
 * Le comportement V0 d'origine (immuable, cf. mémoire projet
 * tradeio5_dca_rainbow_v0_zone_table_clarification_2026-09-12.md) correspond exactement à
 * {@link #TRAILING_STOP} côté achat et {@link #IMMEDIATE} côté vente : les défauts de
 * {@link RainbowDcaBacktestRequest#getBuyReentryMode()} / {@code getSellReentryMode()} reproduisent
 * ce comportement bit à bit tant qu'ils ne sont pas changés explicitement — aucune régression sur
 * les 6 tests historiques de {@code RainbowDcaBacktestServiceTest}.
 */
public enum ReentryMode {

    /**
     * Déclenche dès que le prix repasse la borne (percdown2 pour l'achat, percup3 pour la vente)
     * OU dès qu'il rebondit/recule de {@code trailingStopBuyPercent} (achat) / {@code trailingStopSellPercent} (vente) depuis l'extrême (plus bas côté
     * achat, plus haut côté vente) atteint pendant l'armement. Capture un rebond/repli avant le
     * retour complet dans la zone normale.
     */
    TRAILING_STOP,

    /**
     * Ne déclenche que sur le franchissement pur de la borne — ignore tout rebond intermédiaire.
     * Reste armé plus longtemps que {@link #TRAILING_STOP} dans la plupart des cas.
     */
    IMMEDIATE,

    /**
     * Déclenche automatiquement après {@link RainbowDcaBacktestRequest#getFixedDelayDays()} jours
     * d'armement, quel que soit le prix atteint entre-temps.
     */
    FIXED_DELAY
}
