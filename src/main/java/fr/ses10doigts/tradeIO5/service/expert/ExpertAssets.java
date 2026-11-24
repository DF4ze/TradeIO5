package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
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

    /**
     * Sources de bougies utilisables comme provider de référence / de simulation même quand l'actif n'y est pas configuré
     * (actif listé sur un seul exchange à historique court). Lecture seule : aucune ligne {@code asset_provider} n'est créée.
     */
    public static final Set<MarketDataSource> SIMULATION_ONLY_SOURCES = Set.of(MarketDataSource.BINANCE);

    private final AssetProviderRepository assetProviderRepository;
    private final AssetRepository assetRepository;

    /** Symbole normalisé (majuscules) ; actif inconnu (aucune ligne {@code asset}, donc jamais lié) ⇒ {@link IllegalArgumentException} (400). */
    public String requireAsset(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || assetRepository.findBySymbol(normalized).isEmpty()) {
            throw new IllegalArgumentException("Actif inconnu : " + symbol);
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

    /** Sources de simulation seule proposées pour l'actif : {@link #SIMULATION_ONLY_SOURCES} que l'actif ne porte pas déjà. */
    public List<String> simulationProviders(String asset) {
        Set<String> own = providerNames(asset);
        return SIMULATION_ONLY_SOURCES.stream().map(MarketDataSource::name).filter(n -> !own.contains(n)).sorted().toList();
    }

    /** {@code true} si {@code provider} (normalisé) porte l'actif ou est une source de simulation seule. */
    public boolean isReferenceable(String asset, String provider) {
        return providerNames(asset).contains(provider)
                || SIMULATION_ONLY_SOURCES.stream().anyMatch(s -> s.name().equals(provider));
    }

    /** Vrai si la source n'est pas un provider configuré de l'actif (bougies d'une autre source que celle d'exécution). */
    public boolean isForeignSource(String asset, MarketDataSource source) {
        return source != null && !providerNames(asset).contains(source.name());
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
        if (!isReferenceable(asset, normalized)) {
            throw new ExpertValidationException("referenceProvider", "ne porte pas l'actif " + asset + " : " + provider);
        }
        return normalized;
    }
}
