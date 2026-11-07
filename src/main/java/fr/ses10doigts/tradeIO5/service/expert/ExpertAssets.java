package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Actifs proposés par l'Espace Expert (ceux des presets de base) et providers qui les portent. */
@Component
@RequiredArgsConstructor
public class ExpertAssets {

    private final AssetProviderRepository assetProviderRepository;

    /** Symbole normalisé (majuscules) ; actif hors périmètre ⇒ {@link IllegalArgumentException} (400). */
    public String requireAsset(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (!RainbowLiveDefaultPresets.ASSETS.contains(normalized)) {
            throw new IllegalArgumentException("Actif non autorisé : " + symbol + " (autorisés : " + RainbowLiveDefaultPresets.ASSETS + ")");
        }
        return normalized;
    }

    /** Providers actifs de l'actif, par ordre de préférence. */
    public List<AssetProvider> providers(String asset) {
        return assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc(asset).stream().filter(AssetProvider::isEnabled).toList();
    }

    public Set<String> providerNames(String asset) {
        return providers(asset).stream().map(p -> p.getSource().name()).collect(Collectors.toSet());
    }

    /** Provider normalisé (majuscules) ; nul si vide. Ne vérifie pas qu'il porte un actif. */
    public static String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? null : provider.trim().toUpperCase(Locale.ROOT);
    }

    /** Provider de référence normalisé (nul si vide) ; provider qui ne porte pas l'actif ⇒ erreur sur le champ {@code referenceProvider}. */
    public String requireProvider(String asset, String provider) {
        String normalized = normalizeProvider(provider);
        if (normalized == null) {
            return null;
        }
        if (!providerNames(asset).contains(normalized)) {
            throw new ExpertValidationException("referenceProvider", "ne porte pas l'actif " + asset + " : " + provider);
        }
        return normalized;
    }
}
