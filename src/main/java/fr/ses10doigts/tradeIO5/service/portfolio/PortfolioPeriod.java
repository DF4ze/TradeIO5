package fr.ses10doigts.tradeIO5.service.portfolio;

import java.time.LocalDate;
import java.util.Arrays;

/** Périodes d'affichage du patrimoine (jours UTC). */
public enum PortfolioPeriod {
    D7("7d", 7), D30("30d", 30), D90("90d", 90), Y1("1y", 365), ALL("all", null);

    private final String code;
    private final Integer days;

    PortfolioPeriod(String code, Integer days) {
        this.code = code;
        this.days = days;
    }

    public String code() {
        return code;
    }

    /** Premier jour inclus ; pour {@link #ALL}, une date plancher. */
    public LocalDate from(LocalDate today) {
        return days == null ? LocalDate.of(2000, 1, 1) : today.minusDays(days - 1L);
    }

    /** Période d'un code (« 7d », « 30d », « 90d », « 1y », « all », insensible à la casse) ; {@link IllegalArgumentException} sinon. */
    public static PortfolioPeriod of(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Période inconnue : " + code + " (7d, 30d, 90d, 1y, all)"));
    }
}
