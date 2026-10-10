package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RealPortfolioSource : lecture seule du wallet lié, périmètre BTC/ETH/PAXG/USD (USDC + USDT), jamais de 0 silencieux")
class RealPortfolioSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T23:55:00Z");

    private final RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
    private final BindingCheck bindingCheck = mock(BindingCheck.class);
    private final ReadOnlyBalanceReader reader = mock(ReadOnlyBalanceReader.class);
    private final User user = User.builder().id(1L).username("u").build();
    private final ApiCredential credential = new ApiCredential();
    private RainbowLivePreset preset;
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private RealPortfolioSource source;

    @BeforeEach
    void setUp() {
        preset = RainbowLivePreset.builder().id(5L).user(user).assetSymbol("BTC").build();
        Wallet wallet = Wallet.builder().id(9L).webProviderCode(WebProviderCode.OKX).credential(credential).build();
        RainbowLiveBinding binding = RainbowLiveBinding.builder().id(3L).user(user).assetSymbol("BTC").preset(preset)
                .wallet(wallet).bagPercent(100).priority(0).build();
        when(bindings.findByUserAndAssetSymbol(user, "BTC")).thenReturn(Optional.of(binding));
        when(bindingCheck.check(binding)).thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "OK"));
        when(reader.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC", "USDT"));
        when(groups.byMember(eq(AssetGroup.USD), any())).thenCallRealMethod();
        source = new RealPortfolioSource(bindings, bindingCheck, List.of(reader), groups, new FixedDomainClock(NOW),
                Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("OK : cash USDC + positions du périmètre ; autres soldes ignorés")
    void okKeepsScopeOnly() {
        when(reader.getAvailableBalances(credential)).thenReturn(Map.of("USDC", new BigDecimal("250.5"),
                "BTC", new BigDecimal("0.4"), "DOGE", new BigDecimal("9999")));

        PortfolioReading r = source.read(preset);

        assertEquals(PortfolioStatus.OK, r.status());
        assertEquals(250.5, r.cash(), 1e-12);
        assertEquals(0.4, r.quantity("BTC"), 1e-12);
        assertEquals(0.0, r.quantity("DOGE"), 0);
        assertEquals(9L, r.walletId());
        assertEquals(NOW, r.fetchedAt());
    }

    @Test
    @DisplayName("Solde USDC absent mais BTC présent : cash 0, lecture OK (clé absente = solde nul)")
    void missingUsdcIsZero() {
        when(reader.getAvailableBalances(credential)).thenReturn(Map.of("BTC", new BigDecimal("1")));

        PortfolioReading r = source.read(preset);

        assertEquals(PortfolioStatus.OK, r.status());
        assertEquals(0.0, r.cash(), 0);
    }

    @Test
    @DisplayName("Cash = USDC + USDT, détail par membre conservé")
    void cashIsUsdGroupSum() {
        when(reader.getAvailableBalances(credential)).thenReturn(Map.of("USDC", new BigDecimal("100"),
                "USDT", new BigDecimal("30.5"), "BTC", new BigDecimal("0.1")));

        PortfolioReading r = source.read(preset);

        assertEquals(PortfolioStatus.OK, r.status());
        assertEquals(130.5, r.cash(), 1e-12);
        assertEquals(Map.of("USDC", 100.0, "USDT", 30.5), r.cashByMember());
    }

    @Test
    @DisplayName("USDT seul (sans USDC ni BTC) : lecture OK, cash = USDT")
    void usdtOnlyIsInScope() {
        when(reader.getAvailableBalances(credential)).thenReturn(Map.of("USDT", new BigDecimal("12")));

        PortfolioReading r = source.read(preset);

        assertEquals(PortfolioStatus.OK, r.status());
        assertEquals(12.0, r.cash(), 1e-12);
    }

    @Test
    @DisplayName("Panne de lecture => UNAVAILABLE")
    void outage() {
        when(reader.getAvailableBalances(credential)).thenThrow(new BalanceUnavailableException("timeout"));

        assertEquals(PortfolioStatus.UNAVAILABLE, source.read(preset).status());
    }

    @Test
    @DisplayName("Lecture vide suspecte (aucun solde du périmètre) => UNAVAILABLE")
    void suspiciousEmpty() {
        when(reader.getAvailableBalances(credential)).thenReturn(Map.of());
        assertEquals(PortfolioStatus.UNAVAILABLE, source.read(preset).status());

        when(reader.getAvailableBalances(credential)).thenReturn(Map.of("DOGE", BigDecimal.ONE));
        assertEquals(PortfolioStatus.UNAVAILABLE, source.read(preset).status());
    }

    @Test
    @DisplayName("BindingCheck KO (clé rejetée, paire absente...) => UNAVAILABLE, soldes non lus")
    void bindingCheckFailure() {
        when(bindingCheck.check(any(RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.CREDENTIAL_INVALID, "rejetée"));

        PortfolioReading r = source.read(preset);

        assertEquals(PortfolioStatus.UNAVAILABLE, r.status());
        org.mockito.Mockito.verify(reader, org.mockito.Mockito.never()).getAvailableBalances(any());
    }

    @Test
    @DisplayName("Couple non tradable sans fiat => NOT_TRADABLE, soldes non lus, statut persisté sur le binding")
    void notTradableIsBlockedAndPersisted() {
        when(bindingCheck.check(any(RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "PAXG sans fiat"));

        PortfolioReading r = source.read(preset);

        assertEquals(PortfolioStatus.NOT_TRADABLE, r.status());
        org.mockito.Mockito.verify(reader, org.mockito.Mockito.never()).getAvailableBalances(any());
        org.mockito.ArgumentCaptor<RainbowLiveBinding> saved = org.mockito.ArgumentCaptor.forClass(RainbowLiveBinding.class);
        org.mockito.Mockito.verify(bindings).save(saved.capture());
        assertEquals(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, saved.getValue().getTradability());
        assertEquals(NOW, saved.getValue().getTradabilityCheckedAt());
    }

    @Test
    @DisplayName("Fraîcheur : une lecture plus vieille que le seuil devient STALE")
    void staleAfterThreshold() {
        PortfolioReading old = new PortfolioReading(1, Map.of(), NOW.minus(Duration.ofHours(1)), PortfolioStatus.OK, 9L);

        assertEquals(PortfolioStatus.STALE, source.checkFreshness(old).status());
        assertTrue(source.checkFreshness(new PortfolioReading(1, Map.of(), NOW, PortfolioStatus.OK, 9L)).isOk());
    }
}
