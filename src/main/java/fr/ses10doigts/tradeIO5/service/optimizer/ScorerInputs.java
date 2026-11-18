package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Entrées EXPLICITES du scorer : aucune valeur par défaut ne dépend de l'heure ni du wallet réel. Les bougies viennent du
 * {@code template} de chaque lien (le scorer n'en charge aucune) ; les bornes de plage du {@code template} sont ignorées (les
 * fenêtres sont recalculées depuis {@code endDate}).
 *
 * @param assets             liens rejoués ensemble (cash et exposition communs)
 * @param cash0              capital de départ en USDC, fixé avant le rejeu ; le gain se mesure en % de ce capital
 * @param endDate            date de fin commune des fenêtres (dernier jour inclus), jamais postérieure à la dernière bougie
 * @param baseSettings       réglages de modulation de base (la politique du candidat les remplace jour par jour)
 * @param limits             bornes admin de la modulation et plafonds de drawdown des profils
 * @param profile            profil de risque : plafond de drawdown du veto
 * @param maxFinalStake      mise finale maximale (multiple de la base) ; {@code null} = pas de veto
 * @param completeCosts      coûts du mode complet ; {@code null} = barème statique par défaut ({@link OptimizerCosts#complete})
 */
public record ScorerInputs(List<AssetInput> assets, double cash0, LocalDate endDate, ModulationSettings baseSettings,
                           RainbowModulationControl limits, StartingProfile profile, List<WindowSpec> windows,
                           double dispersionPenalty, Double maxFinalStake, MultiLinkReplayEngine.Costs completeCosts) {

    /**
     * Un lien : identité de service, signal (bougies D1 incluses) et étiquette de la source des bougies.
     * {@code meanMultiplier} : multiplicateur moyen des zones du preset (base de référence, {@link ReferenceBase}) ;
     * {@code referenceBase} : montant de base de référence (USDC) du DCA fixe, identique pour tous les candidats ({@code null} = base du lien).
     */
    public record AssetInput(LiveSlot slot, LinkSignalReplay.Config template, String source, Double meanMultiplier, Double referenceBase) {

        /** Sans base de référence explicite : le DCA fixe garde la base du lien. */
        public AssetInput(LiveSlot slot, LinkSignalReplay.Config template, String source) {
            this(slot, template, source, null, null);
        }

        public AssetInput withReferenceBase(Double base) {
            return new AssetInput(slot, template, source, meanMultiplier, base);
        }

        public AssetInput withTemplate(LinkSignalReplay.Config newTemplate) {
            return new AssetInput(slot, newTemplate, source, meanMultiplier, referenceBase);
        }
    }

    public ScorerInputs {
        Objects.requireNonNull(endDate, "endDate");
        if (assets == null || assets.isEmpty()) {
            throw new IllegalArgumentException("Au moins un lien");
        }
        if (!(cash0 > 0)) {
            throw new IllegalArgumentException("Capital de départ > 0 obligatoire");
        }
        assets = List.copyOf(assets);
        windows = List.copyOf(windows);
    }

    /** Fenêtres, pénalité et mise finale par défaut ; réglages de modulation neutres ; coûts complets statiques. */
    public static ScorerInputs of(List<AssetInput> assets, double cash0, LocalDate endDate, StartingProfile profile,
                                  RainbowModulationControl limits) {
        return new ScorerInputs(assets, cash0, endDate, ModulationSettings.neutral(), limits, profile, WindowSpec.defaults(),
                OptimizerDefaults.DISPERSION_PENALTY, OptimizerDefaults.MAX_FINAL_STAKE_MULTIPLE, null);
    }

    public ScorerInputs withEndDate(LocalDate newEnd) {
        return new ScorerInputs(assets, cash0, newEnd, baseSettings, limits, profile, windows, dispersionPenalty, maxFinalStake,
                completeCosts);
    }

    public ScorerInputs withAssets(List<AssetInput> newAssets) {
        return new ScorerInputs(newAssets, cash0, endDate, baseSettings, limits, profile, windows, dispersionPenalty, maxFinalStake,
                completeCosts);
    }

    public ScorerInputs withProfile(StartingProfile newProfile) {
        return new ScorerInputs(assets, cash0, endDate, baseSettings, limits, newProfile, windows, dispersionPenalty, maxFinalStake,
                completeCosts);
    }

    public ScorerInputs withWindows(List<WindowSpec> newWindows) {
        return new ScorerInputs(assets, cash0, endDate, baseSettings, limits, profile, newWindows, dispersionPenalty, maxFinalStake,
                completeCosts);
    }

    public ScorerInputs withBaseSettings(ModulationSettings newSettings) {
        return new ScorerInputs(assets, cash0, endDate, newSettings, limits, profile, windows, dispersionPenalty, maxFinalStake,
                completeCosts);
    }

    public ScorerInputs withMaxFinalStake(Double max) {
        return new ScorerInputs(assets, cash0, endDate, baseSettings, limits, profile, windows, dispersionPenalty, max,
                completeCosts);
    }

    public ScorerInputs withDispersionPenalty(double penalty) {
        return new ScorerInputs(assets, cash0, endDate, baseSettings, limits, profile, windows, penalty, maxFinalStake,
                completeCosts);
    }
}
