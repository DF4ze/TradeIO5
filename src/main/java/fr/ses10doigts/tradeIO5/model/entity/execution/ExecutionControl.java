package fr.ses10doigts.tradeIO5.model.entity.execution;

import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interrupteurs et plafonds <b>globaux</b> de l'exécution (une seule ligne, id {@value #SINGLETON_ID}), lus à chaque étape
 * sans cache : le kill switch agit sans redéploiement. Plafond d'un ordre = {@code maxOrderMultiplier} × {@code baseAmount}
 * du preset, plafond journalier par utilisateur = {@code maxDayMultiplier} × somme des {@code baseAmount} de ses bindings ;
 * dans tous les cas bornés par les plafonds ABSOLUS en constante ({@link ExecutionDefaults}), que la base ne peut pas relever.
 * Ligne absente => comportement le plus sûr ({@link #failClosed()}).
 */
@Entity
@Table(name = "execution_control")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionControl {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Vrai => aucune nouvelle étape n'est envoyée (état initial : engagé). */
    @Column(nullable = false)
    private boolean killSwitch;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal maxOrderMultiplier;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal maxDayMultiplier;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 64)
    private String updatedBy;

    /** Ligne par défaut : kill switch engagé, multiplicateurs par défaut. */
    public static ExecutionControl initial(Instant now) {
        return ExecutionControl.builder().id(SINGLETON_ID).killSwitch(true)
                .maxOrderMultiplier(ExecutionDefaults.DEFAULT_MAX_ORDER_MULTIPLIER)
                .maxDayMultiplier(ExecutionDefaults.DEFAULT_MAX_DAY_MULTIPLIER).updatedAt(now).updatedBy("initializer").build();
    }

    /** Valeur à utiliser quand la ligne est absente : kill switch engagé. */
    public static ExecutionControl failClosed() {
        return initial(Instant.EPOCH);
    }
}
