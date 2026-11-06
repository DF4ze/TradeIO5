package fr.ses10doigts.tradeIO5.model.entity.doctor;

/** Résultat d'un contrôle du Docteur, du plus bénin au plus grave ({@code UNKNOWN} : pas de donnée pour juger, jamais d'alarme). */
public enum DoctorStatus {
    OK, UNKNOWN, WARN, CRIT;

    /** Rang de gravité d'une alarme (WARN < CRIT). */
    public int severityRank() {
        return switch (this) {
            case WARN -> 1;
            case CRIT -> 2;
            default -> 0;
        };
    }
}
