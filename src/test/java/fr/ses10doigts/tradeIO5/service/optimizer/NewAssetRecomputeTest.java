package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.NewAssetLinkedEvent;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Recalcul admin (D1b) : remplacement en place après REFUSE / calcul dégradé, nouvelle ligne après VALIDE (ligne de taux intacte), un seul calcul par actif. Aucun calcul réel (historique vide). */
@DisplayName("D1b : recalcul d'une proposition (remplacement, ligne de taux intacte, un calcul à la fois)")
class NewAssetRecomputeTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final NewAssetLinkedEvent EVENT = new NewAssetLinkedEvent(1L, "NEWX", 7L, 3L);

    private NewAssetProposalRepository proposals;
    private NewAssetProposalAdminService alerts;
    private AssetBaseRateService rates;
    private NewAssetHistoryResolver history;
    private NewAssetProposalService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        proposals = mock(NewAssetProposalRepository.class);
        when(proposals.save(any())).thenAnswer(i -> i.getArgument(0));
        RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
        when(bindings.findById(7L)).thenReturn(Optional.of(mock(RainbowLiveBinding.class)));
        UserRepository users = mock(UserRepository.class);
        User user = mock(User.class);
        when(user.getUsername()).thenReturn("admin");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        AffectationConfigResolver resolver = mock(AffectationConfigResolver.class);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
        when(resolver.trendConfig(any())).thenReturn(new RainbowLiveTrendConfigs.Resolved(TrendMixCalculator.Params.defaults(),
                RainbowSetSelector.RangeMapping.KEEP_PREVIOUS, sets[0], sets[1]));
        when(resolver.analysisWindowMonths(any())).thenReturn(6);
        RainbowModulationControlService control = mock(RainbowModulationControlService.class);
        when(control.current()).thenReturn(PresetOptimizerTestSupport.LIMITS);
        BindingCheck check = mock(BindingCheck.class);
        when(check.check(any(), any())).thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "ok"));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
        DomainClock clock = mock(DomainClock.class);
        when(clock.now()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        alerts = mock(NewAssetProposalAdminService.class);
        rates = mock(AssetBaseRateService.class);
        when(rates.isCalibrated(any())).thenReturn(false);
        history = mock(NewAssetHistoryResolver.class);
        when(history.resolve(any(), any(), any(), any())).thenReturn(new NewAssetHistoryResolver.History(List.of(), null, "vide"));
        service = new NewAssetProposalService(proposals, bindings, users, mock(AssetAffectationService.class), resolver, control, history, check, tx, clock,
                alerts, rates, dir.toString());
    }

    private static NewAssetProposal existing(Long id, NewAssetProposalStatus status, String flags) {
        return NewAssetProposal.builder().id(id).assetSymbol("NEWX").status(status).presetSource("BTC").flags(flags).levelsJson("[]").createdAt(OLD)
                .decidedAt(status == NewAssetProposalStatus.PROPOSE ? null : OLD).decidedBy(status == NewAssetProposalStatus.PROPOSE ? null : "root").build();
    }

    @Test
    @DisplayName("Après REFUSE : contenu remplacé dans la même ligne, statut PROPOSE, décision effacée, alarme close puis relevée")
    void recomputeAfterRefuse() {
        NewAssetProposal refused = existing(5L, NewAssetProposalStatus.REFUSE, "PROVISOIRE");
        when(proposals.findFirstByAssetSymbolOrderByIdDesc("NEWX")).thenReturn(Optional.of(refused));

        NewAssetProposal out = service.recompute(EVENT);

        assertSame(refused, out);
        assertEquals(5L, out.getId());
        assertEquals(NewAssetProposalStatus.PROPOSE, out.getStatus());
        assertNull(out.getDecidedAt());
        assertNull(out.getDecidedBy());
        assertEquals("HISTORIQUE_INSUFFISANT", out.getFlags());
        assertTrue(out.getCreatedAt().isAfter(OLD));
        InOrder order = inOrder(alerts);
        order.verify(alerts).closeAlert(eq("NEWX"), any());
        order.verify(alerts).alert(out);
    }

    @Test
    @DisplayName("Après calcul dégradé (CALAGE_INTERROMPU) : le nouveau résultat remplace les drapeaux")
    void recomputeAfterDegraded() {
        NewAssetProposal degraded = existing(6L, NewAssetProposalStatus.PROPOSE, "CALAGE_INTERROMPU");
        when(proposals.findFirstByAssetSymbolOrderByIdDesc("NEWX")).thenReturn(Optional.of(degraded));

        NewAssetProposal out = service.recompute(EVENT);

        assertSame(degraded, out);
        assertEquals(NewAssetProposalStatus.PROPOSE, out.getStatus());
        assertFalse(out.getFlags().contains("CALAGE_INTERROMPU"));
        verify(proposals, times(1)).save(degraded);
    }

    @Test
    @DisplayName("Après VALIDE : nouvelle ligne PROPOSE, la validée reste intacte, aucune écriture de taux (asset_base_rate)")
    void recomputeAfterValidatedKeepsRateRow() {
        NewAssetProposal validated = existing(8L, NewAssetProposalStatus.VALIDE, "PROVISOIRE");
        when(proposals.findFirstByAssetSymbolOrderByIdDesc("NEWX")).thenReturn(Optional.of(validated));

        NewAssetProposal out = service.recompute(EVENT);

        assertNotSame(validated, out);
        assertNull(out.getId());
        assertEquals(NewAssetProposalStatus.PROPOSE, out.getStatus());
        assertEquals(NewAssetProposalStatus.VALIDE, validated.getStatus());
        assertEquals("root", validated.getDecidedBy());
        assertEquals("PROVISOIRE", validated.getFlags());
        verify(rates, never()).save(any(), any(), any(), any());
        verify(rates, never()).resetDefaults(any(), any());
        verify(alerts).alert(out);
    }

    @Test
    @DisplayName("Un seul calcul à la fois par actif : refus propre du second recalcul, l'accueil automatique est ignoré, le verrou est levé à la fin")
    void oneComputationPerAsset() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(history.resolve(any(), any(), any(), any())).thenAnswer(i -> {
            started.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return new NewAssetHistoryResolver.History(List.of(), null, "vide");
        });
        when(proposals.findFirstByAssetSymbolOrderByIdDesc("NEWX")).thenReturn(Optional.of(existing(5L, NewAssetProposalStatus.REFUSE, "X")));

        service.requestRecompute(EVENT);
        assertTrue(started.await(30, TimeUnit.SECONDS));
        assertTrue(service.isRunning("NEWX"));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.requestRecompute(EVENT));
        assertTrue(refused.getMessage().contains("déjà en cours"), refused.getMessage());
        service.onLinked(EVENT);

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (service.isRunning("NEWX") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isRunning("NEWX"));
        verify(history, times(1)).resolve(any(), any(), any(), any());
        verify(alerts).alert(any());
        service.requestRecompute(EVENT);
        verify(history, org.mockito.Mockito.timeout(30_000).times(2)).resolve(any(), any(), any(), any());
    }
}
