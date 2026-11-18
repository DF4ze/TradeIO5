package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.List;

/**
 * Demande de run : toutes les entrées sont EXPLICITES (aucun défaut lié à l'heure ni au wallet réel). {@code seed} et
 * {@code sobolSamples} sont facultatifs (défauts de {@link OptimizerDefaults}). {@code mode} nul = {@link Mode#ASSET_L2}.
 * <ul>
 *   <li>{@link Mode#ASSET_L2} : recherche sur un actif ({@code asset}, {@code walletId}, {@code cash0}, {@code endDate}, {@code profile}
 *   obligatoires) ; {@code baseAmount} = montant de base de référence (USDC) du DCA fixe, nul = proposition d'onboarding.</li>
 *   <li>{@link Mode#POLICY_L2} : calibrage walk-forward de la politique sur le wallet de référence. Facultatifs : {@code profiles}
 *   (défaut : les 4), {@code links} (défaut : BTC, ETH, PAXG, premier lien de chaque actif), {@code cash0} (défaut 10 000 USDC),
 *   {@code foldEnds} (défaut : 2023-11-15, 2024-11-15, 2025-11-15), {@code seed}, {@code sobolSamples} ; {@code asset}, {@code walletId},
 *   {@code endDate}, {@code profile}, {@code baseAmount} sont ignorés.</li>
 *   <li>{@link Mode#PRESET_L1} : optimiseur de presets L1, walk-forward simple par actif sur le wallet de référence (challenger seulement,
 *   rien n'est appliqué). Facultatifs : {@code profile} (défaut Équilibré), {@code links}, {@code cash0}, {@code foldEnds} (plis semestriels),
 *   {@code seed}, {@code sobolSamples} ; {@code asset}, {@code walletId}, {@code endDate}, {@code baseAmount}, {@code profiles} sont ignorés.</li>
 * </ul>
 *
 * @param asset      symbole nu de l'actif (un lien actif de l'admin, source = celle du lien)
 * @param walletId   wallet du lien
 * @param cash0      capital de départ en USDC
 * @param endDate    dernier jour des fenêtres (jour clos)
 * @param profile    {@code StartingProfile} (PRUDENT, BALANCED, DYNAMIC, FULL)
 * @param baseAmount montant de base de référence du DCA fixe ; {@code null} = proposition d'onboarding
 */
public record OptimizerRunRequest(String asset, Long walletId, Double cash0, LocalDate endDate, String profile, Double baseAmount,
                                  Long seed, Integer sobolSamples, String mode, List<String> profiles, List<LinkRef> links,
                                  List<LocalDate> foldEnds) {

    public enum Mode { ASSET_L2, POLICY_L2, PRESET_L1 }

    /** Lien du wallet de référence : actif + wallet dont on prend le lien actif de l'admin. */
    public record LinkRef(String asset, Long walletId) {
    }

    /** Recherche sur un actif (mode historique). */
    public OptimizerRunRequest(String asset, Long walletId, Double cash0, LocalDate endDate, String profile, Double baseAmount,
                               Long seed, Integer sobolSamples) {
        this(asset, walletId, cash0, endDate, profile, baseAmount, seed, sobolSamples, null, null, null, null);
    }

    /** Calibrage de la politique L2 ; les arguments nuls prennent les défauts de {@link OptimizerDefaults}. */
    public static OptimizerRunRequest policyL2(Double cash0, List<String> profiles, List<LinkRef> links, List<LocalDate> foldEnds,
                                               Long seed, Integer sobolSamples) {
        return new OptimizerRunRequest(null, null, cash0, null, null, null, seed, sobolSamples, Mode.POLICY_L2.name(), profiles, links,
                foldEnds);
    }

    /**
     * Optimisation des presets L1 (walk-forward simple par actif, challenger seulement) ; arguments nuls = défauts. {@code profile} nul =
     * Équilibré ; {@code links} nul = BTC, ETH, PAXG ; {@code foldEnds} nul = plis semestriels.
     */
    public static OptimizerRunRequest presetL1(Double cash0, String profile, List<LinkRef> links, List<LocalDate> foldEnds, Long seed,
                                               Integer sobolSamples) {
        return new OptimizerRunRequest(null, null, cash0, null, profile, null, seed, sobolSamples, Mode.PRESET_L1.name(), null, links,
                foldEnds);
    }

    /** Mode demandé ({@link Mode#ASSET_L2} par défaut) ; valeur inconnue ⇒ {@link IllegalArgumentException}. */
    public Mode resolvedMode() {
        if (mode == null || mode.isBlank()) {
            return Mode.ASSET_L2;
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode inconnu : " + mode);
        }
    }
}
