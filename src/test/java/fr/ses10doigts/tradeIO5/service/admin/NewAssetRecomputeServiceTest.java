package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.NewAssetLinkedEvent;
import fr.ses10doigts.tradeIO5.service.optimizer.NewAssetProposalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Admin « Recalculer » : validation de la demande, drapeau, journal d'audit")
@SuppressWarnings("unchecked")
class NewAssetRecomputeServiceTest {

    private final ObjectProvider<NewAssetProposalService> provider = mock(ObjectProvider.class);
    private final NewAssetProposalService proposalService = mock(NewAssetProposalService.class);
    private final RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
    private final NewAssetProposalRepository proposals = mock(NewAssetProposalRepository.class);
    private final AdminActionLogService journal = mock(AdminActionLogService.class);
    private final NewAssetRecomputeService service = new NewAssetRecomputeService(provider, bindings, proposals, journal);
    private final RainbowLiveBinding link = RainbowLiveBinding.builder().id(7L).assetSymbol("PAXG").user(User.builder().id(1L).build())
            .wallet(Wallet.builder().id(3L).build()).build();

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(proposalService);
        when(bindings.findFirstByAssetSymbolAndArchivedAtIsNullOrderByIdAsc("PAXG")).thenReturn(Optional.of(link));
        when(proposals.findFirstByAssetSymbolOrderByIdDesc("PAXG")).thenReturn(Optional.of(NewAssetProposal.builder().status(NewAssetProposalStatus.REFUSE).build()));
    }

    @Test
    @DisplayName("Demande valide : symbole normalisé, calcul mis en file avec le lien de contexte, audit OK (cible new-asset-recompute:<ACTIF>)")
    void startsAndAudits() {
        NewAssetRecomputeService.Started started = service.recompute(" paxg ", "root");

        assertEquals("PAXG", started.asset());
        assertEquals("EN_FILE", started.status());
        verify(proposalService).requestRecompute(new NewAssetLinkedEvent(1L, "PAXG", 7L, 3L));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("new-asset-recompute:PAXG"), payload.capture(), eq(AdminActionOutcome.OK));
        assertEquals("recompute", payload.getValue().get("action"));
        assertEquals("REFUSE", payload.getValue().get("previousStatus"));
    }

    @Test
    @DisplayName("Drapeau désactivé : message clair (propriété nommée), aucun calcul, audit REFUSED")
    void disabledByFlag() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertFalse(service.enabled());
        assertFalse(service.running("PAXG"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.recompute("PAXG", "root"));
        assertTrue(e.getMessage().contains("tradeio.new-asset-proposal.enabled"), e.getMessage());
        assertTrue(e.getMessage().contains("désactivé"), e.getMessage());
        verify(proposalService, never()).requestRecompute(any());
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("new-asset-recompute:PAXG"), any(), eq(AdminActionOutcome.REFUSED));
    }

    @Test
    @DisplayName("Calcul déjà en cours : refus propre (IllegalStateException), audit REFUSED")
    void alreadyRunning() {
        doThrow(new IllegalStateException("Un calcul est déjà en cours pour PAXG : attendre sa fin")).when(proposalService).requestRecompute(any());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.recompute("PAXG", "root"));
        assertTrue(e.getMessage().contains("déjà en cours"));
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("new-asset-recompute:PAXG"), any(), eq(AdminActionOutcome.REFUSED));
    }

    @Test
    @DisplayName("Symbole invalide ou actif sans lien actif : IllegalArgumentException, rien en file")
    void invalidOrUnlinked() {
        assertThrows(IllegalArgumentException.class, () -> service.recompute("pa xg!", "root"));
        assertThrows(IllegalArgumentException.class, () -> service.recompute(null, "root"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.recompute("DOGE", "root"));
        assertTrue(e.getMessage().contains("Aucun lien actif"));
        verify(proposalService, never()).requestRecompute(any());
    }

    @Test
    @DisplayName("running() relaie l'état du service de calcul")
    void runningRelays() {
        when(proposalService.isRunning("PAXG")).thenReturn(true);
        assertTrue(service.running("PAXG"));
        assertTrue(service.enabled());
    }
}
