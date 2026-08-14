package fr.ses10doigts.tradeIO5.service.tree.decision;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Restauration au (re)démarrage (Palier 3, étape 4, docs/etudes/etude-branchement-persistance-decision-engine.md
 * §C/§E pt3). Délègue entièrement à {@link DecisionScenarioRestoreService} depuis l'étape 6
 * (archivage sur inactivité) : toute la logique de reconstruction (photo + rejeu delta) a été
 * extraite dans ce service, réutilisable en dehors du seul démarrage (cf. {@code
 * DecisionScenarioRestoreService#restoreOwner}, appelé par le hook de reconnexion de {@code
 * AuthController}).
 * <p>
 * {@code @Order(60)} : volontairement après tous les {@code CommandLineRunner} de seed existants
 * (1 à 50 au moment de l'écriture — {@code @Order(10)} suggéré par le prompt initial entrait en
 * collision avec {@code RoleInitializer}, et la restauration a de toute façon plus de sens après
 * tout seed de données de base, pas seulement après {@code AssetInitializer}).
 */
@Component
@RequiredArgsConstructor
@Order(60)
public class DecisionScenarioRestoreRunner implements CommandLineRunner {

    private final DecisionScenarioRestoreService restoreService;

    @Override
    public void run(String... args) {
        restoreService.restoreAll();
    }
}
