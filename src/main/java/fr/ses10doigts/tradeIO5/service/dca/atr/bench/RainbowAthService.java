package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAthReference;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowAthReferenceRepository;
import fr.ses10doigts.tradeIO5.service.calibration.BinanceDailyCandleFetcher;
import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * ATH de référence par ACTIF (L3 du contrat Rainbow) : stocké en base, injecté dans la machine d'états, jamais
 * recalculé sur une fenêtre courte (une fenêtre trop courte donnerait un ATH trompeur).
 * <p>
 * Amorçage : à la 1re demande pour un actif sans aucune ligne, import ONE-SHOT de l'historique D1 complet
 * Binance ({@code <ACTIF>USDT}, via {@link BinanceDailyCandleFetcher}), ATH conservé jusqu'à la veille du jour
 * demandé. Ensuite la passe 23:55 ajoute une ligne par jour ({@link #record}) ; aucun nouvel appel exchange.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowAthService {

    private final RainbowAthReferenceRepository repository;
    private final BinanceDailyCandleFetcher fetcher;

    /** ATH valable à la fin de la veille de {@code day} ({@link AthReference#NONE} si l'historique est introuvable). */
    @Transactional
    public AthReference athBefore(String asset, LocalDate day) {
        return repository.findFirstByAssetSymbolAndDayBeforeOrderByDayDesc(asset, day)
                .map(RainbowAthService::toRef)
                .orElseGet(() -> seed(asset, day));
    }

    /** Enregistre l'ATH valable à la fin de {@code day} (idempotent : la ligne du jour est remplacée). */
    @Transactional
    public void record(String asset, LocalDate day, double high, long candleTimeMillis) {
        AthReference updated = athBefore(asset, day).observe(high, candleTimeMillis);
        RainbowAthReference row = repository.findByAssetSymbolAndDay(asset, day)
                .orElseGet(() -> RainbowAthReference.builder().assetSymbol(asset).day(day).build());
        row.setAthValue(updated.value());
        row.setAthTimeMillis(updated.timeMillis());
        repository.save(row);
    }

    private AthReference seed(String asset, LocalDate day) {
        if (repository.existsByAssetSymbol(asset)) {
            // lignes existantes mais toutes postérieures à `day` (rejeu d'un jour ancien) : pas d'ATH connu
            return AthReference.NONE;
        }
        List<DailyCandle> history = fetcher.fetchFullHistory(asset + "USDT");
        DailyCandle top = null;
        for (DailyCandle c : history) {
            if (c.date().isBefore(day) && (top == null || c.high() > top.high())) {
                top = c;
            }
        }
        if (top == null) {
            log.warn("ATH {} : historique Binance vide avant {}, ATH inconnu", asset, day);
            return AthReference.NONE;
        }
        long time = top.date().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        repository.save(RainbowAthReference.builder().assetSymbol(asset).day(day.minusDays(1))
                .athValue(top.high()).athTimeMillis(time).build());
        log.info("ATH {} amorcé depuis Binance ({} bougies D1) : {} le {}", asset, history.size(), top.high(), top.date());
        return new AthReference(top.high(), time);
    }

    private static AthReference toRef(RainbowAthReference r) {
        return new AthReference(r.getAthValue(), r.getAthTimeMillis());
    }
}
