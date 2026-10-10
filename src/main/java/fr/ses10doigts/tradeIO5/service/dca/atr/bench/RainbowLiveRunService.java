package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Persistance des runs quotidiens (aucun calcul moteur). {@link #upsertPass} est idempotent par
 * (preset, jour, passe) : rejouer une passe ne met à jour que son bloc. Le wallet mock n'est modifié que
 * par la passe 23:55, une seule fois par (preset, jour).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveRunService {

    private final RainbowLiveRunRepository runRepository;
    private final RainbowLiveMockWalletRepository walletRepository;
    private final DomainClock clock;

    /**
     * @param result résultat de la passe fourni par l'appelant (moteur) ; {@code computedAt}, {@code configHash}
     *               et, pour 23:55, {@code cashAfter}/{@code positionAfter} sont renseignés ici.
     *               Rejeu de 23:55 : indicateurs remplacés, mais action et état du wallet d'origine conservés
     *               (ils correspondent à ce qui a été appliqué au wallet), ainsi que le snapshot et l'action live.
     */
    @Transactional
    public RainbowLiveRun upsertPass(RainbowLivePreset preset, LocalDate day, RainbowLivePass pass,
                                     RainbowLivePassBlock result) {
        Instant now = clock.now();
        RainbowLiveRun run = runRepository.findByPresetAndDay(preset, day)
                .orElseGet(() -> RainbowLiveRun.builder()
                        .preset(preset).user(preset.getUser()).assetSymbol(preset.getAssetSymbol()).day(day).build());

        run.setConfig(copyOf(preset));
        run.setConfigHash(run.getConfig().hash());
        result.setConfigHash(run.getConfigHash());
        result.setComputedAt(now);

        if (pass == RainbowLivePass.T2355) {
            RainbowLivePassBlock previous = run.getPass2355();
            if (previous == null) {
                applyToWallet(preset, result, now);
                run.setPass2355(result);
            } else {
                // rejeu : le wallet a déjà reçu l'action d'origine, on la conserve telle quelle
                result.setActionType(previous.getActionType());
                result.setActionAmountUsdc(previous.getActionAmountUsdc());
                result.setActionQuantity(previous.getActionQuantity());
                result.setActionPrice(previous.getActionPrice());
                result.setCashAfter(previous.getCashAfter());
                result.setPositionAfter(previous.getPositionAfter());
                if (previous.hasLiveSnapshot()) {
                    result.copyLiveFrom(previous);
                }
                run.setPass2355(result);
            }
        } else {
            result.setCashAfter(null);
            result.setPositionAfter(null);
            run.setPass0005(result);
        }

        RainbowLiveRun saved = runRepository.save(run);
        log.info("Passe {} enregistrée preset={} actif={} jour={} action={} deltaActionDiffers={}",
                pass, preset.getId(), preset.getAssetSymbol(), day, result.getActionType(), saved.deltaActionDiffers());
        log.debug("Passe {} preset={} jour={} bloc={}", pass, preset.getId(), day, result);
        return saved;
    }

    private void applyToWallet(RainbowLivePreset preset, RainbowLivePassBlock block, Instant now) {
        RainbowLiveMockWallet wallet = walletRepository.findByPreset(preset)
                .orElseThrow(() -> new IllegalStateException("Wallet mock introuvable pour le preset " + preset.getId()));
        RainbowLiveAction action = block.getActionType() == null ? RainbowLiveAction.NONE : block.getActionType();
        block.setActionType(action);
        switch (action) {
            case BUY -> wallet.applyBuy(requirePositive(block.getActionAmountUsdc()), requirePositive(block.getActionQuantity()));
            case SELL -> wallet.applySell(requirePositive(block.getActionAmountUsdc()), requirePositive(block.getActionQuantity()));
            case NONE -> { }
        }
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);
        block.setCashAfter(wallet.cash());
        block.setPositionAfter(wallet.quantity(preset.getAssetSymbol()));
    }

    private static double requirePositive(Double v) {
        if (v == null || v <= 0) {
            throw new IllegalArgumentException("Montant/quantité d'action requis (> 0) pour BUY/SELL");
        }
        return v;
    }

    private static RainbowAtrConfig copyOf(RainbowLivePreset preset) {
        return RainbowAtrConfig.of(preset.getConfig().toTuning(), preset.getConfig().toGlobals());
    }
}
