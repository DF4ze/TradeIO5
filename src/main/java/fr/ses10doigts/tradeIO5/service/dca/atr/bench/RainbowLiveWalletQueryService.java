package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.TransitDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.repository.execution.TransitStockRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveAssetDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveSnapshotDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Affichage du wallet réel des presets live : lit uniquement la base (bindings + dernier run de chaque lien). Aucune
 * dépendance exchange, lecteur de soldes ni {@code BindingCheck} : l'affichage ne déclenche jamais d'appel réseau.
 */
@Service
@RequiredArgsConstructor
public class RainbowLiveWalletQueryService {

    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveBindingRunRepository runRepository;
    private final TransitStockRepository transitRepository;

    /** Un élément par binding de l'utilisateur (ordre de priorité) ; liste vide sans binding. */
    @Transactional(readOnly = true)
    public List<LiveAssetDto> liveAssets(User user) {
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, RainbowLiveBindingRun> latest = runRepository.latestByBindings(bindings).stream()
                .collect(Collectors.toMap(r -> r.getBinding().getId(), Function.identity()));
        return bindings.stream().map(b -> toDto(b, latest.get(b.getId()), transitOf(b))).toList();
    }

    /** Stock en transit actif du wallet du lien visant son actif (lecture base seule). */
    private List<TransitDto> transitOf(RainbowLiveBinding b) {
        return transitRepository.findByWalletIdAndTargetAssetAndResolvedAtIsNull(b.getWallet().getId(), b.getAssetSymbol()).stream()
                .map(s -> new TransitDto(s.getCurrency(), s.getQuantity(),
                        (s.getFinishSide() == null ? StepSide.BUY : s.getFinishSide()).name(), s.getCreatedAt())).toList();
    }

    private static LiveAssetDto toDto(RainbowLiveBinding b, RainbowLiveBindingRun latest, List<TransitDto> transit) {
        var wallet = b.getWallet();
        return new LiveAssetDto(b.getAssetSymbol(), b.getId(), b.getPreset().getId(), b.getPreset().getName(),
                wallet.getId(), wallet.getName(),
                wallet.getWebProviderCode() == null ? null : wallet.getWebProviderCode().name(),
                b.getBagPercent(), b.getPriority(),
                b.getTradability() == null ? null : b.getTradability().name(), tradabilityMessage(b),
                b.isExecutionEnabled(), b.getFirstLiveApprovedAt() != null, b.getFirstLiveConfirmedAt() != null, b.getRouteKey(), transit, latest == null ? null : snapshotOf(latest));
    }

    /** Snapshot live du run ; nul s'il n'en a pas (preset pas encore joué en live). */
    private static LiveSnapshotDto snapshotOf(RainbowLiveBindingRun run) {
        RainbowLiveLiveBlock b = run.getLive();
        return b != null && b.hasLiveSnapshot() ? toSnapshot(run, b) : null;
    }

    /** Avertissement texte seulement quand l'exécution est bloquée (couple non tradable sans fiat). */
    private static String tradabilityMessage(RainbowLiveBinding b) {
        if (b.getTradability() == BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT) {
            return b.getAssetSymbol() + " non tradable sur " + b.getWallet().getWebProviderCode()
                    + " sans passer par une monnaie fiat : exécution bloquée";
        }
        return b.getTradability() == BindingCheckStatus.TRADABLE_VIA_INTERMEDIARY
                ? "Achat en deux jambes via " + (b.getRouteKey() == null ? "un intermédiaire" : b.getRouteKey().replace(">", " → "))
                + " : l'intermédiaire est détenu entre les deux jambes (risque de prix)" : null;
    }

    private static LiveSnapshotDto toSnapshot(RainbowLiveBindingRun run, RainbowLiveLiveBlock b) {
        return new LiveSnapshotDto(run.getDay(), b.getStatus(), b.getFetchedAt(), b.getCashUsd(),
                CashDetailCodec.read(b.getCashDetail()),
                b.getPositionQty(), b.getTradableQty(), b.getCashReserved(), b.getBlockReason(),
                b.getActionType(), b.getActionAmountUsdc(), b.getActionQuantity());
    }
}
