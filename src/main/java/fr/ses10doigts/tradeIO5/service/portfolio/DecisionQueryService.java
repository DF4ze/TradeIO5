package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.portfolio.DecisionDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.StrategyStateDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Journal des décisions : snapshots live par lien et par jour, filtrables par actif et wallet. */
@Service
@RequiredArgsConstructor
public class DecisionQueryService {

    private final RainbowLiveBindingRunRepository runRepository;
    private final RainbowLiveRunRepository signalRepository;
    private final RainbowLiveEngineStateRepository engineStateRepository;

    @Transactional(readOnly = true)
    public List<DecisionDto> decisions(User user, LocalDate from, LocalDate to, String assetSymbol, Long walletId) {
        Map<String, StrategyStateDto> states = new HashMap<>();
        return runRepository.findByBindingUserAndDayBetweenOrderByDayDesc(user, from, to).stream()
                .filter(r -> r.getLive() != null)
                .filter(r -> assetSymbol == null || assetSymbol.equalsIgnoreCase(r.getBinding().getAssetSymbol()))
                .filter(r -> walletId == null || (r.getBinding().getWallet() != null && walletId.equals(r.getBinding().getWallet().getId())))
                .map(r -> toDto(r, states.computeIfAbsent(r.getBinding().getPreset().getId() + "@" + r.getDay(), k -> state(r))))
                .toList();
    }

    /** État de la machine du preset du lien à la fin du jour (un preset peut servir plusieurs liens : calculé une fois par jour). */
    private StrategyStateDto state(RainbowLiveBindingRun r) {
        RainbowLiveRun signal = signalRepository.findByPresetAndDay(r.getBinding().getPreset(), r.getDay()).orElse(null);
        if (signal == null) {
            return null;
        }
        RainbowLiveEngineState engine = engineStateRepository.findByPresetAndDay(r.getBinding().getPreset(), r.getDay()).orElse(null);
        return StrategyStateDescriber.describe(signal.getSignal(), engine, signal.getConfig());
    }

    private static DecisionDto toDto(RainbowLiveBindingRun r, StrategyStateDto state) {
        RainbowLiveLiveBlock live = r.getLive();
        return new DecisionDto(r.getBinding().getId(), r.getBinding().getAssetSymbol(),
                r.getBinding().getWallet() == null ? null : r.getBinding().getWallet().getId(), r.getDay(),
                live.getStatus() == null ? null : live.getStatus().name(),
                live.getActionType() == null ? null : live.getActionType().name(),
                live.getActionAmountUsdc(), live.getActionQuantity(),
                live.getBlockReason() == null ? null : live.getBlockReason().name(), live.getModulation(), r.getComputedAt(), state);
    }
}
