package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.AthCapSpec;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Résout et valide le champ {@code athCap} d'une demande Expert : le profil donne les défauts
 * ({@link ModulationDefaults#athCapOf}), {@code buyCap} / {@code sellCap} explicites les surchargent.
 */
final class AthCapResolver {

    static final String FIELD = "athCap";

    private AthCapResolver() {
    }

    /**
     * @param spec  champ de la demande ({@code null} accepté)
     * @param field préfixe du champ dans les erreurs de validation ({@link #FIELD} ou {@code policies[i].athCap})
     * @return le plafond appliqué ; {@code null} si aucun
     * @throws ExpertValidationException une erreur par champ ({@code buyCap ≥ 1}, {@code sellCap ≥ 0}, profil connu)
     */
    static AthCap resolve(AthCapSpec spec, String field) {
        if (spec == null) {
            return null;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        AthCap defaults = null;
        if (spec.profile() != null && !spec.profile().isBlank()) {
            try {
                defaults = ModulationDefaults.athCapOf(StartingProfile.valueOf(spec.profile().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                errors.put(field + ".profile", "profil inconnu (attendu : " + Arrays.toString(StartingProfile.values()) + ")");
            }
        }
        if (spec.buyCap() != null && !(Double.isFinite(spec.buyCap()) && spec.buyCap() >= 1)) {
            errors.put(field + ".buyCap", "doit être >= 1");
        }
        if (spec.sellCap() != null && !(Double.isFinite(spec.sellCap()) && spec.sellCap() >= 0)) {
            errors.put(field + ".sellCap", "doit être >= 0");
        }
        if (!errors.isEmpty()) {
            throw new ExpertValidationException(errors);
        }
        AthCap cap = new AthCap(spec.buyCap() != null ? spec.buyCap() : defaults == null ? null : defaults.buyCap(),
                spec.sellCap() != null ? spec.sellCap() : defaults == null ? null : defaults.sellCap());
        return cap.isEmpty() ? null : cap;
    }
}
