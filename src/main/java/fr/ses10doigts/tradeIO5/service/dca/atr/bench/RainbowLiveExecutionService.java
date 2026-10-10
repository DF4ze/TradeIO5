package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exécution des passes quotidiennes du bench grandeur nature (23:55 / 00:05 UTC) : chargement D1, rejeu du
 * {@link RainbowAtrEngine} (inchangé, parité pine), extraction de l'action du jour, dimensionnement sur le wallet
 * mock puis persistance via {@link RainbowLiveRunService#upsertPass}. <b>Aucun ordre</b> ; seul accès à un portefeuille :
 * le port {@link RainbowPortfolioSource} (mock, ou réel en lecture seule pour le preset lié par un binding), plus la lecture
 * de marché ({@link MarketDatasetEngine}).
 * <p>
 * Preset live : en plus de la chaîne mock (inchangée), le moteur dimensionne une action recommandée sur le portefeuille
 * réel ({@link LiveSizing}, snapshot {@code live*} du bloc). Par utilisateur, les presets live sont servis par
 * {@code priority} croissante et se partagent un {@link CashLedger} par wallet. Passe 00:05 : le snapshot de la passe 23:55
 * du jour est relu s'il est encore frais (pas de nouvel appel exchange incohérent). Rejeu 23:55 : action live d'origine
 * conservée.
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
    private final RainbowLivePresetConfigResolver configResolver;
    private final RainbowLiveMockWalletRepository walletRepository;
    private final RainbowLiveRunRepository runRepository;
    private final RainbowLiveRunService runService;
    private final RainbowTrendLiveService trendService;
    private final MockPortfolioSource mockPortfolioSource;
    private final RealPortfolioSource realPortfolioSource;

    /** Récapitulatif d'une passe (log + réponse de l'endpoint admin). */
    public record PassSummary(RainbowLivePass pass, LocalDate day, int processed, int skipped, int errors) {
    }

    /** Série D1 d'un actif tronquée au jour traité (la bougie du jour suivant, éventuelle, est ignorée). */
    private record AssetSeries(RainbowAtrDataset dataset, int dayIdx, List<MarketData> candles) {
    }

    /** Ce que veut le moteur ce jour-là, avant tout plafond : achat en USDC, vente en quantité. */
    private record WantedAction(double buyAmount, double sellQty) {
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

        Map<Long, List<RainbowLivePreset>> presetsByUser = collectPresets(counters);
        Map<String, Integer> presetCountByAsset = new HashMap<>();
        presetsByUser.values().forEach(list -> list.forEach(p -> presetCountByAsset.merge(p.getAssetSymbol(), 1, Integer::sum)));

        Map<String, AssetSeries> seriesByAsset = new HashMap<>();
        Set<String> failedAssets = new HashSet<>();
        for (Map.Entry<String, Integer> entry : presetCountByAsset.entrySet()) {
            loadAsset(pass, asOf, day, entry.getKey(), entry.getValue(), seriesByAsset, failedAssets, counters);
        }
        for (List<RainbowLivePreset> presets : presetsByUser.values()) {
            runUser(pass, asOf, day, presets, seriesByAsset, failedAssets, counters);
        }

        PassSummary summary = new PassSummary(pass, day, counters.processed, counters.skipped, counters.errors);
        log.info("Bench Rainbow : fin passe {} jour={} traités={} sautés={} erreurs={}",
                pass, day, summary.processed(), summary.skipped(), summary.errors());
        return summary;
    }

    /** Création des presets de stratégie manquants puis presets {@code enabled} des utilisateurs actifs, groupés par utilisateur. */
    private Map<Long, List<RainbowLivePreset>> collectPresets(Counters counters) {
        Map<Long, List<RainbowLivePreset>> byUser = new LinkedHashMap<>();
        for (User user : userRepository.findByEnabledTrueAndArchivedAtIsNull()) {
            try {
                presetService.ensureStrategyPresets(user);
                List<RainbowLivePreset> enabled = presetService.list(user).stream().filter(RainbowLivePreset::isEnabled).toList();
                if (!enabled.isEmpty()) {
                    byUser.put(user.getId(), enabled);
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec de préparation des presets pour user={}", user.getId(), e);
            }
        }
        return byUser;
    }

    /** Charge la série D1 de l'actif UNE fois par passe (partagée entre users) ; absente/en échec ⇒ comptée sur ses presets. */
    private void loadAsset(RainbowLivePass pass, Instant asOf, LocalDate day, String asset, int presetCount,
                           Map<String, AssetSeries> seriesByAsset, Set<String> failedAssets, Counters counters) {
        try {
            AssetSeries series = loadSeries(asset, day, asOf);
            if (series == null) {
                counters.skipped += presetCount;
            } else {
                seriesByAsset.put(asset, series);
            }
        } catch (Exception e) {
            failedAssets.add(asset);
            counters.errors += presetCount;
            log.error("Bench Rainbow : chargement D1 impossible pour {} (passe {}, jour {}), actif sauté", asset, pass, day, e);
        }
    }

    /** Presets d'un utilisateur : les presets live d'abord, par {@code priority} croissante (cash commun), puis les autres. */
    private void runUser(RainbowLivePass pass, Instant asOf, LocalDate day, List<RainbowLivePreset> presets,
                         Map<String, AssetSeries> seriesByAsset, Set<String> failedAssets, Counters counters) {
        Map<Long, LiveSlot> slots = liveSlotsOf(presets.getFirst(), counters);
        List<RainbowLivePreset> ordered = new ArrayList<>(presets);
        ordered.sort(Comparator.comparing((RainbowLivePreset p) -> slots.get(p.getId()) == null)
                .thenComparingInt((RainbowLivePreset p) -> slots.containsKey(p.getId()) ? slots.get(p.getId()).priority() : 0)
                .thenComparingInt(p -> RainbowLiveDefaultPresets.ASSETS.indexOf(p.getAssetSymbol())));
        Map<Long, CashLedger> ledgers = new HashMap<>();
        for (RainbowLivePreset preset : ordered) {
            AssetSeries series = seriesByAsset.get(preset.getAssetSymbol());
            if (series == null) {
                continue; // déjà comptée (sautée ou en échec) par loadAsset
            }
            try {
                if (runPreset(pass, day, preset, series, slots.get(preset.getId()), ledgers)) {
                    counters.processed++;
                } else {
                    counters.skipped++;
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec preset={} actif={} user={} passe {} jour {}",
                        preset.getId(), preset.getAssetSymbol(), preset.getUser().getId(), pass, day, e);
            }
        }
    }

    private Map<Long, LiveSlot> liveSlotsOf(RainbowLivePreset anyPreset, Counters counters) {
        try {
            Map<Long, LiveSlot> slots = new HashMap<>();
            realPortfolioSource.liveSlots(anyPreset.getUser()).forEach(slot -> slots.put(slot.presetId(), slot));
            return slots;
        } catch (Exception e) {
            counters.errors++;
            log.error("Bench Rainbow : bindings live illisibles pour user={}, presets traités en simulation seule",
                    anyPreset.getUser().getId(), e);
            return Map.of();
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

    /**
     * @param slot lien live du preset (null : simulation pure) ; {@code ledgers} : pools de cash de l'utilisateur pour la passe
     * @return {@code true} si la passe a été enregistrée, {@code false} si sautée (données insuffisantes).
     */
    private boolean runPreset(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset, AssetSeries series,
                              LiveSlot slot, Map<Long, CashLedger> ledgers) {
        if (preset.isTrendMix()) {
            return runTrendPreset(pass, day, preset, series, slot, ledgers);
        }
        RainbowAtrConfig config = configResolver.config(preset);
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
        int startIdx = Math.max(warmup, firstIndexOnOrAfter(ds, day.minusMonths(configResolver.analysisWindowMonths(preset))));
        startIdx = Math.min(startIdx, endIdx);

        RainbowAtrResult result = RainbowAtrEngine.simulate(ds, globals, new RainbowAtrTuning[]{tuning}, null,
                startIdx, endIdx, true);

        PortfolioReading mock = mockPortfolioSource.read(preset);
        RainbowLivePassBlock block = buildBlock(ds, endIdx, tuning, result);
        double close = ds.close(endIdx);
        WantedAction wanted = wantedAction(result, endIdx);
        sizeAction(block, wanted, close, mock, preset.getAssetSymbol());
        LiveSizing live = liveSizing(pass, day, preset, slot, ledgers);
        if (live != null) {
            live.apply(block, wanted.buyAmount(), wanted.sellQty(), close);
        }

        runService.upsertPass(preset, day, pass, block);
        log.info("Bench Rainbow : preset={} actif={} jour={} passe {} action fictive={} montant={} qté={} live={}",
                preset.getId(), preset.getAssetSymbol(), day, pass, block.getActionType(),
                block.getActionAmountUsdc(), block.getActionQuantity(), block.getLiveActionType());
        return true;
    }

    /** Preset {@code TREND_MIX} : un pas de la machine à partir de l'état persisté (cf. {@link RainbowTrendLiveService}). */
    private boolean runTrendPreset(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset, AssetSeries series,
                                   LiveSlot slot, Map<Long, CashLedger> ledgers) {
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
        LiveSizing live = liveSizing(pass, day, preset, slot, ledgers);
        RainbowTrendLiveService.Outcome outcome = trendService.run(pass, day, preset, series.candles(), ds, endIdx, wallet, live);
        runService.upsertPass(preset, day, pass, outcome.block());
        outcome.commit();
        return true;
    }

    /**
     * Portefeuille réel du preset live pour la passe, ou {@code null} (simulation pure). Rejeu 23:55 d'un snapshot existant :
     * action d'origine conservée, son achat reste réservé dans le pool. 00:05 : snapshot de 23:55 relu s'il est encore frais ;
     * sinon lecture (un seul accès exchange par preset, le cache de soldes mutualise les actifs d'un même wallet).
     */
    private LiveSizing liveSizing(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset, LiveSlot slot,
                                  Map<Long, CashLedger> ledgers) {
        if (slot == null) {
            return null;
        }
        String asset = preset.getAssetSymbol();
        Optional<RainbowLivePassBlock> snapshot = runRepository.findByPresetAndDay(preset, day)
                .map(RainbowLiveRun::getPass2355).filter(RainbowLivePassBlock::hasLiveSnapshot);
        if (pass == RainbowLivePass.T2355 && snapshot.isPresent()) {
            RainbowLivePassBlock previous = snapshot.get();
            CashLedger ledger = ledgerOf(ledgers, slot.walletId(), PortfolioReading.fromSnapshot(previous, asset));
            if (ledger != null && previous.getLiveActionType() == RainbowLiveAction.BUY) {
                ledger.forceReserve(previous.getLiveActionAmountUsdc() == null ? 0.0 : previous.getLiveActionAmountUsdc());
            }
            return LiveSizing.replay();
        }
        PortfolioReading reading = null;
        if (pass == RainbowLivePass.T0005 && snapshot.isPresent()) {
            PortfolioReading reused = realPortfolioSource.checkFreshness(PortfolioReading.fromSnapshot(snapshot.get(), asset));
            if (reused.isOk() && slot.walletId().equals(reused.walletId())) {
                reading = reused;
            }
        }
        if (reading == null) {
            reading = realPortfolioSource.read(preset);
        }
        return LiveSizing.of(asset, reading, ledgerOf(ledgers, slot.walletId(), reading), slot.bagPercent());
    }

    /** Ledger du pool (créé à la première lecture {@code OK} du wallet) ; {@code null} si la lecture n'est pas exploitable. */
    private static CashLedger ledgerOf(Map<Long, CashLedger> ledgers, Long walletId, PortfolioReading reading) {
        if (!reading.isOk()) {
            return ledgers.get(walletId);
        }
        return ledgers.computeIfAbsent(walletId, id -> new CashLedger(reading.cash()));
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

    /** Achat voulu (USDC) et vente voulue (quantité) = events du dernier index du rejeu. */
    private static WantedAction wantedAction(RainbowAtrResult result, int endIdx) {
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
        return new WantedAction(buyAmount, sellQty);
    }

    /**
     * Action fictive du jour. Plafonds portés ICI (moteur inchangé) : achat ≤ cash du wallet mock, vente ≤ position. Achat et
     * vente sur la même bougie (ex. MOON_STOP sans cooldown) : la VENTE l'emporte (WARN).
     */
    private void sizeAction(RainbowLivePassBlock block, WantedAction wanted, double close, PortfolioReading mock,
                            String asset) {
        double buyAmount = wanted.buyAmount();
        double sellQty = wanted.sellQty();
        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow : achat ET vente sur la même bougie (achat {} USDC, vente {}) : la vente l'emporte",
                    buyAmount, sellQty);
        }

        block.setActionType(RainbowLiveAction.NONE);
        if (sellQty > 0) {
            double qty = Math.min(sellQty, mock.quantity(asset));
            if (qty > EPSILON) {
                block.setActionType(RainbowLiveAction.SELL);
                block.setActionQuantity(qty);
                block.setActionAmountUsdc(qty * close);
            }
        } else if (buyAmount > 0) {
            double amount = Math.min(buyAmount, mock.cash());
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
