package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.RegimeBandPolicy;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Espace L2 réel (6 paramètres) : bande d'exposition par régime ({@code bullMin, bullMax, bearMin, bearMax}, politique
 * {@code REGIME_BAND}), plafond de mise ATH à l'achat ({@code buyCap}, borné par le défaut du profil) et multiple du montant de base de
 * référence ({@code baseMultiplier}, 0,5 à 2). Le plafond de vente est celui du profil. Les contraintes de cohérence
 * ({@link #validator}) sont vérifiées avant tout rejeu.
 */
public final class L2Params {

    public static final String BULL_MIN = "bullMin";
    public static final String BULL_MAX = "bullMax";
    public static final String BEAR_MIN = "bearMin";
    public static final String BEAR_MAX = "bearMax";
    public static final String BUY_CAP = "buyCap";
    public static final String BASE_MULTIPLIER = "baseMultiplier";

    private static final double BAND_STEP = 5.0;
    private static final double MULTIPLIER_MIN = 0.5;
    private static final double MULTIPLIER_MAX = 2.0;
    private static final double MULTIPLIER_STEP = 0.25;
    private static final double MULTIPLIER_START = 1.0;
    private static final double MIN_BUY_CAP = 1.0;
    private static final double MAX_BUY_CAP = 15.0;

    private L2Params() {
    }

    /** Espace dont certaines valeurs de départ sont remplacées (variante expérimentale ; recalées sur la grille). */
    public static ParamSpace space(StartingProfile profile, java.util.Map<String, Double> startOverrides) {
        ParamSpace base = space(profile);
        if (startOverrides.isEmpty()) {
            return base;
        }
        return new ParamSpace(base.params().stream().map(p -> startOverrides.containsKey(p.name())
                ? new ParamSpace.Param(p.name(), p.kind(), p.min(), p.max(), p.step(), startOverrides.get(p.name()), p.frozen(), p.choices())
                : p).toList());
    }

    /** Départ : bande 20–90 / 0–30 (règle des mesures H0), plafond d'achat du profil (aussi sa borne haute), multiple 1. */
    public static ParamSpace space(StartingProfile profile) {
        AthCap cap = ModulationDefaults.athCapOf(profile);
        double buyStart = cap == null || cap.buyCap() == null ? MAX_BUY_CAP : cap.buyCap();
        return new ParamSpace(List.of(
                ParamSpace.Param.continuous(BULL_MIN, 0, 50, BAND_STEP, 20),
                ParamSpace.Param.continuous(BULL_MAX, 50, 100, BAND_STEP, 90),
                ParamSpace.Param.continuous(BEAR_MIN, 0, 30, BAND_STEP, 0),
                ParamSpace.Param.continuous(BEAR_MAX, 10, 60, BAND_STEP, 30),
                ParamSpace.Param.integer(BUY_CAP, (int) MIN_BUY_CAP, (int) Math.max(MIN_BUY_CAP + 1, Math.round(buyStart)), 1,
                        (int) Math.round(buyStart)),
                ParamSpace.Param.continuous(BASE_MULTIPLIER, MULTIPLIER_MIN, MULTIPLIER_MAX, MULTIPLIER_STEP, MULTIPLIER_START)));
    }

    /**
     * Cohérence d'un candidat : chaque bande a {@code min < max} et une largeur au moins égale à la largeur minimale admin ; la liste
     * des motifs est vide quand le candidat est valide.
     */
    public static Function<Candidate, List<String>> validator(RainbowModulationControl limits) {
        double minWidth = limits.getMinBandWidthPts() == null ? OptimizerDefaults.DEFAULT_MIN_BAND_WIDTH
                : limits.getMinBandWidthPts().doubleValue();
        return c -> {
            List<String> reasons = new ArrayList<>();
            checkBand(reasons, "bull", c.get(BULL_MIN), c.get(BULL_MAX), minWidth);
            checkBand(reasons, "bear", c.get(BEAR_MIN), c.get(BEAR_MAX), minWidth);
            return reasons;
        };
    }

    private static void checkBand(List<String> reasons, String name, double min, double max, double minWidth) {
        if (!(min < max)) {
            reasons.add(name + " : min " + min + " >= max " + max);
        } else if (max - min < minWidth) {
            reasons.add(name + " : largeur " + (max - min) + " < minimum admin " + minWidth);
        }
    }

    /** Traduction d'un candidat valide (bandes ordonnées) ; le plafond de vente est celui du profil. */
    public static CandidateAdapter adapter(StartingProfile profile) {
        AthCap profileCap = ModulationDefaults.athCapOf(profile);
        Double sellCap = profileCap == null ? null : profileCap.sellCap();
        return c -> new ResolvedCandidate(new RegimeBandPolicy(c.get(BULL_MIN), c.get(BULL_MAX), c.get(BEAR_MIN), c.get(BEAR_MAX)),
                new AthCap(c.get(BUY_CAP), sellCap), c.get(BASE_MULTIPLIER));
    }
}
