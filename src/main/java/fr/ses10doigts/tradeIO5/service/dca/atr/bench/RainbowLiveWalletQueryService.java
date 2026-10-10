package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveAssetDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveSnapshotDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Affichage du wallet réel des presets live : lit uniquement la base (bindings + dernier run du preset lié). Aucune
 * dépendance exchange, lecteur de soldes ni {@code BindingCheck} : l'affichage ne déclenche jamais d'appel réseau.
 */
@Service
@RequiredArgsConstructor
public class RainbowLiveWalletQueryService {

    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveRunRepository runRepository;

    /** Un élément par binding de l'utilisateur (ordre de priorité) ; liste vide sans binding. */
    @Transactional(readOnly = true)
    public List<LiveAssetDto> liveAssets(User user) {
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<RainbowLivePreset> presets = bindings.stream().map(RainbowLiveBinding::getPreset).toList();
        Map<Long, RainbowLiveRun> latest = runRepository.latestByPresets(presets).stream()
                .collect(Collectors.toMap(r -> r.getPreset().getId(), Function.identity()));
        return bindings.stream().map(b -> toDto(b, latest.get(b.getPreset().getId()))).toList();
    }

    private static LiveAssetDto toDto(RainbowLiveBinding b, RainbowLiveRun latest) {
        var wallet = b.getWallet();
        return new LiveAssetDto(b.getAssetSymbol(), b.getId(), b.getPreset().getId(), b.getPreset().getName(),
                wallet.getId(), wallet.getName(),
                wallet.getWebProviderCode() == null ? null : wallet.getWebProviderCode().name(),
                b.getBagPercent(), b.getPriority(), latest == null ? null : snapshotOf(latest));
    }

    /** Bloc le plus récent portant un snapshot live (00:05 avant 23:55) ; nul si le run n'en a pas (preset pas encore joué en live). */
    private static LiveSnapshotDto snapshotOf(RainbowLiveRun run) {
        RainbowLivePassBlock b0005 = run.getPass0005();
        if (b0005 != null && b0005.hasLiveSnapshot()) {
            return toSnapshot(run, RainbowLivePass.T0005, b0005);
        }
        RainbowLivePassBlock b2355 = run.getPass2355();
        return b2355 != null && b2355.hasLiveSnapshot() ? toSnapshot(run, RainbowLivePass.T2355, b2355) : null;
    }

    private static LiveSnapshotDto toSnapshot(RainbowLiveRun run, RainbowLivePass pass, RainbowLivePassBlock b) {
        return new LiveSnapshotDto(run.getDay(), pass, b.getLiveStatus(), b.getLiveFetchedAt(), b.getLiveCashUsdc(),
                b.getLivePositionQty(), b.getLiveTradableQty(), b.getLiveCashReserved(), b.getLiveBlockReason(),
                b.getLiveActionType(), b.getLiveActionAmountUsdc(), b.getLiveActionQuantity());
    }
}
