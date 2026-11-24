package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.market.AssetBlocklist;
import fr.ses10doigts.tradeIO5.model.entity.market.AssetEligibilitySettings;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.repository.market.AssetEligibilitySettingsRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityDefaults;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityService;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilitySettingsService;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Onglet admin « Actifs » : vue par actif, liste noire, seuil de volume. Chaque écriture est journalisée ({@link AdminAction#MODULATION}, cible
 * {@value #BLOCKLIST_TARGET_PREFIX}{@code <SYMBOLE>} ou {@value #SETTINGS_TARGET} : pas de valeur d'enum dédiée, donc aucun changement de schéma).
 * Aucun cache : liste noire et seuil sont relus en base à chaque lecture (éligibilité, {@link LinkStateService}), l'effet est immédiat, sans redémarrage.
 */
@Service
@RequiredArgsConstructor
public class AdminAssetsService {

    public static final String BLOCKLIST_TARGET_PREFIX = "asset-blocklist:";
    public static final String SETTINGS_TARGET = "asset-settings:min-volume";
    public static final int REASON_MAX = 255;

    /** État de calibration : ligne de taux validée ({@code CALIBRE}) ou simulation seule ({@code EN_ATTENTE_VALIDATION}). */
    public enum Calibration { CALIBRE, EN_ATTENTE_VALIDATION }

    /** Volume 24 h d'un actif chez un provider : {@code volumeUsd} nul = « volume non vérifiable » (éligible mais non vérifié). */
    public record VolumeView(WebProviderCode provider, BigDecimal volumeUsd, boolean verified) {
    }

    /**
     * @param proposalStatus dernière proposition D1b de l'actif ({@code PROPOSE}, {@code REFUSE}, {@code VALIDE}), nulle si aucune
     * @param suspendedLinks liens non archivés suspendus (liste noire, provider déconnecté, route retirée)
     */
    public record AssetView(String asset, int linkCount, int userCount, Calibration calibration, NewAssetProposalStatus proposalStatus, boolean blocked,
                            String blockReason, List<VolumeView> volumes, int suspendedLinks, boolean recomputeRunning) {
    }

    public record BlocklistEntry(String asset, String reason, String createdBy, Instant createdAt) {
    }

    public record Overview(List<AssetView> assets, BigDecimal minVolumeUsd, BigDecimal defaultMinVolumeUsd, boolean recomputeEnabled) {
    }

    public record SettingsView(BigDecimal minVolumeUsd, BigDecimal defaultMinVolumeUsd, String updatedBy, Instant updatedAt) {
    }

    private final RainbowLiveBindingRepository bindings;
    private final AssetBlocklistRepository blocklist;
    private final AssetEligibilitySettingsRepository settingsRows;
    private final AssetEligibilitySettingsService settings;
    private final AssetEligibilityService eligibility;
    private final InstrumentCatalog catalog;
    private final AssetBaseRateService baseRates;
    private final LinkStateService linkStates;
    private final NewAssetProposalRepository proposals;
    private final NewAssetRecomputeService recompute;
    private final AdminActionLogService journal;
    private final DomainClock clock;

    // ------------------------------------------------------------------ vue par actif

    /** Actifs ayant au moins un lien non archivé, plus les actifs en liste noire, par ordre alphabétique. */
    @Transactional(readOnly = true)
    public Overview overview() {
        Map<String, List<RainbowLiveBinding>> byAsset = new TreeMap<>();
        for (RainbowLiveBinding b : bindings.findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc()) {
            byAsset.computeIfAbsent(b.getAssetSymbol(), k -> new ArrayList<>()).add(b);
        }
        Map<String, AssetBlocklist> blocked = new LinkedHashMap<>();
        blocklist.findAll().forEach(b -> blocked.put(b.getAssetSymbol(), b));
        TreeSet<String> symbols = new TreeSet<>(byAsset.keySet());
        symbols.addAll(blocked.keySet());
        Map<WebProviderCode, Map<String, BigDecimal>> volumeCache = new EnumMap<>(WebProviderCode.class);
        List<AssetView> views = new ArrayList<>();
        for (String symbol : symbols) {
            views.add(view(symbol, byAsset.getOrDefault(symbol, List.of()), blocked.get(symbol), volumeCache));
        }
        return new Overview(views, settings.minVolumeUsd(), AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD, recompute.enabled());
    }

    private AssetView view(String symbol, List<RainbowLiveBinding> links, AssetBlocklist block, Map<WebProviderCode, Map<String, BigDecimal>> volumeCache) {
        int users = (int) links.stream().map(b -> b.getUser().getId()).distinct().count();
        int suspended = (int) links.stream().filter(b -> linkStates.of(b).suspended()).count();
        TreeSet<WebProviderCode> providers = new TreeSet<>();
        links.forEach(b -> providers.add(b.getWallet().getWebProviderCode()));
        List<VolumeView> volumes = new ArrayList<>();
        for (WebProviderCode code : providers) {
            BigDecimal volume = volumeCache.computeIfAbsent(code, c -> eligibility.usdVolumes(catalog.storedInstruments(c))).get(symbol);
            volumes.add(new VolumeView(code, volume, volume != null));
        }
        NewAssetProposalStatus status = proposals.findFirstByAssetSymbolOrderByIdDesc(symbol).map(NewAssetProposal::getStatus).orElse(null);
        Calibration calibration = baseRates.isCalibrated(symbol) ? Calibration.CALIBRE : Calibration.EN_ATTENTE_VALIDATION;
        return new AssetView(symbol, links.size(), users, calibration, status, block != null, block == null ? null : block.getReason(), volumes, suspended,
                recompute.running(symbol));
    }

    // ------------------------------------------------------------------ liste noire

    @Transactional(readOnly = true)
    public List<BlocklistEntry> blocklist() {
        return blocklist.findAll().stream().sorted(java.util.Comparator.comparing(AssetBlocklist::getAssetSymbol))
                .map(b -> new BlocklistEntry(b.getAssetSymbol(), b.getReason(), b.getCreatedBy(), b.getCreatedAt())).toList();
    }

    /**
     * Ajoute l'actif à la liste noire : nouveaux liens refusés, liens existants suspendus + alarme du Docteur (relus à chaque appel, sans redémarrage).
     *
     * @throws IllegalArgumentException symbole invalide, motif trop long, ou actif déjà listé
     */
    public BlocklistEntry addToBlocklist(String asset, String reason, String actor) {
        String symbol = null;
        try {
            symbol = AssetEligibilityDefaults.normalizeSymbol(asset);
            String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
            if (cleanReason != null && cleanReason.length() > REASON_MAX) {
                throw new IllegalArgumentException("Motif trop long (" + REASON_MAX + " caractères maximum)");
            }
            if (blocklist.existsByAssetSymbol(symbol)) {
                throw new IllegalArgumentException(symbol + " est déjà en liste noire");
            }
            AssetBlocklist saved = blocklist.save(AssetBlocklist.builder().assetSymbol(symbol).reason(cleanReason).createdBy(actor).createdAt(clock.now()).build());
            journal.record(actor, AdminAction.MODULATION, BLOCKLIST_TARGET_PREFIX + symbol, AdminActionLogService.payload("action", "blocklist-add",
                    "asset", symbol, "reason", cleanReason), AdminActionOutcome.OK);
            return new BlocklistEntry(saved.getAssetSymbol(), saved.getReason(), saved.getCreatedBy(), saved.getCreatedAt());
        } catch (RuntimeException e) {
            journalFailure(actor, BLOCKLIST_TARGET_PREFIX + (symbol == null ? "?" : symbol), "blocklist-add", e);
            throw e;
        }
    }

    /** @throws IllegalArgumentException symbole invalide ou actif absent de la liste noire */
    public void removeFromBlocklist(String asset, String actor) {
        String symbol = null;
        try {
            symbol = AssetEligibilityDefaults.normalizeSymbol(asset);
            AssetBlocklist row = blocklist.findByAssetSymbol(symbol).orElseThrow(() -> new IllegalArgumentException(asset.trim().toUpperCase(java.util.Locale.ROOT)
                    + " n'est pas en liste noire"));
            blocklist.delete(row);
            journal.record(actor, AdminAction.MODULATION, BLOCKLIST_TARGET_PREFIX + symbol, AdminActionLogService.payload("action", "blocklist-remove",
                    "asset", symbol, "reason", row.getReason()), AdminActionOutcome.OK);
        } catch (RuntimeException e) {
            journalFailure(actor, BLOCKLIST_TARGET_PREFIX + (symbol == null ? "?" : symbol), "blocklist-remove", e);
            throw e;
        }
    }

    // ------------------------------------------------------------------ seuil de volume

    @Transactional(readOnly = true)
    public SettingsView settings() {
        AssetEligibilitySettings row = settingsRows.findById(AssetEligibilitySettings.SINGLETON_ID).orElse(null);
        return new SettingsView(settings.minVolumeUsd(), AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD, row == null ? null : row.getUpdatedBy(),
                row == null ? null : row.getUpdatedAt());
    }

    /** @throws IllegalArgumentException valeur absente ou négative (0 accepté : aucun seuil) */
    public SettingsView updateMinVolume(BigDecimal value, String actor) {
        BigDecimal before = settings.minVolumeUsd();
        try {
            settings.updateMinVolumeUsd(value, actor);
            journal.record(actor, AdminAction.MODULATION, SETTINGS_TARGET, AdminActionLogService.payload("action", "min-volume",
                    "before", before.toPlainString(), "after", value.toPlainString()), AdminActionOutcome.OK);
            return settings();
        } catch (RuntimeException e) {
            journalFailure(actor, SETTINGS_TARGET, "min-volume", e);
            throw e;
        }
    }

    private void journalFailure(String actor, String target, String action, RuntimeException e) {
        journal.record(actor, AdminAction.MODULATION, target, AdminActionLogService.payload("action", action, "error", e.getMessage()),
                e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
    }
}
