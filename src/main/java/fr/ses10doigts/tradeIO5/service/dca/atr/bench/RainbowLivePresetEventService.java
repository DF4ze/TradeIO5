package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEvent;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetEventRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;

/** Historisation des switchs de preset (écriture et lecture), horodatée par {@link DomainClock}. */
@Service
@RequiredArgsConstructor
public class RainbowLivePresetEventService {

    private final RainbowLivePresetEventRepository repository;
    private final DomainClock clock;

    /** Enregistre un événement ; {@code before}/{@code after} peuvent être null. */
    @Transactional
    public RainbowLivePresetEvent record(User user, String assetSymbol, RainbowLivePresetEventType type,
                                         RainbowLivePreset before, RainbowLivePreset after,
                                         Integer strategyRevision, String reason) {
        return repository.save(RainbowLivePresetEvent.builder()
                .user(user)
                .assetSymbol(assetSymbol)
                .type(type)
                .presetBeforeId(before == null ? null : before.getId())
                .presetBeforeName(before == null ? null : before.getName())
                .presetAfterId(after == null ? null : after.getId())
                .presetAfterName(after == null ? null : after.getName())
                .strategyRevision(strategyRevision)
                .occurredAt(clock.now())
                .reason(reason)
                .build());
    }

    /** Écrit {@code STRATEGY_CHANGED} une seule fois par (preset, révision). */
    @Transactional
    public void recordStrategyChange(RainbowLivePreset preset, int revision) {
        if (repository.existsByPresetAfterIdAndTypeAndStrategyRevision(
                preset.getId(), RainbowLivePresetEventType.STRATEGY_CHANGED, revision)) {
            return;
        }
        record(preset.getUser(), preset.getAssetSymbol(), RainbowLivePresetEventType.STRATEGY_CHANGED, preset, preset,
                revision, "Révision " + revision + " de la stratégie « " + preset.getAssetStrategy().getName() + " »");
    }

    @Transactional(readOnly = true)
    public List<RainbowLivePresetEvent> list(User user, String assetSymbol) {
        return assetSymbol == null
                ? repository.findByUserOrderByOccurredAtDescIdDesc(user)
                : repository.findByUserAndAssetSymbolOrderByOccurredAtDescIdDesc(user, assetSymbol);
    }
}
