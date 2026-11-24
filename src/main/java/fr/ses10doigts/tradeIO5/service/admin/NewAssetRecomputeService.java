package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.NewAssetLinkedEvent;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.NewAssetProposalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bouton admin « Recalculer » : relance le calcul D1b d'un actif (proposition refusée, calcul dégradé, historique devenu suffisant, recalibrage d'une ligne
 * déjà validée). Le calcul lui-même vit dans {@link NewAssetProposalService} (file asynchrone, un calcul à la fois par actif) ; ce service valide la demande,
 * choisit le lien de contexte (plus ancien lien non archivé de l'actif) et journalise ({@link AdminAction#MODULATION}, cible {@value #TARGET_PREFIX}{@code <ACTIF>}).
 * Pipeline coupé ({@code tradeio.new-asset-proposal.enabled=false}) : refus avec message clair, aucun calcul.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewAssetRecomputeService {

    public static final String TARGET_PREFIX = "new-asset-recompute:";
    public static final String DISABLED_MESSAGE = "Calcul des nouveaux actifs désactivé (" + NewAssetProposalService.ENABLED_PROPERTY
            + "=false) : aucun recalcul possible";

    /** Réponse : le calcul est en file (le résultat arrive de façon asynchrone, jusqu'à quelques minutes). */
    public record Started(String asset, String status, String message) {
    }

    private final ObjectProvider<NewAssetProposalService> proposalService;
    private final RainbowLiveBindingRepository bindings;
    private final NewAssetProposalRepository proposals;
    private final AdminActionLogService journal;

    /** Le pipeline de calcul est-il actif sur ce serveur ? */
    public boolean enabled() {
        return proposalService.getIfAvailable() != null;
    }

    /** Un calcul est-il en file ou en cours pour l'actif ? Faux si le pipeline est coupé. */
    public boolean running(String asset) {
        NewAssetProposalService service = proposalService.getIfAvailable();
        return service != null && service.isRunning(asset);
    }

    /**
     * @throws IllegalArgumentException symbole invalide, ou aucun lien non archivé sur l'actif (rien à recalculer)
     * @throws IllegalStateException    pipeline désactivé, ou calcul déjà en cours pour l'actif
     */
    @Transactional(readOnly = true)
    public Started recompute(String asset, String actor) {
        String symbol = null;
        try {
            final String sym = AssetEligibilityDefaults.normalizeSymbol(asset);
            symbol = sym;
            NewAssetProposalService service = proposalService.getIfAvailable();
            if (service == null) {
                throw new IllegalStateException(DISABLED_MESSAGE);
            }
            RainbowLiveBinding link = bindings.findFirstByAssetSymbolAndArchivedAtIsNullOrderByIdAsc(symbol)
                    .orElseThrow(() -> new IllegalArgumentException("Aucun lien actif sur " + sym + " : rien à recalculer"));
            String previous = proposals.findFirstByAssetSymbolOrderByIdDesc(symbol).map(NewAssetProposal::getStatus).map(Enum::name).orElse(null);
            service.requestRecompute(new NewAssetLinkedEvent(link.getUser().getId(), symbol, link.getId(), link.getWallet().getId()));
            journal.record(actor, AdminAction.MODULATION, TARGET_PREFIX + symbol, AdminActionLogService.payload("action", "recompute", "asset", symbol,
                    "linkId", link.getId(), "previousStatus", previous), AdminActionOutcome.OK);
            return new Started(symbol, "EN_FILE", "Calcul de " + symbol + " mis en file : la proposition sera remplacée à la fin du calcul");
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.MODULATION, TARGET_PREFIX + (symbol == null ? "?" : symbol), AdminActionLogService.payload("action", "recompute",
                    "asset", symbol, "error", e.getMessage()), e instanceof IllegalArgumentException || e instanceof IllegalStateException
                    ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }
}
