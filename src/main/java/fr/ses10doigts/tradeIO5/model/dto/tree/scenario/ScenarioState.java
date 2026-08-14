package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;

/**
 * Palier 3, étape 4 : {@code onConstructor_ = @JsonCreator} ajouté sur le constructeur 7-arg généré
 * par Lombok — sans lui, Jackson ne peut pas choisir entre les 3 constructeurs visibles ici (celui-ci
 * plus les 2 constructeurs métier ci-dessous), aucun n'étant annoté ("no Creators ... exist"). Même
 * découverte/correctif que {@code ScenarioOwner} (bug préexistant, pas introduit par ce lot).
 */
@Data
@AllArgsConstructor(onConstructor_ = @JsonCreator)
public class ScenarioState{

    private ScenarioType scenarioType;      // scenario: le scénario actif (TRENDING_UP, RANGE, CRASH, …)
    private ScenarioStatus status;          // status : état du scenario
    private SignalType signal;              // weightedSignal: BULLISH / BEARISH / NEUTRAL
    private double confidence;              // confidence: confidence globale (0–1 )
    private boolean stable;                 // stable: validé / confirmé vs transitoire
    private Instant lastUpdated;            // lastUpdate: dernière confirmation
    private Instant createdAt;              // since: début du scénario

    public ScenarioState(ScenarioType scenarioType, Instant createdAt){
        this.scenarioType = scenarioType;
        this.createdAt = createdAt;
        this.lastUpdated = createdAt;
        this.status = ScenarioStatus.INITIAL;
        this.stable = false;
        this.confidence = 0.0;
        this.signal = SignalType.NEUTRAL;
    }

    public ScenarioState(ScenarioState state, Instant now) {
        scenarioType = state.scenarioType;
        createdAt = now;
        lastUpdated = now;
        status = state.status;
        stable = state.stable;
        confidence = state.confidence;
        signal = state.signal;
    }

    public boolean isExpired(Instant now, Duration duration) {

        if (status == ScenarioStatus.INVALIDATED) {
            stable = false;
            return true;
        }

        if (status == ScenarioStatus.EXPIRED) {
            stable = false;
            return true;
        }

        if (lastUpdated.plus(duration).isBefore(now)) {
            status = ScenarioStatus.EXPIRED;
            stable = false;
            return true;
        }

        return false;
    }

    /**
     * {@code @JsonIgnore} (Palier 3, étape 4, même correctif que {@code ScenarioOwner.getId()}) :
     * sans lui, Jackson sérialise une propriété {@code "active"} en trop (dérivée de cette méthode),
     * que la désérialisation par constructeur canonique rejette ensuite ("Unrecognized field").
     */
    @JsonIgnore
    public boolean isActive(){
        return status.isActive();
    }
}
