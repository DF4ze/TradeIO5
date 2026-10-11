package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Binding : interrupteur d'exécution, armement admin puis confirmation propriétaire (double validation)")
class RainbowLiveBindingExecutionTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    private final User owner = User.builder().id(1L).build();
    private final User other = User.builder().id(2L).build();
    private final RainbowLiveBinding binding = RainbowLiveBinding.builder().id(5L).user(owner).assetSymbol("BTC").build();
    private RainbowLiveBindingService service;

    @BeforeEach
    void setUp() {
        RainbowLiveBindingRepository repo = mock(RainbowLiveBindingRepository.class);
        when(repo.findById(5L)).thenReturn(Optional.of(binding));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new RainbowLiveBindingService(repo, mock(RainbowLivePresetService.class), mock(WalletRepository.class),
                mock(BindingCheck.class), mock(RainbowLivePresetEventService.class), new FixedDomainClock(NOW));
    }

    @Test
    @DisplayName("Défaut éteint ; le propriétaire active / désactive ; un autre utilisateur => 404")
    void toggle() {
        assertFalse(binding.isExecutionEnabled());
        assertTrue(service.setExecution(owner, 5L, true).isExecutionEnabled());
        assertFalse(service.setExecution(owner, 5L, false).isExecutionEnabled());
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.setExecution(other, 5L, true));
    }

    @Test
    @DisplayName("Confirmation sans armement => 409 ; armer puis confirmer valide ; réarmer remet la confirmation à zéro")
    void doubleValidation() {
        assertThrows(RainbowLiveBindingStateException.class, () -> service.confirmFirstLive(owner, 5L));
        assertFalse(binding.firstLiveValidated());
        service.arm(5L);
        assertNotNull(binding.getFirstLiveApprovedAt());
        assertFalse(binding.firstLiveValidated(), "armé seul ne suffit pas");
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.confirmFirstLive(other, 5L));
        service.confirmFirstLive(owner, 5L);
        assertTrue(binding.firstLiveValidated());
        service.arm(5L);
        assertNull(binding.getFirstLiveConfirmedAt());
        assertFalse(binding.firstLiveValidated());
    }

    @Test
    @DisplayName("Armer un binding inconnu => 404")
    void armUnknown() {
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.arm(99L));
        assertEquals(null, binding.getFirstLiveApprovedAt());
    }
}
