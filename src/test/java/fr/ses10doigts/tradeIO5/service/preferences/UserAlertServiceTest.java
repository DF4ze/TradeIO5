package fr.ses10doigts.tradeIO5.service.preferences;

import fr.ses10doigts.tradeIO5.model.dto.AlertDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveAssetDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveSnapshotDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.TransitDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.enumerate.AlertType;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveWalletQueryService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Alertes utilisateur : dérivées des statuts, types coupés exclus")
class UserAlertServiceTest {

    private final RainbowLiveWalletQueryService wallet = mock(RainbowLiveWalletQueryService.class);
    private final OrderPlanQueryService plans = mock(OrderPlanQueryService.class);
    private final UserPreferencesService prefs = mock(UserPreferencesService.class);
    private final UserAlertService service = new UserAlertService(wallet, plans, prefs);
    private final User user = User.builder().username("alice").build();

    @BeforeEach
    void setUp() {
        when(plans.latest(any())).thenReturn(Optional.empty());
        when(prefs.alertsDisabled(any())).thenReturn(EnumSet.noneOf(AlertType.class));
    }

    private static LiveSnapshotDto snapshot(PortfolioStatus status, RainbowLiveAction action, LiveBlockReason reason) {
        return new LiveSnapshotDto(LocalDate.of(2026, 10, 20), status, Instant.EPOCH, 10.0, Map.of(), 0.0, 0.0, 0.0, reason,
                action, null, null, null);
    }

    private static LiveAssetDto asset(String tradability, List<TransitDto> transit, LiveSnapshotDto snapshot) {
        return new LiveAssetDto("BTC", 7L, 1L, "p", 2L, "OKX", "OKX", 100, 0, tradability, null, false, false, false, null, transit,
                snapshot);
    }

    @Test
    @DisplayName("Tout va bien : aucune alerte")
    void nothingToReport() {
        when(wallet.liveAssets(any())).thenReturn(List.of(asset("OK", List.of(),
                snapshot(PortfolioStatus.OK, RainbowLiveAction.BUY, LiveBlockReason.NONE))));
        assertTrue(service.alerts(user).isEmpty());
    }

    @Test
    @DisplayName("Achat bloqué, clé rejetée, données indisponibles, transit : une alerte par cause")
    void derivedFromStatuses() {
        when(wallet.liveAssets(any())).thenReturn(List.of(
                asset("CREDENTIAL_INVALID", List.of(new TransitDto("BTC", BigDecimal.ONE, "BUY", Instant.EPOCH)),
                        snapshot(PortfolioStatus.UNAVAILABLE, RainbowLiveAction.NONE, LiveBlockReason.UNAVAILABLE)),
                asset("OK", List.of(), snapshot(PortfolioStatus.OK, RainbowLiveAction.BLOCKED, LiveBlockReason.INSUFFICIENT_CASH))));

        Set<AlertType> types = EnumSet.noneOf(AlertType.class);
        service.alerts(user).stream().map(AlertDto::type).forEach(types::add);
        assertEquals(EnumSet.of(AlertType.CREDENTIAL_PROBLEM, AlertType.DATA_UNAVAILABLE, AlertType.TRANSIT_STOCK,
                AlertType.BUY_BLOCKED), types);
    }

    @Test
    @DisplayName("Type coupé par l'utilisateur : exclu")
    void disabledTypeExcluded() {
        when(wallet.liveAssets(any())).thenReturn(List.of(asset("OK", List.of(),
                snapshot(PortfolioStatus.OK, RainbowLiveAction.BLOCKED, LiveBlockReason.INSUFFICIENT_CASH))));
        when(prefs.alertsDisabled(any())).thenReturn(EnumSet.of(AlertType.BUY_BLOCKED));
        assertTrue(service.alerts(user).isEmpty());
    }
}
