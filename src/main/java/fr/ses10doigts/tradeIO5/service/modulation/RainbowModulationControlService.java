package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.repository.modulation.RainbowModulationControlRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Accès à la ligne globale {@link RainbowModulationControl}. Aucune mise en cache : chaque appel relit la base. Ligne absente
 * => défauts en constantes. Toute écriture est validée contre les bornes absolues de {@link ModulationDefaults}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowModulationControlService {

    /** Jeu complet des paramètres admin (un PUT remplace tout). */
    public record Params(BigDecimal regulationMarginPct, BigDecimal sellBoostMax, BigDecimal biasMax, BigDecimal powerMin,
                         BigDecimal powerMax, BigDecimal minBandWidthPts, BigDecimal modulationProductMax,
                         Integer autonomyDays, BigDecimal drawdownCapPrudentPct, BigDecimal drawdownCapBalancedPct,
                         BigDecimal drawdownCapDynamicPct, BigDecimal drawdownCapFullPct,
                         BigDecimal baseRateBtcPrudent, BigDecimal baseRateBtcBalanced, BigDecimal baseRateBtcDynamic, BigDecimal baseRateBtcFull, BigDecimal baseRateEthPrudent, BigDecimal baseRateEthBalanced, BigDecimal baseRateEthDynamic, BigDecimal baseRateEthFull, BigDecimal baseRateMarginK) {

        /** Jeu sans plafonds de drawdown ni taux de base : ils prennent leurs défauts (usage Java ; un PUT JSON doit les fournir). */
        public Params(BigDecimal regulationMarginPct, BigDecimal sellBoostMax, BigDecimal biasMax, BigDecimal powerMin,
                      BigDecimal powerMax, BigDecimal minBandWidthPts, BigDecimal modulationProductMax, Integer autonomyDays) {
            this(regulationMarginPct, sellBoostMax, biasMax, powerMin, powerMax, minBandWidthPts, modulationProductMax,
                    autonomyDays, defaultOf(ModulationDefaults.DRAWDOWN_CAP_PRUDENT_PCT),
                    defaultOf(ModulationDefaults.DRAWDOWN_CAP_BALANCED_PCT), defaultOf(ModulationDefaults.DRAWDOWN_CAP_DYNAMIC_PCT),
                    defaultOf(ModulationDefaults.DRAWDOWN_CAP_FULL_PCT));
        }

        /** Jeu sans taux de base : ils prennent leurs défauts. */
        public Params(BigDecimal regulationMarginPct, BigDecimal sellBoostMax, BigDecimal biasMax, BigDecimal powerMin,
                      BigDecimal powerMax, BigDecimal minBandWidthPts, BigDecimal modulationProductMax, Integer autonomyDays,
                      BigDecimal drawdownCapPrudentPct, BigDecimal drawdownCapBalancedPct, BigDecimal drawdownCapDynamicPct,
                      BigDecimal drawdownCapFullPct) {
            this(regulationMarginPct, sellBoostMax, biasMax, powerMin, powerMax, minBandWidthPts, modulationProductMax, autonomyDays,
                    drawdownCapPrudentPct, drawdownCapBalancedPct, drawdownCapDynamicPct, drawdownCapFullPct,
                    defaultOf(ModulationDefaults.BASE_RATE_BTC_PRUDENT),
                    defaultOf(ModulationDefaults.BASE_RATE_BTC_BALANCED),
                    defaultOf(ModulationDefaults.BASE_RATE_BTC_DYNAMIC),
                    defaultOf(ModulationDefaults.BASE_RATE_BTC_FULL),
                    defaultOf(ModulationDefaults.BASE_RATE_ETH_PRUDENT),
                    defaultOf(ModulationDefaults.BASE_RATE_ETH_BALANCED),
                    defaultOf(ModulationDefaults.BASE_RATE_ETH_DYNAMIC),
                    defaultOf(ModulationDefaults.BASE_RATE_ETH_FULL),
                    defaultOf(ModulationDefaults.BASE_RATE_MARGIN_K));
        }

        private static BigDecimal defaultOf(String name) {
            return ModulationDefaults.limit(name).defaultValue();
        }

        public static Params of(RainbowModulationControl c) {
            return new Params(c.getRegulationMarginPct(), c.getSellBoostMax(), c.getBiasMax(), c.getPowerMin(),
                    c.getPowerMax(), c.getMinBandWidthPts(), c.getModulationProductMax(),
                    c.getAutonomyDays(), c.getDrawdownCapPrudentPct(), c.getDrawdownCapBalancedPct(),
                    c.getDrawdownCapDynamicPct(), c.getDrawdownCapFullPct(),
                    c.getBaseRateBtcPrudent(),
                    c.getBaseRateBtcBalanced(),
                    c.getBaseRateBtcDynamic(),
                    c.getBaseRateBtcFull(),
                    c.getBaseRateEthPrudent(),
                    c.getBaseRateEthBalanced(),
                    c.getBaseRateEthDynamic(),
                    c.getBaseRateEthFull(),
                    c.getBaseRateMarginK());
        }
    }

    private final RainbowModulationControlRepository repository;
    private final DomainClock clock;

    @Transactional(readOnly = true)
    public RainbowModulationControl current() {
        return repository.findById(RainbowModulationControl.SINGLETON_ID)
                .orElseGet(() -> RainbowModulationControl.defaults(Instant.EPOCH, null));
    }

    /** Remplace les paramètres après validation ; {@link IllegalArgumentException} (rien écrit) si une valeur est invalide. */
    @Transactional
    public RainbowModulationControl update(Params params, String by) {
        validate(params);
        RainbowModulationControl control = repository.findById(RainbowModulationControl.SINGLETON_ID)
                .orElseGet(() -> RainbowModulationControl.defaults(clock.now(), by));
        control.setRegulationMarginPct(params.regulationMarginPct());
        control.setSellBoostMax(params.sellBoostMax());
        control.setBiasMax(params.biasMax());
        control.setPowerMin(params.powerMin());
        control.setPowerMax(params.powerMax());
        control.setMinBandWidthPts(params.minBandWidthPts());
        control.setModulationProductMax(params.modulationProductMax());
        control.setAutonomyDays(params.autonomyDays());
        control.setDrawdownCapPrudentPct(params.drawdownCapPrudentPct());
        control.setDrawdownCapBalancedPct(params.drawdownCapBalancedPct());
        control.setDrawdownCapDynamicPct(params.drawdownCapDynamicPct());
        control.setDrawdownCapFullPct(params.drawdownCapFullPct());
        control.setBaseRateBtcPrudent(params.baseRateBtcPrudent());
        control.setBaseRateBtcBalanced(params.baseRateBtcBalanced());
        control.setBaseRateBtcDynamic(params.baseRateBtcDynamic());
        control.setBaseRateBtcFull(params.baseRateBtcFull());
        control.setBaseRateEthPrudent(params.baseRateEthPrudent());
        control.setBaseRateEthBalanced(params.baseRateEthBalanced());
        control.setBaseRateEthDynamic(params.baseRateEthDynamic());
        control.setBaseRateEthFull(params.baseRateEthFull());
        control.setBaseRateMarginK(params.baseRateMarginK());
        control.setUpdatedAt(clock.now());
        control.setUpdatedBy(by);
        RainbowModulationControl saved = repository.save(control);
        log.info("Paramètres de modulation Rainbow mis à jour par {}", by);
        return saved;
    }

    static void validate(Params p) {
        if (p == null) {
            throw new IllegalArgumentException("Paramètres de modulation absents");
        }
        check(ModulationDefaults.REGULATION_MARGIN_PCT, p.regulationMarginPct());
        check(ModulationDefaults.SELL_BOOST_MAX, p.sellBoostMax());
        check(ModulationDefaults.BIAS_MAX, p.biasMax());
        check(ModulationDefaults.POWER_MIN, p.powerMin());
        check(ModulationDefaults.POWER_MAX, p.powerMax());
        check(ModulationDefaults.MIN_BAND_WIDTH_PTS, p.minBandWidthPts());
        check(ModulationDefaults.MODULATION_PRODUCT_MAX, p.modulationProductMax());
        if (p.autonomyDays() == null) {
            throw new IllegalArgumentException(ModulationDefaults.AUTONOMY_DAYS + " est obligatoire");
        }
        check(ModulationDefaults.AUTONOMY_DAYS, BigDecimal.valueOf(p.autonomyDays()));
        check(ModulationDefaults.DRAWDOWN_CAP_PRUDENT_PCT, p.drawdownCapPrudentPct());
        check(ModulationDefaults.DRAWDOWN_CAP_BALANCED_PCT, p.drawdownCapBalancedPct());
        check(ModulationDefaults.DRAWDOWN_CAP_DYNAMIC_PCT, p.drawdownCapDynamicPct());
        check(ModulationDefaults.DRAWDOWN_CAP_FULL_PCT, p.drawdownCapFullPct());
        if (p.drawdownCapPrudentPct().compareTo(p.drawdownCapBalancedPct()) < 0
                || p.drawdownCapBalancedPct().compareTo(p.drawdownCapDynamicPct()) < 0
                || p.drawdownCapDynamicPct().compareTo(p.drawdownCapFullPct()) < 0) {
            throw new IllegalArgumentException("Plafonds de drawdown : Prudent ≤ Équilibré ≤ Dynamique ≤ Full en amplitude requis, reçu "
                    + p.drawdownCapPrudentPct().toPlainString() + " / " + p.drawdownCapBalancedPct().toPlainString() + " / "
                    + p.drawdownCapDynamicPct().toPlainString() + " / " + p.drawdownCapFullPct().toPlainString());
        }
        check(ModulationDefaults.BASE_RATE_BTC_PRUDENT, p.baseRateBtcPrudent());
        check(ModulationDefaults.BASE_RATE_BTC_BALANCED, p.baseRateBtcBalanced());
        check(ModulationDefaults.BASE_RATE_BTC_DYNAMIC, p.baseRateBtcDynamic());
        check(ModulationDefaults.BASE_RATE_BTC_FULL, p.baseRateBtcFull());
        check(ModulationDefaults.BASE_RATE_ETH_PRUDENT, p.baseRateEthPrudent());
        check(ModulationDefaults.BASE_RATE_ETH_BALANCED, p.baseRateEthBalanced());
        check(ModulationDefaults.BASE_RATE_ETH_DYNAMIC, p.baseRateEthDynamic());
        check(ModulationDefaults.BASE_RATE_ETH_FULL, p.baseRateEthFull());
        check(ModulationDefaults.BASE_RATE_MARGIN_K, p.baseRateMarginK());
        requireRising("BTC", p.baseRateBtcPrudent(), p.baseRateBtcBalanced(), p.baseRateBtcDynamic(), p.baseRateBtcFull());
        requireRising("ETH", p.baseRateEthPrudent(), p.baseRateEthBalanced(), p.baseRateEthDynamic(), p.baseRateEthFull());
        if (p.powerMin().compareTo(BigDecimal.ONE) > 0 || p.powerMax().compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("powerMin ≤ 1 ≤ powerMax requis, reçu " + p.powerMin() + " / " + p.powerMax());
        }
    }

    /** Un profil plus offensif n'a jamais un taux plus faible : Prudent ≤ Équilibré ≤ Dynamique ≤ Full. */
    private static void requireRising(String asset, BigDecimal prudent, BigDecimal balanced, BigDecimal dynamic, BigDecimal full) {
        if (prudent.compareTo(balanced) > 0 || balanced.compareTo(dynamic) > 0 || dynamic.compareTo(full) > 0) {
            throw new IllegalArgumentException("Taux de base " + asset + " : Prudent ≤ Équilibré ≤ Dynamique ≤ Full requis, reçu "
                    + prudent.toPlainString() + " / " + balanced.toPlainString() + " / " + dynamic.toPlainString() + " / " + full.toPlainString());
        }
    }

    private static void check(String name, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " est obligatoire");
        }
        ModulationDefaults.Limit limit = ModulationDefaults.limit(name);
        if (!limit.contains(value)) {
            throw new IllegalArgumentException(name + " doit être dans [" + limit.min().toPlainString() + ", "
                    + limit.max().toPlainString() + "], reçu " + value.toPlainString());
        }
    }
}
