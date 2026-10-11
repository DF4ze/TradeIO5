package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminBindingDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Supervision admin des bindings de tous les utilisateurs (lecture seule, base uniquement, aucun solde). */
@Service
@RequiredArgsConstructor
public class AdminBindingQueryService {

    private final RainbowLiveBindingRepository repository;

    @Transactional(readOnly = true)
    public List<AdminBindingDto> list() {
        return repository.findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc().stream().map(AdminBindingQueryService::toDto).toList();
    }

    static AdminBindingDto toDto(RainbowLiveBinding b) {
        return new AdminBindingDto(b.getId(), b.getUser().getId(), b.getUser().getUsername(), b.getAssetSymbol(),
                b.getWallet().getWebProviderCode() == null ? null : b.getWallet().getWebProviderCode().name(),
                b.getWallet().getId(), b.getWallet().getName(), b.getPreset().getId(), b.getPreset().getName(),
                b.getBagPercent(), b.getPriority(), b.isExecutionEnabled(),
                b.getTradability() == null ? null : b.getTradability().name(), b.getFirstLiveApprovedAt() != null,
                b.getFirstLiveConfirmedAt() != null);
    }
}
