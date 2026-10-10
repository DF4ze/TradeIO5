package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAssetStrategy;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveTrendConfigRepository;
import lombok.RequiredArgsConstructor;

/**
 * Accesseur unique de la config EFFECTIVE d'un preset : celle de la stratégie suivie (lue à chaque appel, aucune
 * copie) ou, pour un preset propre à l'utilisateur, ses propres colonnes / sa ligne Trend. Tous les lecteurs de config
 * du bench passent par ici.
 */
@Component
@RequiredArgsConstructor
public class RainbowLivePresetConfigResolver {

    private final RainbowLiveTrendConfigRepository trendConfigRepository;

    /** Jeu de paramètres FIXED (ou instantané Bear d'un TREND_MIX). */
    public RainbowAtrConfig config(RainbowLivePreset preset) {
        RainbowAssetStrategy s = preset.getAssetStrategy();
        return s == null ? preset.getConfig() : s.getConfig();
    }

    public int analysisWindowMonths(RainbowLivePreset preset) {
        RainbowAssetStrategy s = preset.getAssetStrategy();
        return s == null ? preset.getAnalysisWindowMonths() : s.getAnalysisWindowMonths();
    }

    /** Réglages Trend Mix résolus (défauts du code si le preset propre n'a aucune ligne). */
    public RainbowLiveTrendConfigs.Resolved trendConfig(RainbowLivePreset preset) {
        RainbowAssetStrategy s = preset.getAssetStrategy();
        if (s == null) {
            return RainbowLiveTrendConfigs.resolve(trendConfigRepository.findByPreset(preset).orElse(null),
                    preset.getAssetSymbol());
        }
        return RainbowLiveTrendConfigs.normalize(preset.getAssetSymbol(),
                RainbowLiveTrendConfigs.fromJson(s.getTrendConfigJson()));
    }

    /**
     * Empreinte de ce qui pilote l'état incrémental : fenêtre + (TREND_MIX) jeux Bear/Bull et réglages Trend, ou
     * (FIXED) le jeu de paramètres. Change ⇒ l'état persisté doit être ré-amorcé.
     */
    public String effectiveHash(RainbowLivePreset preset) {
        String payload = analysisWindowMonths(preset) + "|" + (preset.isTrendMix()
                ? RainbowLiveTrendConfigs.toJson(RainbowLiveTrendConfigs.toDto(trendConfig(preset)))
                : config(preset).hash());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
