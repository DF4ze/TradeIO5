package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Persistance des runs quotidiens (aucun calcul moteur). {@link #upsert} est idempotent par (preset, jour) : rejouer le même
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
    private final RainbowLivePresetConfigResolver configResolver;

    /**
     * @param result signal du jour fourni par l'appelant (moteur) ; {@code computedAt} et {@code configHash} sont renseignés
     *               ici. Rejeu d'un jour déjà enregistré : indicateurs remplacés, action d'origine conservée.
     */
    @Transactional
    public RainbowLiveRun upsert(RainbowLivePreset preset, LocalDate day, RainbowLivePassBlock result) {
        RainbowLiveRun run = runRepository.findByPresetAndDay(preset, day)
                .orElseGet(() -> RainbowLiveRun.builder()
                        .preset(preset).user(preset.getUser()).assetSymbol(preset.getAssetSymbol()).day(day).build());
        stamp(preset, run, result);
        RainbowLivePassBlock previous = run.getSignal();
        if (previous != null) {
            keepAction(previous, result);
        }
        run.setSignal(result);
        RainbowLiveRun saved = runRepository.save(run);
        log.info("Run enregistré preset={} actif={} jour={} action={}", preset.getId(), preset.getAssetSymbol(), day,
                result.getActionType());
        log.debug("Run preset={} jour={} bloc={}", preset.getId(), day, result);
        return saved;
    }

    /**
     * Remplace les indicateurs d'un jour déjà joué par ceux calculés sur la bougie clôturée ; l'action décidée à la passe
     * d'origine est conservée. Sans run ce jour-là : rien (pas de rattrapage).
     *
     * @return {@code true} si un run existait et a été rafraîchi
     */
    @Transactional
    public boolean refresh(RainbowLivePreset preset, LocalDate day, RainbowLivePassBlock closed) {
        Optional<RainbowLiveRun> existing = runRepository.findByPresetAndDay(preset, day);
        if (existing.isEmpty() || existing.get().getSignal() == null) {
            return false;
        }
        RainbowLiveRun run = existing.get();
        stamp(preset, run, closed);
        keepAction(run.getSignal(), closed);
        run.setSignal(closed);
        runRepository.save(run);
        log.info("Run rafraîchi sur la clôture preset={} actif={} jour={} close={}", preset.getId(), preset.getAssetSymbol(),
                day, closed.getClose());
        return true;
    }

    /** {@code true} si un signal est enregistré pour ce jour (condition d'un rafraîchissement, sans effet de bord moteur). */
    @Transactional(readOnly = true)
    public boolean hasSignal(RainbowLivePreset preset, LocalDate day) {
        return runRepository.findByPresetAndDay(preset, day).map(r -> r.getSignal() != null).orElse(false);
    }

    private void stamp(RainbowLivePreset preset, RainbowLiveRun run, RainbowLivePassBlock block) {
        run.setConfig(copyOf(configResolver.config(preset)));
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
