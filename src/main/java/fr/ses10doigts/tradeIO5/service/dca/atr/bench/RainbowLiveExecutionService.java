package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioSnapshotService;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
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
 * Signal par affectation, live par lien : chaque affectation produit un signal ({@link LiveDemand}) ; chaque lien (binding) le
 * dimensionne sur son portefeuille réel ({@link LiveSizing}, snapshot enregistré dans le run du lien par
 * {@link RainbowLiveBindingRunService}). Par utilisateur, les liens sont servis par {@code priority} croissante et se
 * partagent un {@link CashLedger} par wallet. Rejeu du même jour : action live d'origine conservée.
 * <p>
 * Signal par lien sur la source du lien : les candles D1 (720 j, chemin natif hors Bucket) viennent de l'exchange du wallet
 * ({@link LiveCandleSource}). Le affectation est calculé sur la source de son lien le plus prioritaire (défauts
 * {@code asset_provider} sans lien) ; chaque lien d'un affectation ATR sur une AUTRE source rejoue le moteur sur sa propre série.
 * Un affectation Trend Mix (machine d'état unique) partage son signal entre ses liens (WARN si sources différentes). Source
 * indisponible : fallback avec WARN et {@code candleSourceSubstituted} sur le snapshot du lien.
 * <p>
 * Série D1 chargée UNE fois par (actif, source) et partagée entre tous les affectations/users. Une erreur sur un
 * (actif, affectation, user) n'empêche jamais les autres. Pas de rattrapage des jours manqués.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveExecutionService {


    private final MarketDatasetEngine datasetEngine;
    private final UserRepository userRepository;
    private final AssetAffectationService affectationService;
    private final AffectationConfigResolver configResolver;
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveRunService runService;
    private final RainbowLiveBindingRunService bindingRunService;
    private final RainbowTrendLiveService trendService;
    private final RealPortfolioSource realPortfolioSource;
    private final UserTradingSettingsService userSettingsService;
    private final RainbowModulationControlService modulationControl;
    private final PortfolioSnapshotService snapshotService;

    /**
     * Récapitulatif d'une passe (log + réponse de l'endpoint admin). Durées en ms : {@code loadMs} = chargement des séries D1,
     * {@code signalMs} = rejeu des affectations (hors chargement), {@code linksMs} = liens live (dont d'éventuels chargements de
     * séries de lien), {@code totalMs} = durée totale.
     */
    public record PassSummary(LocalDate day, int processed, int skipped, int errors, long loadMs, long signalMs, long linksMs,
                              long totalMs) {
        public PassSummary(LocalDate day, int processed, int skipped, int errors) {
            this(day, processed, skipped, errors, 0, 0, 0, 0);
        }
    }

    /**
     * Session de recalcul groupé (opaque) : partage les séries D1 déjà chargées par (actif, source) entre plusieurs
     * {@code recomputeDay} du même {@code asOf}, un contexte par jour.
     */
    public static final class RecomputeBatch {
        private final Instant asOf;
        private final Map<LocalDate, PassContext> contexts = new HashMap<>();

        private RecomputeBatch(Instant asOf) {
            this.asOf = asOf;
        }

        public Instant asOf() {
            return asOf;
        }

        private PassContext contextFor(LocalDate day) {
            return contexts.computeIfAbsent(day, d -> new PassContext(asOf, d, new HashMap<>(), new long[1]));
        }
    }

    /**
     * Récapitulatif d'une resynchronisation d'état (log + réponse de l'endpoint admin) : {@code toDay} = dernier jour
     * (clôturé) auquel l'état est ramené, affectations Trend Mix actifs uniquement.
     */
    public record ResyncSummary(LocalDate toDay, int resynced, int upToDate, int skipped, int errors) {
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
    private record PassContext(Instant asOf, LocalDate day, Map<SeriesKey, SeriesLookup> series, long[] loadNanos) {
    }

    /** Signal d'un lien : demande dimensionnée + exchange réel des candles et substitution éventuelle. */
    private record LinkSignal(LiveDemand demand, MarketDataSource source, boolean substituted) {
    }

    private static final class Counters {
        int processed;
        int skipped;
        int errors;
        long signalNanos;
        long linksNanos;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    private static PassSummary summaryOf(LocalDate day, Counters counters, long loadNanos, long startNanos) {
        return new PassSummary(day, counters.processed, counters.skipped, counters.errors, ms(loadNanos), ms(counters.signalNanos),
                ms(counters.linksNanos), ms(System.nanoTime() - startNanos));
    }

    /** Jour UTC traité : le jour de {@code asOf} (la passe tourne à 23:55 UTC, sur la bougie du jour encore ouverte). */
    public static LocalDate dayFor(Instant asOf) {
        return asOf.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** Nouvelle session de recalcul groupé : même {@code asOf} pour tout le lot (cf. {@link #recomputeDay(User, Instant, LocalDate, RecomputeBatch)}). */
    public RecomputeBatch newRecomputeBatch(Instant asOf) {
        return new RecomputeBatch(asOf);
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
        long start = System.nanoTime();
        Counters counters = new Counters();

        Map<Long, List<AssetAffectation>> affectationsByUser = collectAffectations(counters);
        PassContext ctx = new PassContext(asOf, day, new HashMap<>(), new long[1]);
        for (List<AssetAffectation> affectations : affectationsByUser.values()) {
            runUser(ctx, affectations, counters);
        }

        PassSummary summary = summaryOf(day, counters, ctx.loadNanos()[0], start);
        log.info("Bench Rainbow : fin passe jour={} traités={} sautés={} erreurs={} (chargement={} ms, signal={} ms, liens={} ms, total={} ms)",
                day, summary.processed(), summary.skipped(), summary.errors(), summary.loadMs(), summary.signalMs(),
                summary.linksMs(), summary.totalMs());
        return summary;
    }

    /**
     * Recalcul forcé du jour pour un seul utilisateur : supprime ses runs de affectation et de lien du jour (donc l'action figée et
     * la réservation de cash) puis rejoue la passe sur la bougie courante. Appelé par le reset d'un plan du jour ouvert, jamais
     * si un ordre est parti.
     */
    public synchronized PassSummary recomputeDay(User user, Instant asOf, LocalDate day) {
        return recomputeDay(user, asOf, day, new RecomputeBatch(asOf));
    }

    /** Idem, avec les séries D1 partagées par la session {@code batch} (même {@code asOf} pour tout le lot). */
    public synchronized PassSummary recomputeDay(User user, Instant asOf, LocalDate day, RecomputeBatch batch) {
        if (!batch.asOf().equals(asOf)) {
            throw new IllegalArgumentException("asOf différent de celui de la session de recalcul");
        }
        log.info("Bench Rainbow : recalcul forcé jour={} user={} (asOf={})", day, user.getId(), asOf);
        long start = System.nanoTime();
        Counters counters = new Counters();
        PassContext ctx = batch.contextFor(day);
        long loadBefore = ctx.loadNanos()[0];
        affectationService.ensureAffectations(user);
        List<AssetAffectation> enabled = affectationService.list(user).stream().filter(AssetAffectation::isEnabled).toList();
        if (!enabled.isEmpty()) {
            runService.discardDay(user, day);
            bindingRunService.discardDay(user, day);
            runUser(ctx, enabled, counters);
        }
        PassSummary summary = summaryOf(day, counters, ctx.loadNanos()[0] - loadBefore, start);
        log.info("Bench Rainbow : fin recalcul forcé jour={} user={} traités={} sautés={} erreurs={} (chargement={} ms, signal={} ms, liens={} ms, total={} ms)",
                day, user.getId(), summary.processed(), summary.skipped(), summary.errors(), summary.loadMs(), summary.signalMs(),
                summary.linksMs(), summary.totalMs());
        return summary;
    }

    /**
     * Resynchronise l'état moteur des affectations Trend Mix actifs (ou du seul {@code affectationId}) jusqu'à la veille de {@code asOf}
     * (dernière bougie clôturée), sans run ni ordre : cf. {@link RainbowTrendLiveService#resync}. Appelée au démarrage du serveur
     * ({@code force = false} : seuls les affectations en retard sont recalculés) et par l'endpoint admin ({@code force = true}).
     * Série indisponible (bougie de la veille absente, ex. candles pas encore à jour) ⇒ affectation compté « sauté », rattrapé par la
     * détection de trou de la prochaine passe.
     */
    public synchronized ResyncSummary resync(Instant asOf, Long affectationId, boolean force, String actor, String reason) {
        LocalDate target = dayFor(asOf).minusDays(1);
        log.info("Bench Rainbow : début resynchronisation jusqu'au {} (affectation={}, force={}, {})", target, affectationId, force, reason);
        Counters counters = new Counters();
        int resynced = 0;
        int upToDate = 0;
        PassContext ctx = new PassContext(asOf, target, new HashMap<>(), new long[1]);
        for (List<AssetAffectation> affectations : collectAffectations(counters).values()) {
            Map<Long, MarketDataSource> referenceSources = referenceSources(liveSlotsOf(affectations.getFirst().getUser(), counters));
            for (AssetAffectation affectation : affectations) {
                if (affectationId != null && !affectationId.equals(affectation.getId())) {
                    continue;
                }
                SeriesLookup lookup = seriesFor(ctx, affectation.getAssetSymbol(), referenceSources.get(affectation.getId()));
                if (lookup.outcome() != SeriesOutcome.OK) {
                    if (lookup.outcome() == SeriesOutcome.FAILED) {
                        counters.errors++;
                    } else {
                        counters.skipped++;
                    }
                    continue;
                }
                try {
                    AssetSeries series = lookup.series();
                    if (trendService.resync(affectation, series.candles(), series.dataset(), series.dayIdx(), force, actor, reason)) {
                        resynced++;
                    } else {
                        upToDate++;
                    }
                } catch (Exception e) {
                    counters.errors++;
                    log.error("Bench Rainbow : échec resynchronisation affectation={} actif={}", affectation.getId(), affectation.getAssetSymbol(), e);
                }
            }
        }
        ResyncSummary summary = new ResyncSummary(target, resynced, upToDate, counters.skipped, counters.errors);
        log.info("Bench Rainbow : fin resynchronisation jusqu'au {} resynchronisés={} à jour={} sautés={} erreurs={}",
                target, resynced, upToDate, summary.skipped(), summary.errors());
        return summary;
    }

    /** Création des affectations de stratégie manquants puis affectations {@code enabled} des utilisateurs actifs, groupés par utilisateur. */
    private Map<Long, List<AssetAffectation>> collectAffectations(Counters counters) {
        Map<Long, List<AssetAffectation>> byUser = new LinkedHashMap<>();
        for (User user : userRepository.findByEnabledTrueAndArchivedAtIsNull()) {
            try {
                affectationService.ensureAffectations(user);
                List<AssetAffectation> enabled = affectationService.list(user).stream().filter(AssetAffectation::isEnabled).toList();
                if (!enabled.isEmpty()) {
                    byUser.put(user.getId(), enabled);
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec de préparation des affectations pour user={}", user.getId(), e);
            }
        }
        return byUser;
    }

    /** Série D1 de (actif, source) : chargée une seule fois par passe (mémorisée dans le contexte, y compris échec/absence). */
    private SeriesLookup seriesFor(PassContext ctx, String asset, MarketDataSource source) {
        return ctx.series().computeIfAbsent(new SeriesKey(asset, source), key -> {
            long start = System.nanoTime();
            try {
                AssetSeries series = loadSeries(asset, source, ctx.day(), ctx.asOf());
                return series == null ? new SeriesLookup(null, SeriesOutcome.SKIPPED)
                        : new SeriesLookup(series, SeriesOutcome.OK);
            } catch (Exception e) {
                log.error("Bench Rainbow : chargement D1 impossible pour {} source={} (jour {}), sauté",
                        asset, source, ctx.day(), e);
                return new SeriesLookup(null, SeriesOutcome.FAILED);
            } finally {
                ctx.loadNanos()[0] += System.nanoTime() - start;
            }
        });
    }

    /**
     * Utilisateur en deux temps : (1) signal de chaque affectation sur la source de son lien le plus prioritaire, en collectant
     * la {@link LiveDemand} ; (2) signal puis dimensionnement live de chaque lien par {@code priority} croissante, cash commun
     * par wallet ({@link CashLedger}). Les affectations sans lien n'ont qu'un signal (source par défaut).
     */
    private void runUser(PassContext ctx, List<AssetAffectation> affectations, Counters counters) {
        User user = affectations.getFirst().getUser();
        List<LiveSlot> slots = liveSlotsOf(user, counters);
        Map<Long, MarketDataSource> referenceSources = referenceSources(slots);

        List<AssetAffectation> ordered = new ArrayList<>(affectations);
        ordered.sort(Comparator.comparingInt(p -> RainbowLiveDefaultPresets.ASSETS.indexOf(p.getAssetSymbol())));
        Map<Long, AssetAffectation> affectationById = new HashMap<>();
        Map<Long, LiveDemand> demands = new HashMap<>();
        Map<Long, AssetSeries> affectationSeries = new HashMap<>();
        for (AssetAffectation affectation : ordered) {
            affectationById.put(affectation.getId(), affectation);
            SeriesLookup lookup = seriesFor(ctx, affectation.getAssetSymbol(), referenceSources.get(affectation.getId()));
            if (lookup.outcome() == SeriesOutcome.SKIPPED) {
                counters.skipped++;
                continue;
            }
            if (lookup.outcome() == SeriesOutcome.FAILED) {
                counters.errors++;
                continue;
            }
            affectationSeries.put(affectation.getId(), lookup.series());
            long signalStart = System.nanoTime();
            try {
                if (runAffectation(ctx.day(), affectation, lookup.series(), demands)) {
                    counters.processed++;
                } else {
                    counters.skipped++;
                }
            } catch (Exception e) {
                counters.errors++;
                log.error("Bench Rainbow : échec affectation={} actif={} user={} jour {}",
                        affectation.getId(), affectation.getAssetSymbol(), affectation.getUser().getId(), ctx.day(), e);
            } finally {
                counters.signalNanos += System.nanoTime() - signalStart;
            }
        }
        long linksStart = System.nanoTime();
        try {
            runLinks(ctx, user, slots, affectationById, demands, affectationSeries, referenceSources, counters);
        } finally {
            counters.linksNanos += System.nanoTime() - linksStart;
        }
    }

    /** Source de référence d'un affectation = candles de son lien le plus prioritaire qui a un exchange ({@code slots} déjà triés). */
    private static Map<Long, MarketDataSource> referenceSources(List<LiveSlot> slots) {
        Map<Long, MarketDataSource> reference = new HashMap<>();
        for (LiveSlot slot : slots) {
            if (slot.candleSource() != null) {
                reference.putIfAbsent(slot.affectationId(), slot.candleSource());
            }
        }
        return reference;
    }

    /** Liens live de l'utilisateur par {@code priority} croissante : cash commun par wallet, un snapshot par lien. */
    private void runLinks(PassContext ctx, User user, List<LiveSlot> slots, Map<Long, AssetAffectation> affectationById,
                          Map<Long, LiveDemand> demands, Map<Long, AssetSeries> affectationSeries,
                          Map<Long, MarketDataSource> referenceSources, Counters counters) {
        Map<Long, CashLedger> ledgers = new HashMap<>();
        ModulationPass modulation = new ModulationPass(userSettingsService.effectiveSettings(user), modulationControl.current(),
                closesOf(affectationById, demands), new ExposureBook(), new HashMap<>());
        for (LiveSlot slot : slots) {
            try {
                LinkSignal signal = signalOf(ctx, slot, affectationById.get(slot.affectationId()), demands, affectationSeries,
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
     * Signal du lien. Source du lien = référence du affectation (ou lien sans exchange) : signal du affectation. Autre source : affectation
     * ATR => rejeu sur la série de cette source (signal propre au lien) ; affectation Trend Mix (machine d'état unique) => signal du
     * affectation partagé, marqué substitué + WARN. {@code null} si le affectation n'a pas été calculé ce jour-là ou si la série manque.
     */
    private LinkSignal signalOf(PassContext ctx, LiveSlot slot, AssetAffectation affectation, Map<Long, LiveDemand> demands,
                                Map<Long, AssetSeries> affectationSeries, Map<Long, MarketDataSource> referenceSources,
                                Counters counters) {
        LiveDemand affectationDemand = demands.get(slot.affectationId());
        AssetSeries reference = affectationSeries.get(slot.affectationId());
        if (affectation == null || affectationDemand == null || reference == null) {
            return null;
        }
        MarketDataSource wanted = slot.candleSource();
        if (wanted == null || wanted == referenceSources.get(slot.affectationId())) {
            return new LinkSignal(affectationDemand, reference.source(), reference.substituted());
        }
        log.warn("⚠️ Bench Rainbow : Affectation {} partagée entre liens de sources différentes : lien {} (source {}) "
                        + "reçoit le signal calculé sur {} (machine d'état unique).",
                affectation.getId(), slot.bindingId(), wanted, reference.source());
        return new LinkSignal(affectationDemand, reference.source(), true);
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
            log.error("Bench Rainbow : bindings live illisibles pour user={}, affectations traités sans dimensionnement live",
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
     * @param demands reçoit la {@link LiveDemand} du affectation (consommée ensuite par chacun de ses liens)
     * @return {@code true} si le run a été enregistré, {@code false} si sauté (données insuffisantes).
     */
    private boolean runAffectation(LocalDate day, AssetAffectation affectation, AssetSeries series, Map<Long, LiveDemand> demands) {
        refreshPreviousDay(day, affectation, series);
        return runTrendAffectation(day, affectation, series, demands);
    }

    /**
     * Run de la veille (s'il existe) rejoué sur la bougie désormais clôturée : indicateurs, et pour le Trend Mix état moteur
     * + ATH. L'action décidée à 23:55 reste figée. Une erreur ici n'empêche pas le run du jour.
     */
    private void refreshPreviousDay(LocalDate day, AssetAffectation affectation, AssetSeries series) {
        int prevIdx = series.dayIdx() - 1;
        LocalDate previousDay = day.minusDays(1);
        if (prevIdx < 0 || !candleDay(series.candles().get(prevIdx)).equals(previousDay)
                || !runService.hasSignal(affectation, previousDay)) {
            return;
        }
        try {
            List<MarketData> upToPrevious = series.candles().subList(0, prevIdx + 1);
            AssetSeries previous = new AssetSeries(RainbowAtrDataset.fromMarketData(upToPrevious), prevIdx, upToPrevious,
                    series.source(), series.substituted());
            RainbowTrendLiveService.Outcome outcome = trendService.run(previousDay, affectation, previous.candles(),
                    previous.dataset(), prevIdx);
            if (runService.refresh(affectation, previousDay, outcome.block())) {
                outcome.commit();
            }
        } catch (Exception e) {
            log.warn("Bench Rainbow : rafraîchissement du {} impossible pour le affectation {} ({}), run du {} poursuivi",
                    previousDay, affectation.getId(), e.getMessage(), day);
        }
    }

        /** Preset {@code TREND_MIX} : un pas de la machine à partir de l'état persisté (cf. {@link RainbowTrendLiveService}). */
    private boolean runTrendAffectation(LocalDate day, AssetAffectation affectation, AssetSeries series, Map<Long, LiveDemand> demands) {
        RainbowAtrDataset ds = series.dataset();
        int endIdx = series.dayIdx();
        int warmup = affectation.getTrendPreset().toParams().warmup();
        if (endIdx < warmup) {
            log.warn("Bench Rainbow : dataset trop court pour le Trend Mix affectation={} actif={} ({} bougies, warmup {}), sauté",
                    affectation.getId(), affectation.getAssetSymbol(), endIdx + 1, warmup);
            return false;
        }
        RainbowTrendLiveService.Outcome outcome = trendService.run(day, affectation, series.candles(), ds, endIdx);
        runService.upsert(affectation, day, outcome.block());
        outcome.commit();
        demands.put(affectation.getId(), outcome.demand());
        return true;
    }

    /**
     * Snapshot live d'un lien pour le jour. Rejeu d'un snapshot existant : action d'origine conservée, son achat reste
     * réservé dans le pool. Sinon lecture du portefeuille réel (un seul accès exchange par lien, le cache de soldes mutualise
     * les liens d'un même wallet). Sans demande du affectation (affectation non calculé ce jour-là), le lien n'est pas dimensionné.
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

    /** Clôture D1 du jour par actif d'après les demandes des affectations de la passe (aucun appel exchange supplémentaire). */
    private static Map<String, Double> closesOf(Map<Long, AssetAffectation> affectationById, Map<Long, LiveDemand> demands) {
        Map<String, Double> closes = new HashMap<>();
        demands.forEach((affectationId, demand) -> {
            AssetAffectation affectation = affectationById.get(affectationId);
            if (affectation != null) {
                closes.putIfAbsent(affectation.getAssetSymbol(), demand.close());
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

                    private static LocalDate candleDay(MarketData candle) {
        return candle.getTimestamp().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
