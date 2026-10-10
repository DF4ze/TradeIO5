package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;

@DisplayName("ExecutionGuard : seule une tradabilité OK persistée autorise l'exécution")
class ExecutionGuardTest {

    private final RainbowLiveBinding binding = RainbowLiveBinding.builder().assetSymbol("PAXG")
            .wallet(Wallet.builder().id(4L).build()).build();

    @Test
    @DisplayName("Jamais vérifié => refus")
    void neverChecked() {
        assertThrows(ExecutionBlockedException.class, () -> ExecutionGuard.requireExecutable(binding));
    }

    @Test
    @DisplayName("NOT_TRADABLE_WITHOUT_FIAT et VIA_BRIDGE => refus")
    void blockedOrBridge() {
        binding.recordCheck(new BindingCheckResult(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "x"), Instant.EPOCH);
        assertThrows(ExecutionBlockedException.class, () -> ExecutionGuard.requireExecutable(binding));
        binding.recordCheck(new BindingCheckResult(BindingCheckStatus.TRADABLE_VIA_BRIDGE, "x", "USDT", "USDC/USDT"),
                Instant.EPOCH);
        assertThrows(ExecutionBlockedException.class, () -> ExecutionGuard.requireExecutable(binding));
    }

    @Test
    @DisplayName("OK => autorisé")
    void ok() {
        binding.recordCheck(new BindingCheckResult(BindingCheckStatus.OK, "OK", "USDC", null), Instant.EPOCH);
        assertDoesNotThrow(() -> ExecutionGuard.requireExecutable(binding));
    }

    @Test
    @DisplayName("Avec chemin calculé : VIA_BRIDGE autorisé ; NOT_TRADABLE_WITHOUT_FIAT et jamais vérifié restent refusés")
    void withComputedPath() {
        binding.recordCheck(new BindingCheckResult(BindingCheckStatus.TRADABLE_VIA_BRIDGE, "x", "USDT", "USDC/USDT"),
                Instant.EPOCH);
        assertDoesNotThrow(() -> ExecutionGuard.requireExecutable(binding, true));
        binding.recordCheck(new BindingCheckResult(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "x"), Instant.EPOCH);
        assertThrows(ExecutionBlockedException.class, () -> ExecutionGuard.requireExecutable(binding, true));
        assertThrows(ExecutionBlockedException.class, () -> ExecutionGuard.requireExecutable(
                RainbowLiveBinding.builder().assetSymbol("BTC").wallet(Wallet.builder().id(1L).build()).build(), true));
    }
}
