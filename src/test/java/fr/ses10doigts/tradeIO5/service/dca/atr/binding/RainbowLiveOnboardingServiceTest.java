package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RealPortfolioSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkOutcome;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.Proposal;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserModulationService;
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
    private final AffectationConfigResolver resolver = mock(AffectationConfigResolver.class);
    private final RainbowModulationControlService control = mock(RainbowModulationControlService.class);
    private final UserModulationService userModulation = mock(UserModulationService.class);
    private final fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository assetRateRepository = mock(fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository.class);
    private final RainbowLiveOnboardingService service = new RainbowLiveOnboardingService(bindingService, repository,
            bindingCheck, quoteService, portfolio, resolver, control, userModulation,
            new fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService(assetRateRepository));

    private final User user = User.builder().id(1L).build();
    private final Wallet wallet = Wallet.builder().id(WALLET_ID).user(user).build();

    @BeforeEach
    void setUp() {
        when(bindingService.wallets(user)).thenReturn(List.of(wallet));
        when(control.current()).thenReturn(RainbowModulationControl.defaults(Instant.EPOCH, "test"));
    }

    private RainbowLiveBinding binding(long id, String asset, Double linkBase) {
        AssetAffectation preset = AssetAffectation.builder().id(id).assetSymbol(asset).build();
        return RainbowLiveBinding.builder().id(id).user(user).assetSymbol(asset).affectation(preset).wallet(wallet)
                .baseAmount(linkBase).build();
    }

    private static RainbowAtrConfig config() {
        return RainbowAtrConfig.builder().multX2(2).multX1(1).multX0_5(0.5).baseAmount(10).build();
    }

    @Test
    @DisplayName("link : actif tradable => lien créé sur l'Affectation de l'actif ; bloqué => non lié avec la raison ; existant => inchangé")
    void linkOutcomes() {
        RainbowLiveBinding existing = binding(11L, "BTC", null);
        when(repository.findByUserAndAssetSymbolAndWallet_Id(any(), any(), anyLong())).thenReturn(Optional.empty());
        when(repository.findByUserAndAssetSymbolAndWallet_Id(user, "BTC", WALLET_ID)).thenReturn(Optional.of(existing));
        when(bindingCheck.check(wallet, "ETH")).thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "ok"));
        when(bindingCheck.check(wallet, "PAXG")).thenReturn(
                new BindingCheckResult(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "PAXG non tradable sans fiat"));
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
    @DisplayName("link : wallet étranger => 404")
    void linkForeignWallet() {
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.link(user, 99L));
        verify(bindingService, never()).create(any(), any());
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

    private void stubProfileProposal(double cash, String... assets) {
        List<RainbowLiveBinding> bindings = new java.util.ArrayList<>();
        for (int i = 0; i < assets.length; i++) {
            bindings.add(binding(i + 1L, assets[i], null));
        }
        when(repository.findByUserOrderByPriorityAscAssetSymbolAsc(user)).thenReturn(bindings);
        when(portfolio.read(any())).thenReturn(new PortfolioReading(cash, Map.of(), Instant.EPOCH, PortfolioStatus.OK, WALLET_ID));
        when(resolver.config(any())).thenReturn(config());
        when(quoteService.quote(eq(user), anyLong(), any(BigDecimal.class))).thenReturn(new BindingPathQuoteService.Result(
                new PathQuote(PathStatus.OK, "USDC", "BTC", BigDecimal.ONE, List.of(), BigDecimal.ONE, FeeTestLevel.GREEN,
                        PathEstimation.BOOK, false), null));
    }

    @Test
    @DisplayName("proposals : défaut = taux du profil x cash alloué (échelle 2, vers le bas), mention de risque, aucune autonomie")
    void proposalFromProfileRate() {
        when(userModulation.profileOf(user)).thenReturn(StartingProfile.BALANCED);
        stubProfileProposal(10_000, "BTC", "ETH");

        List<Proposal> proposals = service.proposals(user, WALLET_ID);

        Proposal btc = proposals.get(0);
        assertEquals(RainbowLiveOnboardingService.Basis.PROFILE_RATE, btc.basis());
        assertEquals(StartingProfile.BALANCED, btc.profile());
        assertEquals(5000.0, btc.cashAllocated(), 1e-9);
        assertEquals(4.736e-4, btc.baseRate(), 1e-12);
        assertEquals(2.36, btc.proposedBaseAmount(), 1e-9);   // 5000 x 4,736e-4 = 2,368 -> 2,36
        assertNull(btc.autonomyDays());
        assertFalse(btc.uncalibrated());
        assertEquals(RainbowLiveOnboardingService.RISK_NOTE, btc.riskNote());
        Proposal eth = proposals.get(1);
        assertEquals(3.17, eth.proposedBaseAmount(), 1e-9);   // 5000 x 6,349e-4 = 3,1745 -> 3,17
        assertFalse(eth.uncalibrated());
    }

    @Test
    @DisplayName("proposals : actif sans ligne propre (PAXG) => repli sur la ligne BTC, drapeau non calibré, jamais bloqué")
    void proposalFallsBackToBtcRow() {
        when(userModulation.profileOf(user)).thenReturn(StartingProfile.FULL);
        stubProfileProposal(3_000, "BTC", "PAXG");

        List<Proposal> proposals = service.proposals(user, WALLET_ID);

        Proposal btc = proposals.get(0);
        Proposal paxg = proposals.get(1);
        assertFalse(btc.uncalibrated());
        assertTrue(paxg.uncalibrated());
        assertEquals(btc.baseRate(), paxg.baseRate());
        assertEquals(btc.proposedBaseAmount(), paxg.proposedBaseAmount());
        assertEquals(2.13, paxg.proposedBaseAmount(), 1e-9);  // 1500 x 1,423e-3 = 2,1345 -> 2,13
        assertEquals(RainbowLiveOnboardingService.RISK_NOTE, paxg.riskNote());
    }

    @Test
    @DisplayName("proposals : invariance au capital (deux capitaux, même taux) ; le montant est proportionnel au cash")
    void proposalScalesWithCapital() {
        when(userModulation.profileOf(user)).thenReturn(StartingProfile.DYNAMIC);
        stubProfileProposal(2_000, "BTC");
        double small = service.proposals(user, WALLET_ID).getFirst().proposedBaseAmount();
        stubProfileProposal(20_000, "BTC");
        double large = service.proposals(user, WALLET_ID).getFirst().proposedBaseAmount();
        assertEquals(1.54, small, 1e-9);   // 2000 x 7,712e-4 = 1,5424
        assertEquals(15.42, large, 1e-9);  // 20000 x 7,712e-4 = 15,424
        assertEquals(10.0, large / small, 0.1);
    }

    @Test
    @DisplayName("proposals : autonomie saisie explicitement => ancien calcul, même avec un profil ; profil absent ou CUSTOM => autonomie du défaut admin")
    void autonomyStillAvailable() {
        when(userModulation.profileOf(user)).thenReturn(StartingProfile.PRUDENT);
        stubProfileProposal(1260, "BTC");
        Proposal explicit = service.proposals(user, WALLET_ID, 180).getFirst();
        assertEquals(RainbowLiveOnboardingService.Basis.AUTONOMY, explicit.basis());
        assertEquals(180, explicit.autonomyDays());
        assertEquals(6.0, explicit.proposedBaseAmount(), 1e-9);
        assertNull(explicit.riskNote());

        for (StartingProfile none : new StartingProfile[]{null, StartingProfile.CUSTOM}) {
            when(userModulation.profileOf(user)).thenReturn(none);
            Proposal p = service.proposals(user, WALLET_ID).getFirst();
            assertEquals(RainbowLiveOnboardingService.Basis.AUTONOMY, p.basis());
            assertEquals(90, p.autonomyDays());
            assertEquals(1260.0 / (90 * 7.0 / 6.0), p.proposedBaseAmount(), 0.01);
            assertFalse(p.uncalibrated());
        }
    }

    @Test
    @DisplayName("proposals : taux de profil édité en admin => la proposition suit ; cash nul => pas de proposition, raison donnée")
    void proposalFollowsAdminRatesAndNoCash() {
        RainbowModulationControl edited = RainbowModulationControl.defaults(Instant.EPOCH, "test");
        edited.setBaseRateBtcPrudent(new BigDecimal("0.001"));
        when(control.current()).thenReturn(edited);
        when(userModulation.profileOf(user)).thenReturn(StartingProfile.PRUDENT);
        stubProfileProposal(1_000, "BTC");
        assertEquals(1.0, service.proposals(user, WALLET_ID).getFirst().proposedBaseAmount(), 1e-9);

        stubProfileProposal(0, "BTC");
        Proposal empty = service.proposals(user, WALLET_ID).getFirst();
        assertNull(empty.proposedBaseAmount());
        assertEquals(RainbowLiveOnboardingService.Basis.PROFILE_RATE, empty.basis());
    }
}
