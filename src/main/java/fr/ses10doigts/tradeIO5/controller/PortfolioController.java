package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.controller.RainbowLiveControllerAdvice.ErrorResponse;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.AssetPerformanceDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.BindingTradesDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.HoldingsDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioHistoryDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.TradeLineDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioSummaryDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException;
import fr.ses10doigts.tradeIO5.service.portfolio.HomeOverviewService;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioPeriod;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Patrimoine et performance de l'utilisateur connecté (instantanés quotidiens et transactions en base, aucun appel exchange).
 * Période invalide ⇒ 400 ; lien d'un autre utilisateur ou inconnu ⇒ 404.
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioQueryService queryService;
    private final HomeOverviewService homeService;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping("/portfolio/summary")
    public PortfolioSummaryDto summary(@RequestParam(defaultValue = "30d") String period) {
        return queryService.summary(authenticationFacade.getConnectedUser(), PortfolioPeriod.of(period));
    }

    @GetMapping("/portfolio/assets")
    public List<AssetPerformanceDto> assets() {
        return queryService.assets(authenticationFacade.getConnectedUser());
    }

    /** Soldes live valorisés, un wallet ({@code walletId}) ou tous ; avec ou sans passe. */
    @GetMapping("/portfolio/holdings")
    public HoldingsDto holdings(@RequestParam(required = false) Long walletId) {
        return homeService.holdings(authenticationFacade.getConnectedUser(), walletId);
    }

    /** Historique reconstruit à rebours depuis les trades (estimation, hors dépôts/retraits). */
    @GetMapping("/portfolio/history")
    public PortfolioHistoryDto history(@RequestParam(required = false) Long walletId, @RequestParam(defaultValue = "30d") String period) {
        return homeService.history(authenticationFacade.getConnectedUser(), walletId, PortfolioPeriod.of(period));
    }

    /** Achats/ventes réels (300 derniers), avec exchange et wallet. */
    @GetMapping("/portfolio/transactions")
    public List<TradeLineDto> transactions(@RequestParam(required = false) Long walletId) {
        return homeService.transactions(authenticationFacade.getConnectedUser(), walletId);
    }

    @GetMapping("/rainbow-live/bindings/{id}/transactions")
    public BindingTradesDto trades(@PathVariable Long id) {
        return queryService.trades(authenticationFacade.getConnectedUser(), id);
    }

    @ExceptionHandler(RainbowLiveBindingNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RainbowLiveBindingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }
}
