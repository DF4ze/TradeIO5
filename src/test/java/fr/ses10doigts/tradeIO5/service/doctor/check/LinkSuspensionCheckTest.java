package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.LinkState;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.SuspensionReason;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LinkSuspensionCheck : une alarme WARN par lien suspendu, sujet stable (pas de doublon), plus de résultat quand la cause disparaît")
class LinkSuspensionCheckTest {

    private final RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
    private final LinkStateService states = mock(LinkStateService.class);
    private final LinkSuspensionCheck check = new LinkSuspensionCheck(bindings, states);
    private final User user = User.builder().id(1L).build();
    private final RainbowLiveBinding suspended = RainbowLiveBinding.builder().id(10L).user(user).assetSymbol("SOL").build();
    private final RainbowLiveBinding healthy = RainbowLiveBinding.builder().id(11L).user(user).assetSymbol("BTC").build();

    @Test
    @DisplayName("Seuls les liens suspendus produisent un WARN ; deux passes => même sujet ; cause levée => aucun résultat")
    void warnsPerSuspendedLinkThenCloses() {
        when(bindings.findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc()).thenReturn(List.of(suspended, healthy));
        when(states.suspensionOf(suspended)).thenReturn(Optional.of(new LinkState(SuspensionReason.BLOCKLISTED, "Suspendu : SOL est en liste noire", false)));
        when(states.suspensionOf(healthy)).thenReturn(Optional.empty());

        List<CheckResult> first = check.run();
        List<CheckResult> second = check.run();
        assertEquals(1, first.size());
        assertEquals(DoctorStatus.WARN, first.getFirst().status());
        assertTrue(first.getFirst().subject().contains("SOL"));
        assertEquals(first.getFirst().subject(), second.getFirst().subject());

        when(states.suspensionOf(suspended)).thenReturn(Optional.empty());
        assertTrue(check.run().isEmpty());
    }

    @Test
    @DisplayName("Identité du contrôle : LINK_SUSPENDED, famille DATA")
    void identity() {
        assertEquals("LINK_SUSPENDED", check.id());
        assertEquals(fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily.DATA, check.family());
    }
}
