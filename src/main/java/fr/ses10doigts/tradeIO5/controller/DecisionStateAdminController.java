package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.decision.Decision;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionEngine;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionSummaryResponse;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultMarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lecture de l'état vivant (scénarios/décisions actifs), plan de test manuel Palier 3 — Clem,
 * 2026-08-17. Comble un manque relevé lors de la rédaction du plan de test initial : les 3 endpoints
 * admin existants (snapshot/archive/orchestrate) sont tous des déclencheurs, aucun ne permettait de
 * relire ce que le cycle avait produit sans passer par la base de données ou les logs.
 * <p>
 * {@code ScenarioEngine.getActiveScenarios}/{@code getAllActiveScenarios} et {@code
 * DecisionEngine.getAllActiveDecisions} existaient déjà (utilisées en interne par la photo
 * quotidienne, cf. étape 4), mais n'étaient exposées par aucun {@code @RestController} ni tool MCP —
 * vérifié par recherche exhaustive avant ce lot. Même préfixe {@code /api/admin/decision}, réservé
 * {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/decision")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DecisionStateAdminController {

    private final ScenarioEngine scenarioEngine;
    private final DecisionEngine decisionEngine;
    private final DomainClock clock;

    /**
     * @param owner optionnel — {@code "SYSTEM"} ou l'id utilisateur (cf. {@link ScenarioOwner#fromString}).
     *              Omis = tous owners confondus.
     */
    @GetMapping("/scenarios")
    public ResponseEntity<List<ScenarioSummaryResponse>> getActiveScenarios(
            @RequestParam(required = false) String owner
    ) {
        List<MarketScenario> scenarios = owner != null
                ? scenarioEngine.getActiveScenarios(ScenarioOwner.fromString(owner), DefaultMarketScenario.EXPIRATION_IDLE, clock.now())
                : scenarioEngine.getAllActiveScenarios(DefaultMarketScenario.EXPIRATION_IDLE, clock.now());
        return ResponseEntity.ok(scenarios.stream().map(ScenarioSummaryResponse::from).toList());
    }

    /**
     * @param owner optionnel — même convention que {@link #getActiveScenarios}. Filtré côté
     *              contrôleur : {@link DecisionEngine} n'expose aucune variante owner-scopée de {@code
     *              getAllActiveDecisions} (toujours "tous owners confondus" par conception, cf. étape 4).
     */
    @GetMapping("/decisions")
    public ResponseEntity<List<DecisionSummaryResponse>> getActiveDecisions(
            @RequestParam(required = false) String owner
    ) {
        List<Decision> decisions = decisionEngine.getAllActiveDecisions();
        if (owner != null) {
            ScenarioOwner target = ScenarioOwner.fromString(owner);
            decisions = decisions.stream().filter(d -> d.getOwner().equals(target)).toList();
        }
        return ResponseEntity.ok(decisions.stream().map(DecisionSummaryResponse::from).toList());
    }
}
