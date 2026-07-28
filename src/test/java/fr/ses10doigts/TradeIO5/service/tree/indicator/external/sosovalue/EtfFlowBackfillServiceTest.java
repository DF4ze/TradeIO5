package fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.repository.market.EtfFlowSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.FarsideEtfFlowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etude-cache-etf-flow-historisation.md, addendum backfill (+ addendum Farside 2026-07-17).
 * Vérifie :
 * - aucune credential (ni SOSOVALUE ni FARSIDE) => backfill ignoré, aucun appel ;
 * - une seule source disponible => backfill se poursuit avec celle-là seule ;
 * - les deux sources disponibles => Farside puis SoSoValue, upsert pour chaque ligne valide, BTC et ETH ;
 * - isolation par asset ET par source (échec sur l'un n'empêche pas les autres).
 */
@DisplayName("EtfFlowBackfillService")
class EtfFlowBackfillServiceTest {

    private static final ApiCredentialDTO SOSOVALUE_CREDENTIAL =
            new ApiCredentialDTO(WebProviderCode.SOSOVALUE, "test-key", "", "http://localhost");
    private static final ApiCredentialDTO FARSIDE_CREDENTIAL =
            new ApiCredentialDTO(WebProviderCode.FARSIDE, "", "", "https://farside.co.uk");

    private SosoValueEtfFlowClient sosoValueEtfFlowClient;
    private FarsideEtfFlowClient farsideEtfFlowClient;
    private CachingEtfFlowClient cachingEtfFlowClient;
    private EtfFlowSnapshotRepository repository;
    private IndicatorCredentialResolver credentialResolver;
    private EtfFlowBackfillService service;

    @BeforeEach
    void setUp() {
        sosoValueEtfFlowClient = mock(SosoValueEtfFlowClient.class);
        farsideEtfFlowClient = mock(FarsideEtfFlowClient.class);
        cachingEtfFlowClient = mock(CachingEtfFlowClient.class);
        repository = mock(EtfFlowSnapshotRepository.class);
        credentialResolver = mock(IndicatorCredentialResolver.class);
        service = new EtfFlowBackfillService(
                sosoValueEtfFlowClient, farsideEtfFlowClient, cachingEtfFlowClient, repository, credentialResolver);
    }

    @Test
    @DisplayName("aucune credential résolue (ni SOSOVALUE ni FARSIDE) => backfill ignoré, aucun appel")
    void backfillAll_noCredential_skipsEverything() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(null);
        when(credentialResolver.resolveWebProvider(WebProviderCode.FARSIDE)).thenReturn(null);

        Map<EtfFlowAsset, Integer> result = service.backfillAll();

