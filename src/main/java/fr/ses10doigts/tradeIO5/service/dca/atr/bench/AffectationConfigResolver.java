package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Accesseur unique de la config EFFECTIVE d'une Affectation : réglages Trend du {@code TrendPreset}, jeux Rainbow des
 * presets DOWN (index 0, ex-« Bear ») et UP (index 1, ex-« Bull »). Tous les lecteurs de config du moteur passent par ici.
 */
@Component
public class AffectationConfigResolver {

    /** Jeu de paramètres de référence de l'Affectation (preset DOWN : montant de base, snapshot informatif). */
    public RainbowAtrConfig config(AssetAffectation affectation) {
        return requireComplete(affectation).getRainbowDown().getConfig();
    }

    /** Fenêtre d'analyse : la plus grande des deux presets Rainbow (identiques quand UP et DOWN partagent le même preset). */
    public int analysisWindowMonths(AssetAffectation affectation) {
        requireComplete(affectation);
        return Math.max(affectation.getRainbowUp().getAnalysisWindowMonths(), affectation.getRainbowDown().getAnalysisWindowMonths());
    }

    /** Réglages résolus pour le moteur : Trend, mapping du RANGE, jeu DOWN puis jeu UP. */
    public RainbowLiveTrendConfigs.Resolved trendConfig(AssetAffectation affectation) {
        requireComplete(affectation);
        return new RainbowLiveTrendConfigs.Resolved(affectation.getTrendPreset().toParams(),
                RangeMapping.valueOf(affectation.getTrendPreset().getRangeMapping()),
                set(affectation.getRainbowDown()), set(affectation.getRainbowUp()));
    }

    /**
     * Empreinte de ce qui pilote l'état incrémental : fenêtre + réglages Trend + jeux Rainbow UP / DOWN. Change ⇒ l'état
     * persisté doit être ré-amorcé (le moteur bascule directement sur les résultats des nouveaux presets).
     */
    public String effectiveHash(AssetAffectation affectation) {
        String payload = analysisWindowMonths(affectation) + "|"
                + RainbowLiveTrendConfigs.toJson(RainbowLiveTrendConfigs.toDto(trendConfig(affectation)));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RainbowAtrParamSet set(RainbowPreset preset) {
        return new RainbowAtrParamSet(preset.getName(), preset.getConfig().toTuning(), preset.getConfig().toGlobals());
    }

    private static AssetAffectation requireComplete(AssetAffectation affectation) {
        if (!affectation.isComplete()) {
            throw new IllegalStateException("Affectation incomplète (id=" + affectation.getId() + ", " + affectation.getAssetSymbol() + ")");
        }
        return affectation;
    }
}
