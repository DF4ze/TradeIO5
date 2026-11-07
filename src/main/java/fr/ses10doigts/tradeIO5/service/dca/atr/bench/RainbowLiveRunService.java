package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Persistance des runs quotidiens (aucun calcul moteur). {@link #upsert} est idempotent par (affectation, jour) : rejouer le même
 * jour remplace les indicateurs mais conserve l'action décidée à la première passe (elle correspond à ce qui a pu être
 * planifié). {@link #refresh} remet à jour les indicateurs d'un jour déjà joué avec la vraie clôture (run du lendemain),
 * action figée.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveRunService {

    private final RainbowLiveRunRepository runRepository;
    private final DomainClock clock;
    private final AffectationConfigResolver configResolver;

    /**
     * @param result signal du jour fourni par l'appelant (moteur) ; {@code computedAt} et {@code configHash} sont renseignés
     *               ici. Rejeu d'un jour déjà enregistré : indicateurs remplacés, action d'origine conservée.
     */
    @Transactional
    public RainbowLiveRun upsert(AssetAffectation affectation, LocalDate day, RainbowLivePassBlock result) {
        RainbowLiveRun run = runRepository.findByAffectationAndDay(affectation, day)
                .orElseGet(() -> RainbowLiveRun.builder()
                        .affectation(affectation).user(affectation.getUser()).assetSymbol(affectation.getAssetSymbol()).day(day).build());
        stamp(affectation, run, result);
        RainbowLivePassBlock previous = run.getSignal();
        if (previous != null) {
            keepAction(previous, result);
        }
        run.setSignal(result);
        RainbowLiveRun saved = runRepository.save(run);
        log.info("Run enregistré affectation={} actif={} jour={} action={}", affectation.getId(), affectation.getAssetSymbol(), day,
                result.getActionType());
        log.debug("Run affectation={} jour={} bloc={}", affectation.getId(), day, result);
        return saved;
    }

    /**
     * Remplace les indicateurs d'un jour déjà joué par ceux calculés sur la bougie clôturée ; l'action décidée à la passe
     * d'origine est conservée. Sans run ce jour-là : rien (pas de rattrapage).
     *
     * @return {@code true} si un run existait et a été rafraîchi
     */
    @Transactional
    public boolean refresh(AssetAffectation affectation, LocalDate day, RainbowLivePassBlock closed) {
        Optional<RainbowLiveRun> existing = runRepository.findByAffectationAndDay(affectation, day);
        if (existing.isEmpty() || existing.get().getSignal() == null) {
            return false;
        }
        RainbowLiveRun run = existing.get();
        stamp(affectation, run, closed);
        keepAction(run.getSignal(), closed);
        run.setSignal(closed);
        runRepository.save(run);
        log.info("Run rafraîchi sur la clôture affectation={} actif={} jour={} close={}", affectation.getId(), affectation.getAssetSymbol(),
                day, closed.getClose());
        return true;
    }

    /**
     * Recalcul forcé d'un jour : supprime les runs de affectation de l'utilisateur pour ce jour afin que la prochaine passe reparte
     * du calcul courant (sans {@link #keepAction}). Réservé aux jours non clôturés dont aucun ordre n'est parti.
     */
    @Transactional
    public long discardDay(User user, LocalDate day) {
        long removed = runRepository.deleteByUserAndDay(user, day);
        log.info("Runs de affectation supprimés user={} jour={} : {} (recalcul forcé)", user.getId(), day, removed);
        return removed;
    }

    /** {@code true} si un signal est enregistré pour ce jour (condition d'un rafraîchissement, sans effet de bord moteur). */
    @Transactional(readOnly = true)
    public boolean hasSignal(AssetAffectation affectation, LocalDate day) {
        return runRepository.findByAffectationAndDay(affectation, day).map(r -> r.getSignal() != null).orElse(false);
    }

    private void stamp(AssetAffectation affectation, RainbowLiveRun run, RainbowLivePassBlock block) {
        run.setConfig(copyOf(configResolver.config(affectation)));
        run.setConfigHash(run.getConfig().hash());
        block.setConfigHash(run.getConfigHash());
        block.setComputedAt(clock.now());
    }

    private static void keepAction(RainbowLivePassBlock from, RainbowLivePassBlock to) {
        to.setActionType(from.getActionType());
        to.setActionAmountUsdc(from.getActionAmountUsdc());
        to.setActionQuantity(from.getActionQuantity());
        to.setActionPrice(from.getActionPrice());
    }

    private static RainbowAtrConfig copyOf(RainbowAtrConfig config) {
        return RainbowAtrConfig.of(config.toTuning(), config.toGlobals());
    }
}
