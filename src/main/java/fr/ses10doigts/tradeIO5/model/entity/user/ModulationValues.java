package fr.ses10doigts.tradeIO5.model.entity.user;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Les cinq réglages de modulation d'un utilisateur à un instant donné (instantané d'historique). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ModulationValues {

    private Double exposureMin;
    private Double exposureMax;
    private Double power;
    private Double bias;
    /** Marge de régulation effective (% de la largeur de bande) au moment du changement. */
    private Double regulationMarginPct;
}
