package fr.ses10doigts.tradeIO5.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.EtfFlowBackfillService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Déclenchement manuel du backfill historique ETF_FLOW ({@link EtfFlowBackfillService}), même
 * patron que {@link MediaWatchAdminController} : pas de trigger automatique au démarrage (décision
 * Clem 2026-07-09, cf. javadoc {@link EtfFlowBackfillService}), un appel explicite le fait tourner
 * à la demande. Réservé ROLE_ADMIN — déclenche un appel réseau SoSoValue + jusqu'à 300 écritures en
 * base par asset, pas un simple GET de lecture.
 */
@RestController
@RequestMapping("/api/admin/etf-flow")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class EtfFlowAdminController {

    private static final Logger logger = LoggerFactory.getLogger(EtfFlowAdminController.class);

    private final EtfFlowBackfillService etfFlowBackfillService;

    /**
     * Synchrone : la requête HTTP répond une fois le backfill terminé pour BTC et ETH (jusqu'à
     * ~300 lignes chacun, quelques secondes). Détail par asset dans la réponse ET dans les logs.
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> triggerBackfill() {
        logger.info("EtfFlowAdminController: déclenchement manuel du backfill historique.");
        Map<EtfFlowAsset, Integer> upsertedByAsset = etfFlowBackfillService.backfillAll();

        Map<String, Integer> byAssetName = new LinkedHashMap<>();
        for (Map.Entry<EtfFlowAsset, Integer> entry : upsertedByAsset.entrySet()) {
            byAssetName.put(entry.getKey().name(), entry.getValue());
        }

        return ResponseEntity.ok(Map.of("status", "done", "upsertedByAsset", byAssetName));
    }
}
