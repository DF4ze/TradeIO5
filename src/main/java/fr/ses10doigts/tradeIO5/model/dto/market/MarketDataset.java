package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

// @EqualsAndHashCode structurel, en excluant "request" : request.endTime() est recalculé
// à chaque appel (Instant.now() côté appelant), donc toujours différent d'un appel à
// l'autre même quand le contenu réel du dataset n'a pas changé. L'inclure casserait
// l'équalité (et donc IndicatorCache) pour toute donnée par ailleurs identique.
@Getter
@Builder
@AllArgsConstructor
@EqualsAndHashCode(exclude = "request")
public class MarketDataset {

    private final String pair;
    private final TimeFrame timeFrame;
    private final List<MarketData> marketDatas;
    private final int size;
    private final MarketDatasetRequest request;
    private final Instant lastUpdate;
    private final boolean isComplete;


}
