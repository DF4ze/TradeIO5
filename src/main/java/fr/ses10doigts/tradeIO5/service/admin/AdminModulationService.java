package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService.Params;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Modification des paramètres admin de la modulation, tracée au journal admin (valeurs avant/après, aucun secret). */
@Service
@RequiredArgsConstructor
public class AdminModulationService {

    public static final String TARGET = "rainbow-modulation";

    private final RainbowModulationControlService control;
    private final AdminActionLogService journal;

    public RainbowModulationControl update(Params params, String actor) {
        Params before = Params.of(control.current());
        try {
            RainbowModulationControl saved = control.update(params, actor);
            journal.record(actor, AdminAction.MODULATION, TARGET,
                    AdminActionLogService.payload("before", view(before), "after", view(Params.of(saved))),
                    AdminActionOutcome.OK);
            return saved;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.MODULATION, TARGET,
                    AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    private static Map<String, Object> view(Params p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put(ModulationDefaults.REGULATION_MARGIN_PCT, p.regulationMarginPct().doubleValue());
        view.put(ModulationDefaults.SELL_BOOST_MAX, p.sellBoostMax().doubleValue());
        view.put(ModulationDefaults.BIAS_MAX, p.biasMax().doubleValue());
        view.put(ModulationDefaults.POWER_MIN, p.powerMin().doubleValue());
        view.put(ModulationDefaults.POWER_MAX, p.powerMax().doubleValue());
        view.put(ModulationDefaults.MIN_BAND_WIDTH_PTS, p.minBandWidthPts().doubleValue());
        view.put(ModulationDefaults.MODULATION_PRODUCT_MAX, p.modulationProductMax().doubleValue());
        view.put(ModulationDefaults.AUTONOMY_DAYS, p.autonomyDays());
        return view;
    }
}
