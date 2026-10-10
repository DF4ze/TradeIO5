package fr.ses10doigts.tradeIO5.repository.dca.bench;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEvent;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface RainbowLivePresetEventRepository extends JpaRepository<RainbowLivePresetEvent, Long> {

    List<RainbowLivePresetEvent> findByUserOrderByOccurredAtDescIdDesc(User user);

    List<RainbowLivePresetEvent> findByUserAndAssetSymbolOrderByOccurredAtDescIdDesc(User user, String assetSymbol);

    boolean existsByPresetAfterIdAndTypeAndStrategyRevision(Long presetAfterId, RainbowLivePresetEventType type,
                                                            Integer strategyRevision);
}
