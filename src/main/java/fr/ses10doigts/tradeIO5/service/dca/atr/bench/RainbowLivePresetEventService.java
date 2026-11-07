package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEvent;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetEventRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Historisation des changements d'Affectation (activation, bascule de presets), horodatée par {@link DomainClock}. Les
 * Affectations sont référencées par id et libellé, sans FK : l'historique survit aux changements de presets.
 */
@Service
@RequiredArgsConstructor
public class RainbowLivePresetEventService {

    private final RainbowLivePresetEventRepository repository;
    private final DomainClock clock;

    /** Enregistre un événement ; {@code beforeLabel} nul pour une première définition. */
    @Transactional
    public RainbowLivePresetEvent record(User user, String assetSymbol, RainbowLivePresetEventType type, String beforeLabel,
                                         AssetAffectation after, String reason) {
        return repository.save(RainbowLivePresetEvent.builder()
                .user(user)
                .assetSymbol(assetSymbol)
                .type(type)
                .presetBeforeId(beforeLabel == null ? null : after.getId())
                .presetBeforeName(beforeLabel)
                .presetAfterId(after.getId())
                .presetAfterName(after.label())
                .occurredAt(clock.now())
                .reason(reason)
                .build());
    }

    /** Variante pour un événement qui ne change pas les presets (activation / désactivation). */
    @Transactional
    public RainbowLivePresetEvent record(User user, String assetSymbol, RainbowLivePresetEventType type,
                                         AssetAffectation before, AssetAffectation after, String reason) {
        return record(user, assetSymbol, type, before == null ? null : before.label(), after, reason);
    }

    @Transactional(readOnly = true)
    public List<RainbowLivePresetEvent> list(User user, String assetSymbol) {
        return assetSymbol == null
                ? repository.findByUserOrderByOccurredAtDescIdDesc(user)
                : repository.findByUserAndAssetSymbolOrderByOccurredAtDescIdDesc(user, assetSymbol);
    }
}
