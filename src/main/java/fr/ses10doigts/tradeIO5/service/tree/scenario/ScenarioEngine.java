package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface ScenarioEngine {

    /**
     * Injection d’une nouvelle opinion de marché.
     * Peut créer, mettre à jour, invalider ou expirer des scénarios.
     */
    void onMarketOpinion(
            OpinionSignal opinion,
            ScenarioContext context
    );

    /**
     * Tous les scénarios actifs (EMERGING / CONFIRMING / VALIDATED)
     */
    List<MarketScenario> getActiveScenarios(ScenarioOwner owner, Duration maxAge, Instant now);

    /**
     * Tous les scénarios actifs, tous owners confondus (Palier 3, étape 4 — photo quotidienne).
     * Symétrique à {@link #getActiveScenarios(ScenarioOwner, Duration, Instant)} mais sans filtre
     * owner : le filtrage par owner est une opération de lecture, pas une propriété structurelle
     * de la donnée (choix documenté dans le prompt d'implémentation de cette étape).
     */
    List<MarketScenario> getAllActiveScenarios(Duration maxAge, Instant now);

    /**
     * Scénarios prêts à proposer une intention d’action
     */
    List<ActionIntent> collectActionIntents(ScenarioOwner owner, Instant now);

    /**
     * Nettoyage explicite (expiration, purge)
     */
    void cleanup(Duration maxAge, Instant now);

    /**
     * Restauration au (re)démarrage (Palier 3, étape 4) : réinjecte des scénarios déjà reconstruits
     * (photo + rejeu delta, cf. {@code DecisionScenarioRestoreRunner}) directement dans la map
     * interne, sans passer par {@link #onMarketOpinion}. Écrase silencieusement toute entrée
     * existante à la même clé (cas normal : appelé une seule fois au démarrage, avant tout trafic).
     */
    void restoreScenarios(List<MarketScenario> scenarios);

    /**
     * Retire toutes les données d'un owner de la mémoire active (Palier 3, étape 6 — archivage sur
     * inactivité). Ne persiste rien : appelant responsable d'avoir pris une photo à jour avant
     * d'évincer (cf. ArchivalService).
     */
    void evictOwner(ScenarioOwner owner);
}
