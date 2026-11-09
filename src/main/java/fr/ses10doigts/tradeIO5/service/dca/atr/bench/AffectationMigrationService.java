package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowTrendConfig;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.legacy.LegacyRainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.legacy.LegacyRainbowTrendConfigRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Migration idempotente des anciens presets {@code rainbow_live_preset} vers les Affectations.
 * <p>
 * Par (utilisateur, actif) sans Affectation : le preset retenu est celui référencé par un binding, sinon l'actif, sinon le
 * plus ancien. Preset qui suivait la stratégie Actif, sans réglages Trend ou identique aux défauts ⇒ presets système de
 * base ; sinon Trend + Rainbow Up / Down propres à l'utilisateur. Les runs, états moteur et bindings du preset retenu sont
 * rattachés à l'Affectation (même {@code configHash} : aucun re-bootstrap) ; ceux des autres presets sont supprimés
 * (bindings : rattachés). Les colonnes {@code preset_id} deviennent nullables. Sans ligne legacy, ne fait rien.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffectationMigrationService {

    private static final List<String> TABLES = List.of("rainbow_live_run", "rainbow_live_engine_state", "rainbow_live_binding");

    private final LegacyRainbowPresetRepository legacyRepository;
    private final LegacyRainbowTrendConfigRepository legacyTrendRepository;
    private final AssetAffectationRepository affectationRepository;
    private final TrendPresetRepository trendRepository;
    private final RainbowPresetRepository rainbowRepository;
    private final JdbcTemplate jdbc;
    private final DomainClock clock;

    /** Renvoie le nombre d'Affectations créées. */
    @Transactional
    public int migrate() {
        relinkOrphanBindings();
        List<LegacyRainbowPreset> legacy = legacyRepository.findAll();
        if (legacy.isEmpty()) {
            return 0;
        }
        Set<String> tablesWithPresetId = TABLES.stream().filter(this::hasPresetId).collect(Collectors.toSet());
        tablesWithPresetId.forEach(this::makePresetIdNullable);
        Map<String, List<LegacyRainbowPreset>> groups = legacy.stream().collect(Collectors.groupingBy(
                p -> p.getUser().getId() + "/" + p.getAssetSymbol()));
        int created = 0;
        for (List<LegacyRainbowPreset> group : groups.values()) {
            LegacyRainbowPreset first = group.getFirst();
            if (!RainbowLiveDefaultPresets.ASSETS.contains(first.getAssetSymbol())
                    || affectationRepository.findByUserAndAssetSymbol(first.getUser(), first.getAssetSymbol()).isPresent()) {
                continue;
            }
            LegacyRainbowPreset kept = choose(group, tablesWithPresetId);
            AssetAffectation a = affectationRepository.save(build(kept));
            rekey(group, kept, a, tablesWithPresetId);
            created++;
            log.info("Migration : user={} actif={} preset legacy id={} ({}) -> Affectation id={} {}", first.getUser().getId(),
                    first.getAssetSymbol(), kept.getId(), kept.getName(), a.getId(), a.label());
        }
        log.info("Migration Affectations : {} créée(s) pour {} preset(s) legacy", created, legacy.size());
        return created;
    }

    /**
     * Rattache à l'Affectation (utilisateur, actif) les liens restés sans Affectation (cas d'une Affectation créée avant la migration :
     * le preset legacy est alors ignoré et le lien n'était jamais rattaché). Sans Affectation correspondante, le lien reste orphelin.
     */
    private void relinkOrphanBindings() {
        int n = jdbc.update("UPDATE rainbow_live_binding b SET b.affectation_id = (SELECT a.id FROM asset_affectation a "
                + "WHERE a.user_id = b.user_id AND a.asset_symbol = b.asset_symbol) WHERE b.affectation_id IS NULL "
                + "AND EXISTS (SELECT 1 FROM asset_affectation a2 WHERE a2.user_id = b.user_id AND a2.asset_symbol = b.asset_symbol)");
        if (n > 0) {
            log.info("Migration Affectations : {} lien(s) orphelin(s) rattaché(s) à l'Affectation de leur actif", n);
        }
    }

    private LegacyRainbowPreset choose(List<LegacyRainbowPreset> group, Set<String> tables) {
        return group.stream().min(Comparator
                .comparing((LegacyRainbowPreset p) -> !referenced(p, tables))
                .thenComparing(p -> !p.isEnabled())
                .thenComparing(LegacyRainbowPreset::getId)).orElseThrow();
    }

    private boolean referenced(LegacyRainbowPreset p, Set<String> tables) {
        return tables.contains("rainbow_live_binding") && Objects.requireNonNullElse(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rainbow_live_binding WHERE preset_id = ?", Integer.class, p.getId()), 0) > 0;
    }

    private AssetAffectation build(LegacyRainbowPreset p) {
        Instant now = clock.now();
        String asset = p.getAssetSymbol();
        TrendPreset baseTrend = trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).orElseThrow();
        RainbowPreset baseUp = rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BULL).orElseThrow();
        RainbowPreset baseDown = rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BEAR).orElseThrow();
        TrendPreset trend = baseTrend;
        RainbowPreset up = baseUp;
        RainbowPreset down = baseDown;
        LegacyRainbowTrendConfig row = p.getAssetStrategyId() != null ? null : legacyTrendRepository.findByPreset(p).orElse(null);
        if (row == null) {
            log.info("Migration : preset legacy id={} sans réglages Trend propres : presets de base", p.getId());
        } else {
            RainbowLiveTrendConfigs.Resolved r = resolve(row, asset);
            RainbowLiveTrendConfigs.Resolved d = RainbowLiveTrendConfigs.defaults(asset);
            if (!r.trend().equals(d.trend()) || r.range() != d.range()) {
                trend = trendRepository.save(ownTrend(p, r, now));
            }
            if (differs(r.bull(), baseUp)) {
                up = rainbowRepository.save(ownRainbow(p, r.bull(), BasePresetRole.RAINBOW_BULL, " Bull", now));
            }
            if (differs(r.bear(), baseDown)) {
                down = rainbowRepository.save(ownRainbow(p, r.bear(), BasePresetRole.RAINBOW_BEAR, " Bear", now));
            }
        }
        return AssetAffectation.builder().user(p.getUser()).assetSymbol(asset).trendPreset(trend).rainbowUp(up)
                .rainbowDown(down).enabled(p.isEnabled()).createdAt(now).updatedAt(now).build();
    }

    private static RainbowLiveTrendConfigs.Resolved resolve(LegacyRainbowTrendConfig row, String asset) {
        TrendConfigDto dto = new TrendConfigDto(
                new fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator.Params(row.getShortWindow(),
                        row.getMediumWindow(), row.getLongWindow(), row.getSlopeScale(), row.getEnterThreshold(),
                        row.getExitThreshold(), row.getConfirmDays(), row.getSmaPeriod(), row.getAtrPeriod(),
                        row.getAtrMultiplier(), row.isWickDown(), row.isWickUp()),
                row.getRangeMapping(),
                configDto(row.getBearJson()), configDto(row.getBullJson()));
        return RainbowLiveTrendConfigs.normalize(asset, dto);
    }

    private static ConfigDto configDto(String json) {
        try {
            return new ObjectMapper().readValue(json, ConfigDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Jeu Rainbow legacy illisible : " + e.getMessage(), e);
        }
    }

    private static boolean differs(RainbowAtrParamSet set, RainbowPreset base) {
        return !RainbowAtrConfig.of(set.tuning(), set.globals()).hash().equals(base.getConfig().hash());
    }

    private TrendPreset ownTrend(LegacyRainbowPreset p, RainbowLiveTrendConfigs.Resolved r, Instant now) {
        TrendPreset t = TrendPreset.builder().assetSymbol(p.getAssetSymbol()).name(cap(p.getName(), ""))
                .origin(PresetOrigin.USER).owner(p.getUser()).revision(1).rangeMapping(r.range().name())
                .createdAt(now).updatedAt(now).build();
        t.applyParams(r.trend());
        return t;
    }

    private RainbowPreset ownRainbow(LegacyRainbowPreset p, RainbowAtrParamSet set, BasePresetRole role, String suffix,
                                     Instant now) {
        return RainbowPreset.builder().assetSymbol(p.getAssetSymbol()).name(cap(p.getName(), suffix))
                .origin(PresetOrigin.USER).owner(p.getUser()).baseRole(null).revision(1)
                .analysisWindowMonths(p.getAnalysisWindowMonths())
                .config(RainbowAtrConfig.of(set.tuning(), set.globals())).createdAt(now).updatedAt(now).build();
    }

    private static String cap(String name, String suffix) {
        return (name.length() + suffix.length() > 64 ? name.substring(0, 64 - suffix.length()) : name) + suffix;
    }

    private void rekey(List<LegacyRainbowPreset> group, LegacyRainbowPreset kept, AssetAffectation a, Set<String> tables) {
        for (String t : tables) {
            for (LegacyRainbowPreset p : group) {
                if (p == kept || t.equals("rainbow_live_binding")) {
                    jdbc.update("UPDATE " + t + " SET affectation_id = ?, preset_id = NULL WHERE preset_id = ? AND affectation_id IS NULL",
                            a.getId(), p.getId());
                } else {
                    int n = jdbc.update("DELETE FROM " + t + " WHERE preset_id = ? AND affectation_id IS NULL", p.getId());
                    if (n > 0) {
                        log.info("Migration : {} ligne(s) de {} supprimée(s) (preset legacy id={} non retenu)", n, t, p.getId());
                    }
                }
            }
        }
    }

    private void makePresetIdNullable(String table) {
        String product = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
        jdbc.execute("ALTER TABLE " + table + ("H2".equalsIgnoreCase(product)
                ? " ALTER COLUMN preset_id SET NULL" : " MODIFY preset_id BIGINT NULL"));
    }

    private boolean hasPresetId(String table) {
        try (Connection c = jdbc.getDataSource().getConnection()) {
            for (String name : new String[]{table, table.toUpperCase()}) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, name, "preset_id")) {
                    if (rs.next()) {
                        return true;
                    }
                }
                try (ResultSet rs = c.getMetaData().getColumns(null, null, name, "PRESET_ID")) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
