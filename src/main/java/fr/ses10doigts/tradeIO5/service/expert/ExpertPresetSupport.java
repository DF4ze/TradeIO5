package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Collaborateurs communs aux services de presets Trend / Rainbow (évite de répéter sept dépendances par service). */
@Component
@Getter
@RequiredArgsConstructor
public class ExpertPresetSupport {

    private final AssetAffectationRepository affectationRepository;
    private final RainbowLiveEngineStateRepository stateRepository;
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLivePresetEventService eventService;
    private final AdminActionLogService journal;
    private final ExpertAssets assets;
    private final ExpertPresetValidator validator;
    private final DomainClock clock;

    /** Vrai si un lien non archivé référence l'Affectation (c'est ce qui la fait jouer : « moteur allumé »). */
    public boolean isActive(AssetAffectation affectation) {
        return bindingRepository.existsByAffectationAndArchivedAtIsNull(affectation);
    }
}