        assertTrue(result.isEmpty());
        verify(sosoValueEtfFlowClient, never()).fetchHistory(any(), any(), anyInt());
        verify(farsideEtfFlowClient, never()).fetchHistory(any(), any());
        verify(cachingEtfFlowClient, never()).upsert(any(), any());
    }

    @Test
    @DisplayName("credential SOSOVALUE présente, FARSIDE absente => seul SoSoValue est appelé")
    void backfillAll_onlySosoValueCredential_callsOnlySosoValue() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(SOSOVALUE_CREDENTIAL);
        when(credentialResolver.resolveWebProvider(WebProviderCode.FARSIDE)).thenReturn(null);

        EtfFlowResponse row = EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 15)).total(1.0).byIssuer(Map.of()).build();
        when(sosoValueEtfFlowClient.fetchHistory(SOSOVALUE_CREDENTIAL, EtfFlowAsset.BTC, EtfFlowBackfillService.BACKFILL_LIMIT))
                .thenReturn(List.of(row));
        when(sosoValueEtfFlowClient.fetchHistory(SOSOVALUE_CREDENTIAL, EtfFlowAsset.ETH, EtfFlowBackfillService.BACKFILL_LIMIT))
                .thenReturn(List.of());

        Map<EtfFlowAsset, Integer> result = service.backfillAll();

        assertEquals(1, result.get(EtfFlowAsset.BTC));
        assertEquals(0, result.get(EtfFlowAsset.ETH));
        verify(farsideEtfFlowClient, never()).fetchHistory(any(), any());
        verify(cachingEtfFlowClient).upsert(EtfFlowAsset.BTC, row);
    }

    @Test
    @DisplayName("credential FARSIDE présente, SOSOVALUE absente => seul Farside est appelé")
    void backfillAll_onlyFarsideCredential_callsOnlyFarside() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(null);
        when(credentialResolver.resolveWebProvider(WebProviderCode.FARSIDE)).thenReturn(FARSIDE_CREDENTIAL);

        EtfFlowResponse row = EtfFlowResponse.builder().valid(true).date(LocalDate.of(2024, 1, 11)).total(655.3).byIssuer(Map.of()).build();
        when(farsideEtfFlowClient.fetchHistory(FARSIDE_CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(List.of(row));
        when(farsideEtfFlowClient.fetchHistory(FARSIDE_CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(List.of());

        Map<EtfFlowAsset, Integer> result = service.backfillAll();

        assertEquals(1, result.get(EtfFlowAsset.BTC));
        assertEquals(0, result.get(EtfFlowAsset.ETH));
        verify(sosoValueEtfFlowClient, never()).fetchHistory(any(), any(), anyInt());
        verify(cachingEtfFlowClient).upsert(EtfFlowAsset.BTC, row);
    }

    @Test
    @DisplayName("les deux credentials présentes => Farside puis SoSoValue, upsert pour chaque ligne valide")
    void backfillAll_bothCredentials_callsBothSourcesForEachAsset() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(SOSOVALUE_CREDENTIAL);
        when(credentialResolver.resolveWebProvider(WebProviderCode.FARSIDE)).thenReturn(FARSIDE_CREDENTIAL);

        EtfFlowResponse farsideRow = EtfFlowResponse.builder().valid(true).date(LocalDate.of(2024, 1, 11)).total(655.3).byIssuer(Map.of()).build();
        EtfFlowResponse sosoValueRow = EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 15)).total(1.0).byIssuer(Map.of()).build();
        // Ligne sans date/total : doit être ignorée (jamais upsertée), pas de crash.
        EtfFlowResponse incomplete = EtfFlowResponse.builder().valid(true).date(null).total(null).byIssuer(Map.of()).build();

        when(farsideEtfFlowClient.fetchHistory(FARSIDE_CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(List.of(farsideRow, incomplete));
        when(farsideEtfFlowClient.fetchHistory(FARSIDE_CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(List.of());
        when(sosoValueEtfFlowClient.fetchHistory(SOSOVALUE_CREDENTIAL, EtfFlowAsset.BTC, EtfFlowBackfillService.BACKFILL_LIMIT))
                .thenReturn(List.of(sosoValueRow));
        when(sosoValueEtfFlowClient.fetchHistory(SOSOVALUE_CREDENTIAL, EtfFlowAsset.ETH, EtfFlowBackfillService.BACKFILL_LIMIT))
                .thenReturn(List.of());

        Map<EtfFlowAsset, Integer> result = service.backfillAll();

        assertEquals(2, result.get(EtfFlowAsset.BTC));
        assertEquals(0, result.get(EtfFlowAsset.ETH));
        verify(cachingEtfFlowClient).upsert(EtfFlowAsset.BTC, farsideRow);
        verify(cachingEtfFlowClient).upsert(EtfFlowAsset.BTC, sosoValueRow);
        verify(cachingEtfFlowClient, never()).upsert(EtfFlowAsset.BTC, incomplete);
        verify(cachingEtfFlowClient, times(2)).upsert(any(), any());
    }

    @Test
    @DisplayName("échec Farside sur un asset n'empêche ni SoSoValue sur ce même asset ni les autres assets")
    void backfillAll_farsideThrows_sosoValueStillAttempted() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(SOSOVALUE_CREDENTIAL);
        when(credentialResolver.resolveWebProvider(WebProviderCode.FARSIDE)).thenReturn(FARSIDE_CREDENTIAL);

        when(farsideEtfFlowClient.fetchHistory(FARSIDE_CREDENTIAL, EtfFlowAsset.BTC)).thenThrow(new RuntimeException("boom"));
        when(farsideEtfFlowClient.fetchHistory(FARSIDE_CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(List.of());
        EtfFlowResponse row = EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 15)).total(1.0).byIssuer(Map.of()).build();
        when(sosoValueEtfFlowClient.fetchHistory(SOSOVALUE_CREDENTIAL, EtfFlowAsset.BTC, EtfFlowBackfillService.BACKFILL_LIMIT))
                .thenReturn(List.of(row));
        when(sosoValueEtfFlowClient.fetchHistory(SOSOVALUE_CREDENTIAL, EtfFlowAsset.ETH, EtfFlowBackfillService.BACKFILL_LIMIT))
                .thenReturn(List.of());

        Map<EtfFlowAsset, Integer> result = service.backfillAll();

        // Farside a levé une exception (0 upsert de sa part), mais SoSoValue a quand même tourné sur BTC.
        assertEquals(1, result.get(EtfFlowAsset.BTC));
        assertEquals(0, result.get(EtfFlowAsset.ETH));
        verify(cachingEtfFlowClient).upsert(EtfFlowAsset.BTC, row);
    }
}
