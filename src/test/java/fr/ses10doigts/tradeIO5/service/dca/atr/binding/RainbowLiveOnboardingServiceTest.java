package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RealPortfolioSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.Candidate;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.PresetOrigin;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkOutcome;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.Proposal;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RainbowLiveOnboardingServiceTest {

    private static final long WALLET_ID = 7L;

    private final RainbowLiveBindingService bindingService = mock(RainbowLiveBindingService.class);
    private final RainbowLiveBindingRepository repository = mock(RainbowLiveBindingRepository.class);
    private final BindingCheck bindingCheck = mock(BindingCheck.class);
    private final BindingPathQuoteService quoteService = mock(BindingPathQuoteService.class);
    private final RealPortfolioSource portfolio = mock(RealPortfolioSource.class);
    private final RainbowLivePresetConfigResolver resolver = mock(RainbowLivePresetConfigResolver.class);
    private final RainbowModulationControlService control = mock(RainbowModulationControlService.class);
    private final RainbowLiveOnboardingService service = new RainbowLiveOnboardingService(bindingService, repository,
            bindingCheck, quoteService, portfolio, resolver, control);

    private final User user = User.builder().id(1L).build();
    private final Wallet wallet = Wallet.builder().id(WALLET_ID).user(user).build();

    @BeforeEach
    void setUp() {
        when(bindingService.wallets(user)).thenReturn(List.of(wallet));
        when(control.current()).thenReturn(RainbowModulationControl.defaults(Instant.EPOCH, "test"));
    }

    private static Candidate system(String asset) {
        return new Candidate(asset.hashCode() + 0L, "Système " + asset, "FIXED", PresetOrigin.SYSTEM, null);
    }

    private RainbowLiveBinding binding(long id, String asset, Double linkBase) {
        RainbowLivePreset preset = RainbowLivePreset.builder().id(id).name("p" + id).assetSymbol(asset).build();
        return RainbowLiveBinding.builder().id(id).user(user).assetSymbol(asset).preset(preset).wallet(wallet)
                .baseAmount(linkBase).build();
    }

    private static RainbowAtrConfig config() {
        return RainbowAtrConfig.builder().multX2(2).multX1(1).multX0_5(0.5).baseAmount(10).build();
    }

    @Test
    @DisplayName("link : actif tradable => lien créé sur le preset système ; bloqué => non lié avec la raison ; existant => inchangé")
    void linkOutcomes() {
        RainbowLiveBinding existing = binding(11L, "BTC", null);
        when(repository.findByUserAndAssetSymbolAndWallet_Id(any(), any(), anyLong())).thenReturn(Optional.empty());
        when(repository.findByUserAndAssetSymbolAndWallet_Id(user, "BTC", WALLET_ID)).thenReturn(Optional.of(existing));
        when(bindingCheck.check(wallet, "ETH")).thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "ok"));
        when(bindingCheck.check(wallet, "PAXG")).thenReturn(
                new BindingCheckResult(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "PAXG non tradable sans fiat"));
        when(bindingService.candidates(user, WALLET_ID, "ETH")).thenReturn(List.of(system("ETH")));
        RainbowLiveBinding created = binding(12L, "ETH", null);
        when(bindingService.create(eq(user), any())).thenReturn(new RainbowLiveBindingService.Checked(created,
                new BindingCheckResult(BindingCheckStatus.OK, "ok")));

        List<LinkResult> results = service.link(user, WALLET_ID);

        assertEquals(LinkOutcome.ALREADY_LINKED, results.get(0).outcome());
        assertEquals(LinkOutcome.LINKED, results.get(1).outcome());
        assertEquals(12L, results.get(1).bindingId());
        assertEquals(LinkOutcome.NOT_LINKED, results.get(2).outcome());
        assertEquals("NOT_TRADABLE_WITHOUT_FIAT", results.get(2).status());
        verify(bindingService, Mockito.times(1)).create(eq(user), any());
    }

    @Test
    @DisplayName("link : aucun preset système => non lié ; wallet étranger => 404")
    void linkWithoutSystemPresetAndForeignWallet() {
        when(repository.findByUserAndAssetSymbolAndWallet_Id(any(), any(), anyLong())).thenReturn(Optional.empty());
        when(bindingCheck.check(any(Wallet.class), any())).thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "ok"));
        when(bindingService.candidates(eq(user), anyLong(), any())).thenReturn(
                List.of(new Candidate(1L, "perso", "FIXED", PresetOrigin.CUSTOM, null)));
        assertTrue(service.link(user, WALLET_ID).stream().allMatch(r -> r.outcome() == LinkOutcome.NOT_LINKED));
        verify(bindingService, never()).create(any(), any());
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.link(user, 99L));
    }

    @Test
    @DisplayName("link : échec transitoire du check (clé/solde) => non lié, statut et message retournés")
    void linkTransientFailure() {
        when(repository.findByUserAndAssetSymbolAndWallet_Id(any(), any(), anyLong())).thenReturn(Optional.empty());
        when(bindingCheck.check(any(Wallet.class), any())).thenReturn(
                new BindingCheckResult(BindingCheckStatus.CREDENTIAL_INVALID, "clé rejetée"));
        List<LinkResult> results = service.link(user, WALLET_ID);
        assertEquals("CREDENTIAL_INVALID", results.getFirst().status());
        assertEquals("clé rejetée", results.getFirst().message());
    }

    @Test
    @DisplayName("proposals : cash réparti entre les liens, base = cash / (jours × multiplicateur moyen), devis joint")
    void proposalFormula() {
        List<RainbowLiveBinding> bindings = List.of(binding(1L, "BTC", null), binding(2L, "ETH", 5.0), binding(3L, "PAXG", null));
        when(repository.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(bindings);
        when(portfolio.read(any())).thenReturn(new PortfolioReading(3000, Map.of(), Instant.EPOCH, PortfolioStatus.OK, WALLET_ID));
        when(resolver.config(any())).thenReturn(config());
        when(quoteService.quote(eq(user), anyLong(), any(BigDecimal.class))).thenReturn(new BindingPathQuoteService.Result(
                new PathQuote(PathStatus.OK, "USDC", "BTC", BigDecimal.ONE, List.of(), BigDecimal.ONE, FeeTestLevel.GREEN,
                        PathEstimation.BOOK, false), null));

        List<Proposal> proposals = service.proposals(user, WALLET_ID);

        assertEquals(3, proposals.size());
        Proposal p = proposals.get(1);
        assertEquals(1000.0, p.cashAllocated(), 1e-9);
        assertEquals(7.0 / 6.0, p.averageMultiplier(), 1e-9);
        assertEquals(9.52, p.proposedBaseAmount(), 1e-9);
        assertEquals(90, p.autonomyDays());
        assertEquals(5.0, p.currentBaseAmount());
        assertEquals(10.0, p.presetBaseAmount());
        assertEquals(FeeTestLevel.GREEN, p.feeTestLevel());
        assertFalse(p.belowMinimum());
    }

    @Test
    @DisplayName("proposals : sous le minimum de l'exchange signalé ; devis indisponible => proposition gardée avec avertissement")
    void proposalBelowMinimumAndQuoteUnavailable() {
        when(repository.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(List.of(binding(1L, "BTC", null)));
        when(portfolio.read(any())).thenReturn(new PortfolioReading(100, Map.of(), Instant.EPOCH, PortfolioStatus.OK, WALLET_ID));
        when(resolver.config(any())).thenReturn(config());
        when(quoteService.quote(eq(user), anyLong(), any(BigDecimal.class))).thenReturn(new BindingPathQuoteService.Result(
                new PathQuote(PathStatus.BELOW_MIN, "USDC", "BTC", BigDecimal.ONE, List.of(), BigDecimal.ONE, FeeTestLevel.GREEN,
                        PathEstimation.BOOK, false), "Montant inférieur au minimum"));
        Proposal below = service.proposals(user, WALLET_ID).getFirst();
        assertTrue(below.belowMinimum());
        assertEquals("Montant inférieur au minimum", below.warning());

        when(quoteService.quote(eq(user), anyLong(), any(BigDecimal.class))).thenThrow(new PathQuoteUnavailableException("hors ligne"));
        Proposal unavailable = service.proposals(user, WALLET_ID).getFirst();
        assertEquals(0.95, unavailable.proposedBaseAmount(), 1e-9);
        assertNull(unavailable.feeTestLevel());
        assertTrue(unavailable.warning().contains("indisponible"));
    }

    @Test
    @DisplayName("proposals : soldes illisibles ou cash nul => aucune proposition, raison donnée")
    void proposalWithoutCash() {
        when(repository.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(List.of(binding(1L, "BTC", null)));
        when(resolver.config(any())).thenReturn(config());
        when(portfolio.read(any())).thenReturn(PortfolioReading.unavailable(WALLET_ID, Instant.EPOCH));
        Proposal unreadable = service.proposals(user, WALLET_ID).getFirst();
        assertNull(unreadable.proposedBaseAmount());
        assertNull(unreadable.cashAllocated());
        when(portfolio.read(any())).thenReturn(new PortfolioReading(0, Map.of(), Instant.EPOCH, PortfolioStatus.OK, WALLET_ID));
        Proposal empty = service.proposals(user, WALLET_ID).getFirst();
        assertNull(empty.proposedBaseAmount());
        assertEquals(0.0, empty.cashAllocated());
        verify(quoteService, never()).quote(any(), anyLong(), any());
    }

    @Test
    @DisplayName("proposals : durée choisie par l'utilisateur (jours) remplace le défaut admin ; hors bornes => refus")
    void proposalWithChosenDuration() {
        when(repository.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(List.of(binding(1L, "BTC", null)));
        when(portfolio.read(any())).thenReturn(new PortfolioReading(1260, Map.of(), Instant.EPOCH, PortfolioStatus.OK, WALLET_ID));
        when(resolver.config(any())).thenReturn(config());
        when(quoteService.quote(eq(user), anyLong(), any(BigDecimal.class))).thenReturn(new BindingPathQuoteService.Result(
                new PathQuote(PathStatus.OK, "USDC", "BTC", BigDecimal.ONE, List.of(), BigDecimal.ONE, FeeTestLevel.GREEN,
                        PathEstimation.BOOK, false), null));

        Proposal sixMonths = service.proposals(user, WALLET_ID, 180).getFirst();
        assertEquals(180, sixMonths.autonomyDays());
        assertEquals(6.0, sixMonths.proposedBaseAmount(), 1e-9);
        assertEquals(1260.0 / (90 * 7.0 / 6.0), service.proposals(user, WALLET_ID).getFirst().proposedBaseAmount(), 0.01);
        assertThrows(IllegalArgumentException.class, () -> service.proposals(user, WALLET_ID, 3));
        assertThrows(IllegalArgumentException.class, () -> service.proposals(user, WALLET_ID, 400));
    }
}
