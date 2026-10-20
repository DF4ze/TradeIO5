package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioSnapshotService;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exécution de la passe quotidienne du bench (23:55 UTC, bougie D1 du jour encore ouverte) : chargement D1, rejeu du
 * {@link RainbowAtrEngine} (inchangé, parité pine), signal du jour (action voulue par le moteur, sans plafond de
 * portefeuille) persisté via {@link RainbowLiveRunService#upsert}. <b>Aucun ordre</b> ; seul accès à un portefeuille : le
 * port {@link RainbowPortfolioSource} (réel, en lecture seule, pour chaque lien), plus la lecture de marché
 * ({@link MarketDatasetEngine}).
 * <p>
 * Avant de calculer le jour J, la passe rafraîchit le run de J-1 (s'il existe) avec la vraie clôture de sa bougie : indicateurs,
 * état moteur et ATH sont recalculés ; l'action décidée à 23:55 reste figée.
 * <p>
 * Signal par preset, live par lien : chaque preset produit un signal ({@link LiveDemand}) ; chaque lien (binding) le
 * dimensionne sur son portefeuille réel ({@link LiveSizing}, snapshot enregistré dans le run du lien par
 * {@link RainbowLiveBindingRunService}). Par utilisateur, les liens sont servis par {@code priority} croissante et se
 * partagent un {@link CashLedger} par wallet. Rejeu du même jour : action live d'origine conservée.
 * <p>
 * Signal par lien sur la source du lien : les candles D1 (720 j, chemin natif hors Bucket) viennent de l'exchange du wallet
 * ({@link LiveCandleSource}). Le preset est calculé sur la source de son lien le plus prioritaire (défauts
 * {@code asset_provider} sans lien) ; chaque lien d'un preset ATR sur une AUTRE source rejoue le moteur sur sa propre série.
 * Un preset Trend Mix (machine d'état unique) partage son signal entre ses liens (WARN si sources différentes). Source
 * indisponible : fallback avec WARN et {@code candleSourceSubstituted} sur le snapshot du lien.
 * <p>
 * Série D1 chargée UNE fois par (actif, source) et partagée entre tous les presets/users. Une erreur sur un
 * (actif, preset, user) n'empêche jamais les autres. Pas de rattrapage des jours manqués.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveExecutionService {

    /** Tolérance flottante sur les quantités/montants. */
    private static final double EPSILON = 1e-9;
    private static final String EVENT_BUY_ZONE = "BUY_ZONE";
    private static final String EVENT_BUY_TRIGGERED = "BUY_TRIGGERED";
    private static final String EVENT_SELL = "SELL";
    private static final String EVENT_MOON_STOP = "MOON_STOP";

    private final MarketDatasetEngine datasetEngine;
    private final UserRepository userRepository;
    private final RainbowLivePresetService presetService;
    private final RainbowLivePresetConfigResolver configResolver;
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveRunService runService;
    private final RainbowLiveBindingRunService bindingRunService;
    private final RainbowTrendLiveService trendService;
    private final RealPortfolioSource realPortfolioSource;
    private final UserTradingSettingsService userSettingsService;
    private final RainbowModulationControlService modulationControl;
    private final PortfolioSnapshotService snapshotService;

    /** Récapitulatif d'une passe (log + réponse de l'endpoint admin). */
    public record PassSummary(LocalDate day, int processed, int skipped, int errors) {
    }

    /** Série D1 d'un actif tronquée au jour traité (la bougie du jour suivant, éventuelle, est ignorée) et sa provenance. */
    private record AssetSeries(RainbowAtrDataset dataset, int dayIdx, List<MarketData> candles, MarketDataSource source,
                               boolean substituted) {
    }

    private record SeriesKey(String asset, MarketDataSource source) {
    }

    private enum SeriesOutcome { OK, SKIPPED, FAILED }

    /** Résultat mémorisé du chargement d'une série (OK, bougie du jour absente, ou échec). */
    private record SeriesLookup(AssetSeries series, SeriesOutcome outcome) {
    }

    /** Contexte d'une passe : séries déjà chargées par (actif, source). */
    private record PassContext(Instant asOf, LocalDate day, Map<SeriesKey, SeriesLookup> series) {
    }

    /** Signal d'un lien : demande dimensionnée + exchange réel des candles et substitution éventuelle. */
    private record LinkSignal(LiveDemand demand, MarketDataSource source, boolean substituted) {
    }

    /** Rejeu ATR du preset sur une série. */
    private record AtrSim(RainbowAtrTuning tuning, RainbowAtrResult result, RainbowAtrDataset dataset, int endIdx,
                          double baseAmount) {
    }

    /** Ce que veut le moteur ce jour-là, avant tout plafond : achat en USDC, vente en quantité. */
    private record WantedAction(double buyAmount, double sellQty) {
    }

    private static final class Counters {
        int processed;
        int skipped;
        int errors;
    }

    /** Jour UTC traité : le jour de {@code asOf} (la passe tourne à 23:55 UTC, sur la bougie du jour encore ouverte). */
    public static LocalDate dayFor(Instant asOf) {
        return asOf.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public PassSummary runPass(Instant asOf) {
        return runPass(asOf, null);
    }

    /**
     * @param dayOverride jour forcé (déclenchement manuel) ; {@code null} ⇒ {@link #dayFor}.
     */
    public synchronized PassSummary runPass(Instant asOf, LocalDate dayOverride) {
        LocalDate day = dayOverride != null ? dayOverride : dayFor(asOf);
        log.info("Bench Rainbow : début passe jour={} (asOf={})", day, asOf);
        Counters counters = new Counters();

        Map<Long, List<RainbowLivePreset>> presetsByUser = collectPresets(counters);
        PassContext ctx = new PassContext(asOf, day, new HashMap<>());
        for (List<RainbowLivePreset> presets : presetsByUser.values()) {
            runUser(ctx, presets, counters);
        }

        PassSummary summary = new PassSummary(day, counters.processed, counters.skipped, counters.errors);
        log.info("Bench Rainbow : fin passe jour={} traités={} sautés={} erreurs={}",
                day, summary.processed(), summary.skipped(), summary.errors());
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

    /** Série D1 de (actif, source) : chargée une seule fois par passe (mémorisée dans le contexte, y compris échec/absence). */
    private SeriesLookup seriesFor(PassContext ctx, String asset, MarketDataSource source) {
        return ctx.series().computeIfAbsent(new SeriesKey(asset, source), key -> {
            try {
                AssetSeries series = loadSeries(asset, source, ctx.day(), ctx.asOf());
                return series == null ? new SeriesLookup(null, SeriesOutcome.SKIPPED)
                        : new SeriesLookup(series, SeriesOutcome.OK);
            } catch (Exception e) {
                log.error("Bench Rainbow : chargement D1 impossible pour {} source={} (jour {}), sauté",
                        asset, source, ctx.day(), e);
                return new SeriesLookup(null, SeriesOutcome.FAILED);
            }
        });
    }

    /**
     * Utilisateur en deux temps : (1) signal de chaque preset sur la source de son lien le plus prioritaire, en collectant
     * la {@link LiveDemand} ; (2) signal puis dimensionnement live de chaque lien par {@code priority} croissante, cash commun
     * par wallet ({@link CashLedger}). Les presets sans lien n'ont qu'un signal (source par défaut).
     */
    private void runUser(PassContext ctx, List<RainbowLivePreset> presets, Counters counters) {
        User user = presets.getFirst().getUser();
        List<LiveSlot> slots = liveSlotsOf(user, counters);
        Map<Long, MarketDataSource> referenceSources = referenceSources(slots);

        List<RainbowLivePreset> ordered = new ArrayList<>(presets);
        ordered.sort(Comparator.comparingInt(p -> RainbowLiveDefaultPresets.ASSETS.indexOf(p.getAssetSymbol())));
        Map<Long, RainbowLivePreset> presetById = new HashMap<>();
        Map<Long, LiveDemand> demands = new HashMap<>();
        Map<Long, AssetSeries> presetSeries = new HashMap<>();
        for (RainbowLivePreset preset : ordered) {
            presetById.put(preset.getId(), preset);
            SeriesLookup lookup = seriesFor(ctx, preset.getAssetSymbol(), referenceSources.get(preset.getId()));
            if (lookup.outcome() == SeriesOutcome.SKIPPED) {
                counters.skipped++;
                continue;
            }
            if (lookup.outcome() == SeriesOutcome.FAILED) {
                counters.errors++;
                continue;
            }
            presetSeries.put(preset.getId(), lookup.series());
            try {
                if (runPreset(ctx.day(), preset, lookup.series(), demands)) {
                    counters.processed++;
                } else {
                    counters.skipped++;
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec preset={} actif={} user={} jour {}",
                        preset.getId(), preset.getAssetSymbol(), preset.getUser().getId(), ctx.day(), e);
            }
        }
        runLinks(ctx, user, slots, presetById, demands, presetSeries, referenceSources, counters);
    }

    /** Source de référence d'un preset = candles de son lien le plus prioritaire qui a un exchange ({@code slots} déjà triés). */
    private static Map<Long, MarketDataSource> referenceSources(List<LiveSlot> slots) {
        Map<Long, MarketDataSource> reference = new HashMap<>();
        for (LiveSlot slot : slots) {
            if (slot.candleSource() != null) {
                reference.putIfAbsent(slot.presetId(), slot.candleSource());
            }
        }
        return reference;
    }

    /** Liens live de l'utilisateur par {@code priority} croissante : cash commun par wallet, un snapshot par lien. */
    private void runLinks(PassContext ctx, User user, List<LiveSlot> slots, Map<Long, RainbowLivePreset> presetById,
                          Map<Long, LiveDemand> demands, Map<Long, AssetSeries> presetSeries,
                          Map<Long, MarketDataSource> referenceSources, Counters counters) {
        Map<Long, CashLedger> ledgers = new HashMap<>();
        ModulationPass modulation = new ModulationPass(userSettingsService.effectiveSettings(user), modulationControl.current(),
                closesOf(presetById, demands), new ExposureBook(), new HashMap<>());
        for (LiveSlot slot : slots) {
            try {
                LinkSignal signal = signalOf(ctx, slot, presetById.get(slot.presetId()), demands, presetSeries,
                        referenceSources, counters);
                runLink(ctx.day(), slot, signal, ledgers, modulation);
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec lien binding={} actif={} wallet={} user={} jour {}",
                        slot.bindingId(), slot.assetSymbol(), slot.walletId(), user.getId(), ctx.day(), e);
            }
        }
        recordSnapshots(ctx.day(), user, slots, modulation);
    }

    /**
     * Instantané quotidien immuable par wallet de l'utilisateur, à partir de la lecture déjà faite par la passe (aucun accès
     * exchange supplémentaire ; rien si le wallet n'a pas été lu ce jour-là ou si l'instantané du jour existe). Une erreur ici ne perturbe ni la passe ni les autres wallets.
     */
    private void recordSnapshots(LocalDate day, User user, List<LiveSlot> slots, ModulationPass modulation) {
        Map<Long, List<LiveSlot>> byWallet = new LinkedHashMap<>();
        for (LiveSlot slot : slots) {
            byWallet.computeIfAbsent(slot.walletId(), id -> new ArrayList<>()).add(slot);
        }
        byWallet.forEach((walletId, walletSlots) -> {
            try {
                if (snapshotService.exists(user.getId(), walletId, day)) {
                    return;
                }
                PortfolioReading reading = modulation.readings().get(walletId);
                RainbowLiveBinding binding = reading == null ? null
                        : bindingRepository.findById(walletSlots.getFirst().bindingId()).orElse(null);
                if (binding == null) {
                    return;
                }
                snapshotService.record(user, binding.getWallet(), day, reading,
                        walletSlots.stream().map(LiveSlot::assetSymbol).distinct().toList(), modulation.closes(),
                        modulation.settings());
            } catch (Exception e) {
                log.error("Bench Rainbow : instantané patrimoine impossible user={} wallet={} jour {}", user.getId(), walletId, day, e);
            }
        });
    }

    /**
     * Signal du lien. Source du lien = référence du preset (ou lien sans exchange) : signal du preset. Autre source : preset
     * ATR => rejeu sur la série de cette source (signal propre au lien) ; preset Trend Mix (machine d'état unique) => signal du
     * preset partagé, marqué substitué + WARN. {@code null} si le preset n'a pas été calculé ce jour-là ou si la série manque.
     */
    private LinkSignal signalOf(PassContext ctx, LiveSlot slot, RainbowLivePreset preset, Map<Long, LiveDemand> demands,
                                Map<Long, AssetSeries> presetSeries, Map<Long, MarketDataSource> referenceSources,
                                Counters counters) {
        LiveDemand presetDemand = demands.get(slot.presetId());
        AssetSeries reference = presetSeries.get(slot.presetId());
        if (preset == null || presetDemand == null || reference == null) {
            return null;
        }
        MarketDataSource wanted = slot.candleSource();
        if (wanted == null || wanted == referenceSources.get(slot.presetId())) {
            return new LinkSignal(presetDemand, reference.source(), reference.substituted());
        }
        if (preset.isTrendMix()) {
            log.warn("⚠️ Bench Rainbow : preset Trend Mix {} partagé entre liens de sources différentes : lien {} (source {}) "
                            + "reçoit le signal calculé sur {} (machine d'état unique).",
                    preset.getId(), slot.bindingId(), wanted, reference.source());
            return new LinkSignal(presetDemand, reference.source(), true);
        }
        SeriesLookup lookup = seriesFor(ctx, slot.assetSymbol(), wanted);
        if (lookup.outcome() != SeriesOutcome.OK) {
            counters.errors += lookup.outcome() == SeriesOutcome.FAILED ? 1 : 0;
            counters.skipped += lookup.outcome() == SeriesOutcome.SKIPPED ? 1 : 0;
            log.warn("Bench Rainbow : série D1 {} source={} indisponible pour le lien {} ({}), lien non dimensionné",
                    slot.assetSymbol(), wanted, slot.bindingId(), lookup.outcome());
            return null;
        }
        AtrSim sim = replayAtr(preset, lookup.series(), ctx.day());
        if (sim == null) {
            return null;
        }
        WantedAction action = wantedAction(sim.result(), sim.endIdx());
        LiveDemand demand = LiveDemand.fixed(action.buyAmount(), action.sellQty(), sim.dataset().close(sim.endIdx()), sim.baseAmount());
        return new LinkSignal(demand, lookup.series().source(), lookup.series().substituted());
    }

    private List<LiveSlot> liveSlotsOf(User user, Counters counters) {
        try {
            return realPortfolioSource.liveSlots(user).stream()
                    .sorted(Comparator.comparingInt(LiveSlot::priority)
                            .thenComparingInt(slot -> RainbowLiveDefaultPresets.ASSETS.indexOf(slot.assetSymbol()))
                            .thenComparing(LiveSlot::bindingId))
                    .toList();
        } catch (Exception e) {
            counters.errors++;
            log.error("Bench Rainbow : bindings live illisibles pour user={}, presets traités sans dimensionnement live",
                    user.getId(), e);
            return List.of();
        }
    }

    /**
     * Charge la série D1 (chemin natif, {@link RainbowLiveDefaultPresets#D1_LOOKBACK_CANDLES} jours) de l'actif sur la source
     * demandée ({@code null} = défauts {@code asset_provider}) et repère la bougie du jour. {@code null} ⇒ bougie du jour absente
     * (WARN, passe sautée pour cette série).
     */
    private AssetSeries loadSeries(String asset, MarketDataSource source, LocalDate day, Instant asOf) {
        long start = System.nanoTime();
        DailyDataset daily = datasetEngine.getDailyDatasetForAsset(asset, RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES,
                asOf, source);
        MarketDataset dataset = daily == null ? null : daily.dataset();
        MarketDataSource used = daily == null ? source : daily.source();
        boolean substituted = daily != null && daily.substituted();
        List<MarketData> candles = dataset == null || dataset.getMarketDatas() == null ? List.of() : dataset.getMarketDatas();
        log.info("Bench Rainbow : D1 {} chargé ({} bougies, source {}{}) en {} ms", asset, candles.size(), used,
                substituted ? ", SUBSTITUÉE à " + source : "", (System.nanoTime() - start) / 1_000_000);

        int dayIdx = -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candleDay(candles.get(i)).equals(day)) {
                dayIdx = i;
                break;
            }
        }
        if (dayIdx < 0) {
            log.warn("Bench Rainbow : bougie D1 du {} absente pour {} source={} (dernière : {}), passe sautée pour cette série",
                    day, asset, used, candles.isEmpty() ? "aucune" : candles.getLast().getTimestamp());
            return null;
        }
        // bougie du jour suivant éventuelle ignorée : le signal se calcule sur la bougie du jour
        List<MarketData> upToDay = candles.subList(0, dayIdx + 1);
        return new AssetSeries(RainbowAtrDataset.fromMarketData(upToDay), dayIdx, upToDay, used, substituted);
    }

    /**
     * @param demands reçoit la {@link LiveDemand} du preset (consommée ensuite par chacun de ses liens)
     * @return {@code true} si le run a été enregistré, {@code false} si sauté (données insuffisantes).
     */
    private boolean runPreset(LocalDate day, RainbowLivePreset preset, AssetSeries series, Map<Long, LiveDemand> demands) {
        refreshPreviousDay(day, preset, series);
        if (preset.isTrendMix()) {
            return runTrendPreset(day, preset, series, demands);
        }
        AtrSim sim = replayAtr(preset, series, day);
        if (sim == null) {
            return false;
        }
        double close = sim.dataset().close(sim.endIdx());
        RainbowLivePassBlock block = buildBlock(sim.dataset(), sim.endIdx(), sim.tuning(), sim.result());
        WantedAction wanted = wantedAction(sim.result(), sim.endIdx());
        applyWanted(block, wanted, close);

        runService.upsert(preset, day, block);
        demands.put(preset.getId(), LiveDemand.fixed(wanted.buyAmount(), wanted.sellQty(), close, sim.baseAmount()));
        log.info("Bench Rainbow : preset={} actif={} jour={} action voulue={} montant={} qté={}",
                preset.getId(), preset.getAssetSymbol(), day, block.getActionType(),
                block.getActionAmountUsdc(), block.getActionQuantity());
        return true;
    }

    /**
     * Run de la veille (s'il existe) rejoué sur la bougie désormais clôturée : indicateurs, et pour le Trend Mix état moteur
     * + ATH. L'action décidée à 23:55 reste figée. Une erreur ici n'empêche pas le run du jour.
     */
    private void refreshPreviousDay(LocalDate day, RainbowLivePreset preset, AssetSeries series) {
        int prevIdx = series.dayIdx() - 1;
        LocalDate previousDay = day.minusDays(1);
        if (prevIdx < 0 || !candleDay(series.candles().get(prevIdx)).equals(previousDay)
                || !runService.hasSignal(preset, previousDay)) {
            return;
        }
        try {
            List<MarketData> upToPrevious = series.candles().subList(0, prevIdx + 1);
            AssetSeries previous = new AssetSeries(RainbowAtrDataset.fromMarketData(upToPrevious), prevIdx, upToPrevious,
                    series.source(), series.substituted());
            if (preset.isTrendMix()) {
                RainbowTrendLiveService.Outcome outcome = trendService.run(previousDay, preset, previous.candles(),
                        previous.dataset(), prevIdx);
                if (runService.refresh(preset, previousDay, outcome.block())) {
                    outcome.commit();
                }
                return;
            }
            AtrSim sim = replayAtr(preset, previous, previousDay);
            if (sim != null) {
                runService.refresh(preset, previousDay, buildBlock(sim.dataset(), sim.endIdx(), sim.tuning(), sim.result()));
            }
        } catch (Exception e) {
            log.warn("Bench Rainbow : rafraîchissement du {} impossible pour le preset {} ({}), run du {} poursuivi",
                    previousDay, preset.getId(), e.getMessage(), day);
        }
    }

    /** Rejeu du moteur ATR sur la fenêtre d'analyse du preset ; {@code null} si la série est trop courte (WARN). */
    private AtrSim replayAtr(RainbowLivePreset preset, AssetSeries series, LocalDate day) {
        RainbowAtrConfig config = configResolver.config(preset);
        RainbowAtrTuning tuning = config.toTuning();
        RainbowAtrGlobals globals = config.toGlobals();
        RainbowAtrDataset ds = series.dataset();
        int endIdx = series.dayIdx();

        int warmup = RainbowAtrDataset.warmup(tuning.smaPeriod(), tuning.atrPeriod());
        if (endIdx < warmup + 1) {
            log.warn("Bench Rainbow : dataset trop court pour preset={} actif={} source={} ({} bougies, warmup {}), sauté",
                    preset.getId(), preset.getAssetSymbol(), series.source(), endIdx + 1, warmup);
            return null;
        }
        int startIdx = Math.max(warmup, firstIndexOnOrAfter(ds, day.minusMonths(configResolver.analysisWindowMonths(preset))));
        startIdx = Math.min(startIdx, endIdx);

        RainbowAtrResult result = RainbowAtrEngine.simulate(ds, globals, new RainbowAtrTuning[]{tuning}, null,
                startIdx, endIdx, true);
        return new AtrSim(tuning, result, ds, endIdx, globals.baseAmount());
    }

    /** Preset {@code TREND_MIX} : un pas de la machine à partir de l'état persisté (cf. {@link RainbowTrendLiveService}). */
    private boolean runTrendPreset(LocalDate day, RainbowLivePreset preset, AssetSeries series, Map<Long, LiveDemand> demands) {
        RainbowAtrDataset ds = series.dataset();
        int endIdx = series.dayIdx();
        int warmup = TrendMixCalculator.Params.defaults().warmup();
        if (endIdx < warmup) {
            log.warn("Bench Rainbow : dataset trop court pour le Trend Mix preset={} actif={} ({} bougies, warmup {}), sauté",
                    preset.getId(), preset.getAssetSymbol(), endIdx + 1, warmup);
            return false;
        }
        RainbowTrendLiveService.Outcome outcome = trendService.run(day, preset, series.candles(), ds, endIdx);
        runService.upsert(preset, day, outcome.block());
        outcome.commit();
        demands.put(preset.getId(), outcome.demand());
        return true;
    }

    /**
     * Snapshot live d'un lien pour le jour. Rejeu d'un snapshot existant : action d'origine conservée, son achat reste
     * réservé dans le pool. Sinon lecture du portefeuille réel (un seul accès exchange par lien, le cache de soldes mutualise
     * les liens d'un même wallet). Sans demande du preset (preset non calculé ce jour-là), le lien n'est pas dimensionné.
     */
    private void runLink(LocalDate day, LiveSlot slot, LinkSignal signal, Map<Long, CashLedger> ledgers,
                         ModulationPass modulation) {
        RainbowLiveBinding binding = bindingRepository.findById(slot.bindingId()).orElse(null);
        if (binding == null) {
            return;
        }
        String asset = slot.assetSymbol();
        Optional<RainbowLiveLiveBlock> snapshot = bindingRunService.snapshotOf(binding, day);
        if (snapshot.isPresent()) {
            RainbowLiveLiveBlock previous = snapshot.get();
            CashLedger ledger = ledgerOf(ledgers, slot.walletId(), PortfolioReading.fromSnapshot(previous, asset));
            if (ledger != null && previous.getActionType() == RainbowLiveAction.BUY) {
                ledger.forceReserve(previous.getActionAmountUsdc() == null ? 0.0 : previous.getActionAmountUsdc());
            }
            if (previous.getActionType() != null && previous.getActionAmountUsdc() != null) {
                modulation.book().replay(slot.walletId(), previous.getActionType(), previous.getActionAmountUsdc());
            }
            return;
        }
        if (signal == null) {
            return;
        }
        PortfolioReading reading = realPortfolioSource.read(binding);
        modulation.readings().putIfAbsent(slot.walletId(), reading);
        RainbowLiveLiveBlock block = new RainbowLiveLiveBlock();
        ExposureLedger exposure = modulation.book().ledgerFor(slot.walletId(), reading, modulation.closes());
        LiveModulation linkModulation = new LiveModulation(modulation.settings(), modulation.limits(), exposure);
        LiveSizing.of(asset, reading, ledgerOf(ledgers, slot.walletId(), reading), slot.bagPercent(), linkModulation)
                .apply(block, signal.demand().withLinkBaseAmount(binding.getBaseAmount()));
        block.setCandleSource(signal.source());
        block.setCandleSourceSubstituted(signal.substituted());
        bindingRunService.upsert(binding, day, block);
    }

    /** Clôture D1 du jour par actif d'après les demandes des presets de la passe (aucun appel exchange supplémentaire). */
    private static Map<String, Double> closesOf(Map<Long, RainbowLivePreset> presetById, Map<Long, LiveDemand> demands) {
        Map<String, Double> closes = new HashMap<>();
        demands.forEach((presetId, demand) -> {
            RainbowLivePreset preset = presetById.get(presetId);
            if (preset != null) {
                closes.putIfAbsent(preset.getAssetSymbol(), demand.close());
            }
        });
        return closes;
    }

    /** Modulation d'un utilisateur pour la passe : réglages effectifs, paramètres admin, clôtures et expositions par wallet. */
    private record ModulationPass(ModulationSettings settings, RainbowModulationControl limits, Map<String, Double> closes,
                                  ExposureBook book, Map<Long, PortfolioReading> readings) {
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
     * Action voulue par le moteur ce jour-là, sans plafond de portefeuille (moteur inchangé ; le plafond réel est appliqué par
     * lien). Achat et vente sur la même bougie (ex. MOON_STOP sans cooldown) : la VENTE l'emporte (WARN).
     */
    private static void applyWanted(RainbowLivePassBlock block, WantedAction wanted, double close) {
        double buyAmount = wanted.buyAmount();
        double sellQty = wanted.sellQty();
        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow : achat ET vente sur la même bougie (achat {} USDC, vente {}) : la vente l'emporte",
                    buyAmount, sellQty);
        }
        block.setActionType(RainbowLiveAction.NONE);
        if (sellQty > EPSILON) {
            block.setActionType(RainbowLiveAction.SELL);
            block.setActionQuantity(sellQty);
            block.setActionAmountUsdc(sellQty * close);
        } else if (buyAmount > EPSILON) {
            block.setActionType(RainbowLiveAction.BUY);
            block.setActionAmountUsdc(buyAmount);
            block.setActionQuantity(buyAmount / close);
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
