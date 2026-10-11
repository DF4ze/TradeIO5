package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminOrderTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Testeur d'ordre : validation seule (aucun ordre créé, aucun endpoint d'envoi). Réservé ADMIN. */
@RestController
@RequestMapping("/api/admin/execution/order-test")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminOrderTestController {

    private final AdminOrderTestService orderTests;
    private final IAuthenticationFacade authenticationFacade;

    @PostMapping("/validate")
    public ValidationReportDto validate(@RequestBody OrderTestRequest request) {
        return orderTests.validate(request, authenticationFacade.getConnectedUser().getUsername());
    }
}
