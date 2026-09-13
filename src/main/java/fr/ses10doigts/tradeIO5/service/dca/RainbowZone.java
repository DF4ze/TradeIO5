package fr.ses10doigts.tradeIO5.service.dca;

import java.math.BigDecimal;

/**
 * Zone du prix (clôture D1) par rapport aux 5 bornes % de {@code RainbowSmaIndicator}
 * (percdown2, percdown1, percup1, percup2, percup3), calculées autour de la SMA du jour.
 * Partition exhaustive et disjointe de l'axe des prix en 6 zones — règle exacte confirmée par
 * Clem le 2026-09-12 (mémoire projet tradeio5_dca_rainbow_v0_zone_table_clarification_2026-09-12.md,
 * qui corrige et remplace le tableau de docs/prompts/prompt-implementation-dca-rainbow-v0.md) :
 *
 * <pre>
 * prix &lt; percdown2              -&gt; EXTREME_BAS (mécanisme ARMÉ/DÉCLENCHÉ achat)
 * percdown2 &lt;= prix &lt; percdown1 -&gt; X2
 * percdown1 &lt;= prix &lt; percup1   -&gt; X1
 * percup1   &lt;= prix &lt; percup2   -&gt; X0_5
 * percup2   &lt;= prix &lt; percup3   -&gt; NO_BUY
 * prix &gt;= percup3               -&gt; EXTREME_HAUT (mécanisme ARMÉ/DÉCLENCHÉ vente)
 * </pre>
 * <p>
 * La SMA elle-même n'est volontairement PAS une borne de zone : elle ne sert qu'à calculer les
 * 5 bornes % ci-dessus (contrairement à une version antérieure — non retenue — qui coupait la
 * zone x1/x0.5 exactement à la SMA).
 * <p>
 * Classifieur pur — ne porte volontairement plus les multiplicateurs associés à chaque zone :
 * depuis l'introduction du bench de calibration (docs/calibration/calibration-dca-rainbow-bounds-multipliers.md),
 * ces valeurs sont paramétrables par requête (cf. {@link RainbowDcaBacktestRequest#getMultX2()} /
 * {@code getMultX1()} / {@code getMultX0_5()} / {@code getMultTriggered()}) plutôt que figées ici.
 * Cf. {@link RainbowDcaBacktestService} pour la résolution du multiplicateur par zone et pour le
 * mécanisme ARMÉ/DÉCLENCHÉ des deux zones extrêmes, qui ne relève pas d'un simple multiplicateur direct.
 */
public enum RainbowZone {

    EXTREME_BAS,
    X2,
    X1,
    X0_5,
    NO_BUY,
    EXTREME_HAUT;

    public static RainbowZone classify(
            BigDecimal close,
            BigDecimal percdown2Level,
            BigDecimal percdown1Level,
            BigDecimal percup1Level,
            BigDecimal percup2Level,
            BigDecimal percup3Level
    ) {
        if (close.compareTo(percdown2Level) < 0) {
            return EXTREME_BAS;
        }
        if (close.compareTo(percdown1Level) < 0) {
            return X2;
        }
        if (close.compareTo(percup1Level) < 0) {
            return X1;
        }
        if (close.compareTo(percup2Level) < 0) {
            return X0_5;
        }
        if (close.compareTo(percup3Level) < 0) {
            return NO_BUY;
        }
        return EXTREME_HAUT;
    }
}
