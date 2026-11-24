package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BindingExecutionMode;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityService;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetIneligibleException;
import fr.ses10doigts.tradeIO5.service.market.eligibility.IneligibilityReason;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("RainbowLiveBindingService : lien sur un actif dynamique (éligibilité, bootstrap) et porte ACTIVE sans taux")
class RainbowLiveBindingDynamicAssetTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final User user = User.builder().id(1L).build();
    private final RainbowLiveBindingRepository repo = mock(RainbowLiveBindingRepository.class);
    private final AssetAffectationService affectations = mock(AssetAffectationService.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final BindingCheck check = mock(BindingCheck.class);
    private final AssetEligibilityService eligibility = mock(AssetEligibilityService.class);
    private final AssetLinkBootstrap bootstrap = mock(AssetLinkBootstrap.class);
    private final AssetBaseRateService rates = mock(AssetBaseRateService.class);
    private RainbowLiveBindingService service;
    private WebProvider provider;

    @BeforeEach
    void setUp() {
        provider = WebProvider.builder().code(WebProviderCode.OKX).name("OKX").build();
        Wallet wallet = Wallet.builder().id(9L).user(user).webProvider(provider).webProviderCode(WebProviderCode.OKX).build();
        when(wallets.findById(9L)).thenReturn(Optional.of(wallet));
        when(repo.findByUserAndAssetSymbolAndWallet_Id(any(), any(), any())).thenReturn(Optional.empty());
        when(repo.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(affectations.forAsset(eq(user), any())).thenReturn(AssetAffectation.builder().id(5L).build());
        when(check.check(any())).thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "OK"));
        service = new RainbowLiveBindingService(repo, affectations, wallets, check, eligibility, bootstrap, rates, new FixedDomainClock(NOW));
    }

    @Test
    @DisplayName("Actif éligible : éligibilité chez le provider du lien, puis bootstrap (asset, Affectation Système, presets), lien créé")
    void createsLinkOnEligibleAsset() {
        var checked = service.create(user, new RainbowLiveBindingService.CreateRequest("sol", 9L, null, null));
        assertEquals("SOL", checked.binding().getAssetSymbol());
        verify(eligibility).require("SOL", provider);
        verify(bootstrap).prepare(eq("SOL"), eq(provider), any());
        verify(affectations).forAsset(user, "SOL");
    }

    @Test
    @DisplayName("Actif inéligible : 400 avec la raison lisible, ni bootstrap ni lien")
    void refusesIneligibleAsset() {
        var refused = new AssetEligibilityService.Result("DOGE", WebProviderCode.OKX, false, IneligibilityReason.VOLUME_TOO_LOW, null, true, null);
        when(eligibility.require(eq("DOGE"), any())).thenThrow(new AssetIneligibleException(refused));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(user, new RainbowLiveBindingService.CreateRequest("DOGE", 9L, null, null)));
        assertTrue(e.getMessage().contains(IneligibilityReason.VOLUME_TOO_LOW.label()));
        verifyNoInteractions(bootstrap);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Priorité par défaut = fin de file (max + 1) : l'ancienneté du lien départage les égalités")
    void defaultPriorityIsEndOfQueue() {
        RainbowLiveBinding existing = RainbowLiveBinding.builder().id(1L).user(user).assetSymbol("BTC").priority(3).build();
        when(repo.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(List.of(existing));
        var checked = service.create(user, new RainbowLiveBindingService.CreateRequest("SOL", 9L, null, null));
        assertEquals(4, checked.binding().getPriority());
    }

    @Test
    @DisplayName("ACTIVE sans ligne de taux valide : 400 « en attente de validation » ; SIMULATION et DISABLED restent permis")
    void activeRefusedWithoutRate() {
        RainbowLiveBinding binding = RainbowLiveBinding.builder().id(7L).user(user).assetSymbol("SOL").build();
        when(repo.findById(7L)).thenReturn(Optional.of(binding));
        when(rates.isCalibrated("SOL")).thenReturn(false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setExecutionMode(user, 7L, BindingExecutionMode.ACTIVE));
        assertTrue(e.getMessage().contains(LinkStateService.PENDING_VALIDATION_MESSAGE));
        assertDoesNotThrow(() -> service.setExecutionMode(user, 7L, BindingExecutionMode.SIMULATION));
        assertDoesNotThrow(() -> service.setExecutionMode(user, 7L, BindingExecutionMode.DISABLED));
    }

    @Test
    @DisplayName("ACTIVE avec taux valide : autorisé")
    void activeAllowedWithRate() {
        RainbowLiveBinding binding = RainbowLiveBinding.builder().id(7L).user(user).assetSymbol("BTC").build();
        when(repo.findById(7L)).thenReturn(Optional.of(binding));
        when(rates.isCalibrated("BTC")).thenReturn(true);
        assertDoesNotThrow(() -> service.setExecutionMode(user, 7L, BindingExecutionMode.ACTIVE));
    }
}
