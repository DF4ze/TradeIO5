package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pagination vers l'avant (par {@code startTime}) d'un endpoint de bougies plafonné par appel.
 * Pure (aucun réseau : la page est fournie par un {@link PageFetcher}), donc testable en unitaire.
 * <p>
 * Le résultat est toujours un préfixe contigu à partir de {@code since}, croissant et sans doublon :
 * si la pagination s'arrête avant {@code until} (garde-fou {@code maxPages}, ou erreur après au moins
 * une page), le reste du trou sera détecté et refetché par le cache DB au prochain appel.
 */
final class CandlePaginator {

    private static final Logger log = LoggerFactory.getLogger(CandlePaginator.class);

    @FunctionalInterface
    interface PageFetcher {
        /** Au plus {@code pageLimit} bougies dont l'ouverture est {@code >= start} ({@code null} = sans borne basse). */
        List<MarketData> fetch(Instant start, int pageLimit);
    }

    private CandlePaginator() {
    }

    static List<MarketData> paginate(Instant since, Instant until, int limit, int pageSize, TimeFrame timeFrame,
                                     int maxPages, long pauseMillis, PageFetcher fetcher) {
        Instant start = since;
        if (start == null && until != null && limit > pageSize) {
            // Pas de since mais plus qu'une page : on remonte jusqu'à limit bougies avant until.
            start = timeFrame.removeTo(CachingMarketDataApiClient.floorToGrid(until, timeFrame), limit - 1L);
        }
        if (start == null) {
            return fetcher.fetch(null, limit > 0 ? Math.min(limit, pageSize) : pageSize);
        }

        List<MarketData> result = new ArrayList<>();
        Instant cursor = start;
        Instant lastTimestamp = null;
        int pages = 0;

        while (pages < maxPages) {
            int remaining = limit > 0 ? limit - result.size() : pageSize;
            int pageLimit = Math.min(pageSize, remaining);

            List<MarketData> page;
            try {
                page = fetcher.fetch(cursor, pageLimit);
            } catch (RuntimeException e) {
                if (result.isEmpty()) {
                    throw e;
                }
                log.warn("Pagination interrompue après {} page(s), {} bougie(s) conservée(s) : {}",
                        pages, result.size(), e.getMessage());
                return result;
            }
            pages++;

            int before = result.size();
            for (MarketData candle : page) {
                if (lastTimestamp == null || candle.getTimestamp().isAfter(lastTimestamp)) {
                    result.add(candle);
                    lastTimestamp = candle.getTimestamp();
                }
            }
            log.debug("Pagination page {} : {} reçue(s), {} nouvelle(s), total {}", pages, page.size(),
                    result.size() - before, result.size());

            boolean noProgress = result.size() == before;
            boolean shortPage = page.size() < pageLimit;
            boolean limitReached = limit > 0 && result.size() >= limit;
            if (noProgress || shortPage || limitReached) {
                return result;
            }

            cursor = timeFrame.addTo(lastTimestamp);
            if (until != null && cursor.isAfter(until)) {
                return result;
            }
            sleep(pauseMillis);
        }

        log.warn("Pagination arrêtée au garde-fou de {} page(s) : {} bougie(s) récupérée(s), dernière={} (until={}). " +
                "Le reste sera repris au prochain appel.", maxPages, result.size(), lastTimestamp, until);
        return result;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
