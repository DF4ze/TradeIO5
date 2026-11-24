package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityService;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * État dérivé d'un lien, sans aucune colonne en base (recalculé à chaque lecture, catalogue en base seul, aucun appel réseau) :
 * <ul>
 *   <li><b>suspendu</b> ({@link SuspensionReason}) : l'actif est en liste noire, le provider du lien est déconnecté (clé de lecture absente ou
 *   désactivée, provider désactivé), ou la route d'achat de l'actif n'existe plus au catalogue du provider (catalogue chargé, plus de route depuis USDC).
 *   Effet : aucun ordre réel (plan, exécuteur) ; le lien, ses signaux et sa simulation restent visibles ; jamais de bascule de provider ;</li>
 *   <li><b>en attente de validation</b> : l'actif n'a pas de ligne {@code asset_base_rate} valide. Effet : simulation seule, passage en exécution ACTIVE refusé.</li>
 * </ul>
 * Un catalogue jamais chargé (ou un exchange sans client de catalogue) ne suspend rien : il n'y a pas de donnée pour juger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkStateService {

    /** Cause d'une suspension. */
    public enum SuspensionReason { BLOCKLISTED, PROVIDER_DISCONNECTED, ROUTE_REMOVED }

    /** Message 400 de l'activation d'un lien dont l'actif n'a pas de ligne de taux validée. */
    public static final String PENDING_VALIDATION_MESSAGE = "Actif en attente de validation : simulation seule, exécution réelle impossible avant validation";

    /** @param suspension nulle si le lien n'est pas suspendu ; {@code suspensionMessage} lisible (« Suspendu : … ») */
    public record LinkState(SuspensionReason suspension, String suspensionMessage, boolean pendingValidation) {

        public static final LinkState NONE = new LinkState(null, null, false);

        public boolean suspended() {
            return suspension != null;
        }

        /** Un ordre réel est-il permis par l'état du lien (ni suspendu ni en attente de validation) ? */
        public boolean allowsRealOrders() {
            return !suspended() && !pendingValidation;
        }

        /** Raison explicite d'un blocage d'ordres réels, nulle si l'état les permet. */
        public String blockMessage() {
            return suspended() ? suspensionMessage : pendingValidation ? PENDING_VALIDATION_MESSAGE : null;
        }
    }

    private final AssetBlocklistRepository blocklist;
    private final AssetBaseRateService rates;
    private final InstrumentCatalog catalog;
    private final AssetEligibilityService eligibility;

    /** État du lien ; un lien archivé n'a aucun état. À appeler dans une transaction (wallet, credential, provider chargés paresseusement). */
    public LinkState of(RainbowLiveBinding binding) {
        if (binding == null || binding.isArchived()) {
            return LinkState.NONE;
        }
        Optional<LinkState> suspended = suspensionOf(binding);
        boolean pending = !rates.isCalibrated(binding.getAssetSymbol());
        return suspended.map(s -> new LinkState(s.suspension(), s.suspensionMessage(), pending)).orElse(pending
                ? new LinkState(null, null, true) : LinkState.NONE);
    }

    /** Cause de suspension du lien (premier critère rencontré : liste noire, provider déconnecté, route retirée), ou vide. */
    public Optional<LinkState> suspensionOf(RainbowLiveBinding binding) {
        String asset = binding.getAssetSymbol();
        if (blocklist.existsByAssetSymbol(asset)) {
            return Optional.of(suspended(SuspensionReason.BLOCKLISTED, asset + " est en liste noire"));
        }
        Wallet wallet = binding.getWallet();
        WebProvider provider = wallet == null ? null : wallet.getWebProvider();
        ApiCredential credential = wallet == null ? null : wallet.getCredential();
        if (provider == null || !provider.isEnabled() || credential == null || !credential.isEnabled()) {
            return Optional.of(suspended(SuspensionReason.PROVIDER_DISCONNECTED, "provider déconnecté ou clé supprimée"));
        }
        WebProviderCode code = provider.getCode();
        if (catalog.supports(code)) {
            var live = catalog.storedLiveInstruments(code);
            if (!live.isEmpty() && !eligibility.hasBuyRoute(live, asset)) {
                return Optional.of(suspended(SuspensionReason.ROUTE_REMOVED, "paire ou route " + asset + " retirée du catalogue de " + code));
            }
        }
        return Optional.empty();
    }

    private static LinkState suspended(SuspensionReason reason, String why) {
        return new LinkState(reason, "Suspendu : " + why, false);
    }
}
