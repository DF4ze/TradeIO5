package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminActionDto;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Journal des actions admin : lecture seule, paginé (taille bornée), plus récent d'abord. */
@RestController
@RequestMapping("/api/admin/actions")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminActionController {

    private final AdminActionLogService journal;

    @GetMapping
    public AdminActionDto.Page actions(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "" + ExecutionDefaults.DEFAULT_PAGE_SIZE) int size) {
        return journal.page(page, size);
    }
}
