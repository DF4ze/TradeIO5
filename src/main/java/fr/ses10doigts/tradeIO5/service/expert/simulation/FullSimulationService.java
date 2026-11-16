package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ResultLineDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.DrawdownDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.DrawdownMeasureDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.DrawdownVerdictDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullAssetChartDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullLinkParamsDto;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserModulationService;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullDefaultsDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullLinkMetaDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullMetaDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullOrderDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullSeriesDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullSimulationResultDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.LinkDefaultDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.LinkOverride;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.ModulationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.ModulationLimitsDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.WalletChoiceDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.WalletValueDto;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicies;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicy;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Simulateur complet de l'Espace Expert (type {@link SimulationType#FULL}) : rejeu LIBRE des liens d'un wallet de l'utilisateur
 * ({@link MultiLinkReplayEngine} : cash et exposition communs, frais, minimum d'ordre, carry), même chaîne que le live, sur une plage
 * choisie. Différences assumées avec le rejeu de parité ({@link FullReplayService}, calé sur les runs live) : période = plage
 * demandée, cours de décision = clôture D1 du jour (le 23:55 n'existe pas dans l'historique), ATH et automate moon calculés sur
 * tout l'historique chargé avec démarrage à froid en début de plage (comme {@code SimulationEngine}), réglages de modulation
 * constants sur la plage, position de départ nulle (tout en USDC), bougies de la source du lien SANS fallback silencieux.
 * Le moteur, le signal ({@link LinkSignalReplay}), les coûts ({@link ReplayCostsFactory}) et les épisodes ({@link EpisodeSeries})
 * sont ceux du rejeu de parité. Aucune écriture, aucun ordre.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FullSimulationService {

    /** Identifiant d'« actif » des simulations de ce type (le wallet entier). */
    public static final String WHOLE_WALLET = "*";

    private static final double EPSILON = 1e-9;

    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveBindingRunRepository bindingRunRepository;
    private final RainbowLiveRunRepository liveRunRepository;
    private final AffectationConfigResolver configResolver;
    private final DailyDataLoader loader;
    private final DomainClock clock;
    private final UserTradingSettingsService settingsService;
    private final RainbowModulationControlService modulationControl;
    private final ReplayCostsFactory costsFactory;
    private final EpisodeResultStore episodeStore;
    private final SimulationEligibility eligibility;
    private final UserModulationService userModulation;

    /** Lien retenu par la validation synchrone ; {@code baseAmount} / {@code bagPercent} nuls = ceux du lien. */
    public record PlannedLink(Long bindingId, String asset, MarketDataSource source, int requiredPoints, Double baseAmount,
                              Double bagPercent) {
    }

    /** Demande validée : tout est résolu sauf les données (bougies, wallet réel, coûts). */
    public record Plan(Long walletId, LocalDate from, LocalDate to, Double cash0, ModulationSettings settings, boolean withCosts,
                       List<PlannedLink> links, PolicySpec policy) {

        /** Sans politique de modulation. */
        public Plan(Long walletId, LocalDate from, LocalDate to, Double cash0, ModulationSettings settings, boolean withCosts,
                    List<PlannedLink> links) {
            this(walletId, from, to, cash0, settings, withCosts, links, null);
        }
    }

    /**
     * Données chargées d'une demande (liens, bougies, capital, coûts) : un même chargement peut alimenter plusieurs rejeux
     * ({@link PolicyCompareService}). {@code metas} / {@code dsByLink} servent à l'assemblage du résultat complet.
     */
    record Loaded(List<MultiLinkReplayEngine.Link> links, List<FullLinkMetaDto> metas, Map<Long, RainbowAtrDataset> dsByLink,
                  double cash0, MultiLinkReplayEngine.Costs costs, List<String> warnings) {
    }

    /** Rappels du thread de calcul : changements d'état de la simulation et annulation. */
    public record Hooks(Runnable loading, Runnable computing, BooleanSupplier cancelled) {
    }

    // ---------------------------------------------------------------- défauts du formulaire

    /** Ce que l'écran préremplit : wallet, valeur réelle, liens, modulation effective et bornes admin (lecture base seule). */
    @Transactional(readOnly = true)
    public FullDefaultsDto defaults(ExpertContext ctx, Long walletId) {
        User user = ctx.user();
        List<RainbowLiveBinding> all = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        List<WalletChoiceDto> wallets = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (RainbowLiveBinding b : all) {
            if (seen.add(b.getWallet().getId())) {
                wallets.add(new WalletChoiceDto(b.getWallet().getId(), b.getWallet().getName(), exchangeOf(b.getWallet())));
            }
        }
        RainbowModulationControl limits = modulationControl.current();
        ModulationSettings m = settingsService.effectiveSettings(user);
        LocalDate to = lastClosedDay();
        ModulationLimitsDto limitsDto = new ModulationLimitsDto(limits.getRegulationMarginPct().doubleValue(),
                limits.getBiasMax().doubleValue(), limits.getPowerMin().doubleValue(), limits.getPowerMax().doubleValue(),
                limits.getMinBandWidthPts().doubleValue());
        ModulationDto modulation = new ModulationDto(m.exposureMin(), m.exposureMax(), m.power(), m.bias(), m.regulationMarginPct());
        if (all.isEmpty()) {
            return new FullDefaultsDto(null, null, null, List.of(), null, List.of(), modulation, limitsDto,
                    to.minusMonths(ExpertSimulationSettings.DEFAULT_RANGE_MONTHS), to, true);
        }
        Long chosen = pickWallet(all, walletId);
        List<RainbowLiveBinding> inWallet = all.stream().filter(b -> b.getWallet().getId().equals(chosen)).toList();
        Wallet wallet = inWallet.getFirst().getWallet();
        List<LinkDefaultDto> links = inWallet.stream().map(b -> {
            String reason = unusableReason(b);
            MarketDataSource source = LiveCandleSource.of(b.getWallet());
            return new LinkDefaultDto(b.getId(), b.getAssetSymbol(), b.getAffectation() == null ? null : b.getAffectation().displayName(),
                    source == null ? null : source.name(), b.getPriority(), b.getBagPercent(), b.getBaseAmount(), reason == null, reason);
        }).toList();
        return new FullDefaultsDto(chosen, wallet.getName(), exchangeOf(wallet), wallets, walletValue(inWallet), links, modulation,
                limitsDto, to.minusMonths(ExpertSimulationSettings.DEFAULT_RANGE_MONTHS), to, true);
    }

    /**
     * Valeur du wallet réel d'après le dernier snapshot live de chaque lien : cash = celui du snapshot le plus récent, position de
     * chaque actif = celle de son lien, au dernier cours enregistré par le live. Un actif sans cours enregistré est ignoré.
     */
    private WalletValueDto walletValue(List<RainbowLiveBinding> bindings) {
        List<RainbowLiveBindingRun> latest = bindings.isEmpty() ? List.of() : bindingRunRepository.latestByBindings(bindings).stream()
                .filter(r -> r.getLive() != null && r.getLive().hasLiveSnapshot()).toList();
        if (latest.isEmpty()) {
            return new WalletValueDto(0.0, 0.0, 0.0, null, Map.of());
        }
        RainbowLiveBindingRun newest = latest.stream().max(Comparator.comparing(RainbowLiveBindingRun::getDay)
                .thenComparing(r -> r.getLive().getFetchedAt() == null ? Instant.EPOCH : r.getLive().getFetchedAt())).orElseThrow();
        double cash = nz(newest.getLive().getCashUsd());
        List<AssetAffectation> affectations = bindings.stream().map(RainbowLiveBinding::getAffectation)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Double> priceByAffectation = new HashMap<>();
        if (!affectations.isEmpty()) {
            for (RainbowLiveRun run : liveRunRepository.latestByAffectations(affectations)) {
                if (run.getSignal() == null) {
                    continue;
                }
                Double p = run.getSignal().getActionPrice() != null ? run.getSignal().getActionPrice() : run.getSignal().getClose();
                if (p != null && p > 0) {
                    priceByAffectation.put(run.getAffectation().getId(), p);
                }
            }
        }
        Map<String, Double> quantities = new LinkedHashMap<>();
        double assets = 0.0;
        for (RainbowLiveBindingRun run : latest) {
            RainbowLiveBinding b = run.getBinding();
            double qty = nz(run.getLive().getPositionQty());
            Double price = b.getAffectation() == null ? null : priceByAffectation.get(b.getAffectation().getId());
            quantities.put(b.getAssetSymbol(), qty);
            if (price != null) {
                assets += qty * price;
            }
        }
        return new WalletValueDto(cash, assets, cash + assets, newest.getDay(), quantities);
    }

    // ---------------------------------------------------------------- validation synchrone

    /** Valide la demande (champs, liens, wallet, historique atteignable) ; aucune donnée de marché n'est lue. */
    @Transactional(readOnly = true)
    public Plan resolve(ExpertContext ctx, FullRequest request) {
        FullRequest req = request == null ? new FullRequest(null, null, null, null, null, null, null, null, null, null, null, null) : request;
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.cash0() != null && !(Double.isFinite(req.cash0()) && req.cash0() > 0)) {
            errors.put("cash0", "doit être un montant > 0");
        }
        LocalDate to = req.to() != null && !req.to().isAfter(lastClosedDay()) ? req.to() : lastClosedDay();
        LocalDate from = req.from() != null ? req.from() : to.minusMonths(ExpertSimulationSettings.DEFAULT_RANGE_MONTHS);
        if (from.isAfter(to)) {
            errors.put("from", "doit précéder la fin de plage (to)");
        }
        Map<Long, LinkOverride> overrides = new HashMap<>();
        if (req.links() != null) {
            for (LinkOverride ov : req.links()) {
                if (ov == null || ov.bindingId() == null) {
                    errors.put("links", "bindingId obligatoire pour chaque lien");
                    continue;
                }
                overrides.put(ov.bindingId(), ov);
                if (ov.baseAmount() != null && !(Double.isFinite(ov.baseAmount()) && ov.baseAmount() > 0)) {
                    errors.put("links[" + ov.bindingId() + "].baseAmount", "doit être > 0");
                }
                if (ov.bagPercent() != null && !(Double.isFinite(ov.bagPercent()) && ov.bagPercent() > 0 && ov.bagPercent() <= 100)) {
                    errors.put("links[" + ov.bindingId() + "].bagPercent", "doit être dans ]0 ; 100]");
                }
            }
        }
        ModulationSettings base = settingsService.effectiveSettings(ctx.user());
        ModulationSettings settings = new ModulationSettings(
                req.exposureMin() != null ? req.exposureMin() : base.exposureMin(),
                req.exposureMax() != null ? req.exposureMax() : base.exposureMax(),
                req.power() != null ? req.power() : base.power(),
                req.bias() != null ? req.bias() : base.bias(),
                req.regulationMarginPct() != null ? req.regulationMarginPct() : base.regulationMarginPct());
        try {
            settingsService.validateModulation(settings.exposureMin(), settings.exposureMax(), settings.power(), settings.bias(),
                    settings.regulationMarginPct());
        } catch (IllegalArgumentException e) {
            errors.put("modulation", e.getMessage());
        }
        if (req.policy() != null) {
            try {
                ModulationPolicies.from(req.policy());
            } catch (ExpertValidationException e) {
                errors.putAll(e.getFields());
            }
        }
        List<RainbowLiveBinding> all = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user());
        if (all.isEmpty()) {
            throw new ExpertApiException(ExpertApiException.Code.BINDING_NOT_FOUND, "Aucun lien : reliez un actif à un wallet avant de simuler");
        }
        Long walletId = pickWallet(all, req.walletId());
        if (req.walletId() != null && all.stream().noneMatch(b -> b.getWallet().getId().equals(req.walletId()))) {
            throw new ExpertApiException(ExpertApiException.Code.BINDING_NOT_FOUND, "Aucun lien pour le wallet #" + req.walletId());
        }
        Map<Long, RainbowLiveBinding> byId = new HashMap<>();
        all.forEach(b -> byId.put(b.getId(), b));
        for (Long id : overrides.keySet()) {
            RainbowLiveBinding b = byId.get(id);
            if (b == null) {
                errors.put("links[" + id + "]", "lien inconnu");
            } else if (!b.getWallet().getId().equals(walletId)) {
                errors.put("links[" + id + "]", "lien d'un autre wallet (cash commun : un seul wallet par simulation)");
            }
        }
        List<PlannedLink> planned = new ArrayList<>();
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        for (RainbowLiveBinding b : all) {
            if (!b.getWallet().getId().equals(walletId)) {
                continue;
            }
            LinkOverride ov = overrides.get(b.getId());
            if (ov != null && Boolean.FALSE.equals(ov.enabled())) {
                continue;
            }
            String reason = unusableReason(b);
            if (reason != null) {
                if (ov != null) {
                    errors.put("links[" + b.getId() + "]", b.getAssetSymbol() + " : " + reason);
                }
                continue;
            }
            MarketDataSource source = LiveCandleSource.of(b.getWallet());
            int required = requiredPoints(b.getAffectation()) + Math.max(0, days);
            if (!errors.containsKey("from") && eligibility.unreachable(b.getAssetSymbol(), source, required)) {
                throw insufficient(required, 0, source == null ? null : source.name());
            }
            planned.add(new PlannedLink(b.getId(), b.getAssetSymbol(), source, required, ov == null ? null : ov.baseAmount(),
                    ov == null ? null : ov.bagPercent()));
        }
        if (planned.isEmpty() && errors.isEmpty()) {
            errors.put("links", "aucun lien actif sélectionné (affectation complète requise)");
        }
        if (!errors.isEmpty()) {
            throw new ExpertValidationException(errors);
        }
        return new Plan(walletId, from, to, req.cash0(), settings, req.withCosts() == null || req.withCosts(), planned, req.policy());
    }

    // ---------------------------------------------------------------- calcul

    /**
     * Charge les bougies de chaque lien (sa source, sans fallback), rejoue le wallet et assemble le résultat. Appelé par le thread de
     * calcul de {@link ExpertSimulationService} ; renvoie {@code null} si la simulation a été annulée.
     * Transaction en écriture (pas readOnly) : le chargement des bougies alimente le cache DB des candles.
     */
    @Transactional
    public FullSimulationResultDto compute(User user, Plan plan, Hooks hooks) {
        Loaded data = load(user, plan, hooks);
        if (data == null) {
            return null;
        }
        List<MultiLinkReplayEngine.Link> links = data.links();
        List<String> warnings = data.warnings();
        double cash0 = data.cash0();
        MultiLinkReplayEngine.Costs costs = data.costs();
        Map<Long, RainbowAtrDataset> dsByLink = data.dsByLink();
        List<FullLinkMetaDto> metas = data.metas();
        ModulationPolicy policy = plan.policy() == null ? null : ModulationPolicies.from(plan.policy());
        hooks.computing().run();
        MultiLinkReplayEngine.Result result = run(data, plan.settings(), policy);
        if (result.walletDays().isEmpty()) {
            throw new ExpertValidationException("from", "aucun jour rejouable dans la plage (SMA / ATR pas encore définis)");
        }
        boolean withCosts = costs != null;
        warnings.add("Cours de décision = clôture D1 du jour (le live décide à 23:55 sur la bougie ouverte) : écart assumé vs la prod.");
        warnings.add(policy == null
                ? "Départ 100 % USDC, sans apport ; réglages de modulation constants sur la plage ; actifs détenus sans lien exclus."
                : "Départ 100 % USDC, sans apport ; réglages de modulation pilotés par la politique " + policy.label()
                + " (bornes admin appliquées) ; actifs détenus sans lien exclus.");
        if (withCosts) {
            warnings.add("Frais, spread et slippage estimés : barème actuel (cache des frais 24 h, un carnet public par instrument "
                    + "lu au lancement) appliqué à toute la période.");
        } else {
            warnings.add("Sans frais : ni frais, ni minimum d'ordre, ni carry.");
        }
        List<EpisodeTimeline.AssetSeries> episodeSeries = EpisodeSeries.build(result, links);
        LocalDate first = result.walletDays().getFirst().day();
        LocalDate last = result.walletDays().getLast().day();
        List<String> notes = new ArrayList<>(warnings);
        notes.add("Rejeu libre (wallet simulé en continu) " + (withCosts ? "avec" : "sans") + " frais ; PnL au prix moyen pondéré, "
                + "position d'ouverture valorisée au cours du 1er jour de la plage.");
        EpisodeResultStore.Stored stored = episodeStore.put(user.getId(), episodeSeries, first, last, notes);
        EpisodeDtos.EpisodeReportDto episodes = FullReplayService.report(stored, first, last);
        FullMetaDto meta = new FullMetaDto(plan.walletId(), first, last, cash0, withCosts, new ModulationDto(plan.settings().exposureMin(),
                plan.settings().exposureMax(), plan.settings().power(), plan.settings().bias(), plan.settings().regulationMarginPct()),
                metas, warnings, policy == null ? null : policy.label());
        FullSeriesDto series = series(result, links);
        DrawdownDto drawdown = drawdown(series, userModulation.profileOf(user), modulationControl.current());
        List<ResultLineDto> lines = lines(result, cash0, withCosts);
        addDrawdownLines(lines, drawdown);
        meta.warnings().add("Drawdown mesuré sur la plage simulée (valeur totale du wallet, cash USDC inclus) ; plus haut initial = capital de "
                + "départ ; signalement seul, aucun effet sur les ordres.");
        return new FullSimulationResultDto(meta, series, assetCharts(result, links, dsByLink), lines, episodes, drawdown);
    }

    /** Un rejeu du wallet chargé avec la politique donnée ({@code null} = réglages constants). */
    MultiLinkReplayEngine.Result run(Loaded data, ModulationSettings settings, ModulationPolicy policy) {
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(data.links(), data.cash0(), settings,
                modulationControl.current(), Map.of(), Map.of(), Map.of(), Map.of(), data.costs(), policy));
    }

    /**
     * Charge les bougies de chaque lien (sa source, sans fallback) et résout capital et coûts ; {@code null} si annulé. Transaction
     * de l'appelant (le chargement alimente le cache DB des candles).
     */
    Loaded load(User user, Plan plan, Hooks hooks) {
        hooks.loading().run();
        Map<Long, RainbowLiveBinding> byId = new HashMap<>();
        bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user).forEach(b -> byId.put(b.getId(), b));
        List<String> warnings = new ArrayList<>();
        List<MultiLinkReplayEngine.Link> links = new ArrayList<>();
        List<RainbowLiveBinding> bindings = new ArrayList<>();
        List<FullLinkMetaDto> metas = new ArrayList<>();
        Map<Long, List<MarketData>> candlesByLink = new HashMap<>();
        Map<Long, RainbowAtrDataset> dsByLink = new HashMap<>();
        double refAmount = 0.0;
        for (PlannedLink pl : plan.links()) {
            RainbowLiveBinding b = byId.get(pl.bindingId());
            if (b == null || unusableReason(b) != null) {
                throw new ExpertValidationException("links[" + pl.bindingId() + "]", "lien retiré ou devenu inexploitable depuis la demande");
            }
            DailyDataset daily = loader.load(pl.asset(), pl.source());
            if (hooks.cancelled().getAsBoolean()) {
                return null;
            }
            if (daily.substituted()) {
                throw new ExpertApiException(ExpertApiException.Code.SIMULATION_DATA_SUBSTITUTED, "Source " + pl.source() + " indisponible pour "
                        + pl.asset() + " (servie par " + daily.source() + ") : pas de fallback silencieux, simulation non calculée");
            }
            List<MarketData> candles = closedCandles(daily);
            if (candles.size() < pl.requiredPoints()) {
                throw insufficient(pl.requiredPoints(), candles.size(), daily.source().name());
            }
            RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
            int startIdx = ReplayDays.firstIndexOnOrAfter(ds, plan.from());
            int endIdx = ReplayDays.lastIndexOnOrBefore(ds, plan.to());
            if (startIdx >= ds.size() || endIdx < startIdx) {
                throw new ExpertValidationException("from", "aucune bougie " + pl.asset() + " dans la plage demandée");
            }
            AssetAffectation aff = b.getAffectation();
            RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(aff);
            int windowMonths = configResolver.analysisWindowMonths(aff);
            double bag = pl.bagPercent() != null ? pl.bagPercent() : b.getBagPercent();
            Double baseAmount = pl.baseAmount() != null ? pl.baseAmount() : b.getBaseAmount();
            LiveSlot slot = new LiveSlot(b.getId(), aff.getId(), pl.asset(), b.getWallet().getId(), b.getPriority(), bag, pl.source());
            // démarrage à froid en début de plage (engineStart = startIdx) : ATH et automate moon portés par tout l'historique chargé
            LinkSignalReplay.Config signal = new LinkSignalReplay.Config(ds, candles, cfg.trend(), cfg.range(), cfg.sets(), startIdx,
                    startIdx, endIdx, baseAmount, Map.of(), Map.of(), Set.of(), windowMonths);
            links.add(new MultiLinkReplayEngine.Link(slot, signal, 0.0));
            bindings.add(b);
            candlesByLink.put(b.getId(), candles);
            dsByLink.put(b.getId(), ds);
            refAmount = Math.max(refAmount, baseAmount != null ? baseAmount : cfg.bull().globals().baseAmount());
            int maxDepth = MarketDataProviderLimits.maxD1Candles(daily.source());
            if (maxDepth < MarketDataProviderLimits.MAX_D1_HISTORY_CANDLES && candles.size() >= maxDepth) {
                warnings.add(pl.asset() + " : historique plafonné par " + daily.source() + " (" + candles.size() + " bougies D1) : l'ATH et "
                        + "l'automate To the moon ne portent que sur cette profondeur.");
            }
            metas.add(new FullLinkMetaDto(b.getId(), pl.asset(), aff.displayName(), pl.source() == null ? null : pl.source().name(),
                    daily.source().name(), daily.providerSymbol(), false, b.getPriority(), bag, baseAmount,
                    ReplayDays.dayOf(ds.time(startIdx)), ReplayDays.dayOf(ds.time(endIdx)), candles.size(),
                    new FullLinkParamsDto(cfg.trend(), cfg.range(), windowMonths, java.util.Arrays.stream(cfg.sets())
                            .map(ps -> new SetDto(ps.name(), ps.tuning(), ps.globals())).toList())));
        }
        // capital de départ : celui demandé, sinon la valeur du wallet réel
        double cash0;
        if (plan.cash0() != null) {
            cash0 = plan.cash0();
        } else {
            WalletValueDto wv = walletValue(bindings);
            cash0 = wv.total();
            if (cash0 <= 0) {
                throw new ExpertValidationException("cash0", "valeur du wallet réel inconnue ou nulle : indiquez un capital de départ");
            }
        }
        MultiLinkReplayEngine.Costs costs = null;
        if (plan.withCosts()) {
            try {
                costs = costsFactory.build(bindings, refAmount).costs();
            } catch (RuntimeException e) {
                log.warn("Simulateur complet : coûts non rejoués : {}", e.getMessage());
                warnings.add("Coûts non rejoués (frais ou carnet indisponibles, aucun coût supposé) : " + e.getMessage());
            }
        }
        if (hooks.cancelled().getAsBoolean()) {
            return null;
        }
        return new Loaded(links, metas, dsByLink, cash0, costs, warnings);
    }

    /** Bloc drawdown : mesures stratégie / DCA fixe et verdict de la stratégie face aux plafonds admin des 4 profils. */
    static DrawdownDto drawdown(FullSeriesDto series, StartingProfile userProfile, RainbowModulationControl control) {
        List<LocalDate> days = series.d().stream().map(LocalDate::parse).toList();
        DrawdownMeter.Measure strategy = DrawdownMeter.measure(days, series.wallet());
        DrawdownMeter.Measure fixed = DrawdownMeter.measure(days, series.fixed());
        List<DrawdownVerdictDto> verdicts = new ArrayList<>();
        control.drawdownCaps().forEach((profile, cap) -> {
            DrawdownMeter.Verdict v = DrawdownMeter.verdict(days, strategy, cap.doubleValue());
            verdicts.add(new DrawdownVerdictDto(profile.name(), v.capPct(), v.respected(), v.firstBreachDay(), v.maxOverrunPts()));
        });
        boolean selectable = userProfile != null && userProfile.selectable();
        return new DrawdownDto(strategy.drawdownPct().stream().map(x -> (Number) x).toList(),
                fixed.drawdownPct().stream().map(x -> (Number) x).toList(), measureDto(strategy), measureDto(fixed), verdicts,
                userProfile == null ? null : userProfile.name(), selectable ? userProfile.name() : null);
    }

    private static DrawdownMeasureDto measureDto(DrawdownMeter.Measure m) {
        return new DrawdownMeasureDto(m.maxPct(), m.peakDay(), m.troughDay(), m.recoveryDay(), m.longestUnderwaterDays(), m.lastPct());
    }

    private static void addDrawdownLines(List<ResultLineDto> lines, DrawdownDto dd) {
        int[] n = {lines.size()};
        lines.add(line(n, "maxDrawdown", "Drawdown max stratégie", dd.strategyMeasure().maxPct(), null, "%"));
        lines.add(line(n, "fixedMaxDrawdown", "Drawdown max DCA fixe", dd.fixedMeasure().maxPct(), null, "%"));
        if (dd.defaultProfile() != null) {
            DrawdownVerdictDto v = dd.verdicts().stream().filter(x -> x.profile().equals(dd.defaultProfile())).findFirst().orElseThrow();
            lines.add(line(n, "drawdownVerdict", "Plafond de drawdown du profil (" + v.profile() + ", " + v.capPct() + " %)", null,
                    v.respected() ? "respecté" : "dépassé le " + v.firstBreachDay(), null));
        }
    }

    // ---------------------------------------------------------------- assemblage du résultat

    private static FullSeriesDto series(MultiLinkReplayEngine.Result result, List<MultiLinkReplayEngine.Link> links) {
        List<String> d = new ArrayList<>();
        List<Number> wallet = new ArrayList<>();
        List<Number> fixed = new ArrayList<>();
        List<Number> cash = new ArrayList<>();
        List<Number> fixedCash = new ArrayList<>();
        List<Number> cashPct = new ArrayList<>();
        Map<String, List<Number>> value = new LinkedHashMap<>();
        Map<String, List<Number>> pct = new LinkedHashMap<>();
        for (MultiLinkReplayEngine.Link l : links) {
            value.put(l.slot().assetSymbol(), new ArrayList<>());
            pct.put(l.slot().assetSymbol(), new ArrayList<>());
        }
        Map<java.time.LocalDate, double[]> costByDay = new HashMap<>();
        for (MultiLinkReplayEngine.TradeDay t : result.trades()) {
            double[] c = costByDay.computeIfAbsent(t.day(), k -> new double[2]);
            c[0] += t.costUsdc();
            c[1] += t.fixedCostUsdc();
        }
        List<Number> costCum = new ArrayList<>();
        List<Number> fixedCostCum = new ArrayList<>();
        double cumCost = 0.0;
        double cumFixed = 0.0;
        for (MultiLinkReplayEngine.WalletDay w : result.walletDays()) {
            d.add(w.day().toString());
            wallet.add(w.totalValue());
            fixed.add(w.fixedValue());
            cash.add(w.cash());
            fixedCash.add(w.fixedCash());
            cashPct.add(w.totalValue() > 0 ? 100.0 * w.cash() / w.totalValue() : null);
            value.forEach((asset, list) -> list.add(w.value().getOrDefault(asset, 0.0)));
            pct.forEach((asset, list) -> list.add(w.totalValue() > 0 ? 100.0 * w.value().getOrDefault(asset, 0.0) / w.totalValue() : null));
            double[] c = costByDay.getOrDefault(w.day(), new double[2]);
            cumCost += c[0];
            cumFixed += c[1];
            costCum.add(cumCost);
            fixedCostCum.add(cumFixed);
        }
        return new FullSeriesDto(d, wallet, fixed, cash, fixedCash, value, pct, cashPct, costCum, fixedCostCum);
    }

    private static List<FullAssetChartDto> assetCharts(MultiLinkReplayEngine.Result result, List<MultiLinkReplayEngine.Link> links,
                                                       Map<Long, RainbowAtrDataset> dsByLink) {
        List<LocalDate> days = result.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::day).toList();
        List<FullAssetChartDto> out = new ArrayList<>();
        for (MultiLinkReplayEngine.Link link : links) {
            Long id = link.slot().bindingId();
            RainbowAtrDataset ds = dsByLink.get(id);
            Map<LocalDate, MultiLinkReplayEngine.LinkDay> linkDay = new HashMap<>();
            result.linkDays().stream().filter(x -> x.bindingId().equals(id)).forEach(x -> linkDay.put(x.day(), x));
            Map<LocalDate, MultiLinkReplayEngine.TradeDay> tradeDay = new HashMap<>();
            result.trades().stream().filter(x -> x.bindingId().equals(id)).forEach(x -> tradeDay.put(x.day(), x));
            Map<LocalDate, MultiLinkReplayEngine.CostDay> costDay = new HashMap<>();
            result.costDays().stream().filter(x -> x.bindingId().equals(id)).forEach(x -> costDay.put(x.day(), x));
            List<Number> o = new ArrayList<>();
            List<Number> h = new ArrayList<>();
            List<Number> l = new ArrayList<>();
            List<Number> c = new ArrayList<>();
            List<Integer> regime = new ArrayList<>();
            List<Integer> active = new ArrayList<>();
            List<Integer> zone = new ArrayList<>();
            List<FullOrderDto> orders = new ArrayList<>();
            for (LocalDate day : days) {
                MultiLinkReplayEngine.LinkDay ld = linkDay.get(day);
                MultiLinkReplayEngine.TradeDay td = tradeDay.get(day);
                if (ld == null) {
                    o.add(null);
                    h.add(null);
                    l.add(null);
                    c.add(null);
                    regime.add(null);
                    active.add(null);
                    zone.add(null);
                    continue;
                }
                int i = ld.index();
                o.add(ds.open(i));
                h.add(ds.high(i));
                l.add(ds.low(i));
                c.add(ds.close(i));
                String reg = td == null ? null : td.regime();
                regime.add("UP".equals(reg) ? 1 : "DOWN".equals(reg) ? 2 : 0);
                active.add(td != null && td.regime() != null ? 1 : 0);
                zone.add(td == null ? -1 : td.zone());
                if (ld.wantedAction() != RainbowLiveAction.NONE) {
                    orders.add(order(day, ld, costDay.get(day)));
                }
            }
            out.add(new FullAssetChartDto(link.slot().assetSymbol(), id, o, h, l, c, regime, active, zone, orders,
                    AssetChartBuilder.build(id, link.slot().assetSymbol(), ds, result, result.cash0(), link.qty0())));
        }
        return out;
    }

    private static FullOrderDto order(LocalDate day, MultiLinkReplayEngine.LinkDay ld, MultiLinkReplayEngine.CostDay cost) {
        boolean passed = ld.action() == RainbowLiveAction.BUY || ld.action() == RainbowLiveAction.SELL;
        double amount = passed && ld.amountUsdc() != null ? ld.amountUsdc() : ld.wantedAmountUsdc();
        double qty = passed && ld.quantity() != null ? ld.quantity() : 0.0;
        if (!passed) {
            String reason = ld.blockReason() == null || ld.blockReason() == LiveBlockReason.NONE ? null : ld.blockReason().name();
            return new FullOrderDto(day.toString(), ld.wantedAction().name(), amount, qty, "BLOCKED", reason, 0.0);
        }
        String outcome = cost == null ? "OK" : cost.outcome();
        return new FullOrderDto(day.toString(), ld.action().name(), amount, qty, outcome, "OK".equals(outcome) ? null : outcome,
                cost == null ? 0.0 : cost.costUsdc());
    }

    private static List<ResultLineDto> lines(MultiLinkReplayEngine.Result result, double cash0, boolean withCosts) {
        long buys = result.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.BUY).count();
        long sells = result.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.SELL).count();
        long blockedCash = result.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.NONE
                && x.wantedAction() != RainbowLiveAction.NONE && x.blockReason() == LiveBlockReason.INSUFFICIENT_CASH).count();
        long belowMin = result.costDays().stream().filter(x -> "BELOW_MIN".equals(x.outcome())).count();
        double fin = result.finalValue();
        double fix = result.fixedFinalValue();
        List<ResultLineDto> l = new ArrayList<>();
        int[] n = {0};
        l.add(line(n, "capital", "Capital de départ", cash0, null, "$"));
        l.add(line(n, "final", "Valeur finale du wallet (stratégie)", fin, null, "$"));
        l.add(line(n, "fixedFinal", "Valeur finale DCA fixe (même budget)", fix, null, "$"));
        l.add(line(n, "gain", "Gain vs DCA fixe", fin - fix, null, "$"));
        l.add(line(n, "gainPct", "Gain vs DCA fixe (% du DCA fixe)", fix > EPSILON ? 100.0 * (fin - fix) / fix : null, null, "%"));
        l.add(line(n, "return", "Rendement stratégie", cash0 > EPSILON ? 100.0 * (fin / cash0 - 1.0) : null, null, "%"));
        l.add(line(n, "fixedReturn", "Rendement DCA fixe", cash0 > EPSILON ? 100.0 * (fix / cash0 - 1.0) : null, null, "%"));
        l.add(line(n, "fees", "Frais totaux stratégie", result.totalCostUsdc(), withCosts ? null : "non simulés", "$"));
        l.add(line(n, "fixedFees", "Frais totaux DCA fixe", result.fixedTotalCostUsdc(), withCosts ? null : "non simulés", "$"));
        l.add(line(n, "orders", "Ordres passés", null, buys + " achats / " + sells + " ventes", null));
        l.add(line(n, "blockedCash", "Ordres bloqués par manque de cash", (double) blockedCash, null, "j"));
        if (withCosts) {
            l.add(line(n, "belowMin", "Ordres sous le minimum (reportés)", (double) belowMin, null, ""));
        }
        return l;
    }

    private static ResultLineDto line(int[] n, String key, String label, Double value, String text, String unit) {
        return new ResultLineDto(++n[0], key, label, value, text, unit);
    }

    // ---------------------------------------------------------------- outils

    private LocalDate lastClosedDay() {
        return clock.now().atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
    }

    /** Bougies chronologiques sans la bougie du jour (UTC) encore ouverte. */
    private List<MarketData> closedCandles(DailyDataset daily) {
        Instant today = clock.now().truncatedTo(ChronoUnit.DAYS);
        return daily.dataset().getMarketDatas().stream()
                .filter(c -> c.getTimestamp().atZone(ZoneOffset.UTC).toInstant().isBefore(today))
                .sorted(Comparator.comparing(MarketData::getTimestamp)).toList();
    }

    /** Wallet demandé, sinon celui du 1er lien exploitable (priorité), sinon du 1er lien. */
    private static Long pickWallet(List<RainbowLiveBinding> all, Long requested) {
        if (requested != null) {
            return requested;
        }
        return all.stream().filter(b -> unusableReason(b) == null).findFirst().orElse(all.getFirst()).getWallet().getId();
    }

    private static String exchangeOf(Wallet wallet) {
        return wallet.getWebProviderCode() == null ? null : wallet.getWebProviderCode().name();
    }

    /** Cause pour laquelle un lien ne peut pas être rejoué ; {@code null} = exploitable (Trend + Rainbow UP + DOWN requis). */
    private static String unusableReason(RainbowLiveBinding b) {
        AssetAffectation a = b.getAffectation();
        if (a == null || a.getTrendPreset() == null || a.getRainbowUp() == null || a.getRainbowDown() == null) {
            return "affectation absente ou incomplète (Trend + Rainbow UP + Rainbow DOWN requis)";
        }
        return null;
    }

    /** Historique D1 nécessaire à une Affectation : max des warm-ups Trend / Rainbow UP / Rainbow DOWN / fenêtre d'analyse. */
    private int requiredPoints(AssetAffectation aff) {
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(aff);
        int window = configResolver.analysisWindowMonths(aff);
        return Math.max(SimulationEligibility.requiredPoints(cfg.trend(), cfg.bull().tuning(), window),
                SimulationEligibility.requiredPoints(cfg.trend(), cfg.bear().tuning(), window));
    }

    private static ExpertApiException insufficient(int required, int available, String provider) {
        Map<String, Object> details = new HashMap<>();
        details.put("requiredPoints", required);
        details.put("availablePoints", available);
        details.put("provider", provider);
        return new ExpertApiException(ExpertApiException.Code.ELIGIBILITY_INSUFFICIENT,
                "Historique insuffisant : " + required + " bougies D1 requises (warm-up + plage), " + available + " disponibles"
                        + (provider == null ? "" : " chez " + provider), details);
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
