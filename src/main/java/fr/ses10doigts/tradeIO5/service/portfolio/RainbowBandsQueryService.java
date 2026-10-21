package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.portfolio.RainbowBandsDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Historique des bandes Rainbow figées d'un lien (runs du preset en base, aucun calcul, aucun appel exchange). */
@Service
@RequiredArgsConstructor
public class RainbowBandsQueryService {

    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveRunRepository runRepository;
    private final DomainClock clock;
    private final RainbowBandsSimulator simulator;

    /** Lien inconnu ou d'un autre utilisateur ⇒ {@link RainbowLiveBindingNotFoundException} (404). */
    @Transactional(readOnly = true)
    public RainbowBandsDto bands(User user, Long bindingId, PortfolioPeriod period) {
        return bands(user, bindingId, period, false);
    }

    /** {@code simulated=true} : bandes recalculées sur les bougies D1 (pointillé), à la place des bandes figées. */
    @Transactional(readOnly = true)
    public RainbowBandsDto bands(User user, Long bindingId, PortfolioPeriod period, boolean simulated) {
        RainbowLiveBinding binding = bindingRepository.findById(bindingId)
                .filter(b -> b.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.binding(bindingId));
        LocalDate from = period.from(clock.now().atOffset(ZoneOffset.UTC).toLocalDate());
        if (simulated) {
            LocalDate to = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
            return new RainbowBandsDto(binding.getId(), binding.getAssetSymbol(), binding.getPreset().getName(), true,
                    simulator.simulate(binding, from, to));
        }
        List<RainbowBandsDto.Point> points = runRepository.findByPresetOrderByDayAsc(binding.getPreset()).stream()
                .filter(r -> !r.getDay().isBefore(from) && r.getSignal() != null)
                .map(r -> point(r.getDay(), r.getSignal())).toList();
        return new RainbowBandsDto(binding.getId(), binding.getAssetSymbol(), binding.getPreset().getName(), false, points);
    }

    private static RainbowBandsDto.Point point(LocalDate day, RainbowLivePassBlock s) {
        return new RainbowBandsDto.Point(day, s.getClose(), s.getSma(), s.getAtr(), s.getBoundDown2(), s.getBoundDown1(),
                s.getBoundUp1(), s.getBoundUp2(), s.getBoundUp3(), s.getZone(), s.getActiveSet(), s.getTrendRegime());
    }
}
