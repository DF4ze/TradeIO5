package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.DefaultsDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.EnabledDto;
import org.springframework.web.bind.annotation.RequestBody;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetEventDto;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * API du bench grandeur nature Rainbow DCA ATR pour l'utilisateur connecté (patron
 * {@link UserTradingSettingsController}). Tout est scopé à l'utilisateur ; preset d'un autre utilisateur ou inexistant
 * ⇒ 404. Erreurs mappées par {@link RainbowLiveControllerAdvice}. Aucun ordre réel.
 */
@RestController
@RequestMapping("/api/rainbow-live")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveController {

    private final AssetAffectationService affectationService;
    private final RainbowLiveQueryService queryService;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping("/defaults")
    public DefaultsDto defaults() {
        return queryService.defaults();
    }

    /** Historique des changements d'Affectation du user connecté (filtre ?asset optionnel), du plus récent au plus ancien. */
    @GetMapping("/preset-events")
    public List<PresetEventDto> presetEvents(@RequestParam(required = false) String asset) {
        return queryService.presetEvents(authenticationFacade.getConnectedUser(), asset);
    }

    /** Affectations du user connecté (une par actif ; créées sur les presets de base à la première lecture). */
    @GetMapping("/affectations")
    public List<AffectationDto> affectations(@RequestParam(required = false) String asset) {
        return queryService.listAffectations(authenticationFacade.getConnectedUser(), asset);
    }

    @GetMapping("/affectations/{id}")
    public AffectationDto affectation(@PathVariable Long id) {
        return queryService.getAffectation(authenticationFacade.getConnectedUser(), id);
    }

    /** Activation / désactivation de l'Affectation (jouée ou non à chaque passe). */
    @PatchMapping("/affectations/{id}/enabled")
    public AffectationDto setEnabled(@PathVariable Long id, @RequestBody EnabledDto request) {
        User user = authenticationFacade.getConnectedUser();
        affectationService.setEnabled(user, id, request.enabled());
        return queryService.getAffectation(user, id);
    }
}
