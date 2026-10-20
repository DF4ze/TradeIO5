package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminModulationService;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService.Params;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paramètres globaux de la modulation Rainbow, réservé ROLE_ADMIN. Le {@code GET} renvoie, par paramètre, la valeur, le défaut
 * et les bornes absolues ; le {@code PUT} remplace le jeu complet (validé) et écrit une ligne {@code MODULATION} au journal.
 */
@RestController
@RequestMapping("/api/admin/rainbow-modulation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminModulationController {

    private final RainbowModulationControlService controlService;
    private final AdminModulationService adminService;
    private final IAuthenticationFacade authenticationFacade;

    /** Un paramètre : valeur courante, défaut et bornes absolues (incluses). */
    public record ParameterDto(Number value, BigDecimal defaultValue, BigDecimal min, BigDecimal max) {
    }

    public record ModulationControlDto(Map<String, ParameterDto> parameters, Instant updatedAt, String updatedBy) {
        static ModulationControlDto of(RainbowModulationControl c) {
            Map<String, Number> values = new LinkedHashMap<>();
            values.put(ModulationDefaults.REGULATION_MARGIN_PCT, c.getRegulationMarginPct());
            values.put(ModulationDefaults.SELL_BOOST_MAX, c.getSellBoostMax());
            values.put(ModulationDefaults.BIAS_MAX, c.getBiasMax());
            values.put(ModulationDefaults.POWER_MIN, c.getPowerMin());
            values.put(ModulationDefaults.POWER_MAX, c.getPowerMax());
            values.put(ModulationDefaults.MIN_BAND_WIDTH_PTS, c.getMinBandWidthPts());
            values.put(ModulationDefaults.MODULATION_PRODUCT_MAX, c.getModulationProductMax());
            values.put(ModulationDefaults.AUTONOMY_DAYS, c.getAutonomyDays());
            Map<String, ParameterDto> parameters = new LinkedHashMap<>();
            values.forEach((name, value) -> {
                ModulationDefaults.Limit limit = ModulationDefaults.limit(name);
                parameters.put(name, new ParameterDto(value, limit.defaultValue(), limit.min(), limit.max()));
            });
            return new ModulationControlDto(parameters, c.getUpdatedAt(), c.getUpdatedBy());
        }
    }

    @GetMapping
    public ModulationControlDto get() {
        return ModulationControlDto.of(controlService.current());
    }

    @PutMapping
    public ModulationControlDto put(@RequestBody Params request) {
        return ModulationControlDto.of(adminService.update(request, authenticationFacade.getConnectedUser().getUsername()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
