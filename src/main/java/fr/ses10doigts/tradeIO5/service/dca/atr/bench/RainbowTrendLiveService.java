package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
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
 * Passe quotidienne d'un affectation {@code TREND_MIX} : Trend Mix → jeu Bull/Bear de l'actif → UN pas de la machine
 * Rainbow ({@link RainbowAtrStrategy#step}) à partir de l'état persisté de la veille, ATH de l'actif lu en base,
 * dimensionnement par le {@link ReferenceSizer} sur la position VIRTUELLE du moteur. Aucun ordre, aucun appel exchange.
 * <ul>
 *   <li><b>Trend</b> : recalculée sur le dataset D1 chargé (régression + SMA/ATR, réglages persistés du affectation,
 *   {@link RainbowLiveTrendConfigs}), jeu actif = {@link RainbowSetSelector} (RANGE selon le mapping du affectation ;
 *   Bear au départ) ;</li>
 *   <li><b>État</b> : dernière ligne {@link RainbowLiveEngineState} antérieure au jour ; absente (1re passe d'un
 *   affectation) ⇒ amorçage par un rejeu de {@code analysisWindowMonths} mois jusqu'à la veille, état enregistré ;</li>
 *   <li><b>Position virtuelle</b> : quantité suivie par le moteur lui-même ({@link RainbowLiveEngineState#getPositionQty()}),
 *   base de la réserve moon et des ventes, comme le rejeu pine ; indépendante de tout wallet ;</li>
 *   <li><b>Écritures</b> (état du jour, position virtuelle, ATH du jour) : via {@link Outcome#commit()}, appelé par
 *   l'appelant une fois le run enregistré. Le run du lendemain rejoue le jour sur la vraie clôture (même chemin) et
 *   remplace ces écritures.</li>
 * </ul>
 * La même machine produit aussi la {@link LiveDemand} du signal (achat voulu, vente = {@code fraction × position tradable}),
 * dimensionnée ensuite par lien sur son portefeuille réel ({@link LiveSizing}). Les quantités du signal ne sont plafonnées
 * par aucun portefeuille : le plafond réel est appliqué par lien.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowTrendLiveService {

    private static final double EPSILON = 1e-9;

    private final RainbowLiveEngineStateRepository stateRepository;
    private final RainbowAthService athService;
    private final AffectationConfigResolver configResolver;
    private final AdminActionLogService journal;

    /**
     * Bloc à enregistrer + demande live du signal (dimensionnée ensuite par lien) + écritures différées (état, ATH) à
     * valider après l'enregistrement de la passe.
     */
    public record Outcome(RainbowLivePassBlock block, LiveDemand demand, Runnable commitAction) {
        public void commit() {
            if (commitAction != null) {
                commitAction.run();
            }
        }
    }

    public Outcome run(LocalDate day, AssetAffectation affectation, List<MarketData> candles, RainbowAtrDataset ds, int dayIdx) {
        String asset = affectation.getAssetSymbol();
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(affectation);
        String configHash = configResolver.effectiveHash(affectation);
        RainbowAtrParamSet[] sets = cfg.sets();

        TrendMixCalculator.Params tp = cfg.trend();
        TrendRegime[] regime = TrendMixCalculator.compute(candles, ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp)
                .regime();
        int[] setOfBar = RainbowSetSelector.select(regime, cfg.range());
        RainbowAtrParamSet set = sets[setOfBar[dayIdx]];

        Start start = loadOrBootstrap(affectation, day, ds, dayIdx, sets, setOfBar, configHash);
        RainbowAtrState state = start.state();
        double position = start.position();
        AthReference ath = athService.athBefore(asset, day);

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
        applyWanted(block, order, close);
        // vente live = fraction du signal x position TRADABLE réelle du lien (bagPercent x réelle), pas la position virtuelle du moteur
        LiveDemand demand = new LiveDemand(order.buyAmount().doubleValue(),
                tradable -> new ReferenceSizer(BigDecimal.valueOf(base))
                        .size(signal, BigDecimal.valueOf(close), BigDecimal.valueOf(tradable)).sellQuantity().doubleValue(),
                close, base);

        long candleTime = ds.time(dayIdx);
        RainbowAtrState next = step.state();
        double nextPosition = Math.max(0.0, position - order.sellQuantity().doubleValue()
                + order.buyAmount().doubleValue() / close);
        Runnable commit = () -> {
            RainbowLiveEngineState row = stateRepository.findByAffectationAndDay(affectation, day)
                    .orElseGet(() -> RainbowLiveEngineState.of(affectation, day, next));
            row.apply(next);
            row.setPositionQty(nextPosition);
            row.setConfigHash(configHash);
            stateRepository.save(row);
            athService.record(asset, day, high, candleTime);
        };
        log.info("Bench Rainbow (Trend Mix) : affectation={} actif={} jour={} trend={} jeu={} action={}",
                affectation.getId(), asset, day, dayRegime, set.name(), block.getActionType());
        return new Outcome(block, demand, commit);
    }

    /** État de départ du jour : état de la machine + position virtuelle du moteur. */
    private record Start(RainbowAtrState state, double position) {
    }

    /**
     * État de fin du dernier jour antérieur à {@code day}, utilisé tel quel seulement s'il est à jour : calculé avec la
     * config EFFECTIVE courante ({@code configHash}) ET sans trou de passes (dernier jour d'état = veille de {@code day}
     * dans la série, cf. {@link #resync}). Sinon (1re passe, config changée — réglage perso modifié, révision de la
     * stratégie suivie —, ou trou de passes : service arrêté, série sautée faute de bougie) : amorçage par rejeu de la
     * fenêtre avec la config courante, enregistré pour la veille. Un trou est tracé (WARN + journal admin) : sans ce
     * ré-amorçage, un événement manqué (ex. levée du verrou d'achat) ne serait jamais vu.
     */
    private Start loadOrBootstrap(AssetAffectation affectation, LocalDate day, RainbowAtrDataset ds, int dayIdx,
                                  RainbowAtrParamSet[] sets, int[] setOfBar, String configHash) {
        Optional<RainbowLiveEngineState> previous = stateRepository.findFirstByAffectationAndDayBeforeOrderByDayDesc(affectation, day);
        boolean sameConfig = previous.isPresent() && Objects.equals(configHash, previous.get().getConfigHash());
        LocalDate expectedLast = dayIdx > 0 ? dayOf(ds.time(dayIdx - 1)) : day.minusDays(1);
        boolean gap = sameConfig && previous.get().getDay().isBefore(expectedLast);
        if (sameConfig && !gap) {
            return new Start(previous.get().toState(), previous.get().getPositionQty());
        }
        Start start = bootstrap(affectation, day, ds, dayIdx, sets, setOfBar, configHash);
        if (gap) {
            traceGap(affectation, previous.get().getDay(), expectedLast, RainbowLiveDefaultPresets.RESYNC_ACTOR, "trou de passes");
        }
        log.info("Bench Rainbow (Trend Mix) : affectation={} état {} par rejeu de {} jour(s) jusqu'à la veille de {}",
                affectation.getId(), previous.isEmpty() ? "amorcé" : gap ? "resynchronisé (trou de passes)" : "ré-amorcé (config changée)",
                Math.max(0, dayIdx - startIndex(affectation, day, ds, dayIdx)), day);
        return start;
    }

    /**
     * Resynchronisation de l'état d'un affectation jusqu'à la bougie {@code lastIdx} (incluse), SANS run, SANS ordre : seul
     * l'état moteur (verrou, cooldown, armements, position virtuelle) avance. Sans {@code force}, ne fait rien si l'état est
     * déjà à jour (config effective identique et dernier jour d'état ≥ jour de {@code lastIdx}).
     *
     * @param ds dataset borné à {@code lastIdx} (la bougie du jour encore ouverte n'en fait pas partie)
     * @return {@code true} si l'état a été recalculé
     */
    public boolean resync(AssetAffectation affectation, List<MarketData> candles, RainbowAtrDataset ds, int lastIdx,
                          boolean force, String actor, String reason) {
        LocalDate lastDay = dayOf(ds.time(lastIdx));
        String configHash = configResolver.effectiveHash(affectation);
        Optional<RainbowLiveEngineState> latest = stateRepository.findFirstByAffectationAndDayBeforeOrderByDayDesc(affectation, lastDay.plusDays(1));
        boolean upToDate = latest.isPresent() && Objects.equals(configHash, latest.get().getConfigHash())
                && !latest.get().getDay().isBefore(lastDay);
        if (upToDate && !force) {
            return false;
        }
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(affectation);
        TrendMixCalculator.Params tp = cfg.trend();
        TrendRegime[] regime = TrendMixCalculator.compute(candles, ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime();
        int[] setOfBar = RainbowSetSelector.select(regime, cfg.range());
        // toute la chaîne est périmée (la passe suivante rejoue la veille depuis l'état d'avant : il doit être sain ou absent)
        stateRepository.deleteByAffectation(affectation);
        stateRepository.flush();
        bootstrap(affectation, lastDay.plusDays(1), ds, lastIdx + 1, cfg.sets(), setOfBar, configHash);
        LocalDate previousDay = latest.map(RainbowLiveEngineState::getDay).orElse(null);
        traceGap(affectation, previousDay, lastDay, actor, reason);
        log.warn("Bench Rainbow (Trend Mix) : affectation={} état resynchronisé jusqu'au {} ({}, dernier état connu : {})",
                affectation.getId(), lastDay, reason, previousDay);
        return true;
    }

    private void traceGap(AssetAffectation affectation, LocalDate lastStateDay, LocalDate resyncedTo, String actor, String reason) {
        log.warn("Bench Rainbow (Trend Mix) : affectation={} actif={} {} : dernier état {} -> resynchronisé jusqu'au {}",
                affectation.getId(), affectation.getAssetSymbol(), reason, lastStateDay, resyncedTo);
        journal.record(actor, AdminAction.RUN, RainbowLiveDefaultPresets.RESYNC_TARGET_PREFIX + affectation.getId(),
                AdminActionLogService.payload("asset", affectation.getAssetSymbol(), "reason", reason,
                        "lastStateDay", lastStateDay == null ? null : lastStateDay.toString(), "resyncedTo", resyncedTo.toString()),
                AdminActionOutcome.OK);
    }

    private int startIndex(AssetAffectation affectation, LocalDate day, RainbowAtrDataset ds, int dayIdx) {
        return Math.min(firstIndexOnOrAfter(ds, day.minusMonths(configResolver.analysisWindowMonths(affectation))), dayIdx);
    }

    /** Amorçage par rejeu de la fenêtre jusqu'à la bougie précédant {@code dayIdx} ; état enregistré pour ce jour. */
    private Start bootstrap(AssetAffectation affectation, LocalDate day, RainbowAtrDataset ds, int dayIdx,
                            RainbowAtrParamSet[] sets, int[] setOfBar, String configHash) {
        int startIdx = startIndex(affectation, day, ds, dayIdx);
        RainbowAtrReplay.Result boot = RainbowAtrReplay.runFull(ds, sets, setOfBar, startIdx, dayIdx - 1);
        if (dayIdx > 0) {
            LocalDate stateDay = dayOf(ds.time(dayIdx - 1));
            RainbowLiveEngineState row = stateRepository.findByAffectationAndDay(affectation, stateDay)
                    .orElseGet(() -> RainbowLiveEngineState.of(affectation, stateDay, boot.finalState()));
            row.apply(boot.finalState());
            row.setPositionQty(boot.finalPosition());
            row.setConfigHash(configHash);
            stateRepository.save(row);
        }
        return new Start(boot.finalState(), boot.finalPosition());
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

    /** Action voulue par le moteur (sans plafond de portefeuille) ; achat ET vente le même jour : la vente l'emporte. */
    private static void applyWanted(RainbowLivePassBlock block, Sizer.Order order, double close) {
        double buyAmount = order.buyAmount().doubleValue();
        double sellQty = order.sellQuantity().doubleValue();
        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow (Trend Mix) : achat ET vente le même jour (achat {} USDC, vente {}) : la vente l'emporte",
                    buyAmount, sellQty);
        }
        if (sellQty > EPSILON) {
            block.setActionType(RainbowLiveAction.SELL);
            block.setActionQuantity(sellQty);
            block.setActionAmountUsdc(sellQty * close);
        } else if (buyAmount > EPSILON) {
            block.setActionType(RainbowLiveAction.BUY);
            block.setActionAmountUsdc(buyAmount);
            block.setActionQuantity(buyAmount / close);
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

    private static LocalDate dayOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
