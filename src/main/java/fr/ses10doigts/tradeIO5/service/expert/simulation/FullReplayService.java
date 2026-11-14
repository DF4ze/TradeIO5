package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowAthReferenceRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthReference;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parité du rejeu mono-actif ({@link FullReplayEngine}) avec les {@link RainbowLiveBindingRun} / {@link RainbowLiveRun} de
 * la prod (copie en base locale). Source des candles = celle du lien (sans fallback silencieux), fenêtre D1 live
 * ({@link RainbowLiveDefaultPresets#D1_LOOKBACK_CANDLES}). Défauts : période = du 1er run exploitable du lien à aujourd'hui,
 * {@code C0} et position initiale = snapshot de ce 1er run, réglages = ceux du lien et de l'utilisateur (surchargeables).
 * Le cours de décision du jour est celui ENREGISTRÉ par le live (23:55), pas la clôture finale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FullReplayService {

    /** Part minimale de jours identiques proposée à Clem ; en plus, tout écart résiduel doit être expliqué. */
    public static final double ACCEPTANCE_PCT = 95.0;
    private static final double AMOUNT_TOLERANCE = 0.01;
    private static final double EPSILON = 1e-9;
    private static final String CAUSE_CONFIG = "config actuelle ≠ config du run (presets recréés, état moteur d'époque non reconstituable)";
    private static final String CAUSE_OPEN_CANDLE = "23:55 : signal sur bougie ouverte (cours décision ≠ clôture) non reproduit (haut/bas partiels, ATH)";
    private static final String CAUSE_REGIME = "jeu/régime Trend différent (bougie ouverte 23:55 ou resync d'état moteur)";
    private static final String CAUSE_SIGNAL = "signal moteur différent (bougie ouverte 23:55 : bornes/ATH, ou resync d'état moteur)";
    private static final String CAUSE_SIZING = "dimensionnement (wallet multi-actifs, cash commun, réglages de modulation modifiés depuis)";
    private static final String CAUSE_MISSING = "jour manquant (pas de run live ou bougie absente)";
    private static final String CAUSE_UNKNOWN = "inexpliqué";

    /** Surcharges facultatives (tout {@code null} ⇒ valeurs du lien / de l'utilisateur / du 1er run). */
    public record Request(Long bindingId, String asset, LocalDate from, LocalDate to, Double cash0, Double qty0,
                          Double baseAmount, Double bagPercent, Double exposureMin, Double exposureMax, Double power,
                          Double bias, Double regulationMarginPct) {
    }

    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveBindingRunRepository bindingRunRepository;
    private final RainbowLiveRunRepository liveRunRepository;
    private final RainbowAthReferenceRepository athRepository;
    private final AffectationConfigResolver configResolver;
    private final MarketDatasetEngine datasetEngine;
    private final DomainClock clock;
    private final UserTradingSettingsService settingsService;
    private final RainbowModulationControlService modulationControl;

    @Transactional(readOnly = true)
    public ParityReport parity(User user, Request request) {
        Request req = request == null ? new Request(null, null, null, null, null, null, null, null, null, null, null, null, null)
                : request;
        RainbowLiveBinding binding = resolveBinding(user, req);
        AssetAffectation affectation = binding.getAffectation();
        String asset = binding.getAssetSymbol();
        List<String> notes = new ArrayList<>();

        LocalDate today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate to = req.to() != null ? req.to() : today;
        LocalDate from = req.from() != null ? req.from() : LocalDate.of(2000, 1, 1);
        List<RainbowLiveBindingRun> runs = bindingRunRepository.findByBindingAndDayBetweenOrderByDayAsc(binding, from, to).stream()
                .filter(r -> r.getLive() != null && r.getLive().getStatus() == PortfolioStatus.OK).toList();
        if (runs.isEmpty()) {
            throw new IllegalStateException("Aucun run live exploitable pour le lien " + binding.getId() + " (" + asset
                    + ") entre " + from + " et " + to + " : base locale non synchronisée ? (operations/dev-sync.md)");
        }
        RainbowLiveBindingRun first = runs.getFirst();
        LocalDate firstDay = first.getDay();
        LocalDate lastDay = runs.getLast().getDay();
        Map<LocalDate, RainbowLiveBindingRun> runByDay = new HashMap<>();
        runs.forEach(r -> runByDay.put(r.getDay(), r));
        Map<LocalDate, RainbowLiveRun> liveByDay = new HashMap<>();
        Map<LocalDate, Double> prices = new HashMap<>();
        liveRunRepository.findByAffectationAndDayBetweenOrderByDayAsc(affectation, firstDay, lastDay).forEach(r -> {
            liveByDay.put(r.getDay(), r);
            // cours de DÉCISION figé à 23:55 (actionPrice) ; à défaut la clôture enregistrée
            if (r.getSignal() != null) {
                Double decisionPrice = r.getSignal().getActionPrice() != null ? r.getSignal().getActionPrice() : r.getSignal().getClose();
                if (decisionPrice != null) {
                    prices.put(r.getDay(), decisionPrice);
                }
            }
        });

        // source des candles = celle du lien, sans fallback silencieux
        MarketDataSource source = first.getLive().getCandleSource() != null ? first.getLive().getCandleSource()
                : LiveCandleSource.of(binding.getWallet());
        DailyDataset daily = datasetEngine.getDailyDatasetForAsset(asset, RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES,
                clock.now(), source);
        if (daily.substituted()) {
            throw new IllegalStateException("Source " + source + " indisponible pour " + asset + " (substituée par "
                    + daily.source() + ") : pas de fallback silencieux, parité non calculée");
        }
        List<MarketData> candles = daily.dataset().getMarketDatas();
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        int startIdx = indexOfDay(ds, firstDay);
        int endIdx = lastIndexOnOrBefore(ds, lastDay);
        if (startIdx < 0 || endIdx < startIdx) {
            throw new IllegalStateException("Bougie D1 du " + firstDay + " absente de la fenêtre chargée (" + candles.size()
                    + " bougies, source " + source + ") : période hors fenêtre de " + RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES
                    + " jours");
        }
        int engineStart = Math.min(firstIndexOnOrAfter(ds, firstDay.minusMonths(configResolver.analysisWindowMonths(affectation))),
                startIdx);

        RainbowLiveLiveBlock firstLive = first.getLive();
        double cash0 = req.cash0() != null ? req.cash0() : nz(firstLive.getCashUsd());
        double qty0 = req.qty0() != null ? req.qty0() : nz(firstLive.getPositionQty());
        Double baseAmount = req.baseAmount() != null ? req.baseAmount() : binding.getBaseAmount();
        double bag = req.bagPercent() != null ? req.bagPercent() : binding.getBagPercent();
        ModulationSettings base = settingsService.effectiveSettings(user);
        ModulationSettings settings = new ModulationSettings(
                req.exposureMin() != null ? req.exposureMin() : base.exposureMin(),
                req.exposureMax() != null ? req.exposureMax() : base.exposureMax(),
                req.power() != null ? req.power() : base.power(),
                req.bias() != null ? req.bias() : base.bias(),
                req.regulationMarginPct() != null ? req.regulationMarginPct() : base.regulationMarginPct());
        RainbowModulationControl limits = modulationControl.current();
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(affectation);

        // ATH de référence = celui stocké en base (L3 du live), jour par jour ; jour sans ligne => ATH de la fenêtre D1
        Map<LocalDate, AthReference> athByDay = new HashMap<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            final LocalDate dayKey = d;
            athRepository.findFirstByAssetSymbolAndDayBeforeOrderByDayDesc(asset, dayKey)
                    .ifPresent(r -> athByDay.put(dayKey, new AthReference(r.getAthValue(), r.getAthTimeMillis())));
        }
        // réglages de modulation de l'époque = ceux du journal live (sauf surcharge demandée) ; jours sans journal : dernier connu
        boolean overridden = req.exposureMin() != null || req.exposureMax() != null || req.power() != null
                || req.bias() != null || req.regulationMarginPct() != null;
        Map<LocalDate, ModulationSettings> settingsByDay = overridden ? Map.of() : recordedSettings(runs, settings);
        // le live ré-amorce son état moteur après un trou de passes (aucun run la veille) : même chose ici
        int windowMonths = configResolver.analysisWindowMonths(affectation);
        java.util.Set<LocalDate> rebootstrap = new java.util.HashSet<>();
        for (RainbowLiveBindingRun run : runs) {
            if (!run.getDay().equals(firstDay) && !runByDay.containsKey(run.getDay().minusDays(1))) {
                rebootstrap.add(run.getDay());
            }
        }
        Map<LocalDate, double[]> pinned = new HashMap<>();
        runs.forEach(r -> pinned.put(r.getDay(), new double[]{nz(r.getLive().getCashUsd()), nz(r.getLive().getPositionQty())}));
        FullReplayEngine.Result freeResult = FullReplayEngine.run(input(ds, candles, cfg, engineStart, startIdx, endIdx, cash0, qty0,
                baseAmount, bag, settings, limits, prices, Map.of(), athByDay, settingsByDay, rebootstrap, windowMonths));
        FullReplayEngine.Result pinnedResult = FullReplayEngine.run(input(ds, candles, cfg, engineStart, startIdx, endIdx, cash0,
                qty0, baseAmount, bag, settings, limits, prices, pinned, athByDay, settingsByDay, rebootstrap, windowMonths));

        String effectiveHash = liveByDay.values().stream().min(java.util.Comparator.comparing(RainbowLiveRun::getDay))
                .map(RainbowLiveRun::getConfigHash).orElse(null);
        String currentHash = configResolver.effectiveHash(affectation);
        if (effectiveHash != null && !effectiveHash.equals(currentHash)) {
            notes.add("Config effective ACTUELLE (" + currentHash + ") différente de celle du 1er run (" + effectiveHash
                    + ") : le rejeu utilise la config actuelle.");
        }
        Map<String, Integer> causes = new LinkedHashMap<>();
        List<ParityReport.Row> rows = new ArrayList<>();
        ParityReport.Stats pinnedStats = compare(pinnedResult, runByDay, liveByDay, currentHash, causes, rows);
        ParityReport.Stats freeStats = compare(freeResult, runByDay, liveByDay, currentHash, new LinkedHashMap<>(), new ArrayList<>());
        boolean accepted = pinnedStats.matchPct() >= ACCEPTANCE_PCT && pinnedStats.unexplained() == 0;
        notes.add("ATH de référence = ATH stocké en base à la veille de chaque jour (" + athByDay.size() + "/" + (runs.size())
                + " jours trouvés ; les autres : ATH de la fenêtre D1). Amorçage moteur/automate moon sur la fenêtre D1 de "
                + RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES + " jours. Réglages de modulation = ceux du journal live de chaque jour"
                + (overridden ? " (surchargés par la requête)" : " (jours sans journal : dernier connu)") + ".");
        notes.add((pinnedResult.days().size() - runs.size()) + " jour(s) de la période sans run live (non comparés) sur "
                + pinnedResult.days().size() + " jours rejoués.");
        if (!rebootstrap.isEmpty()) {
            notes.add("Ré-amorçage de l'état moteur après trou de passes (comme le live) les " + new java.util.TreeSet<>(rebootstrap) + ".");
        }
        notes.add("Mono-actif : le cash et l'exposition du wallet réel (autres actifs) ne sont pas rejoués ici (lot 2).");
        log.info("Parité rejeu {} lien={} : {}/{} jours identiques (décision), {} inexpliqués", asset, binding.getId(),
                pinnedStats.matching(), pinnedStats.compared(), pinnedStats.unexplained());
        return new ParityReport(binding.getId(), asset, firstDay, lastDay, source.name(), cash0, qty0, pinnedStats, freeStats,
                pinnedResult.finalValue(), pinnedResult.fixedFinalValue(), ACCEPTANCE_PCT, accepted, rows, causes, notes);
    }

    /** Réglages de modulation par jour d'après le journal du bloc live ; jours sans journal : dernier connu (le 1er connu avant). */
    private static Map<LocalDate, ModulationSettings> recordedSettings(List<RainbowLiveBindingRun> runs, ModulationSettings fallback) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<LocalDate, ModulationSettings> parsed = new LinkedHashMap<>();
        for (RainbowLiveBindingRun run : runs) {
            String json = run.getLive().getModulation();
            if (json == null) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode n = mapper.readTree(json);
                if (n.hasNonNull("bandMin") && n.hasNonNull("bandMax") && n.hasNonNull("power") && n.hasNonNull("bias")) {
                    parsed.put(run.getDay(), new ModulationSettings(n.get("bandMin").asDouble(), n.get("bandMax").asDouble(),
                            n.get("power").asDouble(), n.get("bias").asDouble(),
                            n.hasNonNull("marginPct") ? n.get("marginPct").asDouble() : fallback.regulationMarginPct()));
                }
            } catch (java.io.IOException e) {
                log.warn("Parité : journal de modulation illisible le {} : {}", run.getDay(), e.getMessage());
            }
        }
        Map<LocalDate, ModulationSettings> out = new HashMap<>();
        ModulationSettings last = parsed.isEmpty() ? null : parsed.values().iterator().next();
        for (RainbowLiveBindingRun run : runs) {
            last = parsed.getOrDefault(run.getDay(), last);
            if (last != null) {
                out.put(run.getDay(), last);
            }
        }
        return out;
    }

    private static FullReplayEngine.Input input(RainbowAtrDataset ds, List<MarketData> candles, RainbowLiveTrendConfigs.Resolved cfg,
                                                int engineStart, int startIdx, int endIdx, double cash0, double qty0,
                                                Double baseAmount, double bag, ModulationSettings settings,
                                                RainbowModulationControl limits, Map<LocalDate, Double> prices,
                                                Map<LocalDate, double[]> pinned, Map<LocalDate, AthReference> athByDay,
                                                Map<LocalDate, ModulationSettings> settingsByDay,
                                                java.util.Set<LocalDate> rebootstrap, int windowMonths) {
        return new FullReplayEngine.Input(ds, candles, cfg.trend(), cfg.range(), cfg.sets(), engineStart, startIdx, endIdx,
                cash0, qty0, baseAmount, bag, settings, limits, prices, pinned, athByDay, settingsByDay, rebootstrap, windowMonths);
    }

    /** Compare jour par jour (live -> rejeu) et classe chaque écart. */
    private ParityReport.Stats compare(FullReplayEngine.Result result, Map<LocalDate, RainbowLiveBindingRun> runByDay,
                                       Map<LocalDate, RainbowLiveRun> liveByDay, String effectiveHash,
                                       Map<String, Integer> causes, List<ParityReport.Row> rows) {
        Map<LocalDate, FullReplayEngine.Day> simByDay = new HashMap<>();
        result.days().forEach(d -> simByDay.put(d.day(), d));
        int compared = 0;
        int matching = 0;
        int explained = 0;
        int unexplained = 0;
        for (Map.Entry<LocalDate, RainbowLiveBindingRun> entry : new java.util.TreeMap<>(runByDay).entrySet()) {
            LocalDate day = entry.getKey();
            RainbowLiveLiveBlock live = entry.getValue().getLive();
            FullReplayEngine.Day sim = simByDay.get(day);
            compared++;
            if (sim == null) {
                explained++;
                record(causes, rows, day, describe(live.getActionType(), live.getActionAmountUsdc(), live.getActionQuantity()),
                        "—", CAUSE_MISSING);
                continue;
            }
            if (same(live, sim)) {
                matching++;
                continue;
            }
            String cause = cause(live, sim, liveByDay.get(day), effectiveHash);
            if (CAUSE_UNKNOWN.equals(cause)) {
                unexplained++;
            } else {
                explained++;
            }
            record(causes, rows, day, describe(live.getActionType(), live.getActionAmountUsdc(), live.getActionQuantity()),
                    describe(sim.action(), sim.amountUsdc(), sim.quantity()), cause);
        }
        double pct = compared == 0 ? 0.0 : 100.0 * matching / compared;
        return new ParityReport.Stats(compared, matching, explained, unexplained, pct);
    }

    private static void record(Map<String, Integer> causes, List<ParityReport.Row> rows, LocalDate day, String expected,
                               String got, String cause) {
        causes.merge(cause, 1, Integer::sum);
        rows.add(new ParityReport.Row(day, expected, got, cause));
    }

    /** Même action ; pour un achat / une vente / un blocage, montant identique à {@link #AMOUNT_TOLERANCE} près (relatif). */
    private static boolean same(RainbowLiveLiveBlock live, FullReplayEngine.Day sim) {
        RainbowLiveAction expected = live.getActionType() == null ? RainbowLiveAction.NONE : live.getActionType();
        if (expected != sim.action()) {
            return false;
        }
        if (expected == RainbowLiveAction.NONE) {
            return true;
        }
        double want = nz(live.getActionAmountUsdc());
        double got = sim.amountUsdc() == null ? 0.0 : sim.amountUsdc();
        return Math.abs(want - got) <= Math.max(EPSILON, AMOUNT_TOLERANCE * Math.abs(want));
    }

    private String cause(RainbowLiveLiveBlock live, FullReplayEngine.Day sim, RainbowLiveRun liveRun, String effectiveHash) {
        if (liveRun == null || liveRun.getSignal() == null) {
            return CAUSE_MISSING;
        }
        RainbowLivePassBlock signal = liveRun.getSignal();
        RainbowLiveAction wanted = signal.getActionType() == null ? RainbowLiveAction.NONE : signal.getActionType();
        if (wanted != sim.wantedAction()) {
            if (Math.abs(sim.price() - sim.dayClose()) > 0.001 * sim.dayClose()) {
                return CAUSE_OPEN_CANDLE;
            }
            if (liveRun.getConfigHash() != null && !liveRun.getConfigHash().equals(effectiveHash)) {
                return CAUSE_CONFIG;
            }
            boolean regimeDiffers = (signal.getTrendRegime() != null && !signal.getTrendRegime().equals(sim.regime()))
                    || (signal.getActiveSet() != null && !signal.getActiveSet().equals(sim.setName()));
            return regimeDiffers ? CAUSE_REGIME : CAUSE_SIGNAL;
        }
        if (wanted != RainbowLiveAction.NONE) {
            return CAUSE_SIZING;
        }
        return CAUSE_UNKNOWN;
    }

    private static String describe(RainbowLiveAction action, Double amount, Double quantity) {
        RainbowLiveAction a = action == null ? RainbowLiveAction.NONE : action;
        if (a == RainbowLiveAction.NONE) {
            return "NONE";
        }
        return String.format(Locale.ROOT, "%s %.2f USDC / %.8f", a, amount == null ? 0.0 : amount, quantity == null ? 0.0 : quantity);
    }

    private RainbowLiveBinding resolveBinding(User user, Request req) {
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        String asset = req.asset() != null ? req.asset().toUpperCase(Locale.ROOT) : "BTC";
        RainbowLiveBinding binding = bindings.stream()
                .filter(b -> req.bindingId() != null ? b.getId().equals(req.bindingId()) : b.getAssetSymbol().equals(asset))
                .findFirst()
                .orElseThrow(() -> new ExpertApiException(ExpertApiException.Code.BINDING_NOT_FOUND,
                        "Aucun lien " + (req.bindingId() != null ? "#" + req.bindingId() : asset) + " pour cet utilisateur"));
        if (binding.getAffectation() == null) {
            throw new IllegalStateException("Le lien " + binding.getId() + " n'a pas d'affectation");
        }
        return binding;
    }

    private static int indexOfDay(RainbowAtrDataset ds, LocalDate day) {
        for (int i = 0; i < ds.size(); i++) {
            if (dayOf(ds.time(i)).equals(day)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOnOrBefore(RainbowAtrDataset ds, LocalDate day) {
        int last = -1;
        for (int i = 0; i < ds.size(); i++) {
            if (!dayOf(ds.time(i)).isAfter(day)) {
                last = i;
            }
        }
        return last;
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

    private static LocalDate dayOf(long millis) {
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
