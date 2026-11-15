package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.EpisodeReportDto;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.simulation.FullReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Timeline des épisodes et répartition du gain du rejeu complet (simulateur complet, lot 4, sans UI) : mode EXPERT requis, lecture
 * seule, rien de persisté. {@code GET /episodes} rejoue (mêmes paramètres facultatifs que la parité) et renvoie un {@code resultId}
 * gardé en mémoire (TTL des simulations) ; {@code GET /episodes/{resultId}} recalcule sur la sous-plage {@code from}/{@code to}
 * sans rejouer.
 */
@RestController
@RequestMapping("/api/expert/simulations/episodes")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertSimulationEpisodesController {

    private final ExpertAccessGuard guard;
    private final FullReplayService service;

    @GetMapping
    public EpisodeReportDto run(@RequestParam(required = false) Long bindingId,
                                @RequestParam(required = false) String asset,
                                @RequestParam(required = false) Long walletId,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) Double cash0,
                                @RequestParam(required = false) Double qty0,
                                @RequestParam(required = false) Double baseAmount,
                                @RequestParam(required = false) Double bagPercent,
                                @RequestParam(required = false) Double exposureMin,
                                @RequestParam(required = false) Double exposureMax,
                                @RequestParam(required = false) Double power,
                                @RequestParam(required = false) Double bias,
                                @RequestParam(required = false) Double regulationMarginPct,
                                @RequestParam(required = false, defaultValue = "false") boolean withCosts) {
        return service.episodes(guard.require().user(), new FullReplayService.Request(bindingId, asset, walletId, from, to, cash0,
                qty0, baseAmount, bagPercent, exposureMin, exposureMax, power, bias, regulationMarginPct, withCosts));
    }

    @GetMapping("/{resultId}")
    public EpisodeReportDto range(@PathVariable String resultId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.episodesOf(guard.require().user(), resultId, from, to);
    }
}
