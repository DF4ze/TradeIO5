package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.ModulationChangeSource;
import fr.ses10doigts.tradeIO5.model.entity.user.ModulationValues;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.entity.user.UserModulationHistory;
import fr.ses10doigts.tradeIO5.service.UserModulationService;
import java.time.Instant;
import java.util.List;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réglages de modulation (bande d'exposition, puissance, biais) de l'utilisateur connecté. Le {@code GET} renvoie les valeurs
 * stockées (neutres si absentes) et les limites admin en vigueur ; le {@code PUT} valide contre ces limites (400 sinon).
 */
@RestController
@RequestMapping("/api/user/modulation-settings")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserModulationSettingsController {

    private final UserTradingSettingsService settingsService;
    private final UserModulationService modulationService;
    private final RainbowModulationControlService controlService;
    private final IAuthenticationFacade authenticationFacade;

    /** Corps du PUT : bande, puissance et biais obligatoires ; {@code regulationMarginPct} facultatif (absent = inchangé). */
    public record ModulationSettingsRequest(Double exposureMin, Double exposureMax, Double power, Double bias, Double regulationMarginPct) {
    }

    /** Limites admin en vigueur applicables aux réglages utilisateur. */
    public record LimitsDto(double minBandWidthPts, double powerMin, double powerMax, double biasMax, double marginDefaultPct,
                            double marginMinPct, double marginMaxPct) {
        static LimitsDto of(RainbowModulationControl c) {
            var margin = ModulationDefaults.LIMITS.get(ModulationDefaults.REGULATION_MARGIN_PCT);
            return new LimitsDto(c.getMinBandWidthPts().doubleValue(), c.getPowerMin().doubleValue(),
                    c.getPowerMax().doubleValue(), c.getBiasMax().doubleValue(), c.getRegulationMarginPct().doubleValue(),
                    margin.min().doubleValue(), margin.max().doubleValue());
        }
    }

    /** {@code profile} : profil en vigueur ({@code CUSTOM} dès qu'un paramètre a été modifié à la main) ; nul si jamais choisi. */
    public record ModulationSettingsDto(ModulationSettings settings, LimitsDto limits, StartingProfile profile) {
        public ModulationSettingsDto(ModulationSettings settings, LimitsDto limits) {
            this(settings, limits, null);
        }
    }

    /** Une ligne d'historique : quand, d'où (profil ou manuel), profil avant / après, valeurs avant / après. */
    public record ChangeDto(Instant at, ModulationChangeSource source, StartingProfile profileBefore, StartingProfile profileAfter,
                            ModulationValues before, ModulationValues after) {
        static ChangeDto of(UserModulationHistory h) {
            return new ChangeDto(h.getOccurredAt(), h.getSource(), h.getProfileBefore(), h.getProfileAfter(), h.getBefore(), h.getAfter());
        }
    }

    @GetMapping
    public ModulationSettingsDto get() {
        var user = authenticationFacade.getConnectedUser();
        return new ModulationSettingsDto(settingsService.getModulationSettings(user), LimitsDto.of(controlService.current()),
                modulationService.profileOf(user));
    }

    @PutMapping
    public ModulationSettingsDto put(@RequestBody ModulationSettingsRequest request) {
        if (request.exposureMin() == null || request.exposureMax() == null || request.power() == null
                || request.bias() == null) {
            throw new IllegalArgumentException("exposureMin, exposureMax, power et bias sont obligatoires");
        }
        var user = authenticationFacade.getConnectedUser();
        UserModulationService.Result saved = modulationService.update(user, request.exposureMin(), request.exposureMax(), request.power(),
                request.bias(), request.regulationMarginPct());
        return new ModulationSettingsDto(saved.settings(), LimitsDto.of(controlService.current()), saved.profile());
    }

    /** Historique des changements de modulation (profil choisi ou paramètre modifié), du plus récent au plus ancien. */
    @GetMapping("/history")
    public List<ChangeDto> history() {
        return modulationService.history(authenticationFacade.getConnectedUser()).stream().map(ChangeDto::of).toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
