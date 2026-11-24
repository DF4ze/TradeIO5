package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.AssetRates;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rates;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Écritures admin des taux de base (4 taux par actif, marge k), tracées au journal admin ({@link AdminAction#MODULATION}) : valeurs avant / après. */
@Service
@RequiredArgsConstructor
public class AdminBaseRateService {

    public static final String TARGET = "base-rates";

    private final AssetBaseRateService rates;
    private final RainbowModulationControlService control;
    private final AdminActionLogService journal;

    /** Enregistre les 4 taux de l'actif (crée la ligne dédiée si l'actif n'en avait pas). */
    public AssetRates save(String asset, Rates request, String actor) {
        String symbol = normalize(asset);
        return journaled(actor, "save", symbol, () -> rates.save(symbol, request, null, actor));
    }

    /** BTC / ETH : table de calage ; autre actif : suppression de la ligne dédiée (retour au repli BTC). */
    public AssetRates resetDefaults(String asset, String actor) {
        String symbol = normalize(asset);
        return journaled(actor, "defaults", symbol, () -> rates.resetDefaults(symbol, actor));
    }

    public RainbowModulationControl updateMarginK(BigDecimal marginK, String actor) {
        BigDecimal before = control.current().getBaseRateMarginK();
        try {
            RainbowModulationControl saved = control.updateMarginK(marginK, actor);
            journal.record(actor, AdminAction.MODULATION, TARGET, AdminActionLogService.payload("action", "margin-k",
                    "before", before == null ? null : before.toPlainString(), "after", marginK.toPlainString()), AdminActionOutcome.OK);
            return saved;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.MODULATION, TARGET, AdminActionLogService.payload("action", "margin-k", "error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    private AssetRates journaled(String actor, String action, String asset, java.util.function.Supplier<AssetRates> write) {
        AssetRates before = rates.rates(asset);
        try {
            AssetRates after = write.get();
            journal.record(actor, AdminAction.MODULATION, TARGET, AdminActionLogService.payload("action", action, "asset", asset,
                    "before", view(before), "after", view(after)), AdminActionOutcome.OK);
            return after;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.MODULATION, TARGET, AdminActionLogService.payload("action", action, "asset", asset,
                    "error", e.getClass().getSimpleName()), e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    private static Map<String, Object> view(AssetRates r) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("prudent", r.rates().prudent().toPlainString());
        view.put("balanced", r.rates().balanced().toPlainString());
        view.put("dynamic", r.rates().dynamic().toPlainString());
        view.put("full", r.rates().full().toPlainString());
        view.put("calibrated", r.calibrated());
        view.put("dedicated", r.dedicated());
        return view;
    }

    private static String normalize(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("Actif invalide : " + asset);
        }
        return asset.trim().toUpperCase(Locale.ROOT);
    }
}
