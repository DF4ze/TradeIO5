package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisFacade;
import fr.ses10doigts.tradeIO5.service.tree.decision.DefaultLocalOpinionParamsProvider;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionOrchestrator;
import fr.ses10doigts.tradeIO5.service.tree.decision.OpinionSignalResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Déclenchement manuel d'une Opinion (plan de test manuel Palier 3 — Clem, 2026-08-17 : "tout vient
 * de là à la base"). Toute la chaîne Scenario/Decision part d'un {@code OpinionSignal} calculé par
 * {@link TreeAnalysisFacade#getOpinion}. Avant ce lot, seul le tool MCP {@code get_opinion} pouvait le
 * déclencher — aucun équivalent REST, donc pas testable via un simple curl/Postman sans client MCP.
 * <p>
 * Pour le scope {@code LOCAL}, réutilise {@link DefaultLocalOpinionParamsProvider} — exactement les
 * mêmes paramètres par défaut que {@link DecisionOrchestrator}, pour que ce déclenchement manuel
 * reflète fidèlement ce que le cycle automatique calcule (pas une implémentation parallèle qui
 * pourrait diverger silencieusement). Pour {@code GLOBAL}/{@code MACRO} (strategies ignorées par
 * construction) et {@code EXTERNAL} (pas de Strategy du tout, cf. {@code ExternalMarketOpinion}),
 * des paramètres vides suffisent.
 * <p>
 * Même préfixe {@code /api/admin/decision} que les autres endpoints admin du palier, réservé
 * {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/decision")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class OpinionAdminController {

    private static final Logger logger = LoggerFactory.getLogger(OpinionAdminController.class);

    private final TreeAnalysisFacade treeAnalysisFacade;
    private final DefaultLocalOpinionParamsProvider localOpinionParamsProvider;

    @GetMapping("/opinion")
    public ResponseEntity<OpinionSignalResponse> getOpinion(
            @RequestParam String symbol,
            @RequestParam OpinionScope scope
    ) {
        logger.info("OpinionAdminController: déclenchement manuel d'une Opinion (symbol={}, scope={}).", symbol, scope);
        MarketOpinionParameters params = scope == OpinionScope.LOCAL
                ? localOpinionParamsProvider.build()
                : MarketOpinionParameters.builder().build();
        return ResponseEntity.ok(OpinionSignalResponse.from(treeAnalysisFacade.getOpinion(symbol, scope, params)));
    }
}
