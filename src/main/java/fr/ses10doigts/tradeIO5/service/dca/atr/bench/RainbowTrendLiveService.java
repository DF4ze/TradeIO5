package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthReference;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrBand;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrStrategy;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal;
import fr.ses10doigts.tradeIO5.service.dca.atr.ReferenceSizer;
import fr.ses10doigts.tradeIO5.service.dca.atr.Sizer;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Passe quotidienne d'un preset {@code TREND_MIX} : Trend Mix → jeu Bull/Bear de l'actif → UN pas de la machine
 * Rainbow ({@link RainbowAtrStrategy#step}) à partir de l'état persisté de la veille, ATH de l'actif lu en base,
 * dimensionnement par le {@link ReferenceSizer} plafonné par le wallet mock. Aucun ordre, aucun appel exchange.
 * <ul>
 *   <li><b>Trend</b> : recalculée sur le dataset D1 chargé (régression + SMA/ATR, réglages persistés du preset,
 *   {@link RainbowLiveTrendConfigs}), jeu actif = {@link RainbowSetSelector} (RANGE selon le mapping du preset ;
 *   Bear au départ) ;</li>
 *   <li><b>État</b> : dernière ligne {@link RainbowLiveEngineState} antérieure au jour ; absente (1re passe d'un
 *   preset) ⇒ amorçage par un rejeu de {@code analysisWindowMonths} mois jusqu'à la veille, état enregistré ;</li>
 *   <li><b>Écritures</b> (état du jour, ATH du jour) : seulement à la passe 23:55 et seulement via
 *   {@link Outcome#commit()}, appelé par l'appelant une fois la passe enregistrée. La passe 00:05 recalcule sur la
 *   vraie clôture à partir du même état de la veille, sans rien écrire.</li>
 * </ul>
 * Preset live : la même machine alimente en plus l'action recommandée sur le portefeuille réel ({@link LiveSizing}), la vente
 * étant {@code fraction × position tradable} ; la chaîne mock reste inchangée.
 * <p>
 * Limite assumée (comme le mode FIXED) : l'état amorcé par rejeu repose sur une position virtuelle, le wallet mock
 * est la vérité cumulée ; les ordres sont donc plafonnés par le cash / la position du wallet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowTrendLiveService {

    private static final double EPSILON = 1e-9;

    private final RainbowLiveEngineStateRepository stateRepository;
    private final RainbowLiveRunRepository runRepository;
    private final RainbowAthService athService;
    private final RainbowLivePresetConfigResolver configResolver;
    private final RainbowLivePresetEventService eventService;

    /** Bloc à enregistrer + écritures différées (état, ATH) à valider après l'enregistrement de la passe. */
    public record Outcome(RainbowLivePassBlock block, Runnable commitAction) {
        public void commit() {
            if (commitAction != null) {
                commitAction.run();
            }
        }
    }

    public Outcome run(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset, List<MarketData> candles,
                       RainbowAtrDataset ds, int dayIdx, RainbowLiveMockWallet wallet, LiveSizing live) {
        String asset = preset.getAssetSymbol();
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(preset);
        String configHash = configResolver.effectiveHash(preset);
        RainbowAtrParamSet[] sets = cfg.sets();

        TrendMixCalculator.Params tp = cfg.trend();
        TrendRegime[] regime = TrendMixCalculator.compute(candles, ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp)
                .regime();
        int[] setOfBar = RainbowSetSelector.select(regime, cfg.range());
        RainbowAtrParamSet set = sets[setOfBar[dayIdx]];

        RainbowAtrState state = loadOrBootstrap(pass, preset, day, ds, dayIdx, sets, setOfBar, configHash);
        AthReference ath = athService.athBefore(asset, day);

        double[] cashPos = cashAndPositionBefore(pass, day, preset, wallet);
        double cash = cashPos[0];
        double position = cashPos[1];

        double close = ds.close(dayIdx);
        double high = ds.high(dayIdx);
        double sma = ds.sma(set.tuning().smaPeriod())[dayIdx];
        double atr = ds.atr(set.tuning().atrPeriod())[dayIdx];
        boolean valid = !Double.isNaN(sma) && !Double.isNaN(atr);
        RainbowAtrBand band = valid ? RainbowAtrBand.of(close, sma, atr, set.tuning()) : null;

        RainbowAtrStrategy.StepResult step = valid
                ? RainbowAtrStrategy.step(state, band, high, ath, position, set.tuning(), set.globals())
                : RainbowAtrStrategy.stepInvalid(state, close, high, ath, set.globals());
        TrendRegime dayRegime = regime[dayIdx];
        RainbowSignal signal = step.signal().withContext(set.name(), dayRegime == null ? null : dayRegime.name());

        double base = set.globals().baseAmount();
        Sizer.Order order = new ReferenceSizer(BigDecimal.valueOf(base))
                .size(signal, BigDecimal.valueOf(close), BigDecimal.valueOf(position));
        RainbowLivePassBlock block = buildBlock(band, close, signal, step.state());
        applyCaps(block, order, close, cash, position);
        if (live != null) {
            // vente live = fraction du signal x position TRADABLE réelle (bagPercent x réelle), pas la position du mock
            double liveSell = new ReferenceSizer(BigDecimal.valueOf(base))
                    .size(signal, BigDecimal.valueOf(close), BigDecimal.valueOf(live.tradablePosition()))
                    .sellQuantity().doubleValue();
            live.apply(block, order.buyAmount().doubleValue(), liveSell, close);
        }

        Runnable commit = null;
        if (pass == RainbowLivePass.T2355) {
            long candleTime = ds.time(dayIdx);
            RainbowAtrState next = step.state();
            commit = () -> {
                RainbowLiveEngineState row = stateRepository.findByPresetAndDay(preset, day)
                        .orElseGet(() -> RainbowLiveEngineState.of(preset, day, next));
                row.apply(next);
                row.setConfigHash(configHash);
                stateRepository.save(row);
                athService.record(asset, day, high, candleTime);
            };
        }
        log.info("Bench Rainbow (Trend Mix) : preset={} actif={} jour={} passe {} trend={} jeu={} action={}",
                preset.getId(), asset, day, pass, dayRegime, set.name(), block.getActionType());
        return new Outcome(block, commit);
    }

    /**
     * État de fin du dernier jour antérieur à {@code day} s'il a été calculé avec la config EFFECTIVE courante
     * ({@code configHash}). Sinon (1re passe, ou config changée — réglage perso modifié, révision de la stratégie
     * suivie) : amorçage par rejeu de la fenêtre avec la config courante. Le rejeu est enregistré à la passe 23:55
     * (et à la 1re passe, quelle qu'elle soit) ; la passe 00:05 d'un changement de config ne fait que le calculer.
     */
    private RainbowAtrState loadOrBootstrap(RainbowLivePass pass, RainbowLivePreset preset, LocalDate day,
                                            RainbowAtrDataset ds, int dayIdx, RainbowAtrParamSet[] sets,
                                            int[] setOfBar, String configHash) {
        Optional<RainbowLiveEngineState> previous = stateRepository.findFirstByPresetAndDayBeforeOrderByDayDesc(preset, day);
        if (previous.isPresent() && Objects.equals(configHash, previous.get().getConfigHash())) {
            return previous.get().toState();
        }
        boolean reseed = previous.isPresent();
        int startIdx = Math.min(firstIndexOnOrAfter(ds, day.minusMonths(configResolver.analysisWindowMonths(preset))), dayIdx);
        RainbowAtrReplay.Result boot = RainbowAtrReplay.runFull(ds, sets, setOfBar, startIdx, dayIdx - 1);
        if (dayIdx > 0 && (!reseed || pass == RainbowLivePass.T2355)) {
            LocalDate stateDay = dayOf(ds.time(dayIdx - 1));
            RainbowLiveEngineState row = stateRepository.findByPresetAndDay(preset, stateDay)
                    .orElseGet(() -> RainbowLiveEngineState.of(preset, stateDay, boot.finalState()));
            row.apply(boot.finalState());
            row.setConfigHash(configHash);
            stateRepository.save(row);
        }
        if (reseed && preset.isFollowingStrategy()) {
            eventService.recordStrategyChange(preset, preset.getAssetStrategy().getRevision());
        }
        log.info("Bench Rainbow (Trend Mix) : preset={} état {} par rejeu de {} jour(s) jusqu'à la veille de {}",
                preset.getId(), reseed ? "ré-amorcé (config changée)" : "amorcé", Math.max(0, dayIdx - startIdx), day);
        return boot.finalState();
    }

    /** Cash et position AVANT l'action du jour : wallet courant (23:55), ou wallet moins l'action déjà appliquée (00:05). */
    private double[] cashAndPositionBefore(RainbowLivePass pass, LocalDate day, RainbowLivePreset preset,
                                           RainbowLiveMockWallet wallet) {
        double cash = wallet.getCashUsd();
        double pos = wallet.getPositionQuantity();
        if (pass == RainbowLivePass.T0005) {
            Optional<RainbowLiveRun> run = runRepository.findByPresetAndDay(preset, day);
            RainbowLivePassBlock applied = run.map(RainbowLiveRun::getPass2355).orElse(null);
            if (applied != null && applied.getActionType() != null) {
                double amount = nz(applied.getActionAmountUsdc());
                double qty = nz(applied.getActionQuantity());
                if (applied.getActionType() == RainbowLiveAction.BUY) {
                    cash += amount;
                    pos -= qty;
                } else if (applied.getActionType() == RainbowLiveAction.SELL) {
                    cash -= amount;
                    pos += qty;
                }
            }
        }
        return new double[]{cash, Math.max(0.0, pos)};
    }

    private static RainbowLivePassBlock buildBlock(RainbowAtrBand b, double close, RainbowSignal s, RainbowAtrState st) {
        RainbowLivePassBlock.RainbowLivePassBlockBuilder out = RainbowLivePassBlock.builder()
                .close(close)
                .activeSet(s.activeSet())
                .trendRegime(s.trend())
                .moonMode(s.moonMode())
                .buyArmed(st.buyArmed())
                .sellArmed(st.sellArmed())
                .buyLocked(st.buyLocked())
                .cooldownRemaining(st.cooldown())
                .moonReserveQty(st.reserveQty())
                .actionPrice(close)
                .actionType(RainbowLiveAction.NONE);
        if (b != null) {
            out.sma(b.sma()).atr(b.atr()).boundDown2(b.down2()).boundDown1(b.down1())
                    .boundUp1(b.up1()).boundUp2(b.up2()).boundUp3(b.up3()).zone(b.zone())
                    .athDistance(s.athDistance()).buyFactor(s.buyAthFactor()).sellFactor(s.sellAthFactor());
        }
        return out.build();
    }

    /** Plafonds du wallet mock : vente ≤ position, achat ≤ cash ; achat ET vente le même jour : la vente l'emporte. */
    private static void applyCaps(RainbowLivePassBlock block, Sizer.Order order, double close, double cash, double position) {
        double buyAmount = order.buyAmount().doubleValue();
        double sellQty = order.sellQuantity().doubleValue();
        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow (Trend Mix) : achat ET vente le même jour (achat {} USDC, vente {}) : la vente l'emporte",
                    buyAmount, sellQty);
        }
        if (sellQty > 0) {
            double qty = Math.min(sellQty, position);
            if (qty > EPSILON) {
                block.setActionType(RainbowLiveAction.SELL);
                block.setActionQuantity(qty);
                block.setActionAmountUsdc(qty * close);
            }
        } else if (buyAmount > 0) {
            double amount = Math.min(buyAmount, cash);
            if (amount > EPSILON) {
                block.setActionType(RainbowLiveAction.BUY);
                block.setActionAmountUsdc(amount);
                block.setActionQuantity(amount / close);
            }
        }
    }

    private static int firstIndexOnOrAfter(RainbowAtrDataset ds, LocalDate from) {
        long fromMillis = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int i = 0; i < ds.size(); i++) {
            if (ds.time(i) >= fromMillis) {
                return i;
            }
        }
        return ds.size();
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static LocalDate dayOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
