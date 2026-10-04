package fr.ses10doigts.tradeIO5.service.tree.trend;

/**
 * Régime de structure de marché déduit de la classification des derniers pivots confirmés par
 * {@link SwingStructureCalculator} (cf. docs/etudes/spec-structure-regression-rainbow.md sec. 1.4).
 * <p>
 * {@code WARNING_*} est un état intermédiaire assumé  -  une seule jambe (high ou low) casse le
 * schéma en cours, l'autre ne confirme pas encore  -  pas un défaut à corriger : il doit rester
 * lisible comme tel tant que la jambe manquante ne confirme pas à son tour.
 */
public enum SwingStructureRegime {
    /** Dernier pivot high = HH et dernier pivot low = HL. */
    BULL_CONFIRMED,
    /** Dernier pivot high = LH et dernier pivot low = LL. */
    BEAR_CONFIRMED,
    /** On sortait d'un régime BEAR confirmé, une jambe vient de casser le schéma bear. */
    WARNING_BULL_BREAK,
    /** On sortait d'un régime BULL confirmé, une jambe vient de casser le schéma bull. */
    WARNING_BEAR_BREAK,
    /** Historique insuffisant pour statuer (moins de 2 pivots confirmés d'un des deux types). */
    UNDEFINED
}
