package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/**
 * Mode d'exécution d'un lien (actif × exchange) : {@link #DISABLED} (plan visible sans étape, rien n'est envoyé),
 * {@link #SIMULATION} (toute la chaîne est jouée jusqu'au plan d'ordres chiffré, tagué « simulation », sans jamais rien
 * envoyer) et {@link #ACTIVE} (des ordres réels peuvent partir, sous réserve des garde-fous d'exécution).
 */
public enum BindingExecutionMode {
    DISABLED, SIMULATION, ACTIVE
}
