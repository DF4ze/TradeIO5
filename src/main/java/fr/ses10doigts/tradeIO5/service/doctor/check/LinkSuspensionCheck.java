package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Liens suspendus ({@link LinkStateService} : paire ou route retirée du catalogue, provider déconnecté ou clé supprimée, actif en liste noire) :
 * une alarme WARN par lien. Réconciliée à chaque passe du Docteur : clôturée quand la cause disparaît (le lien n'est plus rendu), pas de doublon
 * (clé = contrôle + lien), acquittable. Lecture seule, catalogue en base, aucun appel réseau. La notification de l'utilisateur est un TODO hors lot.
 */
@Component
@RequiredArgsConstructor
public class LinkSuspensionCheck implements DoctorCheck {

    private final RainbowLiveBindingRepository bindings;
    private final LinkStateService linkStates;

    @Override
    public String id() {
        return "LINK_SUSPENDED";
    }

    @Override
    public String label() {
        return "Liens suspendus (paire retirée, provider déconnecté, liste noire)";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.DATA;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckResult> run() {
        List<CheckResult> results = new ArrayList<>();
        for (RainbowLiveBinding binding : bindings.findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc()) {
            linkStates.suspensionOf(binding).ifPresent(state -> results.add(CheckResult.warn(
                    "lien#" + binding.getId() + " " + binding.getAssetSymbol() + " (user " + binding.getUser().getId() + ")",
                    state.suspensionMessage() + " : aucun ordre réel tant que la cause persiste")));
        }
        return results;
    }
}
