package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Palier 3, étape 3 : preuve que le contexte Spring démarre correctement avec les deux moteurs
 * devenus des {@code @Service} singletons partagés (option B3, étape 1 et 2). Patron repris de
 * {@code MarketDatasetEngineSpringTest}.
 * <p>
 * Ne prouve pas seulement que l'injection réussit (échec sinon dès le démarrage du contexte) :
 * prouve aussi explicitement le scope singleton, qui est le prérequis structurel de toute l'option
 * B3 — deux résolutions du même type doivent renvoyer la même instance, jamais deux instances
 * distinctes.
 */
@DisplayName("Decision/Scenario Engine - câblage Spring (Palier 3, étape 3)")
@SpringBootTest
class DecisionEngineSpringWiringTest {

    @Autowired
    private ScenarioEngine scenarioEngine;

    @Autowired
    private DecisionEngine decisionEngine;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Le contexte Spring démarre avec les deux beans non-null")
    void beansAreInjectedAndNonNull() {
        assertNotNull(scenarioEngine, "ScenarioEngine doit être injectable comme bean Spring");
        assertNotNull(decisionEngine, "DecisionEngine doit être injectable comme bean Spring");
    }

    @Test
    @DisplayName("ScenarioEngine est bien un singleton Spring (scope par défaut)")
    void scenarioEngineIsSingleton() {
        ScenarioEngine first = applicationContext.getBean(ScenarioEngine.class);
        ScenarioEngine second = applicationContext.getBean(ScenarioEngine.class);

        assertSame(first, second, "Deux résolutions du bean ScenarioEngine doivent renvoyer la même instance");
    }

    @Test
    @DisplayName("DecisionEngine est bien un singleton Spring (scope par défaut)")
    void decisionEngineIsSingleton() {
        DecisionEngine first = applicationContext.getBean(DecisionEngine.class);
        DecisionEngine second = applicationContext.getBean(DecisionEngine.class);

        assertSame(first, second, "Deux résolutions du bean DecisionEngine doivent renvoyer la même instance");
    }
}
