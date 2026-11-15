package fr.ses10doigts.tradeIO5.service.expert.simulation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Séries par actif de {@link EpisodeTimeline} construites depuis un résultat de {@link MultiLinkReplayEngine} (parité et rejeu libre). */
final class EpisodeSeries {

    private EpisodeSeries() {
    }

    /** Une série par lien ayant au moins un jour ; exposition (% du wallet) par jour et par actif. */
    static List<EpisodeTimeline.AssetSeries> build(MultiLinkReplayEngine.Result result, List<MultiLinkReplayEngine.Link> links) {
        Map<String, Map<LocalDate, Double>> expo = new HashMap<>();
        for (MultiLinkReplayEngine.WalletDay w : result.walletDays()) {
            w.value().forEach((asset, v) -> expo.computeIfAbsent(asset, k -> new HashMap<>())
                    .put(w.day(), w.totalValue() > 0 ? 100.0 * v / w.totalValue() : null));
        }
        Map<Long, List<MultiLinkReplayEngine.TradeDay>> byLink = new LinkedHashMap<>();
        result.trades().forEach(t -> byLink.computeIfAbsent(t.bindingId(), k -> new ArrayList<>()).add(t));
        List<EpisodeTimeline.AssetSeries> series = new ArrayList<>();
        for (MultiLinkReplayEngine.Link link : links) {
            List<MultiLinkReplayEngine.TradeDay> days = byLink.getOrDefault(link.slot().bindingId(), List.of());
            if (!days.isEmpty()) {
                series.add(new EpisodeTimeline.AssetSeries(link.slot().assetSymbol(), days, link.qty0(),
                        expo.getOrDefault(link.slot().assetSymbol(), Map.of())));
            }
        }
        return series;
    }
}
