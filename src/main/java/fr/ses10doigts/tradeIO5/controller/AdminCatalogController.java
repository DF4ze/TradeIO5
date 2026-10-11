package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminInstrumentDto;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.admin.AdminCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Paires du catalogue d'un exchange (liste du testeur d'ordre). Lecture seule, réservé ADMIN. */
@RestController
@RequestMapping("/api/admin/catalog")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCatalogController {

    private final AdminCatalogService catalogService;

    @GetMapping("/instruments")
    public List<AdminInstrumentDto> instruments(@RequestParam WebProviderCode provider, @RequestParam(required = false) String q) {
        return catalogService.instruments(provider, q);
    }
}
