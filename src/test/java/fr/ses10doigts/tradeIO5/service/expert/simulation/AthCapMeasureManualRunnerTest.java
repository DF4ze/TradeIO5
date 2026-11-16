package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.AthCapSpec;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.LinkOverride;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyRunDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runner manuel (DB dev = copie de prod, candles via le pipeline habituel) : mesure du plafond de mise ATH (étape 2 de
 * {@code roadmap-moteur-optimisation.md}) par {@link PolicyCompareService}, même méthode que la mesure H0 de l'étape 1 : un lien par
 * run (les autres coupés), départ 100 % USDC, capital fixé, sans frais. Politiques CONSTANT et REGIME_BAND (bear 0–30, bull 20–90) ×
 * plafonds aucun / Prudent / Équilibré / Dynamique / Full. Écrit {@code target/athcap-measure.md}.
 * <p>
 * Lancement : {@code mvn test -Dtest=AthCapMeasureManualRunnerTest -Dathcap.measure=true} (ignoré sinon).
 */
@SpringBootTest(properties = {
        "logging.level.fr.ses10doigts.tradeIO5=INFO",
        // Cible délibérément la base de dev locale (copie de prod), pas le H2 de test ; aucune migration lancée par ce runner.
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:localhost}:3306/tradeio5?rewriteBatchedStatements=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=klm",
        "spring.datasource.password=klm31",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@EnabledIfSystemProperty(named = "athcap.measure", matches = "true")
class AthCapMeasureManualRunnerTest {

    private static final String USER = "OKlm";
    private static final LocalDate TO = LocalDate.parse("2026-11-15");
    private static final List<String> PROFILES = List.of("PRUDENT", "BALANCED", "DYNAMIC", "FULL");
    private static final Map<String, Double> BAND = Map.of("bullMin", 20.0, "bullMax", 90.0, "bearMin", 0.0, "bearMax", 30.0);

    /** Actif, wallet, lien du wallet portant l'actif, capital, plages ({@code from}). */
    private record Case(String asset, long walletId, long bindingId, double cash0, List<LocalDate> froms) {
    }

    private static final List<Case> CASES = List.of(
            new Case("BTC", 1L, 1L, 2484.0, List.of(LocalDate.parse("2024-11-15"), LocalDate.parse("2018-07-12"))),
            new Case("ETH", 1L, 3L, 2425.0, List.of(LocalDate.parse("2024-11-15"), LocalDate.parse("2018-07-12"))),
            new Case("PAXG", 3L, 5L, 1062.0, List.of(LocalDate.parse("2025-04-23"))));

    @Autowired
    private PolicyCompareService compare;
    @Autowired
    private UserRepository users;

    private static PolicySpec spec(String type, Map<String, Double> params, String profile) {
        return new PolicySpec(type, params, profile == null ? null : new AthCapSpec(profile, null, null));
    }

    @Test
    @DisplayName("Mesure du plafond ATH : CONSTANT / REGIME_BAND × plafonds, BTC / ETH / PAXG")
    void measure() throws IOException {
        ExpertContext ctx = new ExpertContext(users.findByUsername(USER).orElseThrow(), true);
        List<String> lines = new ArrayList<>();
        lines.add("| Actif | Plage | Politique | Plafond | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing "
                + "| maxStake × | maxFinalStake × | Achats plafonnés | ms |");
        lines.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (Case c : CASES) {
            for (LocalDate from : c.froms()) {
                List<PolicyRunDto> runs = new ArrayList<>();
                try {
                    runs.addAll(run(ctx, c, from, constantVariants()).runs());
                    runs.addAll(run(ctx, c, from, bandVariants()).runs().subList(1, 1 + PROFILES.size()));
                } catch (RuntimeException e) {
                    lines.add("| " + c.asset() + " | " + from + " → " + TO + " | ERREUR | " + e.getMessage() + " |");
                    continue;
                }
                for (PolicyRunDto r : runs) {
                    lines.add(row(c.asset(), from, r));
                }
            }
        }
        Files.writeString(Path.of("target/athcap-measure.md"), String.join("\n", lines) + "\n");
        lines.forEach(System.out::println);
    }

    /** CONSTANT (référence sans plafond, ajoutée en tête) + CONSTANT × 4 profils + REGIME_BAND sans plafond. */
    private static List<PolicySpec> constantVariants() {
        List<PolicySpec> v = new ArrayList<>();
        PROFILES.forEach(p -> v.add(spec("CONSTANT", Map.of(), p)));
        v.add(spec("REGIME_BAND", BAND, null));
        return v;
    }

    /** REGIME_BAND × 4 profils (la référence CONSTANT de tête est jetée). */
    private static List<PolicySpec> bandVariants() {
        List<PolicySpec> v = new ArrayList<>();
        PROFILES.forEach(p -> v.add(spec("REGIME_BAND", BAND, p)));
        return v;
    }

    private PolicyCompareDto run(ExpertContext ctx, Case c, LocalDate from, List<PolicySpec> variants) {
        List<LinkOverride> links = List.of(new LinkOverride(c.bindingId(), true, null, null));
        FullRequest full = new FullRequest(c.walletId(), from, TO, c.cash0(), null, null, null, null, null, false, links);
        return compare.compare(ctx, new PolicyCompareRequest(full, variants));
    }

    private static String row(String asset, LocalDate from, PolicyRunDto r) {
        String label = r.spec() != null && r.spec().type().equals("REGIME_BAND") ? "REGIME_BAND" : "CONSTANT";
        return String.format(Locale.ROOT, "| %s | %s → %s | %s | %s | %.0f | %.0f | %+.1f | %.1f | %.1f | %+.0f | %+.0f | %.2f | %.2f | %d | %d |",
                asset, from, TO, label, r.athCap() == null ? "aucun" : r.athCap(), r.finalValue(), r.fixedFinalValue(),
                r.gainVsFixedPct() == null ? Double.NaN : r.gainVsFixedPct(), r.maxDrawdownPct(), r.avgExposurePct(),
                r.gain().exposure(), r.gain().timing(), r.maxStakeMultiple(), r.maxFinalStakeMultiple(), r.cappedBuys(), r.runMs());
    }
}
