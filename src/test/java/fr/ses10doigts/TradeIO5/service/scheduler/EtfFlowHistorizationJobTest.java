package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.CachingEtfFlowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etude-cache-etf-flow-historisation.md. Vérifie :
 * - aucune credential SOSOVALUE résolue => exécution ignorée proprement, aucun appel refresh() ;
 * - credential présente => refresh() appelé pour chaque EtfFlowAsset (BTC et ETH) ;
 * - isolation par asset : un échec sur l'un n'empêche pas la tentative sur l'autre.
 */
@DisplayName("EtfFlowHistorizationJob")
class EtfFlowHistorizationJobTest {

    private static final ApiCredentialDTO CREDENTIAL =
            new ApiCredentialDTO(WebProviderCode.SOSOVALUE, "test-key", "", "http://localhost");

    private CachingEtfFlowClient cachingEtfFlowClient;
    private IndicatorCredentialResolver credentialResolver;
    private EtfFlowHistorizationJob job;

    @BeforeEach
    void setUp() {
        cachingEtfFlowClient = mock(CachingEtfFlowClient.class);
        credentialResolver = mock(IndicatorCredentialResolver.class);
        job = new EtfFlowHistorizationJob(cachingEtfFlowClient, credentialResolver);
    }

    @Test
    @DisplayName("aucune credential résolue => exécution ignorée, refresh() jamais appelé")
    void refreshDailySnapshots_noCredential_skipsExecution() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(null);

        job.refreshDailySnapshots();

        verify(cachingEtfFlowClient, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("credential présente => refresh() appelé pour BTC et ETH")
    void refreshDailySnapshots_withCredential_refreshesBothAssets() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(CREDENTIAL);
        when(cachingEtfFlowClient.refresh(CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(
                EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 16)).total(1.0).byIssuer(Map.of()).build());
        when(cachingEtfFlowClient.refresh(CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(
                EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 16)).total(2.0).byIssuer(Map.of()).build());

        job.refreshDailySnapshots();

        verify(cachingEtfFlowClient, times(1)).refresh(CREDENTIAL, EtfFlowAsset.BTC);
        verify(cachingEtfFlowClient, times(1)).refresh(CREDENTIAL, EtfFlowAsset.ETH);
    }

    @Test
    @DisplayName("échec sur un asset (exception) n'empêche pas la tentative sur l'autre")
    void refreshDailySnapshots_oneAssetThrows_otherStillAttempted() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(CREDENTIAL);
        when(cachingEtfFlowClient.refresh(CREDENTIAL, EtfFlowAsset.BTC)).thenThrow(new RuntimeException("boom"));
        when(cachingEtfFlowClient.refresh(CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(
                EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 16)).total(2.0).byIssuer(Map.of()).build());

        job.refreshDailySnapshots();

        verify(cachingEtfFlowClient, times(1)).refresh(CREDENTIAL, EtfFlowAsset.BTC);
        verify(cachingEtfFlowClient, times(1)).refresh(CREDENTIAL, EtfFlowAsset.ETH);
    }

    @Test
    @DisplayName("réponse invalide pour un asset => pas d'exception, l'autre asset est quand même tenté")
    void refreshDailySnapshots_invalidResponse_noException() {
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(CREDENTIAL);
        when(cachingEtfFlowClient.refresh(CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(EtfFlowResponse.invalid());
        when(cachingEtfFlowClient.refresh(CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(
                EtfFlowResponse.builder().valid(true).date(LocalDate.of(2026, 7, 16)).total(2.0).byIssuer(Map.of()).build());

        job.refreshDailySnapshots();

        verify(cachingEtfFlowClient, times(1)).refresh(CREDENTIAL, EtfFlowAsset.BTC);
        verify(cachingEtfFlowClient, times(1)).refresh(CREDENTIAL, EtfFlowAsset.ETH);
    }
}
