package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.calibration.ConsolidationZoneService;
import fr.ses10doigts.tradeIO5.service.calibration.dto.CalibrationZonesResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de diagnostic/visualisation pour {@code tools/calibration/zone_view_v2.html} :
 * recalcule les zones "consolidation" + régime ADX à la demande (bougies D1 fraîches + détection
 * live), au lieu du snapshot statique généré une fois par {@code export_zones_v2.py}.
 * <p>
 * Volontairement SANS {@code @PreAuthorize} : ne renvoie que des données de marché publiques (pas
 * de données utilisateur), et le filtre de sécurité global ({@code WebSecurityConfig}) laisse de
 * toute façon passer {@code /**} sans authentification — seule l'annotation méthode/classe protège
 * réellement (cf. {@code AssetOverviewController}). Choix par défaut pour un outil de diagnostic
 * local ; à revoir si l'app est un jour exposée publiquement sur internet.
 * <p>
 * {@code @CrossOrigin(origins = "*")} : {@code zone_view_v2.html} est ouverte directement en
 * {@code file://} (origin {@code null}) ou servie depuis un port différent de l'app — sans CORS
 * explicite ici, le navigateur bloque la lecture de la réponse malgré l'absence de
 * {@code @PreAuthorize} (CORS et authentification sont deux couches distinctes). Pas de
 * `WebSecurityConfig`/bean global touché : la permissivité reste scopée à ce seul controller.
 */
@RestController
@RequestMapping("/api/calibration")
@CrossOrigin(origins = "*")
public class CalibrationController {

    private final ConsolidationZoneService consolidationZoneService;

    public CalibrationController(ConsolidationZoneService consolidationZoneService) {
        this.consolidationZoneService = consolidationZoneService;
    }

    @GetMapping("/zones")
    public CalibrationZonesResponse getConsolidationZones(
            @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        return consolidationZoneService.getZones(symbol);
    }
}
