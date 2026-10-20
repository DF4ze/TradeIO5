package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.portfolio.NextPassService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prochain déclenchement de la passe quotidienne (UTC), pour l'affichage. Lecture seule. */
@RestController
@RequestMapping("/api/rainbow-live/next-pass")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveNextPassController {

    private final NextPassService service;

    @GetMapping
    public NextPassService.NextPass next() {
        return service.next();
    }
}
