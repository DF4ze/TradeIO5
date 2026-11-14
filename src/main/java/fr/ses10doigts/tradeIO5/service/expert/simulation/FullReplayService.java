package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
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
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthReference;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Parité du rejeu d'un WALLET ({@link MultiLinkReplayEngine} : tous les liens du wallet partagent cash et exposition) avec les
 * {@link RainbowLiveBindingRun} / {@link RainbowLiveRun} de la prod (copie en base locale). Source des candles = celle du lien (sans
 * fallback silencieux), fenêtre D1 live ({@link RainbowLiveDefaultPresets#D1_LOOKBACK_CANDLES}). Défauts : liens = tous ceux du
 * wallet (ou le seul lien demandé), période de chaque lien = de son 1er run exploitable au dernier, {@code C0} = cash du snapshot le
 * plus ancien du wallet, position initiale de chaque actif = snapshot du 1er run de son lien, réglages = ceux de l'utilisateur
 * (journal live jour par jour). Le cours de décision du jour est celui ENREGISTRÉ par le live (23:55), pas la clôture finale.
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
    private static final String CAUSE_CASH = "cash commun : INSUFFICIENT_CASH différent (ordre de service/priorité, cash réservé, wallet du jour)";
    private static final String CAUSE_EXPOSURE_UNKNOWN = "exposition inconnue d'un côté (actif détenu sans prix ce jour-là : bande non modulée)";
    private static final String CAUSE_SIZING = "dimensionnement (actif détenu sans lien non rejoué, réglages de modulation modifiés depuis)";
    private static final String CAUSE_MISSING = "jour manquant (pas de run live ou bougie absente)";
    private static final String CAUSE_UNKNOWN = "inexpliqué";

    /**
     * Surcharges facultatives (tout {@code null} ⇒ valeurs du lien / de l'utilisateur / du 1er run). {@code bindingId} ou {@code asset} ⇒
     * un seul lien (mono-lien) ; sinon tous les liens du wallet {@code walletId} (défaut : wallet du lien le plus prioritaire).
     * {@code qty0}, {@code baseAmount} et {@code bagPercent} ne s'appliquent qu'en mono-lien.
     */
    public record Request(Long bindingId, String asset, Long walletId, LocalDate from, LocalDate to, Double cash0, Double qty0,
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

    /** Données d'un lien nécessaires au rejeu et à la comparaison. */
    private record LinkData(RainbowLiveBinding binding, LiveSlot slot, List<RainbowLiveBindingRun> runs,
                            Map<LocalDate, RainbowLiveBindingRun> runByDay, Map<LocalDate, RainbowLiveRun> liveByDay,
                            MarketDataSource source, LinkSignalReplay.Config signal, double qty0, String currentHash) {
    }

    @Transactional(readOnly = true)
    public ParityReport parity(User user, Request request) {
        Request req = request == null
                ? new Request(null, null, null, null, null, null, null, null, null, null, null, null, null, null) : request;
        List<RainbowLiveBinding> bindings = resolveBindings(user, req);
        boolean mono = bindings.size() == 1;
        List<String> notes = new ArrayList<>();
        LocalDate today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate to = req.to() != null ? req.to() : today;
        LocalDate from = req.from() != null ? req.from() : LocalDate.of(2000, 1, 1);

        ModulationSettings base = settingsService.effectiveSettings(user);
        ModulationSettings settings = new ModulationSettings(
                req.exposureMin() != null ? req.exposureMin() : base.exposureMin(),
                req.exposureMax() != null ? req.exposureMax() : base.exposureMax(),
                req.power() != null ? req.power() : base.power(),
                req.bias() != null ? req.bias() : base.bias(),
                req.regulationMarginPct() != null ? req.regulationMarginPct() : base.regulationMarginPct());
        boolean overridden = req.exposureMin() != null || req.exposureMax() != null || req.power() != null
                || req.bias() != null || req.regulationMarginPct() != null;
        RainbowModulationControl limits = modulationControl.current();

        // liens exploitables (au moins un run live OK) ; les autres sont signalés
        List<LinkData> links = new ArrayList<>();
        for (RainbowLiveBinding binding : bindings) {
            List<RainbowLiveBindingRun> runs = bindingRunRepository.findByBindingAndDayBetweenOrderByDayAsc(binding, from, to).stream()
                    .filter(r -> r.getLive() != null && r.getLive().getStatus() == PortfolioStatus.OK).toList();
            if (runs.isEmpty()) {
                notes.add("Lien " + binding.getId() + " (" + binding.getAssetSymbol() + ") sans run live exploitable entre " + from
                        + " et " + to + " : ignoré (base locale non synchronisée ? operations/dev-sync.md).");
                continue;
            }
            links.add(loadLink(binding, runs, mono ? req : null));
        }
        if (links.isEmpty()) {
            throw new IllegalStateException("Aucun run live exploitable pour les liens demandés entre " + from + " et " + to
                    + " : base locale non synchronisée ? (operations/dev-sync.md)");
        }
        links.sort((a, b) -> LiveSlot.SERVICE_ORDER.compare(a.slot(), b.slot()));
        Long walletId = links.getFirst().slot().walletId();

        // période : chaque lien du 1er au dernier de ses runs ; C0 = cash du snapshot le plus ancien du wallet
        LocalDate firstDay = links.stream().map(l -> l.runs().getFirst().getDay()).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate lastDay = links.stream().map(l -> l.runs().getLast().getDay()).max(Comparator.naturalOrder()).orElseThrow();
        LinkData earliest = links.stream().min(Comparator.comparing((LinkData l) -> l.runs().getFirst().getDay())).orElseThrow();
        double cash0 = req.cash0() != null ? req.cash0() : nz(earliest.runs().getFirst().getLive().getCashUsd());

        // réglages de modulation de l'époque = journal live (tous liens confondus), sauf surcharge demandée
        List<RainbowLiveBindingRun> allRuns = links.stream().flatMap(l -> l.runs().stream())
                .sorted(Comparator.comparing(RainbowLiveBindingRun::getDay)).toList();
        Map<LocalDate, ModulationSettings> settingsByDay = overridden ? Map.of() : recordedSettings(allRuns, settings);
        Map<LocalDate, MultiLinkReplayEngine.Pin> pinned = pins(links);

        List<MultiLinkReplayEngine.Link> engineLinks = links.stream()
                .map(l -> new MultiLinkReplayEngine.Link(l.slot(), l.signal(), l.qty0())).toList();
        MultiLinkReplayEngine.Result free = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(engineLinks, cash0, settings,
                limits, settingsByDay, Map.of(), Map.of(), Map.of()));
        MultiLinkReplayEngine.Result pinnedResult = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(engineLinks, cash0,
                settings, limits, settingsByDay, pinned, Map.of(), Map.of()));

        Map<String, Integer> causes = new LinkedHashMap<>();
        List<ParityReport.Row> rows = new ArrayList<>();
        List<ParityReport.LinkReport> reports = new ArrayList<>();
        ParityReport.Stats totalPinned = ParityReport.Stats.of(0, 0, 0, 0);
        ParityReport.Stats totalFree = ParityReport.Stats.of(0, 0, 0, 0);
        boolean accepted = true;
        for (LinkData link : links) {
            Long id = link.slot().bindingId();
            ParityReport.Stats ps = compare(link, pinnedResult, causes, rows);
            ParityReport.Stats fs = compare(link, free, new LinkedHashMap<>(), new ArrayList<>());
            boolean linkOk = ps.matchPct() >= ACCEPTANCE_PCT && ps.unexplained() == 0;
            accepted &= linkOk;
            totalPinned = totalPinned.plus(ps);
            totalFree = totalFree.plus(fs);
            reports.add(new ParityReport.LinkReport(id, link.slot().assetSymbol(), link.slot().priority(),
                    link.runs().getFirst().getDay(), link.runs().getLast().getDay(), link.source().name(), link.qty0(), ps, fs, linkOk));
            String effectiveHash = link.liveByDay().values().stream().min(Comparator.comparing(RainbowLiveRun::getDay))
                    .map(RainbowLiveRun::getConfigHash).orElse(null);
            if (effectiveHash != null && !effectiveHash.equals(link.currentHash())) {
                notes.add(link.slot().assetSymbol() + " : config effective ACTUELLE (" + link.currentHash()
                        + ") différente de celle du 1er run (" + effectiveHash + ") : le rejeu utilise la config actuelle.");
            }
        }
        accepted &= totalPinned.matchPct() >= ACCEPTANCE_PCT && totalPinned.unexplained() == 0;
        notes.add("Ordre de service = priorité, rang d'actif, identifiant du lien (LiveSlot.SERVICE_ORDER, partagé avec le live). "
                + "Achat accepté ⇒ cash réservé ; ventes non créditées le jour même ; wallet mis à jour après tous les liens du jour.");
        notes.add("ATH = ATH stocké en base à la veille de chaque jour ; amorçage moteur sur la fenêtre D1 de "
                + RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES + " jours. Réglages de modulation = journal live de chaque jour"
                + (overridden ? " (surchargés par la requête)" : " (jours sans journal : dernier connu)") + ".");
        notes.add("Période par lien (1er → dernier run) ; wallet recalé sur les snapshots du jour : cash, quantité de chaque actif, "
                + "cash déjà réservé par les liens servis avant. Actifs détenus sans lien : non reconstituables depuis les snapshots "
                + "(exposition rejouée sur les seuls liens du wallet).");
        if (links.size() < bindings.size() || bindings.size() < bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user)
                .stream().filter(b -> b.getWallet().getId().equals(walletId)).count()) {
            notes.add("Rejeu partiel : tous les liens du wallet ne sont pas rejoués (lien demandé seul ou sans run).");
        }
        log.info("Parité rejeu wallet={} ({} lien(s)) : {}/{} jours-liens identiques (décision), {} inexpliqués", walletId,
                links.size(), totalPinned.matching(), totalPinned.compared(), totalPinned.unexplained());
        return new ParityReport(walletId, firstDay, lastDay, cash0, totalPinned, totalFree, pinnedResult.finalValue(),
                pinnedResult.fixedFinalValue(), ACCEPTANCE_PCT, accepted, reports, rows, causes, notes);
    }

    /** Charge candles, config, ATH stocké, ré-amorçages et position initiale d'un lien. */
    private LinkData loadLink(RainbowLiveBinding binding, List<RainbowLiveBindingRun> runs, Request mono) {
        AssetAffectation affectation = binding.getAffectation();
        String asset = binding.getAssetSymbol();
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
                    + " bougies, source " + source + ") pour " + asset + " : période hors fenêtre de "
                    + RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES + " jours");
        }
        int windowMonths = configResolver.analysisWindowMonths(affectation);
        int engineStart = Math.min(firstIndexOnOrAfter(ds, firstDay.minusMonths(windowMonths)), startIdx);
        // ATH de référence = celui stocké en base (L3 du live), jour par jour ; jour sans ligne => ATH de la fenêtre D1
        Map<LocalDate, AthReference> athByDay = new HashMap<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            final LocalDate dayKey = d;
            athRepository.findFirstByAssetSymbolAndDayBeforeOrderByDayDesc(asset, dayKey)
                    .ifPresent(r -> athByDay.put(dayKey, new AthReference(r.getAthValue(), r.getAthTimeMillis())));
        }
        // le live ré-amorce son état moteur après un trou de passes (aucun run la veille) : même chose ici
        Set<LocalDate> rebootstrap = new HashSet<>();
        for (RainbowLiveBindingRun run : runs) {
            if (!run.getDay().equals(firstDay) && !runByDay.containsKey(run.getDay().minusDays(1))) {
                rebootstrap.add(run.getDay());
            }
        }
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(affectation);
        Double baseAmount = mono != null && mono.baseAmount() != null ? mono.baseAmount() : binding.getBaseAmount();
        double bag = mono != null && mono.bagPercent() != null ? mono.bagPercent() : binding.getBagPercent();
        double qty0 = mono != null && mono.qty0() != null ? mono.qty0() : nz(first.getLive().getPositionQty());
        LiveSlot slot = new LiveSlot(binding.getId(), affectation.getId(), asset, binding.getWallet().getId(), binding.getPriority(),
                bag, source);
        LinkSignalReplay.Config signal = new LinkSignalReplay.Config(ds, candles, cfg.trend(), cfg.range(), cfg.sets(), engineStart,
                startIdx, endIdx, baseAmount, prices, athByDay, rebootstrap, windowMonths);
        return new LinkData(binding, slot, runs, runByDay, liveByDay, source, signal, qty0, configResolver.effectiveHash(affectation));
    }

    /**
     * Wallet imposé par jour d'après les snapshots live : cash du wallet, quantité de chaque actif (snapshot de son lien) et cash
     * déjà réservé par les liens servis avant chaque lien.
     */
    private static Map<LocalDate, MultiLinkReplayEngine.Pin> pins(List<LinkData> links) {
        Map<LocalDate, Double> cash = new HashMap<>();
        Map<LocalDate, Map<String, Double>> qty = new HashMap<>();
        Map<LocalDate, Map<Long, Double>> reserved = new HashMap<>();
        for (LinkData link : links) {
            for (RainbowLiveBindingRun run : link.runs()) {
                LocalDate day = run.getDay();
                RainbowLiveLiveBlock live = run.getLive();
                cash.putIfAbsent(day, nz(live.getCashUsd()));
                qty.computeIfAbsent(day, d -> new HashMap<>()).put(link.slot().assetSymbol(), nz(live.getPositionQty()));
                reserved.computeIfAbsent(day, d -> new HashMap<>()).put(link.slot().bindingId(), nz(live.getCashReserved()));
            }
        }
        Map<LocalDate, MultiLinkReplayEngine.Pin> pins = new HashMap<>();
        cash.forEach((day, c) -> pins.put(day, new MultiLinkReplayEngine.Pin(c, qty.get(day), reserved.get(day))));
        return pins;
    }

    /** Réglages de modulation par jour d'après le journal du bloc live ; jours sans journal : dernier connu (le 1er connu avant). */
    private static Map<LocalDate, ModulationSettings> recordedSettings(List<RainbowLiveBindingRun> runs, ModulationSettings fallback) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<LocalDate, ModulationSettings> parsed = new TreeMap<>();
        for (RainbowLiveBindingRun run : runs) {
            String json = run.getLive().getModulation();
            if (json == null || parsed.containsKey(run.getDay())) {
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
        for (LocalDate day : new TreeSet<>(runs.stream().map(RainbowLiveBindingRun::getDay).toList())) {
            last = parsed.getOrDefault(day, last);
            if (last != null) {
                out.put(day, last);
            }
        }
        return out;
    }

    /** Compare jour par jour (live -> rejeu) pour UN lien et classe chaque écart. */
    private ParityReport.Stats compare(LinkData link, MultiLinkReplayEngine.Result result, Map<String, Integer> causes,
                                       List<ParityReport.Row> rows) {
        Long id = link.slot().bindingId();
        String asset = link.slot().assetSymbol();
        Map<LocalDate, MultiLinkReplayEngine.LinkDay> simByDay = new HashMap<>();
        result.linkDays().stream().filter(d -> d.bindingId().equals(id)).forEach(d -> simByDay.put(d.day(), d));
        String effectiveHash = link.currentHash();
        int compared = 0;
        int matching = 0;
        int explained = 0;
        int unexplained = 0;
        for (Map.Entry<LocalDate, RainbowLiveBindingRun> entry : new TreeMap<>(link.runByDay()).entrySet()) {
            LocalDate day = entry.getKey();
            RainbowLiveLiveBlock live = entry.getValue().getLive();
            MultiLinkReplayEngine.LinkDay sim = simByDay.get(day);
            compared++;
            if (sim == null) {
                explained++;
                record(causes, rows, id, asset, day, describe(live.getActionType(), live.getActionAmountUsdc(), live.getActionQuantity()),
                        "—", CAUSE_MISSING);
                continue;
            }
            if (same(live, sim)) {
                matching++;
                continue;
            }
            String cause = cause(live, sim, link.liveByDay().get(day), effectiveHash);
            if (CAUSE_UNKNOWN.equals(cause)) {
                unexplained++;
            } else {
                explained++;
            }
            record(causes, rows, id, asset, day, describe(live.getActionType(), live.getActionAmountUsdc(), live.getActionQuantity()),
                    describe(sim.action(), sim.amountUsdc(), sim.quantity()), cause);
        }
        return ParityReport.Stats.of(compared, matching, explained, unexplained);
    }

    private static void record(Map<String, Integer> causes, List<ParityReport.Row> rows, Long id, String asset, LocalDate day,
                               String expected, String got, String cause) {
        causes.merge(cause, 1, Integer::sum);
        rows.add(new ParityReport.Row(id, asset, day, expected, got, cause));
    }

    /** Même action ; pour un achat / une vente / un blocage, montant identique à {@link #AMOUNT_TOLERANCE} près (relatif). */
    private static boolean same(RainbowLiveLiveBlock live, MultiLinkReplayEngine.LinkDay sim) {
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

    private String cause(RainbowLiveLiveBlock live, MultiLinkReplayEngine.LinkDay sim, RainbowLiveRun liveRun, String effectiveHash) {
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
            if (live.getBlockReason() == LiveBlockReason.INSUFFICIENT_CASH || sim.blockReason() == LiveBlockReason.INSUFFICIENT_CASH) {
                return CAUSE_CASH;
            }
            boolean liveUnknown = live.getModulation() != null && live.getModulation().contains("\"exposureStatus\":\"UNKNOWN\"");
            boolean simUnknown = sim.modulation() != null && "UNKNOWN".equals(sim.modulation().get("exposureStatus"));
            return liveUnknown != simUnknown ? CAUSE_EXPOSURE_UNKNOWN : CAUSE_SIZING;
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

    /** Un lien (bindingId/asset) ou tous ceux du wallet ; liens triés dans l'ordre de service. */
    private List<RainbowLiveBinding> resolveBindings(User user, Request req) {
        List<RainbowLiveBinding> all = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream()
                .filter(b -> b.getAffectation() != null).toList();
        if (req.bindingId() != null || req.asset() != null) {
            String asset = req.asset() != null ? req.asset().toUpperCase(Locale.ROOT) : null;
            RainbowLiveBinding binding = all.stream()
                    .filter(b -> req.bindingId() != null ? b.getId().equals(req.bindingId()) : b.getAssetSymbol().equals(asset)
                            && (req.walletId() == null || b.getWallet().getId().equals(req.walletId())))
                    .findFirst()
                    .orElseThrow(() -> new ExpertApiException(ExpertApiException.Code.BINDING_NOT_FOUND,
                            "Aucun lien " + (req.bindingId() != null ? "#" + req.bindingId() : asset) + " pour cet utilisateur"));
            return List.of(binding);
        }
        if (all.isEmpty()) {
            throw new ExpertApiException(ExpertApiException.Code.BINDING_NOT_FOUND, "Aucun lien pour cet utilisateur");
        }
        Long walletId = req.walletId() != null ? req.walletId() : all.stream()
                .min(Comparator.comparingInt(RainbowLiveBinding::getPriority)
                        .thenComparingInt(b -> RainbowLiveDefaultPresets.ASSETS.indexOf(b.getAssetSymbol())).thenComparing(RainbowLiveBinding::getId))
                .orElseThrow().getWallet().getId();
        List<RainbowLiveBinding> inWallet = all.stream().filter(b -> b.getWallet().getId().equals(walletId)).toList();
        if (inWallet.isEmpty()) {
            throw new ExpertApiException(ExpertApiException.Code.BINDING_NOT_FOUND, "Aucun lien pour le wallet #" + walletId);
        }
        return inWallet;
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
