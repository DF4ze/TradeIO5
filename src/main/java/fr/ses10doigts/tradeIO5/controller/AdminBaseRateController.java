package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.controller.AdminModulationController.ParameterDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.AssetRates;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rates;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Taux de base par actif (onglet admin « Taux de base »), réservé ROLE_ADMIN. Un jeu de 4 taux par actif (taux par jour / cash alloué au lien,
 * unité interne ; la conversion en $ pour 1 000 USDC est faite par le front). Source unique : {@link AssetBaseRateService}. Aucun effet sur les
 * ordres : proposition indicative d'onboarding.
 */
@RestController
@RequestMapping("/api/admin/base-rates")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBaseRateController {

    private final AssetBaseRateService rates;
    private final RainbowModulationControlService control;
    private final AdminBaseRateService adminService;
    private final IAuthenticationFacade authenticationFacade;

    /** Un actif : 4 taux, {@code calibre} (faux = repli BTC « non calibré »), {@code dedicated} (ligne propre en base). */
    public record AssetDto(String asset, BigDecimal prudent, BigDecimal balanced, BigDecimal dynamic, BigDecimal full, boolean calibre,
                           boolean dedicated, String fallbackAsset) {
        static AssetDto of(AssetRates r) {
            Rates v = r.rates();
            return new AssetDto(r.asset(), v.prudent(), v.balanced(), v.dynamic(), v.full(), r.calibrated(), r.dedicated(),
                    r.calibrated() ? null : ModulationDefaults.FALLBACK_RATE_ASSET);
        }
    }

    /** Liste : bornes absolues d'un taux, marge k (valeur, défaut, bornes) et actifs. */
    public record BaseRatesDto(BigDecimal rateMin, BigDecimal rateMax, ParameterDto marginK, List<AssetDto> assets) {
    }

    public record MarginKRequest(BigDecimal marginK) {
    }

    @GetMapping
    public BaseRatesDto list() {
        return new BaseRatesDto(ModulationDefaults.BASE_RATE_MIN, ModulationDefaults.BASE_RATE_MAX, marginK(), rates.list().stream().map(AssetDto::of).toList());
    }

    /** Remplace les 4 taux de l'actif ; crée la ligne dédiée si l'actif n'en avait pas. */
    @PutMapping("/{asset}")
    public AssetDto put(@PathVariable String asset, @RequestBody Rates request) {
        return AssetDto.of(adminService.save(asset, request, actor()));
    }

    /** BTC / ETH : table de calage ; autre actif : supprime la ligne dédiée (retour au repli BTC). */
    @PostMapping("/{asset}/defaults")
    public AssetDto defaults(@PathVariable String asset) {
        return AssetDto.of(adminService.resetDefaults(asset, actor()));
    }

    @PutMapping("/margin-k")
    public ParameterDto putMarginK(@RequestBody MarginKRequest request) {
        adminService.updateMarginK(request == null ? null : request.marginK(), actor());
        return marginK();
    }

    private ParameterDto marginK() {
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.BASE_RATE_MARGIN_K);
        return new ParameterDto(control.current().getBaseRateMarginK(), limit.defaultValue(), limit.min(), limit.max());
    }

    private String actor() {
        return authenticationFacade.getConnectedUser().getUsername();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
