package fr.ses10doigts.tradeIO5.service.optimizer;

/** Budget d'une évaluation (successive halving) : de la plus rapide à la plus fidèle. */
public enum Fidelity {
    /** Fenêtre courte seule, frais simplifiés. */
    SHORT_WINDOW_SIMPLIFIED,
    /** Toutes les fenêtres éligibles, frais simplifiés. */
    ALL_WINDOWS_SIMPLIFIED,
    /** Toutes les fenêtres éligibles, coûts complets ({@code OrderReplay}) : finalistes. */
    ALL_WINDOWS_COMPLETE
}
