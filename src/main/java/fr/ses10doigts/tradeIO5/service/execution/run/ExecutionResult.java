package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;

import java.util.Map;

/**
 * Résultat d'un déclenchement : statut du plan après l'exécution, motifs de blocage par actif (clé {@code *} pour un
 * blocage global) et nombre d'ordres effectivement envoyés.
 */
public record ExecutionResult(Long planId, PlanStatus status, Map<String, ExecutionBlockReason> blocked, int sent) {

    public static ExecutionResult blockedGlobally(Long planId, PlanStatus status, ExecutionBlockReason reason) {
        return new ExecutionResult(planId, status, Map.of("*", reason), 0);
    }
}
