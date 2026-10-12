package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;
import java.util.List;

/**
 * Une route candidate d'un devis (lecture seule). {@code routeKey} = nœuds du chemin (ex. {@code USDC>BTC>PAXG}) ;
 * {@code chosen = false} : route chiffrée et affichée mais jamais choisie automatiquement (ex. intermédiaire ETH).
 * {@code warnings} : avertissements propres à la route (intermédiaire exposé, liquidité croisée).
 */
public record RouteQuote(String routeKey, String source, List<PathLeg> legs, BigDecimal totalCostPct,
                         FeeTestLevel feeTestLevel, PathStatus status, PathEstimation estimation, boolean chosen,
                         List<String> warnings) {
}
