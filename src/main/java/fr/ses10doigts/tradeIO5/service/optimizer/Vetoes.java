package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.ScoreReport.WindowReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Vetos d'un jeu, fonction pure des rapports de fenêtres : drawdown d'une fenêtre bornée (12 m / 24 m / test, pas l'historique complet : sa
 * profondeur de marché ne dépend pas de la politique) sous le plafond du profil ; capital
 * dépassé (cash négatif : invariant du wallet fermé) ; mise finale > maximum autorisé ; jeu dégénéré ({@code ne vend jamais} ou
 * {@code ne rachète jamais}), jugé sur la plus longue fenêtre éligible (« toute la fenêtre » : sur 12 mois d'un marché baissier, ne
 * pas vendre est normal).
 *
 * @param drawdownCapPct plafond du profil, % négatif ({@code null} : pas de veto drawdown)
 * @param maxFinalStake  mise finale maximale en multiple de la base ({@code null} : pas de veto)
 */
public record Vetoes(Double drawdownCapPct, Double maxFinalStake) {

    public List<String> evaluate(List<WindowReport> windows) {
        List<String> out = new ArrayList<>();
        if (drawdownCapPct != null && windows.stream().filter(WindowReport::scored).anyMatch(w -> w.maxDrawdownPct() < drawdownCapPct)) {
            out.add(OptimizerDefaults.VETO_DRAWDOWN);
        }
        if (windows.stream().anyMatch(w -> w.minCash() < -OptimizerDefaults.EPSILON)) {
            out.add(OptimizerDefaults.VETO_CAPITAL);
        }
        if (maxFinalStake != null && windows.stream().anyMatch(w -> w.maxFinalStakeMultiple() > maxFinalStake)) {
            out.add(OptimizerDefaults.VETO_FINAL_STAKE);
        }
        windows.stream().min(Comparator.comparing(WindowReport::from)).ifPresent(longest -> {
            if (longest.neverSells() || longest.neverRebuys()) {
                out.add(OptimizerDefaults.VETO_DEGENERATE);
            }
        });
        return out;
    }
}
