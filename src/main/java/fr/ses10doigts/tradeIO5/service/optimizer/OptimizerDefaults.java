package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.List;

/** Défauts documentés du moteur d'optimisation : source unique des poids de fenêtres, seuils, graines et budgets de recherche. */
public final class OptimizerDefaults {

    private OptimizerDefaults() {
    }

    // ---- fenêtres du scorer (ancrées à la même date de fin)
    public static final int WINDOW_SHORT_MONTHS = 12;
    public static final int WINDOW_SHORT_WEIGHT = 3;
    public static final int WINDOW_MEDIUM_MONTHS = 24;
    public static final int WINDOW_MEDIUM_WEIGHT = 2;
    public static final int WINDOW_FULL_WEIGHT = 1;

    // ---- score
    /** Pénalité de dispersion : {@code λ × écart absolu moyen pondéré autour de la médiane} (points de % du capital de gain vs DCA fixe de référence). */
    public static final double DISPERSION_PENALTY = 0.5;
    /** Score d'un jeu vetoé (très inférieur à tout gain en % réaliste). */
    public static final double VETO_SCORE = -1000.0;
    /** Mise finale maximale autorisée (multiple de la base, après modulation) : au-delà, veto. */
    public static final double MAX_FINAL_STAKE_MULTIPLE = 25.0;
    /** Tolérance numérique des vetos (cash négatif). */
    public static final double EPSILON = 1e-6;

    // ---- coûts
    /** Frais « simplifiés » : taux constant par ordre (% du notionnel), sans spread ni minimum d'ordre. */
    public static final double SIMPLIFIED_FEE_PCT = 0.40;
    /** Barème « complet » statique (aucun appel exchange) : frais taker, demi-spread et slippage en %. */
    public static final double COMPLETE_FEE_PCT = 0.40;
    public static final double COMPLETE_HALF_SPREAD_PCT = 0.05;
    public static final double COMPLETE_SLIPPAGE_PCT = 0.02;
    /** Achat minimum d'un lien (USDC) dans le barème complet statique. */
    public static final double COMPLETE_MIN_BUY_USD = 1.0;
    public static final double LOT_SIZE = 1e-8;

    // ---- recherche
    public static final long SEED = 20261116L;
    public static final int SOBOL_SAMPLES = 64;
    public static final int HALVING_FACTOR = 3;
    public static final int FINALISTS = 4;
    public static final int SENSITIVITY_POINTS = 5;
    /** Effet minimal (points de score) sous lequel un paramètre est gelé. */
    public static final double FREEZE_THRESHOLD = 0.5;
    public static final int REFINE_SAMPLES = 16;
    public static final int REFINE_RADIUS_STEPS = 3;
    public static final int REFINE_TOP = 2;
    /** Un voisin est « dans le plateau » s'il vaut au plus cet écart (points de score) sous le centre. */
    public static final double PLATEAU_BAND = 2.0;
    /** Robustesse : écart relatif toléré au score de base ; plancher du dénominateur (points de score). */
    public static final double ROBUSTNESS_TOLERANCE = 0.25;
    public static final double ROBUSTNESS_FLOOR = 1.0;
    /** Décalages de la date de fin (jours en arrière) des bousculades. */
    public static final List<Integer> BUMP_DAYS = List.of(1, 7, 15, 30);

    // ---- exécution
    public static final int POOL_SIZE = 2;
    public static final long RESULT_TTL_MINUTES = 30;
    public static final String ENABLED_PROPERTY = "tradeio.optimizer.enabled";

    // ---- vocabulaire des vetos
    public static final String VETO_DRAWDOWN = "DRAWDOWN";
    public static final String VETO_DEGENERATE = "DEGENERATE";
    public static final String VETO_CAPITAL = "CAPITAL_EXCEEDED";
    public static final String VETO_FINAL_STAKE = "FINAL_STAKE";
    public static final String VETO_NO_WINDOW = "NO_ELIGIBLE_WINDOW";
    /** Candidat incohérent (bande inversée ou trop étroite) : rejeté sans rejeu, compté dans les essais. */
    public static final String VETO_INVALID = "INVALID_CANDIDATE";

