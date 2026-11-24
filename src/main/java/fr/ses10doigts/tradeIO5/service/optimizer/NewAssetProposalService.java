package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.NewAssetLinkedEvent;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Pipeline d'accueil d'un nouvel actif (D1b, lot 1) : à la création d'un lien sur un actif sans ligne de taux {@code asset_base_rate} valide ({@link AssetBaseRateService#isCalibrated})
 * et sans proposition, calcule EN ASYNCHRONE (pool dédié d'un fil, priorité minimale, délai maximal, jamais sur le chemin du cron live) une proposition
 * {@link NewAssetProposalCalculator} et la persiste au statut {@code PROPOSE}. Rien n'est appliqué : le lien tourne déjà sur le repli BTC « non calibré ».
 * Aucune exception ne remonte : un échec devient une proposition dégradée (drapeaux). Désactivé par défaut ({@value #ENABLED_PROPERTY}).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = NewAssetProposalService.ENABLED_PROPERTY, havingValue = "true")
public class NewAssetProposalService {

    public static final String ENABLED_PROPERTY = "tradeio.new-asset-proposal.enabled";
    static final String PRESET_SOURCE = "BTC";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NewAssetProposalRepository proposals;
    private final RainbowLiveBindingRepository bindings;
    private final UserRepository users;
    private final AssetAffectationService affectations;
    private final AffectationConfigResolver configResolver;
    private final RainbowModulationControlService control;
    private final NewAssetHistoryResolver history;
    private final BindingCheck bindingCheck;
    private final TransactionTemplate tx;
    private final DomainClock clock;
    private final fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService alerts;
    private final AssetBaseRateService baseRates;
    private final Path benchDir;
    private final NewAssetConfig cfg = NewAssetConfig.defaults();
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "new-asset-proposal");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "new-asset-proposal-calc");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public NewAssetProposalService(NewAssetProposalRepository proposals, RainbowLiveBindingRepository bindings, UserRepository users,
                                   AssetAffectationService affectations, AffectationConfigResolver configResolver, RainbowModulationControlService control,
                                   NewAssetHistoryResolver history, BindingCheck bindingCheck, TransactionTemplate tx, DomainClock clock,
                                   fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService alerts, AssetBaseRateService baseRates,
                                   @Value("${tradeio.new-asset-proposal.bench-dir:" + BenchCandleStore.DEFAULT_DIR + "}") String benchDir) {
        this.proposals = proposals;
        this.bindings = bindings;
        this.users = users;
        this.affectations = affectations;
        this.configResolver = configResolver;
        this.control = control;
        this.history = history;
        this.bindingCheck = bindingCheck;
        this.tx = tx;
        this.clock = clock;
        this.alerts = alerts;
        this.baseRates = baseRates;
        this.benchDir = Path.of(benchDir);
    }

    /** Point d'entrée : lien créé (après commit). Ne calcule rien ici : met en file et rend la main. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLinked(NewAssetLinkedEvent event) {
        if (!needsProposal(event.assetSymbol())) {
            return;
        }
        log.info("Nouvel actif {} : proposition mise en file (lien {})", event.assetSymbol(), event.bindingId());
        queue.submit(() -> {
            try {
                propose(event);
            } catch (RuntimeException e) {
                log.error("Nouvel actif {} : proposition en échec", event.assetSymbol(), e);
            }
        });
    }

    boolean needsProposal(String asset) {
        return asset != null && !baseRates.isCalibrated(asset) && !proposals.existsByAssetSymbol(asset);
    }

    /** Calcule et persiste la proposition (synchrone : tests, relance). Une proposition existante pour l'actif est rendue telle quelle. */
    public NewAssetProposal propose(NewAssetLinkedEvent event) {
        String asset = event.assetSymbol();
        if (!needsProposal(asset)) {
            return proposals.findFirstByAssetSymbolOrderByIdDesc(asset).orElse(null);
        }
        long t0 = System.nanoTime();
        Context ctx = tx.execute(s -> context(event));
        Set<ProposalFlag> extra = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        String warning = ctx.tradabilityWarning();
        if (warning != null) {
            extra.add(ProposalFlag.NON_TRADABLE);
        }
        NewAssetProposalResult result;
        try {
            NewAssetHistoryResolver.History h = history.resolve(asset, ctx.source(), cfg, benchDir);
            if (h.note() != null) {
                notes.add(h.note());
            }
            result = CompletableFuture.supplyAsync(() -> NewAssetProposalCalculator.compute(asset, h.sourceLabel(), h.candles(), ctx.btc(), cfg,
                    ctx.k(), ctx.limits(), null), worker).get(cfg.timeoutMinutes(), TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            result = interrupted(asset, ctx, "délai maximal de " + cfg.timeoutMinutes() + " min atteint");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = interrupted(asset, ctx, "interrompu");
        } catch (Exception e) {
            log.warn("Nouvel actif {} : calcul en échec : {}", asset, String.valueOf(e.getCause() == null ? e : e.getCause()));
            result = interrupted(asset, ctx, "erreur : " + (e.getCause() == null ? e : e.getCause()).getMessage());
        }
        NewAssetProposal saved = proposals.save(toEntity(result, extra, notes, warning, ctx.requestedBy(), (System.nanoTime() - t0) / 1_000_000));
        alerts.alert(saved);
        log.info("Nouvel actif {} : proposition {} (statut {}, drapeaux {}, {} ms)", asset, saved.getId(), saved.getStatus(), saved.getFlags(),
                saved.getComputeMs());
        return saved;
    }

    private record Context(MarketDataSource source, TransferStudy.Source btc, double k, RainbowModulationControl limits, String requestedBy,
                           String tradabilityWarning) {
    }

    private Context context(NewAssetLinkedEvent event) {
        User user = users.findById(event.userId()).orElseThrow(() -> new IllegalStateException("utilisateur introuvable : " + event.userId()));
        RainbowLiveBinding binding = bindings.findById(event.bindingId()).orElseThrow(() -> new IllegalStateException("lien introuvable : " + event.bindingId()));
        AssetAffectation btcAffectation = affectations.forAsset(user, PRESET_SOURCE);
        RainbowAtrParamSet[] sets = configResolver.trendConfig(btcAffectation).sets();
        double cash0 = TransferConfig.defaults().cash0();
        TransferStudy.Source btc = new TransferStudy.Source(PRESET_SOURCE, sets, configResolver.analysisWindowMonths(btcAffectation),
                sets[0].globals().baseAmount() / cash0);
        RainbowModulationControl limits = control.current();
        String warning = null;
        try {
            BindingCheckResult check = bindingCheck.check(binding.getWallet(), event.assetSymbol());
            if (!check.isOk()) {
                warning = "Actif non tradable chez le provider du lien (" + check.status() + ") : " + check.message();
            }
        } catch (RuntimeException e) {
            warning = "Tradabilité non vérifiée : " + e.getMessage();
        }
        return new Context(LiveCandleSource.of(binding.getWallet()), btc, limits.getBaseRateMarginK().doubleValue(), limits, user.getUsername(), warning);
    }

    private NewAssetProposalResult interrupted(String asset, Context ctx, String why) {
        return new NewAssetProposalResult(asset, PRESET_SOURCE, null, null, 0, 0, 0, List.of(ProposalFlag.CALAGE_INTERROMPU), ctx.k(), List.of(), 0.0, 0, 0.0,
                0, List.of(why));
    }

    NewAssetProposal toEntity(NewAssetProposalResult r, Set<ProposalFlag> extra, List<String> notes, String warning, String requestedBy, long computeMs) {
        Set<ProposalFlag> all = new LinkedHashSet<>(r.flags());
        all.addAll(extra);
        List<String> allNotes = new ArrayList<>(notes);
        allNotes.addAll(r.notes());
        String text = joinText(warning, allNotes);
        CandleFingerprint fp = r.fingerprint();
        return NewAssetProposal.builder().assetSymbol(r.asset()).status(NewAssetProposalStatus.PROPOSE).presetSource(r.presetSource())
                .historySource(cut(r.historySource(), 64)).candleFirstDay(fp == null ? null : fp.firstDay()).candleLastDay(fp == null ? null : fp.lastDay())
                .candleCount(fp == null ? null : fp.count()).candleChecksum(fp == null ? null : fp.checksum()).historyMonths(r.historyMonths())
                .blockCount(r.blocks()).switchCount(r.switches()).flags(cut(all.stream().map(Enum::name).collect(Collectors.joining(",")), NewAssetProposal.FLAGS_MAX))
                .marginK(java.math.BigDecimal.valueOf(r.marginK())).levelsJson(levelsJson(r)).replays(r.replays()).computeMs(computeMs)
                .warning(cut(text, NewAssetProposal.TEXT_MAX)).requestedBy(cut(requestedBy, 64)).createdAt(clock.now()).build();
    }

    private static String joinText(String warning, List<String> notes) {
        List<String> parts = new ArrayList<>();
        if (warning != null) {
            parts.add(warning);
        }
        parts.addAll(notes);
        return parts.isEmpty() ? null : String.join(" ; ", parts);
    }

    private static String levelsJson(NewAssetProposalResult r) {
        if (!r.hasRates()) {
            return null;
        }
        try {
            return cut(MAPPER.writeValueAsString(r.levels()), NewAssetProposal.JSON_MAX);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        queue.shutdownNow();
        worker.shutdownNow();
    }
}
