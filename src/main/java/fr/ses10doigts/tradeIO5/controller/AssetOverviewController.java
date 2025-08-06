package fr.ses10doigts.tradeIO5.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.ses10doigts.tradeIO5.model.dto.AssetOverview;
import fr.ses10doigts.tradeIO5.service.agregation.AssetOverviewService;

@RestController
@RequestMapping("/api/overview")
@PreAuthorize("isAuthenticated()")
public class AssetOverviewController {

    private final AssetOverviewService assetOverviewService;

    public AssetOverviewController(AssetOverviewService assetOverviewService) {
        this.assetOverviewService = assetOverviewService;
    }

    @GetMapping
    public List<AssetOverview> getUserHoldings(
        @RequestParam(defaultValue = "USDC") String quoteCurrency,
        @RequestParam(required = false) String exchangeCode
    ) {
        return assetOverviewService.getUserHoldings(quoteCurrency, Optional.ofNullable(exchangeCode));
    }
}
