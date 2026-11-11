package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fabrique de données pour les tests : une Affectation (utilisateur, actif) dont les presets Rainbow UP et DOWN portent le
 * MÊME jeu {@code (tuning, globals)} ; le résultat du moteur ne dépend alors pas du régime Trend. Presets propres à
 * l'utilisateur (noms uniques), Trend aux défauts.
 */
@Component
public class ExpertFixtures {

    public static final int WINDOW_MONTHS = 6;
    /** Trend à fenêtres courtes : exploitable sur les séries de 30 bougies des tests (UP et DOWN sont identiques de toute façon). */
    public static final TrendMixCalculator.Params SHORT_TREND = new TrendMixCalculator.Params(3, 4, 5, 400.0, 0.1, 0.05, 1, 5, 3, 1.25, false, false);

    @Autowired private TrendPresetRepository trendRepository;
    @Autowired private RainbowPresetRepository rainbowRepository;
    @Autowired private AssetAffectationRepository affectationRepository;

    private int seq;

    /** Crée (ou met à jour) l'Affectation de l'utilisateur sur l'actif avec ce jeu (jouée seulement si un lien la référence). */
    public AssetAffectation affectation(User u, String asset, String name, RainbowAtrTuning t, RainbowAtrGlobals g) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String unique = name + "#" + (++seq);
        TrendPreset trend = trendRepository.save(trend(u, asset, unique, now));
        RainbowPreset up = rainbowRepository.save(rainbow(u, asset, unique + " Up", t, g, now));
        RainbowPreset down = rainbowRepository.save(rainbow(u, asset, unique + " Down", t, g, now));
        AssetAffectation a = affectationRepository.findByUserAndAssetSymbolOrderByIdAsc(u, asset).stream().filter(x -> !x.isSystem()).findFirst()
                .orElseGet(() -> AssetAffectation.builder().user(u).assetSymbol(asset).createdAt(now).build());
        a.setName(unique);
        a.setTrendPreset(trend);
        a.setRainbowUp(up);
        a.setRainbowDown(down);
        a.setUpdatedAt(now);
        return affectationRepository.save(a);
    }

    /** Affectation utilisateur éditable de l'actif ; à défaut, créée sur les presets de l'instance Système (« Perso »). */
    public AssetAffectation editable(AssetAffectationService service, User u, String asset) {
        return affectationRepository.findByUserAndAssetSymbolOrderByIdAsc(u, asset).stream().filter(x -> !x.isSystem()).findFirst().orElseGet(() -> {
            AssetAffectation base = service.forAsset(u, asset);
            return service.create(u, asset, "Perso", base.getTrendPreset().getId(), base.getRainbowUp().getId(), base.getRainbowDown().getId());
        });
    }

    private static TrendPreset trend(User u, String asset, String name, Instant now) {
        TrendPreset t = TrendPreset.builder().assetSymbol(asset).name(name).origin(PresetOrigin.USER).owner(u).revision(1)
                .rangeMapping("KEEP_PREVIOUS").createdAt(now).updatedAt(now).build();
        t.applyParams(SHORT_TREND);
        return t;
    }

    private static RainbowPreset rainbow(User u, String asset, String name, RainbowAtrTuning t, RainbowAtrGlobals g, Instant now) {
        return RainbowPreset.builder().assetSymbol(asset).name(name).origin(PresetOrigin.USER).owner(u).revision(1)
                .analysisWindowMonths(WINDOW_MONTHS).config(RainbowAtrConfig.of(t, g)).createdAt(now).updatedAt(now).build();
    }
}
