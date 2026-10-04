package fr.ses10doigts.tradeIO5.service.tree.trend;

/**
 * Encodage numérique [-1, 1] du {@link SwingStructureRegime} — extrait du {@code switch} de
 * {@code SwingStructureIndicator.compute} (Étape 1 de la roadmap Trend unifié) pour être réutilisé
 * par {@code TrendConfirmationStrategy} (Étape 4) comme composante directionnelle de son score
 * final — cf. {@code docs/prompts/prompt-implementation-trend-unifie-etape4.md} sec. 1.2.
 * <p>
 * <b>Choix de conception de ce lot</b> (ni l'étude ni la roadmap ne l'imposent explicitement) :
 * réutiliser l'encodage numérique déjà établi par l'adaptateur {@code SwingStructureIndicator}
 * plutôt que d'en inventer un nouveau pour la Strategy.
 */
public final class SwingStructureRegimeScore {

    private SwingStructureRegimeScore() {
    }

    public static double toScore(SwingStructureRegime regime) {
        return switch (regime) {
            case BULL_CONFIRMED -> 1.0;
            case WARNING_BULL_BREAK -> 0.5;
            case UNDEFINED -> 0.0;
            case WARNING_BEAR_BREAK -> -0.5;
            case BEAR_CONFIRMED -> -1.0;
        };
    }
}
