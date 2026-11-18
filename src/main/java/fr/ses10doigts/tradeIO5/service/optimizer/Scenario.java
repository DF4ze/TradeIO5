package fr.ses10doigts.tradeIO5.service.optimizer;

/** Contexte d'évaluation : le monde de base, ou un monde bousculé (date de fin décalée, source de candles alternative). */
public record Scenario(String label) {

    public static final Scenario BASE = new Scenario("base");
}
