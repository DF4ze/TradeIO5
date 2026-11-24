package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.expert.simulation.DailyDataLoader;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Historique D1 d'un nouvel actif (D1b) : celui du provider du lien s'il suffit ({@link NewAssetConfig#providerSufficientMonths()}), sinon
 * complété par Binance en USDT et stocké dans le stockage bench ({@link BenchCandleStore}, étiquette {@code BINANCE/USDT}, jamais lu par le live).
 * Le complément n'est retenu que s'il est plus long que celui du provider. Source injoignable : on garde ce qu'on a (éventuellement rien) ; jamais
 * d'exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewAssetHistoryResolver {

    private final DailyDataLoader dailyLoader;
    private final BenchHistoryFetcher fetcher;
    private final DomainClock clock;

    /** Bougies retenues (closes, D1) et leur étiquette source ({@code candles} vide ⇒ historique insuffisant). */
    public record History(List<MarketData> candles, String sourceLabel, String note) {
    }

    public History resolve(String asset, MarketDataSource preferred, NewAssetConfig cfg, Path benchDir) {
        List<MarketData> provider = List.of();
        String providerLabel = null;
        String note = null;
        try {
            DailyDataset daily = dailyLoader.load(asset, preferred);
            LocalDate today = clock.now().atZone(ZoneOffset.UTC).toLocalDate();
            provider = daily.dataset().getMarketDatas().stream().filter(c -> c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)).toList();
            providerLabel = daily.source() + "/" + daily.providerSymbol();
        } catch (RuntimeException e) {
            note = "historique du provider du lien indisponible : " + e.getMessage();
            log.warn("Nouvel actif {} : {}", asset, note);
        }
        if (months(provider) >= cfg.providerSufficientMonths()) {
            return new History(provider, providerLabel, null);
        }
        Optional<BenchHistoryFetcher.Fetched> bench = fetcher.fetch(asset);
        if (bench.isPresent() && bench.get().candles().size() > provider.size()) {
            BenchHistoryFetcher.Fetched f = bench.get();
            try {
                BenchCandleStore.save(benchDir, asset, f.symbol(), f.quote(), f.candles());
            } catch (java.io.IOException | RuntimeException e) {
                log.warn("Nouvel actif {} : stockage bench impossible ({}), historique utilisé sans être stocké", asset, e.toString());
            }
            return new History(f.candles(), BenchCandleStore.SOURCE_LABEL, "historique complété depuis Binance (USDT), bench uniquement");
        }
        if (bench.isEmpty()) {
            note = (note == null ? "" : note + " ; ") + "complément Binance injoignable ou actif absent";
        }
        return new History(provider, providerLabel, note);
    }

    static long months(List<MarketData> candles) {
        return candles.isEmpty() ? 0 : ChronoUnit.MONTHS.between(candles.getFirst().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                candles.getLast().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
    }
}
