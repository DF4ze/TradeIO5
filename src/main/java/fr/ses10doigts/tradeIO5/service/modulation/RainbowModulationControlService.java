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
                         Integer autonomyDays) {

        public static Params of(RainbowModulationControl c) {
            return new Params(c.getRegulationMarginPct(), c.getSellBoostMax(), c.getBiasMax(), c.getPowerMin(),
                    c.getPowerMax(), c.getMinBandWidthPts(), c.getModulationProductMax(),
                    c.getAutonomyDays());
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
        if (p.powerMin().compareTo(BigDecimal.ONE) > 0 || p.powerMax().compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("powerMin ≤ 1 ≤ powerMax requis, reçu " + p.powerMin() + " / " + p.powerMax());
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
