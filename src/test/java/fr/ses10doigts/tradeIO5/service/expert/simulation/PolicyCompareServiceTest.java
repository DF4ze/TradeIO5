package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.AthCapSpec;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Comparaison de politiques : validation de la demande")
class PolicyCompareServiceTest {

    private final PolicyCompareService service = new PolicyCompareService(mock(FullSimulationService.class));

    private static PolicySpec constant() {
        return new PolicySpec("CONSTANT", Map.of());
    }

    @Test
    @DisplayName("Aucune politique, plus de 5 politiques, type inconnu ou paramètre invalide : refusé avant tout chargement")
    void rejectsInvalidRequests() {
        assertThrows(ExpertValidationException.class, () -> service.compare(null, null));
        assertThrows(ExpertValidationException.class, () -> service.compare(null, new PolicyCompareRequest(null, null)));
        assertThrows(ExpertValidationException.class, () -> service.compare(null, new PolicyCompareRequest(null, List.of())));
        assertThrows(ExpertValidationException.class,
                () -> service.compare(null, new PolicyCompareRequest(null, Collections.nCopies(PolicyCompareService.MAX_POLICIES + 1, constant()))));
        ExpertValidationException unknown = assertThrows(ExpertValidationException.class,
                () -> service.compare(null, new PolicyCompareRequest(null, List.of(new PolicySpec("NOPE", Map.of())))));
        assertTrue(unknown.getFields().containsKey("policy.type"));
        assertThrows(ExpertValidationException.class, () -> service.compare(null, new PolicyCompareRequest(null,
                List.of(new PolicySpec("BEAR_DERISK_RAMP", Map.of("bearTarget", 10.0))))));
    }

    @Test
    @DisplayName("Plafond ATH invalide d'une variante : refusé avant tout chargement, champ qualifié par variante")
    void rejectsInvalidAthCap() {
        ExpertValidationException bad = assertThrows(ExpertValidationException.class, () -> service.compare(null,
                new PolicyCompareRequest(null, List.of(constant(), new PolicySpec("CONSTANT", Map.of(), new AthCapSpec("YOLO", 0.5, null))))));
        assertTrue(bad.getFields().containsKey("policies[1].athCap.profile"));
        assertTrue(bad.getFields().containsKey("policies[1].athCap.buyCap"));
    }
}
