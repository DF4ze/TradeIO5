package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

/**
 * Configuration de l'étape E1 (coupure du bag au switch Bull → Bear, ré-entrée au switch Bear → Bull). Tout seuil de décision est ici, fixé
 * avant de regarder les résultats.
 *
 * @param parity        blocs (4 × 24 mois) et règle « blocs gagnés » de l'étape B' (3 sur 4)
 * @param multipliers   grille du multiplicateur du montant de base (seul levier d'exposition), identique pour les deux lignes
 * @param sellFraction  fraction du bag vendue au switch Bull → Bear (1,0 = 100 %)
 * @param reentryPct    ré-entrée au switch Bear → Bull, en % du capital initial (20) ; le reste du cash passe par les achats Rainbow habituels
 * @param minCapsPass   plafonds de profil (sur 4) à satisfaire pour conclure « coupure utile » (3)
 */
public record SwitchCutConfig(ParityConfig parity, List<Double> multipliers, double sellFraction, double reentryPct, int minCapsPass) {

    public SwitchCutConfig {
        if (multipliers.isEmpty() || sellFraction < 0 || sellFraction > 1 || reentryPct < 0 || minCapsPass < 1) {
            throw new IllegalArgumentException("configuration E1 invalide");
        }
    }

    /** Grille de C1 (base + élargissements haut et bas) : les rejeux sont peu coûteux, l'élargissement est donc pris d'emblée. */
    public static SwitchCutConfig defaults(LocalDate lastEnd) {
        FrontierConfig f = FrontierConfig.defaults(lastEnd);
        TreeSet<Double> grid = new TreeSet<>(f.multipliers());
        grid.addAll(f.extendUp());
        grid.addAll(f.extendDown());
        return new SwitchCutConfig(f.parity(), List.copyOf(grid), 1.0, 20.0, 3);
    }

    public SwitchCutConfig withReentry(double pct) {
        return new SwitchCutConfig(parity, multipliers, sellFraction, pct, minCapsPass);
    }

    /** Rejeux au pire : blocs × niveaux × 2 lignes. */
    public long replays() {
        return (long) parity.blockCount() * multipliers.size() * 2L;
    }
}
