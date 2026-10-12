package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Persistance des runs de lien (aucun calcul, aucun appel exchange). {@link #upsert} est idempotent par (lien, jour). Rejeu
 * du même jour : le snapshot et l'action live d'origine sont conservés (ils correspondent à ce qui a été réservé dans le cash
 * du pool), le nouveau bloc est ignoré.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveBindingRunService {

    private final RainbowLiveBindingRunRepository runRepository;
    private final DomainClock clock;

    /** Snapshot live déjà enregistré pour le jour (vide si la passe n'a pas encore été jouée pour ce lien). */
    @Transactional(readOnly = true)
    public Optional<RainbowLiveLiveBlock> snapshotOf(RainbowLiveBinding binding, LocalDate day) {
        return runRepository.findByBindingAndDay(binding, day)
                .map(RainbowLiveBindingRun::getLive)
                .filter(RainbowLiveLiveBlock::hasLiveSnapshot);
    }

    @Transactional
    public RainbowLiveBindingRun upsert(RainbowLiveBinding binding, LocalDate day, RainbowLiveLiveBlock block) {
        RainbowLiveBindingRun run = runRepository.findByBindingAndDay(binding, day)
                .orElseGet(() -> RainbowLiveBindingRun.builder().binding(binding).day(day).build());
        if (run.getLive() != null && run.getLive().hasLiveSnapshot()) {
            log.debug("Run de lien binding={} jour={} : rejeu, snapshot d'origine conservé", binding.getId(), day);
            return run;
        }
        run.setLive(block);
        run.setComputedAt(clock.now());
        RainbowLiveBindingRun saved = runRepository.save(run);
        log.info("Run de lien enregistré binding={} actif={} wallet={} jour={} live={}", binding.getId(),
                binding.getAssetSymbol(), binding.getWallet().getId(), day, block.getActionType());
        return saved;
    }
}
