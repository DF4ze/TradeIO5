package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Persistance des runs de lien (aucun calcul, aucun appel exchange). {@link #upsertPass} est idempotent par
 * (lien, jour, passe). Rejeu de la passe 23:55 : le snapshot et l'action live d'origine sont conservés (ils correspondent à
 * ce qui a été réservé dans le cash du pool), le nouveau bloc est ignoré.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveBindingRunService {

    private final RainbowLiveBindingRunRepository runRepository;
    private final DomainClock clock;

    /** Snapshot live déjà enregistré pour la passe (vide si la passe n'a pas encore été jouée pour ce lien). */
    @Transactional(readOnly = true)
    public Optional<RainbowLiveLiveBlock> snapshotOf(RainbowLiveBinding binding, LocalDate day, RainbowLivePass pass) {
        return runRepository.findByBindingAndDay(binding, day)
                .map(run -> pass == RainbowLivePass.T2355 ? run.getPass2355() : run.getPass0005())
                .filter(RainbowLiveLiveBlock::hasLiveSnapshot);
    }

    @Transactional
    public RainbowLiveBindingRun upsertPass(RainbowLiveBinding binding, LocalDate day, RainbowLivePass pass,
                                            RainbowLiveLiveBlock block) {
        RainbowLiveBindingRun run = runRepository.findByBindingAndDay(binding, day)
                .orElseGet(() -> RainbowLiveBindingRun.builder().binding(binding).day(day).build());
        if (pass == RainbowLivePass.T2355) {
            if (run.getPass2355() != null && run.getPass2355().hasLiveSnapshot()) {
                log.debug("Run de lien binding={} jour={} : rejeu 23:55, snapshot d'origine conservé", binding.getId(), day);
                return run;
            }
            run.setPass2355(block);
        } else {
            run.setPass0005(block);
        }
        run.setComputedAt(clock.now());
        RainbowLiveBindingRun saved = runRepository.save(run);
        log.info("Passe {} enregistrée binding={} actif={} wallet={} jour={} live={}", pass, binding.getId(),
                binding.getAssetSymbol(), binding.getWallet().getId(), day, block.getActionType());
        return saved;
    }
}
