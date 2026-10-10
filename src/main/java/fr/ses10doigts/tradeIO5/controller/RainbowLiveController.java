package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.DefaultsDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.EnabledDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PerformanceDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.RunDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetEventDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.StrategyDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.UiModeDto;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDeltaCalculator;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.UpdateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final RainbowLivePresetService presetService;
    private final RainbowLiveQueryService queryService;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping("/defaults")
    public DefaultsDto defaults() {
        return queryService.defaults();
    }

    /** Stratégies Actif (lecture seule côté user ; l'édition est réservée à System, cf. RainbowLiveAdminController). */
    @GetMapping("/strategies")
    public List<StrategyDto> strategies() {
        return queryService.strategies();
    }

    /** Historique des switchs de preset du user connecté (filtre ?asset optionnel), du plus récent au plus ancien. */
    @GetMapping("/preset-events")
    public List<PresetEventDto> presetEvents(@RequestParam(required = false) String asset) {
        return queryService.presetEvents(authenticationFacade.getConnectedUser(), asset);
    }

    /** Mode d'affichage de l'utilisateur (EXPERT par défaut). */
    @GetMapping("/ui-mode")
    public UiModeDto uiMode() {
        return new UiModeDto(presetService.uiMode(authenticationFacade.getConnectedUser()).name());
    }

    @PutMapping("/ui-mode")
    public UiModeDto setUiMode(@RequestBody UiModeDto request) {
        return new UiModeDto(presetService.setUiMode(authenticationFacade.getConnectedUser(), request.mode()).name());
    }

    @GetMapping("/presets")
    public List<PresetDto> presets(@RequestParam(required = false) String asset) {
        return queryService.listPresets(authenticationFacade.getConnectedUser(), asset);
    }

    @PostMapping("/presets")
    public ResponseEntity<PresetDto> create(@RequestBody CreateRequest request) {
        User user = authenticationFacade.getConnectedUser();
        Long id = presetService.create(user, request).getId();
        return ResponseEntity.status(HttpStatus.CREATED).body(queryService.getPreset(user, id));
    }

    @GetMapping("/presets/{id}")
    public PresetDto preset(@PathVariable Long id) {
        return queryService.getPreset(authenticationFacade.getConnectedUser(), id);
    }

    @PutMapping("/presets/{id}")
    public PresetDto update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        User user = authenticationFacade.getConnectedUser();
        presetService.update(user, id, request);
        return queryService.getPreset(user, id);
    }

    /** Activation / désactivation : seule modification permise sur un preset qui suit une stratégie Actif. */
    @PatchMapping("/presets/{id}/enabled")
    public PresetDto setEnabled(@PathVariable Long id, @RequestBody EnabledDto request) {
        User user = authenticationFacade.getConnectedUser();
        presetService.setEnabled(user, id, request.enabled());
        return queryService.getPreset(user, id);
    }

    @DeleteMapping("/presets/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        presetService.delete(authenticationFacade.getConnectedUser(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/presets/{id}/runs")
    public List<RunDto> runs(@PathVariable Long id,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queryService.runs(authenticationFacade.getConnectedUser(), id, from, to);
    }

    @GetMapping("/presets/{id}/performance")
    public PerformanceDto performance(@PathVariable Long id,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queryService.performance(authenticationFacade.getConnectedUser(), id, to);
    }

    @GetMapping("/presets/{id}/delta")
    public RainbowLiveDeltaCalculator.Delta delta(@PathVariable Long id,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queryService.delta(authenticationFacade.getConnectedUser(), id, from, to);
    }
}
