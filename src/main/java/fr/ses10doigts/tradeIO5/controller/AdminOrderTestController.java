package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.RouteValidationRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.RouteValidationDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.RealOrderRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.RealOrderResultDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.PriceSuggestionDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.ReconcileRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.admin.AdminOrderTestService;
import fr.ses10doigts.tradeIO5.service.admin.OrderPriceSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Testeur d'ordre : {@code /validate} (aucun ordre créé), {@code /real} (envoi d'un ordre RÉEL, confirmation et verrous requis)
 * et {@code /reconcile} (relecture seule). Réservé ADMIN.
 */
@RestController
@RequestMapping("/api/admin/execution/order-test")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminOrderTestController {

    private final AdminOrderTestService orderTests;
    private final IAuthenticationFacade authenticationFacade;
    private final OrderPriceSuggestionService priceSuggestions;

    /** Prix limite auto (meilleur ask/bid du carnet public ± tolérance 0,5 ou 0,25 %), lecture seule. */
    @GetMapping("/price-suggestion")
    public PriceSuggestionDto priceSuggestion(@RequestParam WebProviderCode provider, @RequestParam String instId,
                                              @RequestParam StepSide side, @RequestParam(required = false) java.math.BigDecimal tolerancePct) {
        return priceSuggestions.suggest(provider, instId, side, tolerancePct);
    }

    @PostMapping("/validate")
    public ValidationReportDto validate(@RequestBody OrderTestRequest request) {
        return orderTests.validate(request, authenticationFacade.getConnectedUser().getUsername());
    }

    /** Valide chaque jambe d'une route multi-jambes (aucun ordre créé). */
    @PostMapping("/validate-route")
    public RouteValidationDto validateRoute(@RequestBody RouteValidationRequest request) {
        return orderTests.validateRoute(request, authenticationFacade.getConnectedUser().getUsername());
    }

    @PostMapping("/real")
    public RealOrderResultDto realOrder(@RequestBody RealOrderRequest request) {
        return orderTests.sendReal(request, authenticationFacade.getConnectedUser().getUsername());
    }

    @PostMapping("/reconcile")
    public RealOrderResultDto reconcile(@RequestBody ReconcileRequest request) {
        return orderTests.reconcile(request, authenticationFacade.getConnectedUser().getUsername());
    }
}
