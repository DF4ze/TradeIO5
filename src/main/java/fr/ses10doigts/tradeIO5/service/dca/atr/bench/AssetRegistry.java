package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.TreeSet;

/**
 * Registre unique des actifs du moteur (remplace l'ancienne liste figée BTC / ETH / PAXG). Un actif « existe » pour un utilisateur quand il porte
 * un lien non archivé ; pour tous les utilisateurs, quand au moins un lien non archivé le porte. Aucune liste d'activation, aucune whitelist : les
 * providers font foi (éligibilité au lien, {@code AssetEligibilityService}).
 * <p>
 * {@link #SEED_ASSETS} n'est PAS un périmètre : c'est uniquement le jeu semé au démarrage (presets de base, Affectations Système) et le périmètre par
 * défaut de la liaison automatique d'un wallet (onboarding) tant que l'écran de choix d'actifs n'existe pas.
 */
@Component
@RequiredArgsConstructor
public class AssetRegistry {

    /** Actifs dont les presets de base et les Affectations Système sont semés au démarrage (ordre d'affichage historique). */
    public static final List<String> SEED_ASSETS = List.of("BTC", "ETH", "PAXG");

    private final RainbowLiveBindingRepository bindings;

    /** Actifs ayant au moins un lien non archivé (tous utilisateurs), par ordre alphabétique. */
    @Transactional(readOnly = true)
    public List<String> linkedAssets() {
        return bindings.findActiveAssetSymbols();
    }

    /** Actifs liés par l'utilisateur (liens non archivés), par ordre alphabétique, sans doublon. */
    @Transactional(readOnly = true)
    public List<String> linkedAssets(User user) {
        return bindings.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream().map(RainbowLiveBinding::getAssetSymbol)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)).stream().toList();
    }

    /** Graine + actifs liés de l'utilisateur : actifs dont l'Affectation Système doit exister, par ordre alphabétique. */
    @Transactional(readOnly = true)
    public List<String> affectationAssets(User user) {
        TreeSet<String> all = new TreeSet<>(SEED_ASSETS);
        all.addAll(linkedAssets(user));
        return List.copyOf(all);
    }
}
