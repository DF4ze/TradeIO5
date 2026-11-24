package fr.ses10doigts.tradeIO5.service.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.model.entity.modulation.AssetBaseRate;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService;
import fr.ses10doigts.tradeIO5.service.doctor.AlarmService;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorReport;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Level;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alerte et validation admin des propositions d'accueil d'un nouvel actif (D1b, lot 2). Aucune application automatique : seule une action admin
 * change quelque chose.
 * <ul>
 *   <li><b>Alerte</b> : {@link #alert} lève l'alarme Docteur « actif à valider » (clé {@value #ALARM_CHECK}{@code :<actif>}, une seule fois par
 *   proposition), acquittable, avec le lien de l'écran de validation ({@value #SCREEN_LINK}) ; Valider / Refuser la clôt.</li>
 *   <li><b>Valider</b> : écrit la ligne de taux dédiée de l'actif ({@link AssetBaseRate}, lue avant le repli BTC) ; le preset BTC n'est appliqué QUE si
 *   l'actif n'a AUCUN preset ni Affectation dédié (jamais d'écrasement : cas PAXG réglé à la main) ; proposition sans taux ⇒ validation refusée.</li>
 *   <li><b>Refuser</b> : rien n'est écrit, le lien reste sur le repli BTC « non calibré ».</li>
 * </ul>
 * Chaque décision est journalisée ({@link AdminAction#MODULATION}, cible {@code new-asset-proposal:<id>}) : qui, quand (horodatage du journal), valeurs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewAssetProposalAdminService {

    public static final String ALARM_CHECK = "NEW_ASSET_PROPOSAL";
    public static final String SCREEN_LINK = "/admin#modulation";
    public static final String TARGET_PREFIX = "new-asset-proposal:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ce que la validation fera du preset de l'actif. */
    public enum PresetPlan {
        /** L'actif n'a aucun preset ni Affectation dédié : les presets de base BTC (Trend, Bull, Bear) lui seront copiés. */
        APPLIQUE_BTC,
        /** L'actif a déjà un preset ou une Affectation dédié : rien n'est touché. */
        DEDIE_CONSERVE
    }

    public record RateView(String profile, double capPct, double multiplier, double baseRate, String status, String worstWindow) {
    }

    public record ProposalView(Long id, String asset, NewAssetProposalStatus status, String presetSource, String historySource, LocalDate candleFirstDay,
                               LocalDate candleLastDay, Integer candleCount, String candleChecksum, Integer historyMonths, Integer blockCount,
                               Integer switchCount, List<String> flags, BigDecimal marginK, List<RateView> rates, String warning, boolean nonTradable,
                               String requestedBy, java.time.Instant createdAt, java.time.Instant decidedAt, String decidedBy, PresetPlan presetPlan,
                               String presetPlanText, boolean canValidate, String validateBlockedReason) {
    }

    private final NewAssetProposalRepository proposals;
    private final AssetBaseRateRepository rates;
    private final TrendPresetRepository trendPresets;
    private final RainbowPresetRepository rainbowPresets;
    private final AssetAffectationRepository affectations;
    private final ExpertBasePresetService basePresets;
    private final AlarmService alarms;
    private final AdminActionLogService journal;
    private final TransactionTemplate tx;
    private final DomainClock clock;

    // ------------------------------------------------------------------ alerte

    /** Lève l'alarme « actif à valider » pour une proposition {@code PROPOSE} : une seule fois, acquittable. Best-effort : un échec n'annule pas la proposition. */
    public void alert(NewAssetProposal p) {
        if (p == null || p.getStatus() != NewAssetProposalStatus.PROPOSE) {
            return;
        }
        try {
            String flags = p.getFlags() == null || p.getFlags().isBlank() ? "" : " (drapeaux : " + p.getFlags() + ")";
            alarms.raise(new DoctorReport.Entry(ALARM_CHECK, "Actif à valider", DoctorFamily.DATA, DoctorImpact.QUALITY, p.getAssetSymbol(),
                    DoctorStatus.WARN, "Proposition d'accueil de l'actif " + p.getAssetSymbol() + " à valider" + flags + " : Administration > Modulation ("
                            + SCREEN_LINK + ")"));
        } catch (RuntimeException e) {
            log.warn("Nouvel actif {} : alarme « actif à valider » non levée : {}", p.getAssetSymbol(), e.toString());
        }
    }

    static String alarmKey(String asset) {
        return ALARM_CHECK + ":" + asset;
    }

    // ------------------------------------------------------------------ lecture

    /** Propositions {@code PROPOSE} (plus anciennes d'abord) ; {@code all} : historique complet, plus récentes d'abord. */
    public List<ProposalView> list(boolean all) {
        List<NewAssetProposal> list = all ? proposals.findAllByOrderByIdDesc() : proposals.findByStatusOrderByCreatedAtAsc(NewAssetProposalStatus.PROPOSE);
        return list.stream().map(this::view).toList();
    }

    public boolean hasDedicatedPreset(String asset) {
        return trendPresets.existsByAssetSymbol(asset) || rainbowPresets.existsByAssetSymbol(asset) || affectations.existsByAssetSymbol(asset);
    }

    ProposalView view(NewAssetProposal p) {
        List<RateView> rateViews = parseLevels(p).stream()
                .map(l -> new RateView(l.profile(), l.capPct(), l.multiplier(), l.baseRate(), l.status(), l.worstWindow())).toList();
        PresetPlan plan = hasDedicatedPreset(p.getAssetSymbol()) ? PresetPlan.DEDIE_CONSERVE : PresetPlan.APPLIQUE_BTC;
        String planText = plan == PresetPlan.DEDIE_CONSERVE
                ? "L'actif a déjà un preset / une Affectation dédié : conservé tel quel, rien n'est écrasé. Seule la ligne de taux est écrite."
                : "L'actif n'a aucun preset dédié : les presets de base BTC (Trend, Rainbow Bull, Rainbow Bear) lui sont copiés, en plus de la ligne de taux.";
        String blocked = p.getStatus() != NewAssetProposalStatus.PROPOSE ? "Proposition déjà décidée"
                : rateViews.isEmpty() ? "Aucun taux calculé (voir drapeaux) : rien à valider, seulement refuser" : null;
        List<String> flags = p.getFlags() == null || p.getFlags().isBlank() ? List.of() : Arrays.asList(p.getFlags().split(","));
        return new ProposalView(p.getId(), p.getAssetSymbol(), p.getStatus(), p.getPresetSource(), p.getHistorySource(), p.getCandleFirstDay(),
                p.getCandleLastDay(), p.getCandleCount(), p.getCandleChecksum(), p.getHistoryMonths(), p.getBlockCount(), p.getSwitchCount(), flags,
                p.getMarginK(), rateViews, p.getWarning(), flags.contains("NON_TRADABLE"), p.getRequestedBy(), p.getCreatedAt(), p.getDecidedAt(),
                p.getDecidedBy(), plan, planText, blocked == null, blocked);
    }

    private static List<Level> parseLevels(NewAssetProposal p) {
        if (p.getLevelsJson() == null || p.getLevelsJson().isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(p.getLevelsJson(), new TypeReference<List<Level>>() {
            });
        } catch (Exception e) {
            log.warn("Proposition {} : taux illisibles : {}", p.getId(), e.toString());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ décisions

    /** Valide : ligne de taux dédiée + (si aucun preset dédié) presets de base BTC copiés ; clôt l'alarme ; journalise. */
    public ProposalView validate(Long id, String actor) {
        String target = TARGET_PREFIX + id;
        try {
            Outcome out = tx.execute(s -> doValidate(id, actor));
            journal.record(actor, AdminAction.MODULATION, target, AdminActionLogService.payload("action", "validate", "asset", out.asset(),
                    "rates", out.rates(), "presetPlan", out.plan().name(), "presetsCreated", out.presetsCreated(), "flags", out.flags(),
                    "historySource", out.historySource(), "candleChecksum", out.checksum()), AdminActionOutcome.OK);
            log.info("Nouvel actif {} : proposition {} VALIDÉE par {} ({}, {} preset(s) créé(s))", out.asset(), id, actor, out.plan(), out.presetsCreated());
            return view(proposals.findById(id).orElseThrow());
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.MODULATION, target, AdminActionLogService.payload("action", "validate", "error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    /** Refuse : rien n'est écrit, le lien reste sur le repli BTC « non calibré » ; clôt l'alarme ; journalise. */
    public ProposalView refuse(Long id, String actor) {
        String target = TARGET_PREFIX + id;
        try {
            NewAssetProposal saved = tx.execute(s -> {
                NewAssetProposal p = pending(id);
                decide(p, NewAssetProposalStatus.REFUSE, actor);
                alarms.resolve(alarmKey(p.getAssetSymbol()), "proposition refusée");
                return p;
            });
            journal.record(actor, AdminAction.MODULATION, target, AdminActionLogService.payload("action", "refuse", "asset", saved.getAssetSymbol(),
                    "flags", saved.getFlags()), AdminActionOutcome.OK);
            log.info("Nouvel actif {} : proposition {} REFUSÉE par {} (repli BTC « non calibré » conservé)", saved.getAssetSymbol(), id, actor);
            return view(saved);
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.MODULATION, target, AdminActionLogService.payload("action", "refuse", "error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    private record Outcome(String asset, Map<String, Object> rates, PresetPlan plan, int presetsCreated, String flags, String historySource, String checksum) {
    }

    private Outcome doValidate(Long id, String actor) {
        NewAssetProposal p = pending(id);
        List<Level> levels = parseLevels(p);
        if (levels.size() != 4) {
            throw new IllegalArgumentException("Proposition sans taux exploitables (" + levels.size() + " profil(s)) : validation impossible, refuser");
        }
        Map<String, BigDecimal> byProfile = new LinkedHashMap<>();
        for (Level l : levels) {
            byProfile.put(l.profile(), BigDecimal.valueOf(l.baseRate()).setScale(8, RoundingMode.HALF_UP));
        }
        BigDecimal prudent = rate(byProfile, "PRUDENT");
        BigDecimal balanced = rate(byProfile, "BALANCED");
        BigDecimal dynamic = rate(byProfile, "DYNAMIC");
        BigDecimal full = rate(byProfile, "FULL");
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.BASE_RATE_BTC_PRUDENT);
        for (BigDecimal r : List.of(prudent, balanced, dynamic, full)) {
            if (!limit.contains(r)) {
                throw new IllegalArgumentException("Taux hors bornes absolues [" + limit.min().toPlainString() + " ; " + limit.max().toPlainString() + "] : "
                        + r.toPlainString());
            }
        }
        if (prudent.compareTo(balanced) > 0 || balanced.compareTo(dynamic) > 0 || dynamic.compareTo(full) > 0) {
            throw new IllegalArgumentException("Taux non croissants Prudent ≤ Équilibré ≤ Dynamique ≤ Full");
        }
        String asset = p.getAssetSymbol();
        AssetBaseRate row = rates.findByAssetSymbol(asset).orElseGet(() -> AssetBaseRate.builder().assetSymbol(asset).build());
        row.setRatePrudent(prudent);
        row.setRateBalanced(balanced);
        row.setRateDynamic(dynamic);
        row.setRateFull(full);
        row.setProposalId(p.getId());
        row.setValidatedBy(actor);
        row.setValidatedAt(clock.now());
        rates.save(row);
        // Preset : décidé AVANT toute écriture de preset (jamais d'écrasement d'un preset / d'une Affectation déjà dédié).
        PresetPlan plan = hasDedicatedPreset(asset) ? PresetPlan.DEDIE_CONSERVE : PresetPlan.APPLIQUE_BTC;
        int created = plan == PresetPlan.APPLIQUE_BTC ? basePresets.copyBasePresetsFromBtc(asset) : 0;
        decide(p, NewAssetProposalStatus.VALIDE, actor);
        alarms.resolve(alarmKey(asset), "proposition validée");
        Map<String, Object> shown = new LinkedHashMap<>();
        byProfile.forEach((k, v) -> shown.put(k, v.toPlainString()));
        return new Outcome(asset, shown, plan, created, p.getFlags(), p.getHistorySource(), p.getCandleChecksum());
    }

    private static BigDecimal rate(Map<String, BigDecimal> byProfile, String profile) {
        BigDecimal v = byProfile.get(profile);
        if (v == null) {
            throw new IllegalArgumentException("Taux manquant pour le profil " + profile);
        }
        return v;
    }

    private NewAssetProposal pending(Long id) {
        NewAssetProposal p = proposals.findById(id).orElseThrow(() -> new IllegalArgumentException("proposition introuvable : " + id));
        if (p.getStatus() != NewAssetProposalStatus.PROPOSE) {
            throw new IllegalArgumentException("proposition déjà décidée (" + p.getStatus() + ") : " + id);
        }
        return p;
    }

    private void decide(NewAssetProposal p, NewAssetProposalStatus status, String actor) {
        p.setStatus(status);
        p.setDecidedAt(clock.now());
        p.setDecidedBy(actor == null ? null : actor.length() > 64 ? actor.substring(0, 64) : actor);
        proposals.save(p);
    }
}