    // ---- calibrage de la politique L2 (étape 4)
    /** Capital normalisé du wallet de référence (USDC). */
    public static final double REFERENCE_CASH_USD = 10_000.0;
    /** Actifs du wallet de référence (cash commun). */
    public static final List<String> REFERENCE_ASSETS = List.of("BTC", "ETH", "PAXG");
    /** Fins d'entraînement des plis (le test = les {@link #TEST_MONTHS} mois suivants) ; ajustables dans la demande. */
    public static final List<String> FOLD_ENDS = List.of("2023-11-15", "2024-11-15", "2025-11-15");
    public static final int TEST_MONTHS = 12;
    /** Largeur de bande minimale utilisée si les bornes admin ne la donnent pas. */
    public static final double DEFAULT_MIN_BAND_WIDTH = 10.0;
    /** Pli sans candidat exploitable : tous les candidats (ou le départ) sont vetoés à l'entraînement ⇒ {@link #VERDICT_KEEP_START}. */
    public static final String FOLD_TRAIN_VETOED = "ENTRAINEMENT_VETOE";
    /** Lien sans assez de bougies pour une fenêtre (rapporté avec bougies disponibles / requises, jamais exclu en silence). */
    public static final String STATUS_INSUFFICIENT_HISTORY = "HISTORIQUE_INSUFFISANT";
    public static final String VERDICT_ADOPT = "ADOPTER";
    public static final String VERDICT_KEEP_START = "GARDER_DEPART";
    /** Part de la pénalité au-delà de laquelle une alternative de dispersion est proposée (fraction du score absolu). */
    public static final double PENALTY_SHARE_ALERT = 0.5;

    // ---- optimiseur de presets L1 (étape 5)
    /** Rayon relatif par défaut autour du preset en place (±25 %) et crans par côté de la grille de recherche. */
    public static final double PRESET_DELTA = 0.25;
    public static final int PRESET_GRID_STEPS = 4;
    /** Plis semestriels du walk-forward (le test = les {@link #PRESET_TEST_MONTHS} mois suivants). */
    public static final List<String> PRESET_FOLD_ENDS = List.of("2023-05-15", "2023-11-15", "2024-05-15", "2024-11-15", "2025-05-15",
            "2025-11-15");
    public static final int PRESET_TEST_MONTHS = 6;
    /** Cadences de ré-optimisation comparées (jours) et premier jour d'entraînement de la chaîne. */
    public static final List<Integer> PRESET_CADENCES = List.of(30, 90, 180);
    public static final String PRESET_CADENCE_START = "2023-11-15";
    /** Plis (ou segments) à entraînement vetoé au-delà desquels le processus n'est pas exploitable (fraction). */
    public static final double PRESET_MAX_TRAIN_BLOCKED_SHARE = 0.30;
    /** Veto L1 : exposition moyenne d'une fenêtre notée au-dessus de la bande du profil de risque. */
    public static final String VETO_EXPOSURE = "EXPOSURE_BAND";
    public static final String STATUS_OPTIMIZABLE = "OPTIMISABLE";
    public static final String STATUS_NOT_OPTIMIZABLE = "NON_OPTIMISABLE";
    public static final String REASON_NO_FREE_PARAM = "AUCUN_PARAMETRE_LIBRE";
    /** Verdict L1 : aucun challenger retenu (résultat nul valide). */
    public static final String VERDICT_KEEP_PRESET = "GARDER_PRESET";

    /** Rappel de significativité joint à tout rapport : la barre monte avec le nombre d'essais. */
    public static final String SIGNIFICANCE_NOTE = "Plus le nombre d'essais est élevé, plus la barre de significativité monte : "
            + "un gain doit dépasser ce que le hasard produit sur autant de candidats avant d'être cru.";
}
