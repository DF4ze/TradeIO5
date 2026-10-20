package fr.ses10doigts.tradeIO5.service.preferences;

import fr.ses10doigts.tradeIO5.model.dto.AlertDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveAssetDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveSnapshotDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.PlanDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.enumerate.AlertType;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveWalletQueryService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Alertes de l'utilisateur, <b>dérivées</b> des statuts déjà en base (dernier snapshot de chaque lien, état de route, dernier
 * plan d'ordres) : aucun état propre, aucun appel exchange, affichage dans l'app seulement. Les types coupés par
 * l'utilisateur sont exclus.
 */
@Service
@RequiredArgsConstructor
public class UserAlertService {

    private static final String WARNING = "WARNING";
    private static final String ERROR = "ERROR";

    private final RainbowLiveWalletQueryService walletQuery;
    private final OrderPlanQueryService planQuery;
    private final UserPreferencesService preferences;

    public List<AlertDto> alerts(User user) {
        Set<AlertType> disabled = preferences.alertsDisabled(user);
        List<AlertDto> all = new ArrayList<>();
        for (LiveAssetDto asset : walletQuery.liveAssets(user)) {
            assetAlerts(asset, all);
        }
        planQuery.latest(user).ifPresent(plan -> planAlert(plan, all));
        return all.stream().filter(a -> !disabled.contains(a.type())).toList();
    }

    private static void assetAlerts(LiveAssetDto a, List<AlertDto> out) {
        LiveSnapshotDto s = a.snapshot();
        if ("CREDENTIAL_INVALID".equals(a.tradability()) || "BALANCE_UNAVAILABLE".equals(a.tradability())) {
            out.add(alert(AlertType.CREDENTIAL_PROBLEM, ERROR, a, s, "Clé rejetée ou soldes illisibles sur " + a.exchange()));
        }
        if ("NOT_TRADABLE_WITHOUT_FIAT".equals(a.tradability()) || (s != null && s.status() == PortfolioStatus.NOT_TRADABLE)) {
            out.add(alert(AlertType.NOT_TRADABLE, WARNING, a, s, a.assetSymbol() + " non tradable sur " + a.exchange()
                    + " sans monnaie fiat : aucune action"));
        }
        if (s != null && s.status() == PortfolioStatus.UNAVAILABLE) {
            out.add(alert(AlertType.DATA_UNAVAILABLE, WARNING, a, s, "Données indisponibles pour " + a.assetSymbol()
                    + " : aucune action"));
        }
        if (s != null && s.liveActionType() == RainbowLiveAction.BLOCKED) {
            out.add(alert(AlertType.BUY_BLOCKED, WARNING, a, s, "Achat " + a.assetSymbol() + " bloqué (" + s.blockReason() + ")"));
        }
        if (a.transit() != null && !a.transit().isEmpty()) {
            out.add(alert(AlertType.TRANSIT_STOCK, WARNING, a, s, "Stock intermédiaire en transit pour " + a.assetSymbol()
                    + " : jambe 2 en attente"));
        }
    }

    private static void planAlert(PlanDto plan, List<AlertDto> out) {
        switch (plan.status()) {
            case BLOCKED, PARTIAL, FAILED -> out.add(new AlertDto(AlertType.PLAN_PROBLEM, ERROR, null, null,
                    "Dernier plan d'ordres " + plan.status() + (plan.blockReason() == null ? "" : " (" + plan.blockReason() + ")"),
                    plan.day()));
            default -> { }
        }
    }

    private static AlertDto alert(AlertType type, String severity, LiveAssetDto a, LiveSnapshotDto s, String message) {
        return new AlertDto(type, severity, a.assetSymbol(), a.bindingId(), message, s == null ? null : s.day());
    }
}
