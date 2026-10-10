package fr.ses10doigts.tradeIO5.model.dto.execution;

/** OK = chemin chiffré ; NO_PATH = aucun chemin sans fiat ; BELOW_MIN = une jambe sous le minimum de l'exchange. */
public enum PathStatus {
    OK,
    NO_PATH,
    BELOW_MIN
}
