package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exécution des passes quotidiennes du bench grandeur nature (23:55 / 00:05 UTC) : chargement D1, rejeu du
 * {@link RainbowAtrEngine} (inchangé, parité pine), extraction de l'action du jour, dimensionnement sur le wallet
 * mock puis persistance via {@link RainbowLiveRunService#upsertPass}. <b>Aucun ordre, aucun accès exchange</b>
 * (seule la lecture de marché passe par {@link MarketDatasetEngine}).
 * <p>
 * Dataset D1 chargé UNE fois par (actif, passe) et partagé entre tous les presets/users. Une erreur sur un
 * (actif, preset, user) n'empêche jamais les autres. Pas de rattrapage des jours manqués.
 * <p>
 * Limite connue assumée : le rejeu (fenêtre du preset) repart d'une position moteur nulle ; le wallet mock est la
 * vérité cumulée. Le moteur peut donc vouloir vendre plus que le wallet ne détient (vente plafonnée à la position)
 * ou acheter sans cash (achat plafonné au cash, {@code NONE} si cash nul).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveExecutionService {

    /** Tolérance flottante sur cash/position. */
    private static final double EPSILON = 1e-9;
    private static final String EVENT_BUY_ZONE = "BUY_ZONE";
    private static final String EVENT_BUY_TRIGGERED = "BUY_TRIGGERED";
    private static final String EVENT_SELL = "SELL";
    private static final String EVENT_MOON_STOP = "MOON_STOP";

    private final MarketDatasetEngine datasetEngine;
    private final UserRepository userRepository;
    private final RainbowLivePresetService presetService;
    private final RainbowLiveMockWalletRepository walletRepository;
    private final RainbowLiveRunService runService;
    private final RainbowTrendLiveService trendService;

    /** Récapitulatif d'une passe (log + réponse de l'endpoint admin). */
    public record PassSummary(RainbowLivePass pass, LocalDate day, int processed, int skipped, int errors) {
    }

    /** Série D1 d'un actif tronquée au jour traité (la bougie du jour suivant, éventuelle, est ignorée). */
    private record AssetSeries(RainbowAtrDataset dataset, int dayIdx, List<MarketData> candles) {
    }

    private static final class Counters {
        int processed;
        int skipped;
        int errors;
    }

    /** Jour UTC traité : 23:55 ⇒ jour de {@code asOf} ; 00:05 ⇒ la veille (la clôture qui vient de se terminer). */
    public static LocalDate dayFor(RainbowLivePass pass, Instant asOf) {
        LocalDate utcDay = asOf.atOffset(ZoneOffset.UTC).toLocalDate();
        return pass == RainbowLivePass.T0005 ? utcDay.minusDays(1) : utcDay;
    }

    public PassSummary runPass(RainbowLivePass pass, Instant asOf) {
        return runPass(pass, asOf, null);
    }

    /**
     * @param dayOverride jour forcé (déclenchement manuel) ; {@code null} ⇒ {@link #dayFor}.
     */
    public synchronized PassSummary runPass(RainbowLivePass pass, Instant asOf, LocalDate dayOverride) {
        LocalDate day = dayOverride != null ? dayOverride : dayFor(pass, asOf);
        log.info("Bench Rainbow : début passe {} jour={} (asOf={})", pass, day, asOf);
        Counters counters = new Counters();

        Map<String, List<RainbowLivePreset>> presetsByAsset = collectPresets(counters);
        for (Map.Entry<String, List<RainbowLivePreset>> entry : presetsByAsset.entrySet()) {
            runAsset(pass, asOf, day, entry.getKey(), entry.getValue(), counters);
        }

        PassSummary summary = new PassSummary(pass, day, counters.processed, counters.skipped, counters.errors);
        log.info("Bench Rainbow : fin passe {} jour={} traités={} sautés={} erreurs={}",
                pass, day, summary.processed(), summary.skipped(), summary.errors());
        return summary;
    }

    /** Copie des templates système manquants puis presets {@code enabled} des utilisateurs actifs, groupés par actif. */
    private Map<String, List<RainbowLivePreset>> collectPresets(Counters counters) {
        Map<String, List<RainbowLivePreset>> byAsset = new LinkedHashMap<>();
        for (User user : userRepository.findByEnabledTrueAndArchivedAtIsNull()) {
            try {
                presetService.ensureSystemPresets(user);
                for (RainbowLivePreset preset : presetService.list(user)) {
                    if (preset.isEnabled()) {
                        byAsset.computeIfAbsent(preset.getAssetSymbol(), k -> new ArrayList<>()).add(preset);
                    }
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec de préparation des presets pour user={}", user.getId(), e);
            }
        }
        return byAsset;
    }

    private void runAsset(RainbowLivePass pass, Instant asOf, LocalDate day, String asset,
                          List<RainbowLivePreset> presets, Counters counters) {
        AssetSeries series;
        try {
            series = loadSeries(asset, day, asOf);
        } catch (Exception e) {
            counters.errors += presets.size();
            log.error("Bench Rainbow : chargement D1 impossible pour {} (passe {}, jour {}), actif sauté", asset, pass, day, e);
            return;
        }
        if (series == null) {
            counters.skipped += presets.size();
            return;
        }
        for (RainbowLivePreset preset : presets) {
            try {
                if (runPreset(pass, day, preset, series)) {
                    counters.processed++;
                } else {
                    counters.skipped++;
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec preset={} actif={} user={} passe {} jour {}",
                        preset.getId(), asset, preset.getUser().getId(), pass, day, e);
            }
        }
    }

    /**
     * Charge le dataset D1 de l'actif (une fois par passe) et repère la bougie du jour. {@code null} ⇒ bougie du
     * jour absente (WARN, passe sautée pour cet actif).
     */
    private AssetSeries loadSeries(String asset, LocalDate day, Instant asOf) {
        long start = System.nanoTime();
        MarketDataset dataset = datasetEngine.getDatasetForAsset(asset, TimeFrame.D1,
                RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES, asOf);
        List<MarketData> candles = dataset == null || dataset.getMarketDatas() == null ? List.of() : dataset.getMarketDatas();
        log.info("Bench Rainbow : D1 {} chargé ({} bougies) en {} ms", asset, candles.size(), (System.nanoTime() - start) / 1_000_000);

        int dayIdx = -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candleDay(candles.get(i)).equals(day)) {
                dayIdx = i;
                break;
            }
        }
        if (dayIdx < 0) {
            log.warn("Bench Rainbow : bougie D1 du {} absente pour {} (dernière : {}), passe sautée pour cet actif",
                    day, asset, candles.isEmpty() ? "aucune" : candles.getLast().getTimestamp());
            return null;
        }
        // bougie du jour suivant (00:05) ignorée : la vraie clôture est la bougie du jour
        List<MarketData> upToDay = candles.subList(0, dayIdx + 1);
        return new AssetSeries(RainbowAtrDataset.fromMarketData(upToDay), dayIdx, upToDay);
    }

    /** @return {@code true} si la passe a été enregistrée, {@code false} si sautée (données insuffisantes). */
    private boolean runPreset(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset, AssetSeries series) {
        if (preset.isTrendMix()) {
            return runTrendPreset(pass, day, preset, series);
        }
        RainbowAtrConfig config = preset.getConfig();
        RainbowAtrTuning tuning = config.toTuning();
        RainbowAtrGlobals globals = config.toGlobals();
        RainbowAtrDataset ds = series.dataset();
        int endIdx = series.dayIdx();

        int warmup = RainbowAtrDataset.warmup(tuning.smaPeriod(), tuning.atrPeriod());
        if (endIdx < warmup + 1) {
            log.warn("Bench Rainbow : dataset trop court pour preset={} actif={} ({} bougies, warmup {}), sauté",
                    preset.getId(), preset.getAssetSymbol(), endIdx + 1, warmup);
            return false;
        }
        int startIdx = Math.max(warmup, firstIndexOnOrAfter(ds, day.minusMonths(preset.getAnalysisWindowMonths())));
        startIdx = Math.min(startIdx, endIdx);

        RainbowAtrResult result = RainbowAtrEngine.simulate(ds, globals, new RainbowAtrTuning[]{tuning}, null,
                startIdx, endIdx, true);

        RainbowLiveMockWallet wallet = walletRepository.findByPreset(preset)
                .orElseThrow(() -> new IllegalStateException("Wallet mock introuvable pour le preset " + preset.getId()));
        RainbowLivePassBlock block = buildBlock(ds, endIdx, tuning, result);
        sizeAction(block, result, endIdx, ds.close(endIdx), wallet);

        runService.upsertPass(preset, day, pass, block);
        log.info("Bench Rainbow : preset={} actif={} jour={} passe {} action fictive={} montant={} qté={}",
                preset.getId(), preset.getAssetSymbol(), day, pass, block.getActionType(),
                block.getActionAmountUsdc(), block.getActionQuantity());
        return true;
    }

    /** Preset {@code TREND_MIX} : un pas de la machine à partir de l'état persisté (cf. {@link RainbowTrendLiveService}). */
    private boolean runTrendPreset(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset, AssetSeries series) {
        RainbowAtrDataset ds = series.dataset();
        int endIdx = series.dayIdx();
        int warmup = TrendMixCalculator.Params.defaults().warmup();
        if (endIdx < warmup) {
            log.warn("Bench Rainbow : dataset trop court pour le Trend Mix preset={} actif={} ({} bougies, warmup {}), sauté",
                    preset.getId(), preset.getAssetSymbol(), endIdx + 1, warmup);
            return false;
        }
        RainbowLiveMockWallet wallet = walletRepository.findByPreset(preset)
                .orElseThrow(() -> new IllegalStateException("Wallet mock introuvable pour le preset " + preset.getId()));
        RainbowTrendLiveService.Outcome outcome = trendService.run(pass, day, preset, series.candles(), ds, endIdx, wallet);
        runService.upsertPass(preset, day, pass, outcome.block());
        outcome.commit();
        return true;
    }

    private static RainbowLivePassBlock buildBlock(RainbowAtrDataset ds, int endIdx, RainbowAtrTuning t, RainbowAtrResult r) {
        double sma = r.lastSma();
        double atr = r.lastAtr();
        return RainbowLivePassBlock.builder()
                .close(ds.close(endIdx))
                .sma(sma)
                .atr(atr)
                .boundDown2(sma - atr * t.atrMultDown2())
                .boundDown1(sma - atr * t.atrMultDown1())
                .boundUp1(sma + atr * t.atrMultUp1())
                .boundUp2(sma + atr * t.atrMultUp2())
                .boundUp3(sma + atr * t.atrMultUp3())
                .zone(r.lastZone())
                .athDistance(r.lastAthDistance())
                .buyFactor(r.lastBuyFactor())
                .sellFactor(r.lastSellFactor())
                .moonMode(r.moonModeLast())
                .buyArmed(r.buyArmed())
                .sellArmed(r.sellArmed())
                .buyLocked(r.buyLocked())
                .cooldownRemaining(r.cooldownRemaining())
                .moonReserveQty(r.moonReserveQty())
                .actionPrice(ds.close(endIdx))
                .build();
    }

    /**
     * Action du jour = events du dernier index du rejeu. Plafonds portés ICI (moteur inchangé) : achat ≤ cash du
     * wallet mock, vente ≤ position. Achat et vente sur la même bougie (ex. MOON_STOP sans cooldown) : la VENTE
     * l'emporte (WARN).
     */
    private void sizeAction(RainbowLivePassBlock block, RainbowAtrResult result, int endIdx, double close,
                            RainbowLiveMockWallet wallet) {
        double buyAmount = 0;
        double sellQty = 0;
        for (RainbowAtrResult.Event e : result.events()) {
            if (e.index() != endIdx) {
                continue;
            }
            switch (e.type()) {
                case EVENT_BUY_ZONE, EVENT_BUY_TRIGGERED -> buyAmount += e.amount();
                case EVENT_SELL, EVENT_MOON_STOP -> sellQty += e.quantity();
                default -> { }
            }
        }
        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow : achat ET vente sur la même bougie (achat {} USDC, vente {}) : la vente l'emporte",
                    buyAmount, sellQty);
        }

        block.setActionType(RainbowLiveAction.NONE);
        if (sellQty > 0) {
            double qty = Math.min(sellQty, wallet.getPositionQuantity());
            if (qty > EPSILON) {
                block.setActionType(RainbowLiveAction.SELL);
                block.setActionQuantity(qty);
                block.setActionAmountUsdc(qty * close);
            }
        } else if (buyAmount > 0) {
            double amount = Math.min(buyAmount, wallet.getCashUsdc());
            if (amount > EPSILON) {
                block.setActionType(RainbowLiveAction.BUY);
                block.setActionAmountUsdc(amount);
                block.setActionQuantity(amount / close);
            }
        }
    }

    private static int firstIndexOnOrAfter(RainbowAtrDataset ds, LocalDate from) {
        long fromMillis = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int i = 0; i < ds.size(); i++) {
            if (ds.time(i) >= fromMillis) {
                return i;
            }
        }
        return ds.size();
    }

    private static LocalDate candleDay(MarketData candle) {
        return candle.getTimestamp().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
