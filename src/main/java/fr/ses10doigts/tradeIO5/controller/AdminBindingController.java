package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminBindingDto;
import fr.ses10doigts.tradeIO5.service.admin.AdminBindingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Supervision admin des bindings de tous les utilisateurs (lecture seule ; l'armement reste {@code /api/admin/execution/bindings/{id}/arm}). */
@RestController
@RequestMapping("/api/admin/bindings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBindingController {

    private final AdminBindingQueryService bindingQueryService;

    @GetMapping
    public List<AdminBindingDto> list() {
        return bindingQueryService.list();
    }
}
